# 循环工程一期：Artifact、Publisher、Merge Request 与状态同步 Implementation Plan

> **执行说明：** 每次只执行一个 Task。若环境具备 `superpowers:subagent-driven-development` 或 `superpowers:executing-plans`，可以使用；不具备这些技能时，直接严格遵循本 Task 的 checkbox、Red→Green、验证和提交步骤。缺少技能不得成为跳步、伪造验证或扩大范围的理由。

**Goal:** 将成功 Job 的只读候选 Artifact 在控制面重新校验，安全发布到目标 GitLab 项目的新分支和 Merge Request，并且只在全部门槛满足后把缺陷标记为 `repair::ready-for-test`。

**Architecture:** 节点没有写权限。中央 Publisher 监听 repair Job 终态、下载 Artifact，在全新临时仓库验证 manifest、Base SHA、补丁和路径策略，再使用独立机器人凭据推分支并幂等创建 MR；每一步保存发布状态并通过 Outbox 同步 Issue。

**Tech Stack:** Spring Boot 4.1、PostgreSQL 17、Flyway、Git CLI、GitLab Jobs/Artifacts/Branches/Merge Requests API v4、WireMock、Testcontainers。

---

## File map

- `control-plane/src/main/resources/db/migration/V5__publishing.sql`：Artifact 与发布记录。
- `control-plane/src/main/java/com/company/loopengine/execution/job/*`：Job Hook 和终态核对。
- `control-plane/src/main/java/com/company/loopengine/publishing/artifact/*`：下载、解压、摘要验证。
- `control-plane/src/main/java/com/company/loopengine/publishing/git/*`：临时仓库、补丁和推送。
- `control-plane/src/main/java/com/company/loopengine/publishing/mr/*`：MR 幂等创建。
- `control-plane/src/main/java/com/company/loopengine/publishing/PublishRepair.java`：发布编排。
- `control-plane/src/test/java/com/company/loopengine/publishing/*`：安全与流程测试。

### Task 1: Track GitLab repair Job terminal states

**Files:**
- Create: `control-plane/src/main/resources/db/migration/V5__publishing.sql`
- Create: `control-plane/src/main/java/com/company/loopengine/execution/job/RepairJobWebhookController.java`
- Create: `control-plane/src/main/java/com/company/loopengine/execution/job/RepairJobService.java`
- Test: `control-plane/src/test/java/com/company/loopengine/execution/job/RepairJobServiceTest.java`

- [ ] **Step 1: Write a failing terminal-state test**

```java
@Test
void successfulKnownJobQueuesPublishingOnce() {
    Attempt attempt = runningAttempt(202L);
    service.handle(jobHook(202L, "success", "evt-job-1"));
    service.handle(jobHook(202L, "success", "evt-job-1"));
    assertThat(attempts.get(attempt.id()).state()).isEqualTo(ARTIFACT_PENDING);
    assertThat(publishQueue.countFor(attempt.id())).isEqualTo(1);
}

@Test
void exhaustedFailedJobNeverQueuesPublishing() {
    Attempt attempt = runningAttempt(203L, 2, profileWithMaxExternalAttempts(2));
    service.handle(jobHook(203L, "failed", "evt-job-2"));
    assertThat(attempts.get(attempt.id()).state()).isEqualTo(FAILED);
    assertThat(publishQueue.countFor(attempt.id())).isZero();
}
```

- [ ] **Step 2: Run and confirm the job service is absent**

Run: `mvn -pl control-plane test -Dtest=RepairJobServiceTest`

Expected: FAIL to compile.

- [ ] **Step 3: Add publishing persistence and Job Hook handling**

