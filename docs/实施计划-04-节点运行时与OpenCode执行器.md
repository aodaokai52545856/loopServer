# 循环工程一期：节点运行时与 OpenCode 执行器 Implementation Plan

> **执行说明：** 每次只执行一个 Task。若环境具备 `superpowers:subagent-driven-development` 或 `superpowers:executing-plans`，可以使用；不具备这些技能时，直接严格遵循本 Task 的 checkbox、Red→Green、验证和提交步骤。缺少技能不得成为跳步、伪造验证或扩大范围的理由。

**Goal:** 交付可在 Linux、macOS、Windows 本机运行的 `repair-node` 和 `repair-executor`：完成注册、Runner 治理、心跳、只读取码、OpenCode 修复、最多两轮验证以及结构化 Artifact。

**Architecture:** `repair-node` 是常驻治理进程；`repair-executor` 是每个 GitLab Job 启动一次的无状态执行器。执行器仅通过稳定的内部 `Agent` 接口依赖 OpenCode，先写本地 JSONL 再补传事件，并在清除 GitLab 凭据后启动 AI 子进程。

**Tech Stack:** Go 1.26、Go 标准库、`golang.org/x/sys`、GitLab Runner、Git CLI、OpenCode CLI JSON、launchd/systemd/Windows Service、JSON Schema v1。

---

## File map

- `node-runtime/cmd/repair-node/main.go`：join、run、status、drain 命令。
- `node-runtime/cmd/repair-executor/main.go`：单次 Job 执行入口。
- `node-runtime/internal/enrollment/*`：设备密钥、证书与安全存储。
- `node-runtime/internal/heartbeat/*`：探测、心跳、desired-state。
- `node-runtime/internal/runner/*`：GitLab Runner 安装、注册与并发配置。
- `node-runtime/internal/executor/*`：任务流水线编排。
- `node-runtime/internal/opencode/*`：OpenCode CLI 适配器。
- `node-runtime/internal/process/*`：无 shell、可取消的跨平台进程。
- `node-runtime/internal/spool/*`：事件 JSONL 与补传。
- `node-runtime/internal/artifact/*`：补丁、测试结果和 manifest。

### Task 1: Implement device join and protected local state

**Files:**
- Create: `node-runtime/internal/enrollment/client.go`
- Create: `node-runtime/internal/enrollment/keystore.go`
- Create: `node-runtime/internal/enrollment/keystore_unix.go`
- Create: `node-runtime/internal/enrollment/keystore_windows.go`
- Modify: `node-runtime/cmd/repair-node/main.go`
- Test: `node-runtime/internal/enrollment/client_test.go`

- [ ] **Step 1: Write failing enrollment persistence tests**

```go
func TestJoinGeneratesKeyAndStoresReturnedIdentity(t *testing.T) {
	dir := t.TempDir()
	server := newEnrollmentServer(t, http.StatusCreated, enrollmentResponse("node-1"))
	client := NewClient(server.URL, NewFileKeyStore(dir), server.Client())

	got, err := client.Join(context.Background(), JoinRequest{Name: "alice-mac", Code: "invite"})
	if err != nil { t.Fatal(err) }
	if got.NodeID != "node-1" { t.Fatalf("node = %q", got.NodeID) }
	assertMode(t, filepath.Join(dir, "device.key"), 0o600)
	if bytes.Contains(mustRead(t, filepath.Join(dir, "state.json")), []byte("invite")) {
		t.Fatal("invite code persisted")
	}
}
```

- [ ] **Step 2: Run and confirm missing package**

Run: `go -C node-runtime test ./internal/enrollment -run TestJoin`

Expected: FAIL because `NewClient` and `NewFileKeyStore` are undefined.

- [ ] **Step 3: Implement key generation, CSR and atomic state writes**