```sql
create table job_delivery (
  event_uuid varchar(64) primary key,
  job_id bigint not null,
  payload_json jsonb not null,
  received_at timestamptz not null default now()
);
create table publish_record (
  id uuid primary key,
  task_id uuid not null unique references repair_task(id),
  attempt_id uuid not null unique references repair_attempt(id),
  state varchar(32) not null,
  artifact_sha256 char(64) null,
  patch_sha256 char(64) null,
  branch_name text null,
  commit_sha char(40) null,
  merge_request_iid bigint null,
  merge_request_url text null,
  failure_code varchar(128) null,
  failure_detail text null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create table publish_step (
  publish_id uuid not null references publish_record(id),
  step varchar(32) not null,
  state varchar(16) not null,
  idempotency_key varchar(256) not null,
  detail_json jsonb not null,
  started_at timestamptz not null,
  finished_at timestamptz null,
  primary key(publish_id, step),
  unique(idempotency_key)
);
```

Authenticate the central repair project's Job Hook with a separate secret. Accept only configured central project ID. Insert `job_delivery` with `on conflict do nothing`; ignore unknown job IDs but emit a security audit. For `success`, create one `PENDING` publish record and set attempt `ARTIFACT_PENDING`. For `failed`, `canceled` or `timedout`, finish the attempt and release the node slot. Runner/system/API/stuck failures requeue the task on a different eligible node while `attemptNo < profile.maxExternalAttempts`; script/validation/OpenCode failures requeue only when `retryFunctionalFailure=true`. Otherwise finish task and defect as `FAILED`. Never reuse an attempt ID or its task token.

- [ ] **Step 4: Run duplicate, unknown and failed Job tests**

Run: `mvn -pl control-plane test -Dtest=RepairJobServiceTest`

Expected: PASS; duplicate Hook changes no counts, unknown project returns 403.

- [ ] **Step 5: Commit**

```bash
git add control-plane/src/main/resources/db/migration/V5__publishing.sql control-plane/src/main/java/com/company/loopengine/execution/job control-plane/src/test/java/com/company/loopengine/execution/job
git commit -m "feat(publisher): track repair job outcomes"
```

### Task 2: Download and verify the Artifact as hostile input

**Files:**
- Create: `control-plane/src/main/java/com/company/loopengine/publishing/artifact/ArtifactDownloader.java`
- Create: `control-plane/src/main/java/com/company/loopengine/publishing/artifact/ArtifactVerifier.java`
- Create: `control-plane/src/main/java/com/company/loopengine/publishing/artifact/VerifiedArtifact.java`
- Test: `control-plane/src/test/java/com/company/loopengine/publishing/artifact/ArtifactVerifierTest.java`

- [ ] **Step 1: Write failing happy-path and zip-slip tests**

```java
@Test
void verifiesManifestAndEveryReferencedDigest() {
    Path zip = fixture("valid-artifact.zip");
    VerifiedArtifact result = verifier.verify(zip, expectedAttempt());
    assertThat(result.patchSha256()).isEqualTo(sha256(result.patch()));
    assertThat(result.manifest().outcome()).isEqualTo("SUCCEEDED");
}

@Test
void rejectsArchiveEntryOutsideExtractionRoot() {
    assertThatThrownBy(() -> verifier.verify(fixture("zip-slip.zip"), expectedAttempt()))
        .isInstanceOf(UnsafeArtifactException.class)
        .hasMessageContaining("ARCHIVE_PATH_ESCAPE");
}
```

- [ ] **Step 2: Run and confirm verifier is missing**

Run: `mvn -pl control-plane test -Dtest=ArtifactVerifierTest`

Expected: FAIL to compile.

- [ ] **Step 3: Implement bounded download and verification**

Download `GET /projects/{centralProjectId}/jobs/{jobId}/artifacts` to a newly created temp file. Use connect timeout 3 seconds, response timeout 60 seconds and maximum response 200 MiB. Compute archive SHA-256 while streaming.

When extracting, reject absolute paths, `..`, drive letters, symlinks and duplicate names; cap entries at 10,000, extracted bytes at 500 MiB and each file at 100 MiB. Require exactly these files:

```text
out/change.patch
out/result-manifest.json
out/events.jsonl
```