```go
type State struct {
	NodeID          string   `json:"nodeId"`
	Server          string   `json:"server"`
	CertificatePEM  string   `json:"certificatePem"`
	CAPEM           string   `json:"caPem"`
	RunnerTag       string   `json:"runnerTag"`
	AllowedProjects []string `json:"allowedProjects"`
}

func (c *Client) Join(ctx context.Context, request JoinRequest) (State, error) {
	publicKey, privateKey, err := ed25519.GenerateKey(rand.Reader)
	if err != nil { return State{}, err }
	csr, err := createCSR(privateKey, request.Name)
	if err != nil { return State{}, err }
	body, _ := json.Marshal(map[string]any{"name": request.Name, "code": request.Code, "csrPem": csr})
	req, _ := http.NewRequestWithContext(ctx, http.MethodPost, c.server+"/api/node/v1/enroll", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	resp, err := c.http.Do(req)
	if err != nil { return State{}, err }
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusCreated { return State{}, decodeAPIError(resp) }
	var state State
	if err := json.NewDecoder(io.LimitReader(resp.Body, 1<<20)).Decode(&state); err != nil { return State{}, err }
	if err := verifyCertificate(state, publicKey); err != nil { return State{}, err }
	if err := c.store.Save(privateKey, state); err != nil { return State{}, err }
	return state, nil
}
```

Unix saves under `/var/lib/repair-node` for system service or `$XDG_STATE_HOME/repair-node` for user service with directories 0700/files 0600. Windows saves under `%ProgramData%\repair-node` and protects the private-key bytes with machine-scope DPAPI. Write to a sibling temporary file, `fsync`, then atomically rename. Never persist the invite code.

- [ ] **Step 4: Run join, bad certificate and partial-write tests**

Run: `go -C node-runtime test ./internal/enrollment`

Expected: PASS on the current OS; returned certificate must contain the generated public key.

- [ ] **Step 5: Commit**

```bash
git add node-runtime/internal/enrollment node-runtime/cmd/repair-node/main.go
git commit -m "feat(node): join with protected device identity"
```

### Task 2: Probe capabilities and maintain heartbeats

**Files:**
- Create: `node-runtime/internal/probe/probe.go`
- Create: `node-runtime/internal/heartbeat/client.go`
- Create: `node-runtime/internal/heartbeat/loop.go`
- Test: `node-runtime/internal/heartbeat/loop_test.go`

- [ ] **Step 1: Write a failing timing and desired-state test**

```go
func TestLoopSendsImmediatelyThenEveryFifteenSeconds(t *testing.T) {
	clock := newFakeClock(time.Date(2026, 7, 18, 8, 0, 0, 0, time.UTC))
	transport := &fakeTransport{responses: []Response{{DesiredRevision: 2, Config: &Config{Concurrency: 4}}}}
	loop := NewLoop(transport, clock, &fakeApplier{})
	ctx, cancel := context.WithCancel(context.Background())
	go loop.Run(ctx)
	transport.waitCalls(t, 1)
	clock.Advance(14 * time.Second)
	transport.assertCalls(t, 1)
	clock.Advance(time.Second)
	transport.waitCalls(t, 2)
	cancel()
}
```

- [ ] **Step 2: Run and confirm loop is missing**

Run: `go -C node-runtime test ./internal/heartbeat -run TestLoop`

Expected: FAIL to compile.

- [ ] **Step 3: Implement bounded probes and desired revision acknowledgment**

Probe each executable with `exec.CommandContext` and a 3-second timeout:

```go
var toolCommands = []ToolCommand{
	{Name: "git", Program: "git", Args: []string{"--version"}},
	{Name: "java", Program: "java", Args: []string{"-version"}},
	{Name: "maven", Program: "mvn", Args: []string{"--version"}},
	{Name: "node", Program: "node", Args: []string{"--version"}},
	{Name: "pnpm", Program: "pnpm", Args: []string{"--version"}},
	{Name: "opencode", Program: "opencode", Args: []string{"--version"}},
	{Name: "docker", Program: "docker", Args: []string{"version", "--format", "{{.Server.Version}}"}},
}
```

Send the first heartbeat immediately and subsequent heartbeats every 15 seconds with ±10% jitter. Use a mutual-TLS `http.Client` built from the device key/certificate. Apply config to a temporary file, update Runner, then report `appliedRevision`; on failure keep the old revision and set `lastError` to a redacted error code.

- [ ] **Step 4: Run loop, jitter, retry and redaction tests**

Run: `go -C node-runtime test ./internal/probe ./internal/heartbeat`