Validate `result-manifest.json` against `contracts/v1/result-manifest.schema.json`, then compare task ID, attempt ID, node ID, Base SHA, patch SHA-256, event-log SHA-256 and last event sequence with PostgreSQL. Require outcome `SUCCEEDED`, non-empty patch and every mandatory validation exit code 0.

- [ ] **Step 4: Run corrupt ZIP, oversized file, wrong digest and wrong-attempt tests**

Run: `mvn -pl control-plane test -Dtest=ArtifactVerifierTest`

Expected: PASS; every hostile fixture returns a stable failure code and never reaches Git.

- [ ] **Step 5: Commit**

```bash
git add control-plane/src/main/java/com/company/loopengine/publishing/artifact control-plane/src/test/java/com/company/loopengine/publishing/artifact
git commit -m "feat(publisher): verify untrusted job artifacts"
```

### Task 3: Apply the patch in a new trusted repository

**Files:**
- Create: `control-plane/src/main/java/com/company/loopengine/publishing/git/GitProcess.java`
- Create: `control-plane/src/main/java/com/company/loopengine/publishing/git/PatchPolicy.java`
- Create: `control-plane/src/main/java/com/company/loopengine/publishing/git/PreparePublication.java`
- Test: `control-plane/src/test/java/com/company/loopengine/publishing/git/PreparePublicationTest.java`

- [ ] **Step 1: Write a failing Base-SHA and forbidden-path test**

```java
@Test
void appliesOnlyWhenTargetBranchStillMatchesTheTaskBase() {
    Publication publication = preparer.prepare(task(baseSha), verifiedPatch("src/A.java"));
    assertThat(publication.parentSha()).isEqualTo(baseSha);
    assertThat(publication.changedFiles()).containsExactly("src/A.java");
}

@Test
void blocksPublicationWhenTargetMoved() {
    advanceRemoteMain();
    assertThatThrownBy(() -> preparer.prepare(task(baseSha), verifiedPatch("src/A.java")))
        .isInstanceOf(BaseMovedException.class);
}

@Test
void rejectsForbiddenFileEvenWhenManifestLied() {
    assertThatThrownBy(() -> preparer.prepare(task(baseSha), verifiedPatch(".gitlab-ci.yml")))
        .isInstanceOf(PatchPolicyException.class);
}
```

- [ ] **Step 2: Run and confirm Git preparation is absent**

Run: `mvn -pl control-plane test -Dtest=PreparePublicationTest`

Expected: FAIL to compile.

- [ ] **Step 3: Implement clean-clone verification without a shell**

Create a unique directory beneath `LOOP_PUBLISH_WORK_ROOT`, ensure it is not a symlink, and run argument-vector Git commands with a 10-minute overall timeout:

```text
git init <dir>
git -C <dir> remote add origin <target-url>
git -C <dir> fetch --no-tags --depth=50 origin refs/heads/<target>:refs/remotes/origin/<target>
git -C <dir> rev-parse refs/remotes/origin/<target>
git -C <dir> checkout --detach <baseSha>
git -C <dir> apply --check --whitespace=error-all <absolute-change.patch>
git -C <dir> apply --index --whitespace=error-all <absolute-change.patch>
git -C <dir> diff --cached --check
git -C <dir> diff --cached --name-status -z
```

The fetched target SHA must equal the task Base SHA. If it moved, mark this publication `BLOCKED/BASE_MOVED`, create a new task revision on the latest SHA and do not publish stale test evidence. Recompute changed files from the Git index and reapply the profile's forbidden paths, file count and patch-size policy; do not trust manifest filenames.

Use an ephemeral `GIT_ASKPASS` file for the robot read token, delete it immediately after fetch, and redact process arguments and environment in logs.

- [ ] **Step 4: Run valid, malformed, binary, rename, traversal and moved-base tests**

Run: `mvn -pl control-plane test -Dtest=PreparePublicationTest`

Expected: PASS; no rejected case creates a branch or calls the GitLab MR API.

- [ ] **Step 5: Commit**

```bash
git add control-plane/src/main/java/com/company/loopengine/publishing/git control-plane/src/test/java/com/company/loopengine/publishing/git
git commit -m "feat(publisher): revalidate patches in clean repositories"
```

### Task 4: Commit and push an idempotent repair branch

**Files:**
- Create: `control-plane/src/main/java/com/company/loopengine/publishing/git/BranchPublisher.java`
- Create: `control-plane/src/main/java/com/company/loopengine/publishing/RobotCredentialProvider.java`
- Test: `control-plane/src/test/java/com/company/loopengine/publishing/git/BranchPublisherTest.java`

- [ ] **Step 1: Write a failing repeat-publication test**

```java
@Test
void repeatedPublishReturnsTheExistingIdenticalCommit() {
    PublishedBranch first = publisher.publish(prepared(taskId, issueIid, patchSha));
    PublishedBranch second = publisher.publish(prepared(taskId, issueIid, patchSha));
    assertThat(second.branch()).isEqualTo(first.branch());
    assertThat(second.commitSha()).isEqualTo(first.commitSha());
    assertThat(remoteBranchCount("repair/backend-a/12-" + shortId(taskId))).isEqualTo(1);
}
```

- [ ] **Step 2: Run and confirm branch publisher is missing**

Run: `mvn -pl control-plane test -Dtest=BranchPublisherTest`

Expected: FAIL to compile.

- [ ] **Step 3: Implement deterministic branch and commit metadata**

Branch name: `repair/{sanitized-project-key}/{issue-iid}-{first-8-task-id}`. Commit message:

```text
fix(<module>): repair GitLab issue #<iid>

Loop-Engine-Task: <task-id>
Loop-Engine-Attempt: <attempt-id>
Loop-Engine-Patch-SHA256: <patch-sha256>
```

Configure a fixed robot name/email, commit the staged index, and push with `git push origin HEAD:refs/heads/<branch>` without force. If the branch exists, fetch it and accept only when its commit contains the same task/attempt/patch trailers; otherwise fail `BRANCH_CONFLICT`. Keep the robot token in a process-local secret provider backed by a mounted file or Vault adapter, never in a node task, database field, URL or command argument.

- [ ] **Step 4: Run idempotency, name-sanitization and conflict tests**

Run: `mvn -pl control-plane test -Dtest=BranchPublisherTest`

Expected: PASS; a foreign existing branch is never overwritten.

- [ ] **Step 5: Commit**

```bash
git add control-plane/src/main/java/com/company/loopengine/publishing/git control-plane/src/main/java/com/company/loopengine/publishing/RobotCredentialProvider.java control-plane/src/test/java/com/company/loopengine/publishing/git
git commit -m "feat(publisher): push idempotent repair branches"
```

### Task 5: Create or recover the Merge Request idempotently

**Files:**
- Create: `control-plane/src/main/java/com/company/loopengine/publishing/mr/MergeRequestPublisher.java`
- Create: `control-plane/src/main/java/com/company/loopengine/gitlab/client/GitLabMergeRequestClient.java`
- Test: `control-plane/src/test/java/com/company/loopengine/publishing/mr/MergeRequestPublisherTest.java`

- [ ] **Step 1: Write a failing timeout-recovery test**

```java
@Test
void findsTheCreatedMrAfterTheCreateResponseWasLost() {
    gitlab.createReturnsConnectionResetAfterPersisting();
    gitlab.searchReturns(mr(44, branch, "https://gitlab/mr/44", marker(taskId)));
    PublishedMergeRequest result = publisher.ensure(request(taskId, branch));
    assertThat(result.iid()).isEqualTo(44);
    assertThat(gitlab.createCalls()).isEqualTo(1);
}
```

- [ ] **Step 2: Run and confirm MR publisher is missing**

Run: `mvn -pl control-plane test -Dtest=MergeRequestPublisherTest`

Expected: FAIL to compile.