Expected: PASS; HTTP failure backs off up to 15 seconds but never stops the loop.

- [ ] **Step 5: Commit**

```bash
git add node-runtime/internal/probe node-runtime/internal/heartbeat
git commit -m "feat(node): report capabilities and heartbeat"
```

### Task 3: Manage GitLab Runner consistently on three operating systems

**Files:**
- Create: `node-runtime/internal/runner/manager.go`
- Create: `node-runtime/internal/runner/service_linux.go`
- Create: `node-runtime/internal/runner/service_darwin.go`
- Create: `node-runtime/internal/runner/service_windows.go`
- Test: `node-runtime/internal/runner/manager_test.go`

- [ ] **Step 1: Write a failing configuration test**

```go
func TestConfigurePinsTagsAndConcurrency(t *testing.T) {
	dir := t.TempDir()
	runner := &fakeCommandRunner{}
	m := NewManager(dir, runner, fakeService{})
	err := m.Configure(Config{URL: "https://gitlab.internal", Token: "glrt-one-time",
		Tag: "repair-node-node-1", Concurrency: 4, BuildsDir: filepath.Join(dir, "builds")})
	if err != nil { t.Fatal(err) }
	config := mustRead(t, filepath.Join(dir, "config.toml"))
	assertContains(t, config, `concurrent = 4`)
	assertContains(t, config, `run_untagged = false`)
	assertContains(t, config, `tag_list = ["repair-node-node-1"]`)
}
```

- [ ] **Step 2: Run and confirm manager is missing**

Run: `go -C node-runtime test ./internal/runner -run TestConfigure`

Expected: FAIL to compile.

- [ ] **Step 3: Implement config validation and OS service adapters**

Clamp concurrency to 1–10, resolve all paths to absolute paths, and invoke Runner registration with an argument vector rather than a shell:

```go
args := []string{"register", "--non-interactive", "--url", cfg.URL,
	"--token", cfg.Token, "--executor", "shell", "--description", cfg.Tag,
	"--tag-list", cfg.Tag, "--locked=true", "--run-untagged=false",
	"--builds-dir", cfg.BuildsDir}
if err := m.commands.Run(ctx, m.binary, args, sanitizedRunnerEnv()); err != nil { return err }
```

Service adapters execute these fixed actions:

| OS | Install/start/restart mechanism | Default service identity |
|---|---|---|
| Linux | `systemctl` unit `repair-node.service` and `gitlab-runner.service` | dedicated `repair-node` user |
| macOS | user LaunchAgent plists under `~/Library/LaunchAgents` | logged-in developer |
| Windows | SCM services through `golang.org/x/sys/windows/svc` | dedicated local service account |

Do not download Runner automatically. `repair-node doctor` must report the expected installation URL and checksum field when the binary is absent.

- [ ] **Step 4: Run unit tests and platform builds**

```powershell
go -C node-runtime test ./internal/runner
New-Item -ItemType Directory -Force node-runtime/dist | Out-Null
$env:GOOS='linux'
$env:GOARCH='amd64'
go -C node-runtime build -o dist/repair-node-linux-amd64 ./cmd/repair-node
$env:GOOS='darwin'
$env:GOARCH='arm64'
go -C node-runtime build -o dist/repair-node-darwin-arm64 ./cmd/repair-node
$env:GOOS='windows'
$env:GOARCH='amd64'
go -C node-runtime build -o dist/repair-node-windows-amd64.exe ./cmd/repair-node
Remove-Item Env:GOOS
Remove-Item Env:GOARCH
```

Expected: tests and all three cross-compiles exit 0.

- [ ] **Step 5: Commit**

```bash
git add node-runtime/internal/runner
git commit -m "feat(node): manage cross-platform GitLab Runner"
```

### Task 4: Expose attempt bootstrap and ordered event APIs

**Files:**
- Create: `control-plane/src/main/resources/db/migration/V4__execution.sql`
- Create: `control-plane/src/main/java/com/company/loopengine/execution/AttemptBootstrapController.java`
- Create: `control-plane/src/main/java/com/company/loopengine/execution/AttemptEventController.java`
- Create: `control-plane/src/main/java/com/company/loopengine/execution/AttemptService.java`
- Create: `control-plane/src/main/java/com/company/loopengine/execution/AttachmentProxyController.java`
- Test: `control-plane/src/test/java/com/company/loopengine/execution/AttemptApiTest.java`

- [ ] **Step 1: Write a failing bootstrap-and-replay integration test**

```java
@Test
void startsOnlyTheReservedNodeAndAcknowledgesDuplicateEvents() throws Exception {
    Reservation reservation = activeReservation(taskId, nodeId, clock.instant().plusSeconds(120));
    BootstrapResponse bootstrap = api.bootstrap(deviceCertificate(nodeId),
        new BootstrapRequest(taskId, reservation.id(), 101L, 202L));
    assertThat(bootstrap.taskToken()).isNotBlank();
    assertThat(bootstrap.taskPackage().attemptId()).isEqualTo(bootstrap.attemptId());

    assertThat(api.append(bootstrap.taskToken(), events(1, 2, 3)).ackSeq()).isEqualTo(3);
    assertThat(api.append(bootstrap.taskToken(), events(2, 3, 4)).ackSeq()).isEqualTo(4);
    assertThat(eventRepository.count(bootstrap.attemptId())).isEqualTo(4);
}
```

- [ ] **Step 2: Run and confirm attempt APIs are absent**

Run: `mvn -pl control-plane test -Dtest=AttemptApiTest`

Expected: FAIL because attempt tables and controllers do not exist.

- [ ] **Step 3: Add attempt persistence and task-scoped tokens**

```sql
create table repair_attempt (
  id uuid primary key,
  task_id uuid not null references repair_task(id),
  attempt_no integer not null,
  node_id uuid not null references repair_node(id),
  reservation_id uuid not null references task_reservation(id),
  pipeline_id bigint not null,
  job_id bigint not null,
  state varchar(32) not null,
  task_token_hash char(64) not null unique,
  lease_expires_at timestamptz not null,
  started_at timestamptz not null,
  finished_at timestamptz null,
  outcome_code varchar(128) null,
  unique(task_id, attempt_no),
  unique(job_id)
);
create table task_event (
  attempt_id uuid not null references repair_attempt(id),
  seq bigint not null,
  event_time timestamptz not null,
  received_at timestamptz not null default now(),
  type varchar(128) not null,
  payload_json jsonb not null,
  primary key(attempt_id, seq)
);
create index ix_task_event_time on task_event(attempt_id, event_time);
```

Under one transaction, lock and validate the active reservation, node identity, 120-second expiry and task state; create exactly one attempt for the GitLab `jobId`, move the task/defect to `RUNNING`, consume the reservation, and generate a 32-byte opaque token. Store only its SHA-256 hash and return it once. Its lease is 15 minutes and each accepted event extends it by 15 minutes, capped at the Job timeout.

Event append must validate protocol v1, matching task/attempt/node IDs, payload ≤256 KiB and batch ≤100. Insert with `on conflict(attempt_id,seq) do nothing`, reject a new sequence that creates a gap beyond the current contiguous acknowledgment, and return the greatest contiguous `ackSeq`.

The generated task package replaces every GitLab upload URL with `/api/node/v1/attempts/{attemptId}/attachments/{attachmentId}`. That endpoint accepts only the matching task token, fetches the original upload with the control plane's read credential into a bounded temporary file, verifies the stored size and SHA-256, then streams it with the stored content type and filename. Maximum response is 20 MiB; a changed digest returns 409 `ATTACHMENT_CHANGED`. GitLab credentials, original private URL and redirect targets never appear in the node response.

- [ ] **Step 4: Run auth, replay, gap and expiry tests**

Run: `mvn -pl control-plane test -Dtest=AttemptApiTest`

Expected: PASS; wrong node, expired reservation, wrong token and sequence gap are rejected, while replay is idempotent.

- [ ] **Step 5: Commit**

```bash
git add control-plane/src/main/resources/db/migration/V4__execution.sql control-plane/src/main/java/com/company/loopengine/execution control-plane/src/test/java/com/company/loopengine/execution
git commit -m "feat(execution): bootstrap attempts and ingest events"
```

### Task 5: Bootstrap a task and create an isolated workspace