- [ ] **Step 3: Implement marker-based create-or-find**

Before POST, search open and closed MRs by source branch. Accept an existing MR only when its description contains `<!-- loop-engine:task:{taskId} -->`, source branch and target branch match. Otherwise POST `/projects/{targetProjectId}/merge_requests` with:

```json
{
  "source_branch": "repair/backend-a/12-12345678",
  "target_branch": "main",
  "title": "fix(order): GitLab issue #12",
  "description": "Closes engineering/defect-intake#12\n\n自动修复摘要：修正订单金额舍入并通过必选单元测试。\n\n<!-- loop-engine:task:0dbb4b5e-bf4c-4f18-8d20-f15a169c5c3a -->",
  "remove_source_branch": true,
  "squash": false
}
```

Resolve configured reviewer group to user IDs before create and record unresolved reviewers as a warning, not a failed publication. After ambiguous timeout, search again before retrying POST.

- [ ] **Step 4: Run existing, create, ambiguous-timeout and foreign-MR tests**

Run: `mvn -pl control-plane test -Dtest=MergeRequestPublisherTest`

Expected: PASS; repeated calls yield one logical MR.

- [ ] **Step 5: Commit**

```bash
git add control-plane/src/main/java/com/company/loopengine/publishing/mr control-plane/src/main/java/com/company/loopengine/gitlab/client/GitLabMergeRequestClient.java control-plane/src/test/java/com/company/loopengine/publishing/mr
git commit -m "feat(publisher): create idempotent merge requests"
```

### Task 6: Finalize only after every ready-for-test gate passes

**Files:**
- Create: `control-plane/src/main/java/com/company/loopengine/publishing/PublishRepair.java`
- Create: `control-plane/src/main/java/com/company/loopengine/publishing/ReadyForTestPolicy.java`
- Test: `control-plane/src/test/java/com/company/loopengine/publishing/PublishRepairTest.java`

- [ ] **Step 1: Write a failing all-gates test**

```java
@Test
void movesToReadyForTestOnlyAfterArtifactBranchCommitAndMrExist() {
    publish.run(publishId);
    assertThat(publishes.get(publishId).state()).isEqualTo(COMPLETED);
    assertThat(tasks.get(taskId).state()).isEqualTo(READY_FOR_TEST);
    assertThat(defects.get(defectId).state()).isEqualTo(READY_FOR_TEST);
    assertThat(outbox.findPending(20)).extracting(OutboxEvent::eventType)
        .contains("GITLAB_ISSUE_READY_FOR_TEST");
}

@ParameterizedTest
@ValueSource(strings={"ARTIFACT_INVALID","BASE_MOVED","PATCH_REJECTED","PUSH_FAILED","MR_FAILED"})
void noFailureCodeCanSetReadyForTest(String failureCode) {
    arrangeFailure(failureCode);
    publish.run(publishId);
    assertThat(defects.get(defectId).state()).isNotEqualTo(READY_FOR_TEST);
}
```

- [ ] **Step 2: Run and confirm orchestration is absent**

Run: `mvn -pl control-plane test -Dtest=PublishRepairTest`

Expected: FAIL to compile.

- [ ] **Step 3: Implement restartable publish steps and the hard gate**

Run steps `ARTIFACT_VERIFIED`, `PATCH_PREPARED`, `BRANCH_PUSHED`, `MR_CREATED`, `STATE_FINALIZED`. Before each external action, read its `publish_step`; skip a completed step after revalidating its recorded output. Save each completed output before advancing.

`ReadyForTestPolicy` requires all of:

```java
return artifact.verified()
    && artifact.patchBytes() > 0
    && artifact.mandatoryValidationsPassed()
    && branch.commitSha() != null
    && mergeRequest.iid() != null
    && attempt.hasTerminalSuccessEvent()
    && publishSteps.allCompletedBefore("STATE_FINALIZED");
```