**Files:**
- Create: `node-runtime/internal/executor/bootstrap.go`
- Create: `node-runtime/internal/workspace/workspace.go`
- Create: `node-runtime/internal/credentials/env.go`
- Test: `node-runtime/internal/executor/bootstrap_test.go`

- [ ] **Step 1: Write a failing credential-isolation test**

```go
func TestBootstrapClonesThenRemovesGitLabCredentials(t *testing.T) {
	git := &fakeGit{head: "aabbcc", envSeen: map[string]string{"CI_JOB_TOKEN": "secret"}}
	ws, err := bootstrap.Run(context.Background(), taskPackage("aabbcc"), git)
	if err != nil { t.Fatal(err) }
	if ws.BaseSHA != "aabbcc" { t.Fatalf("base = %s", ws.BaseSHA) }
	for _, key := range []string{"CI_JOB_TOKEN", "GITLAB_TOKEN", "GIT_ASKPASS"} {
		if _, ok := ws.AgentEnv[key]; ok { t.Fatalf("agent inherited %s", key) }
	}
}
```

- [ ] **Step 2: Run and confirm bootstrap package is missing**

Run: `go -C node-runtime test ./internal/executor -run TestBootstrap`

Expected: FAIL to compile.

- [ ] **Step 3: Implement authenticated bootstrap then irreversible cleanup**

1. Use the node device certificate and `reservationId` to POST `/api/node/v1/attempts/bootstrap`.
2. Receive a 15-minute, task/attempt/node-bound bearer token and `task-package.json`.
3. Validate `task-package.json` size ≤1 MiB, protocol `v1`, IDs, callback host and canonical SHA-1 Base SHA.
4. Create `<workRoot>/<taskId>/<attemptId>`; reject symlinks in any parent.
5. Run `git init`, add the target URL, fetch only `baseSha` with depth 50, checkout detached, set `remote.origin.url` to `no-push://redacted`, remove credential helpers and delete askpass files.
6. Download each task-package attachment from the control-plane callback with the short task token into `<workspace-parent>/input/attachments`, verify size and SHA-256, use filenames generated from attachment UUIDs, and reject redirects to another host. Pass only these verified local paths to the prompt; never expose the original GitLab upload URL.
7. Build the AI environment from this allowlist only:

```go
var agentEnvAllowlist = map[string]bool{
	"PATH": true, "HOME": true, "USERPROFILE": true, "TMPDIR": true, "TEMP": true,
	"LANG": true, "LC_ALL": true, "JAVA_HOME": true, "MAVEN_HOME": true,
	"NODE_HOME": true, "OPENCODE_CONFIG_DIR": true, "OPENCODE_CONFIG_CONTENT": true,
	"ANTHROPIC_API_KEY": true, "OPENAI_API_KEY": true, "GOOGLE_GENERATIVE_AI_API_KEY": true,
}
```

Model environment variables come from the local `repair-node` service configuration. Never include `CI_*`, `GITLAB_*`, `GIT_ASKPASS`, proxy passwords or the short task token in OpenCode's environment.

- [ ] **Step 4: Run bootstrap path, SHA and environment tests**

Run: `go -C node-runtime test ./internal/executor ./internal/workspace ./internal/credentials`

Expected: PASS; wrong Base SHA, symlink escape and callback-host mismatch fail before OpenCode starts.

- [ ] **Step 5: Commit**

```bash
git add node-runtime/internal/executor node-runtime/internal/workspace node-runtime/internal/credentials
git commit -m "feat(executor): bootstrap isolated repair workspaces"
```

### Task 6: Wrap OpenCode behind a stable streaming Agent interface

**Files:**
- Create: `node-runtime/internal/agent/agent.go`
- Create: `node-runtime/internal/opencode/cli.go`
- Create: `node-runtime/internal/opencode/events.go`
- Create: `node-runtime/internal/opencode/testdata/run-success.jsonl`
- Test: `node-runtime/internal/opencode/cli_test.go`

- [ ] **Step 1: Write failing golden-event tests**

```go
func TestCLIMapsOpenCodeJSONToInternalEvents(t *testing.T) {
	runner := fakeProcessFromFile(t, "testdata/run-success.jsonl")
	agent := NewCLI(runner, "opencode")
	session, err := agent.Start(context.Background(), Task{Prompt: "fix issue"}, t.TempDir(), Policy{})
	if err != nil { t.Fatal(err) }
	var types []string
	for event := range session.Events() { types = append(types, event.Type) }
	if diff := cmp.Diff([]string{"agent.started", "agent.message", "tool.started", "tool.finished", "agent.finished"}, types); diff != "" {
		t.Fatal(diff)
	}
}
```

- [ ] **Step 2: Run and confirm Agent adapter is missing**

Run: `go -C node-runtime test ./internal/opencode -run TestCLIMaps`

Expected: FAIL to compile.

- [ ] **Step 3: Define the stable interface and CLI adapter**

```go
type Agent interface {
	Probe(context.Context) (Capabilities, error)
	Start(context.Context, Task, string, Policy) (Session, error)
}

type Session interface {
	ID() string
	Events() <-chan Event
	Wait() Result
	Cancel() error
}
```

Start OpenCode without a shell:

```go
args := []string{"run", "--format", "json", "--dir", workspace, prompt}
process, err := runner.Start(ctx, binary, args, policy.Environment)
```

Use `json.Decoder` over stdout with a 4 MiB maximum event size. Map only validated OpenCode event types; wrap unknown upstream events as `agent.raw` with sensitive fields removed. Read stderr into a 256 KiB ring buffer. Cancellation first sends graceful interrupt, waits 10 seconds, then kills the whole process group. `Probe` rejects versions outside the profile's validated allowlist.

- [ ] **Step 4: Run success, malformed-event, oversized-event and cancellation tests**

Run: `go -C node-runtime test ./internal/agent ./internal/opencode`

Expected: PASS; malformed output returns `OPENCODE_PROTOCOL_ERROR` without crashing the executor.

- [ ] **Step 5: Commit**

```bash
git add node-runtime/internal/agent node-runtime/internal/opencode
git commit -m "feat(executor): adapt OpenCode JSON sessions"
```

### Task 7: Spool ordered task events and recover after disconnection

**Files:**
- Create: `node-runtime/internal/spool/spool.go`
- Create: `node-runtime/internal/spool/uploader.go`
- Test: `node-runtime/internal/spool/uploader_test.go`

- [ ] **Step 1: Write a failing reconnect test**

```go
func TestUploaderResumesFromServerAcknowledgedSequence(t *testing.T) {
	spool := newSpoolWithEvents(t, 1, 2, 3, 4, 5)
	api := &fakeEventAPI{acks: []int64{2, 5}}
	uploader := NewUploader(spool, api, 3)
	if err := uploader.Flush(context.Background()); err != nil { t.Fatal(err) }
	if diff := cmp.Diff([][]int64{{1, 2, 3}, {3, 4, 5}}, api.sentSequences); diff != "" { t.Fatal(diff) }
	if spool.Acked() != 5 { t.Fatalf("acked = %d", spool.Acked()) }
}
```

- [ ] **Step 2: Run and confirm spool is missing**

Run: `go -C node-runtime test ./internal/spool -run TestUploader`

Expected: FAIL to compile.

- [ ] **Step 3: Implement append-before-send and cumulative acknowledgment**

Write one compact JSON object per line to `out/events.jsonl`, assign monotonic sequence numbers inside a mutex, flush after each terminal or test event and at least every second. POST batches of at most 100 events or 256 KiB. The server returns `ackSeq`; only then atomically persist `events.ack`. On 409, query `/events/ack` and resume from the server's value. Retry network/429/5xx with decorrelated jitter capped at 30 seconds. Never drop local events because upload failed.

- [ ] **Step 4: Run restart, duplicate and truncated-tail tests**

Run: `go -C node-runtime test ./internal/spool`

Expected: PASS; reopening a truncated final line keeps all complete events and resumes at the acknowledged sequence.

- [ ] **Step 5: Commit**

```bash
git add node-runtime/internal/spool
git commit -m "feat(executor): spool and replay ordered events"
```

### Task 8: Run validation feedback loops and build safe Artifacts