In one final transaction, complete publish/attempt/task, release the node slot, move defect to `READY_FOR_TEST`, append transitions/audit and enqueue Issue label/comment. The comment includes node name, validation summary, changed-file list, commit and MR links but no model prompt, model key or raw token.

- [ ] **Step 4: Run failure injection at every step boundary**

Run: `mvn -pl control-plane test -Dtest=PublishRepairTest`

Expected: PASS; restarting after each injected crash converges to one branch, one commit, one MR and one ready-for-test comment.

- [ ] **Step 5: Commit**

```bash
git add control-plane/src/main/java/com/company/loopengine/publishing control-plane/src/test/java/com/company/loopengine/publishing
git commit -m "feat(publisher): finalize ready-for-test safely"
```

### Task 7: Reconcile lost Job Hooks and prove publication end to end

**Files:**
- Create: `control-plane/src/main/java/com/company/loopengine/execution/job/RepairJobReconciler.java`
- Create: `control-plane/src/test/java/com/company/loopengine/publishing/PublicationFlowTest.java`
- Create: `docs/operations/publisher-recovery.md`

- [ ] **Step 1: Write a failing lost-Hook recovery test**

```java
@Test
void reconcilerFindsCompletedGitLabJobAndFinishesPublication() {
    Attempt attempt = runningAttemptWithSuccessfulRemoteJob();
    reconciler.runOnce();
    publishWorker.drain();
    assertThat(defects.get(attempt.defectId()).state()).isEqualTo(READY_FOR_TEST);
    assertThat(gitlab.countMergeRequestsFor(attempt.taskId())).isEqualTo(1);
}
```

- [ ] **Step 2: Run and confirm reconciler is absent**

Run: `mvn -pl control-plane test -Dtest=PublicationFlowTest`

Expected: FAIL to compile.

- [ ] **Step 3: Implement bounded reconciliation and operator recovery**

Every minute, query attempts in `RUNNING`/`ARTIFACT_PENDING` whose last update is older than 60 seconds. Read the Job from GitLab, feed terminal state into the same `RepairJobService`, and process no more than 100 attempts per run. When a node is offline but GitLab still reports the Job running, mark the attempt `SUSPECT` and do not reassign; first cancel or observe a terminal old Job so two writers cannot repair the same task. A Job not found for 30 minutes becomes `BLOCKED/JOB_NOT_FOUND`; an operator may retry from the management API, which creates a new attempt rather than mutating the historical one.

Document exact recovery actions:

```text
ARTIFACT_INVALID: inspect failure code; never override digest mismatch; create a new attempt.
BASE_MOVED: accept the automatically created task revision on the new Base SHA.
PUSH_FAILED: rotate/restore Publisher robot credential, then retry the same publish record.
MR_FAILED: restore GitLab API access, then retry; create-or-find prevents duplicates.
JOB_NOT_FOUND: verify central project retention, then create a new attempt.
```

- [ ] **Step 4: Run the phase and full backend suites**

```powershell
mvn -pl control-plane test -Dtest='*Publish*,*Artifact*,*RepairJob*'
mvn -pl control-plane test
git diff --check
```

Expected: all commands exit 0; the flow test uses a real temporary bare Git repository and a fake GitLab HTTP API.

- [ ] **Step 5: Commit**

```bash
git add control-plane/src/main/java/com/company/loopengine/execution/job control-plane/src/test/java/com/company/loopengine/publishing docs/operations/publisher-recovery.md
git commit -m "test(publisher): prove recovery and publication flow"
```

## Plan 05 completion gate

- Node and Job credentials cannot push to target repositories; only Publisher can write.
- Artifact archive, manifest, event log, patch and IDs are independently verified before Git access.
- A moved target branch never reuses stale unit-test evidence.
- Publisher never force-pushes or overwrites an unrelated branch/MR.
- Crash/retry after every external action converges without duplicate branch, MR or Issue comment.
- `repair::ready-for-test` is impossible unless patch, mandatory unit tests, commit and MR all exist and audit events are stored.