**Files:**
- Create: `node-runtime/internal/process/process.go`
- Create: `node-runtime/internal/process/process_unix.go`
- Create: `node-runtime/internal/process/process_windows.go`
- Create: `node-runtime/internal/executor/run.go`
- Create: `node-runtime/internal/artifact/builder.go`
- Test: `node-runtime/internal/executor/run_test.go`

- [ ] **Step 1: Write a failing two-round behavior test**

```go
func TestFailedValidationIsFedBackAtMostTwice(t *testing.T) {
	agent := &fakeAgent{results: []agent.Result{{Success: true}, {Success: true}, {Success: true}}}
	validator := &fakeValidator{results: []Validation{{ExitCode: 1}, {ExitCode: 1}, {ExitCode: 1}}}
	result := NewRun(agent, validator, artifact.NewBuilder()).Execute(context.Background(), taskWithMaxRounds(2))
	if result.Code != "VALIDATION_FAILED" { t.Fatalf("code = %s", result.Code) }
	if agent.Starts != 3 { t.Fatalf("initial plus repairs = %d", agent.Starts) }
}
```

- [ ] **Step 2: Run and confirm orchestration is missing**

Run: `go -C node-runtime test ./internal/executor -run TestFailedValidation`

Expected: FAIL to compile.

- [ ] **Step 3: Implement the finite execution sequence**

For each validation command, execute `program` and `args` directly in the workspace, enforce its `timeoutSeconds`, capture stdout/stderr to separate 10 MiB rotating files, and kill descendants on timeout. Never concatenate a shell command. Feed OpenCode only the failing command, exit code and final 200 lines after secret redaction. Run one initial session plus at most `maxRepairRounds` feedback sessions; default 2 and reject values above 2 in phase one.

After success run these exact Git commands:

```text
git status --porcelain=v1 -z
git diff --binary --full-index --no-ext-diff HEAD --
git diff --check
```

Reject no diff, submodule changes, symlinks escaping the repository, forbidden paths, more than `maxChangedFiles`, or patch bytes above `maxPatchBytes`. Write `out/change.patch`, copy test reports beneath `out/test-results`, and write:

```json
{
  "protocol": "v1",
  "taskId": "0dbb4b5e-bf4c-4f18-8d20-f15a169c5c3a",
  "attemptId": "9a606751-5a0c-43ba-8cd6-07bb64729e74",
  "nodeId": "c0c96215-fc48-4f49-a2bb-1dd03bdad413",
  "baseSha": "0123456789012345678901234567890123456789",
  "patchSha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "patchBytes": 1234,
  "changedFiles": [{"path": "src/A.java", "status": "modified"}],
  "validationResults": [{"program": "mvn", "args": ["-B", "test"], "exitCode": 0, "durationMs": 4000}],
  "eventLog": {"path": "events.jsonl", "sha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "lastSeq": 42},
  "startedAt": "2026-07-18T08:00:00Z",
  "finishedAt": "2026-07-18T08:05:00Z",
  "outcome": "SUCCEEDED"
}
```

- [ ] **Step 4: Run executor, artifact and race tests**

```powershell
go -C node-runtime test -race ./internal/executor ./internal/artifact ./internal/spool
go -C node-runtime test ./...
go -C node-runtime build ./cmd/repair-node ./cmd/repair-executor
```

Expected: all commands exit 0; the failing validation fixture creates a failed manifest and never a successful patch result.

- [ ] **Step 5: Commit**

```bash
git add node-runtime/internal/process node-runtime/internal/executor node-runtime/internal/artifact node-runtime/cmd/repair-executor/main.go
git commit -m "feat(executor): validate repairs and emit artifacts"
```

## Plan 04 completion gate

- `repair-node join`, heartbeat, desired config and Runner restart work without transmitting model keys.
- Linux amd64, macOS arm64 and Windows amd64 binaries compile; Linux/macOS are release-blocking, Windows is beta.
- OpenCode sees only the allowlisted local model configuration and no GitLab write or task credential.
- Every event is append-before-send and is replayed in order after a simulated 10-minute disconnection.
- Validation runs without a shell and stops process trees on timeout.
- A successful Job always contains a non-empty checked patch, manifest, JSONL and test evidence; a failed Job never claims success.
