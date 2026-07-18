# 循环工程一期：GitLab 缺陷入口与状态机 Implementation Plan

> **执行说明：** 每次只执行一个 Task。若环境具备 `superpowers:subagent-driven-development` 或 `superpowers:executing-plans`，可以使用；不具备这些技能时，直接严格遵循本 Task 的 checkbox、Red→Green、验证和提交步骤。缺少技能不得成为跳步、伪造验证或扩大范围的理由。

**Goal:** 将内网 GitLab `defect-intake` 项目的 Issue Webhook 幂等转换为控制面缺陷，自动判定“待补充/可排队”，并可靠回写标签与评论。

**Architecture:** Webhook 接口只验签、落原始 delivery、返回 202；异步消费者解析 Issue，使用显式状态机更新 `defect`，并在同一事务写 Outbox。GitLab 回写由 Outbox worker 完成，失败不回滚业务状态。

**Tech Stack:** Spring Boot 4.1、PostgreSQL 17、Flyway、JdbcClient、WireMock、GitLab REST API v4、JUnit 5、Testcontainers。

---

## File map

- `control-plane/src/main/resources/db/migration/V2__defect_intake.sql`：Webhook、缺陷、状态迁移表。
- `control-plane/src/main/java/com/company/loopengine/gitlab/webhook/*`：验签、收件、异步消费。
- `control-plane/src/main/java/com/company/loopengine/defect/domain/*`：完整性规则与状态机。
- `control-plane/src/main/java/com/company/loopengine/defect/application/*`：Issue 转换用例。
- `control-plane/src/main/java/com/company/loopengine/gitlab/client/*`：GitLab 读写适配器。
- `control-plane/src/test/java/com/company/loopengine/defect/*`：领域与集成测试。

### Task 1: Persist each GitLab delivery exactly once

**Files:**
- Create: `control-plane/src/main/resources/db/migration/V2__defect_intake.sql`
- Create: `control-plane/src/main/java/com/company/loopengine/gitlab/webhook/WebhookDeliveryRepository.java`
- Test: `control-plane/src/test/java/com/company/loopengine/gitlab/webhook/WebhookDeliveryRepositoryTest.java`

- [ ] **Step 1: Write the failing duplicate-delivery test**

```java
@Test
void storesTheSameGitLabEventUuidOnce() {
    String body = "{\"object_kind\":\"issue\"}";
    assertThat(repository.insert("evt-1", "Issue Hook", body, Instant.parse("2026-07-18T08:00:00Z"))).isTrue();
    assertThat(repository.insert("evt-1", "Issue Hook", body, Instant.parse("2026-07-18T08:00:01Z"))).isFalse();
    assertThat(repository.count()).isEqualTo(1);
}
```

- [ ] **Step 2: Confirm the test fails for the missing table**

Run: `mvn -pl control-plane test -Dtest=WebhookDeliveryRepositoryTest`

Expected: FAIL because `gitlab_webhook_delivery` does not exist.

- [ ] **Step 3: Add the migration and insert contract**

```sql
create table gitlab_webhook_delivery (
  event_uuid varchar(64) primary key,
  event_name varchar(64) not null,
  payload_json jsonb not null,
  received_at timestamptz not null,
  processing_state varchar(16) not null default 'PENDING',
  attempt_count integer not null default 0,
  next_attempt_at timestamptz not null default now(),
  processed_at timestamptz null,
  last_error text null
);
create index ix_webhook_pending
  on gitlab_webhook_delivery(next_attempt_at)
  where processing_state in ('PENDING', 'RETRY');

create table defect (
  id uuid primary key,
  intake_project_id bigint not null,
  issue_iid bigint not null,
  issue_global_id bigint not null,
  issue_url text not null,
  title text not null,
  description text not null,
  state varchar(32) not null,
  missing_fields_json jsonb not null default '[]',
  source_updated_at timestamptz not null,
  version bigint not null default 0,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique(intake_project_id, issue_iid)
);

create table defect_transition (
  id bigserial primary key,
  defect_id uuid not null references defect(id),
  from_state varchar(32) null,
  to_state varchar(32) not null,
  reason varchar(128) not null,
  source_event_uuid varchar(64) null,
  created_at timestamptz not null default now()
);
create table defect_attachment (
  id uuid primary key,
  defect_id uuid not null references defect(id),
  source_url text not null,
  name text not null,
  content_type varchar(255) null,
  size_bytes bigint null,
  sha256 char(64) null,
  source_updated_at timestamptz not null,
  unique(defect_id, source_url)
);
```

Repository insert:

```java
public boolean insert(String eventUuid, String eventName, String payload, Instant receivedAt) {
    return jdbc.sql("""
        insert into gitlab_webhook_delivery(event_uuid,event_name,payload_json,received_at)
        values (:uuid,:name,cast(:payload as jsonb),:received)
        on conflict (event_uuid) do nothing
        """)
        .param("uuid", eventUuid).param("name", eventName)
        .param("payload", payload).param("received", receivedAt).update() == 1;
}
```

- [ ] **Step 4: Run repository tests**

Run: `mvn -pl control-plane test -Dtest=WebhookDeliveryRepositoryTest`

Expected: PASS; row count remains 1 after duplicate insert.

- [ ] **Step 5: Commit**

```bash
git add control-plane/src/main/resources/db/migration/V2__defect_intake.sql control-plane/src/main/java/com/company/loopengine/gitlab/webhook control-plane/src/test/java/com/company/loopengine/gitlab/webhook
git commit -m "feat(defect): persist GitLab webhook deliveries"
```

### Task 2: Verify the hook and acknowledge quickly

**Files:**
- Create: `control-plane/src/main/java/com/company/loopengine/gitlab/webhook/GitLabWebhookProperties.java`
- Create: `control-plane/src/main/java/com/company/loopengine/gitlab/webhook/GitLabWebhookController.java`
- Test: `control-plane/src/test/java/com/company/loopengine/gitlab/webhook/GitLabWebhookControllerTest.java`

- [ ] **Step 1: Write failing MVC tests for accepted, duplicate and invalid tokens**

```java
@Test
void acceptsAValidIssueHook() throws Exception {
    mvc.perform(post("/internal/webhooks/gitlab")
            .header("X-Gitlab-Token", "hook-secret")
            .header("X-Gitlab-Event", "Issue Hook")
            .header("X-Gitlab-Event-UUID", "evt-1")
            .contentType(APPLICATION_JSON).content(issuePayload()))
        .andExpect(status().isAccepted());
}

@Test
void rejectsAnInvalidTokenWithoutPersisting() throws Exception {
    mvc.perform(post("/internal/webhooks/gitlab")
            .header("X-Gitlab-Token", "wrong")
            .header("X-Gitlab-Event", "Issue Hook")
            .header("X-Gitlab-Event-UUID", "evt-2")
            .contentType(APPLICATION_JSON).content(issuePayload()))
        .andExpect(status().isUnauthorized());
    verifyNoInteractions(repository);
}
```

- [ ] **Step 2: Run and observe 404**

Run: `mvn -pl control-plane test -Dtest=GitLabWebhookControllerTest`

Expected: FAIL with HTTP 404 because the endpoint is absent.

- [ ] **Step 3: Implement constant-time token verification and 202 response**

```java
@ConfigurationProperties("loop.gitlab.webhook")
public record GitLabWebhookProperties(String token) {}
```

```java
@RestController
@RequestMapping("/internal/webhooks/gitlab")
class GitLabWebhookController {
    private final GitLabWebhookProperties properties;
    private final WebhookDeliveryRepository repository;

    @PostMapping
    ResponseEntity<Void> receive(
            @RequestHeader("X-Gitlab-Token") String token,
            @RequestHeader("X-Gitlab-Event") String event,
            @RequestHeader("X-Gitlab-Event-UUID") String uuid,
            @RequestBody String body) {
        if (!MessageDigest.isEqual(token.getBytes(UTF_8), properties.token().getBytes(UTF_8))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!event.equals("Issue Hook")) return ResponseEntity.accepted().build();
        repository.insert(uuid, event, body, Instant.now());
        return ResponseEntity.accepted().build();
    }
}
```

Add to `application.yml`:

```yaml
loop.gitlab.webhook.token: ${LOOP_GITLAB_WEBHOOK_TOKEN}
```

- [ ] **Step 4: Run controller and module tests**

Run: `mvn -pl control-plane test -Dtest=GitLabWebhookControllerTest`

Expected: PASS; valid and duplicate events return 202, invalid token returns 401.

- [ ] **Step 5: Commit**

```bash
git add control-plane/src/main/java/com/company/loopengine/gitlab/webhook control-plane/src/test/java/com/company/loopengine/gitlab/webhook control-plane/src/main/resources/application.yml
git commit -m "feat(defect): accept authenticated issue hooks"
```

### Task 3: Parse the Issue template deterministically

**Files:**
- Create: `control-plane/src/main/java/com/company/loopengine/defect/domain/IssueFacts.java`
- Create: `control-plane/src/main/java/com/company/loopengine/defect/domain/CompletenessResult.java`
- Create: `control-plane/src/main/java/com/company/loopengine/defect/domain/IssueDescriptionParser.java`
- Test: `control-plane/src/test/java/com/company/loopengine/defect/domain/IssueDescriptionParserTest.java`

- [ ] **Step 1: Write the failing completeness tests**

```java
@ParameterizedTest
@MethodSource("cases")
void reportsExactMissingFields(String description, List<String> expected) {
    assertThat(parser.parse(description).missingFields()).containsExactlyElementsOf(expected);
}

static Stream<Arguments> cases() {
    return Stream.of(
        arguments("## 项目标识\nbackend-a\n## 模块\norder\n## 复现步骤\n1. create\n## 期望结果\nok\n## 实际结果\n500",
                  List.of()),
        arguments("## 项目标识\nbackend-a\n## 模块\n\n## 复现步骤\n\n",
                  List.of("module", "steps", "expected", "actual"))
    );
}
```

- [ ] **Step 2: Confirm the missing parser failure**

Run: `mvn -pl control-plane test -Dtest=IssueDescriptionParserTest`

Expected: FAIL to compile because `IssueDescriptionParser` does not exist.

- [ ] **Step 3: Implement fixed headings and canonical missing-field order**

```java
public record IssueFacts(String projectKey, String module, String steps,
                         String expected, String actual, List<String> imageUrls) {}

public record CompletenessResult(IssueFacts facts, List<String> missingFields) {
    public boolean complete() { return missingFields.isEmpty(); }
}
```

```java
public final class IssueDescriptionParser {
    private static final LinkedHashMap<String,String> HEADINGS = new LinkedHashMap<>();
    static {
        HEADINGS.put("projectKey", "项目标识");
        HEADINGS.put("module", "模块");
        HEADINGS.put("steps", "复现步骤");
        HEADINGS.put("expected", "期望结果");
        HEADINGS.put("actual", "实际结果");
    }

    public CompletenessResult parse(String markdown) {
        Map<String,String> values = new LinkedHashMap<>();
        for (var entry : HEADINGS.entrySet()) values.put(entry.getKey(), section(markdown, entry.getValue()));
        List<String> missing = HEADINGS.keySet().stream().filter(k -> values.get(k).isBlank()).toList();
        List<String> images = Pattern.compile("!\\[[^]]*]\\(([^)]+)\\)").matcher(markdown).results()
            .map(m -> m.group(1)).toList();
        return new CompletenessResult(new IssueFacts(values.get("projectKey"), values.get("module"),
            values.get("steps"), values.get("expected"), values.get("actual"), images), missing);
    }

    private String section(String markdown, String heading) {
        Matcher m = Pattern.compile("(?ms)^##\\s+" + Pattern.quote(heading) + "\\s*$\\R(.*?)(?=^##\\s|\\z)")
            .matcher(markdown == null ? "" : markdown);
        return m.find() ? m.group(1).trim() : "";
    }
}
```

- [ ] **Step 4: Run parser tests**

Run: `mvn -pl control-plane test -Dtest=IssueDescriptionParserTest`

Expected: PASS for complete, incomplete, reordered and CRLF descriptions.

- [ ] **Step 5: Commit**

```bash
git add control-plane/src/main/java/com/company/loopengine/defect/domain control-plane/src/test/java/com/company/loopengine/defect/domain
git commit -m "feat(defect): parse issue completeness fields"
```

### Task 4: Enforce the defect state machine

**Files:**
- Create: `control-plane/src/main/java/com/company/loopengine/defect/domain/DefectState.java`
- Create: `control-plane/src/main/java/com/company/loopengine/defect/domain/DefectStateMachine.java`
- Test: `control-plane/src/test/java/com/company/loopengine/defect/domain/DefectStateMachineTest.java`

- [ ] **Step 1: Write failing transition-table tests**

```java
@Test
void allowsOnlyDeclaredTransitions() {
    assertThat(machine.canMove(NEW, TRIAGING)).isTrue();
    assertThat(machine.canMove(TRIAGING, NEEDS_INFO)).isTrue();
    assertThat(machine.canMove(TRIAGING, QUEUED)).isTrue();
    assertThat(machine.canMove(NEEDS_INFO, TRIAGING)).isTrue();
    assertThat(machine.canMove(READY_FOR_TEST, RUNNING)).isFalse();
    assertThatThrownBy(() -> machine.requireMove(READY_FOR_TEST, RUNNING))
        .isInstanceOf(IllegalStateException.class);
}
```

- [ ] **Step 2: Run and confirm missing domain classes**

Run: `mvn -pl control-plane test -Dtest=DefectStateMachineTest`

Expected: FAIL to compile.

- [ ] **Step 3: Implement the explicit table**

```java
public enum DefectState {
    NEW, TRIAGING, NEEDS_INFO, QUEUED, RUNNING, BLOCKED, FAILED, READY_FOR_TEST, CANCELLED
}
```

```java
public final class DefectStateMachine {
    private static final Map<DefectState, Set<DefectState>> ALLOWED = Map.of(
        NEW, Set.of(TRIAGING, CANCELLED),
        TRIAGING, Set.of(NEEDS_INFO, QUEUED, CANCELLED),
        NEEDS_INFO, Set.of(TRIAGING, CANCELLED),
        QUEUED, Set.of(TRIAGING, RUNNING, BLOCKED, CANCELLED),
        RUNNING, Set.of(BLOCKED, FAILED, READY_FOR_TEST, CANCELLED),
        BLOCKED, Set.of(TRIAGING, QUEUED, CANCELLED),
        FAILED, Set.of(TRIAGING, QUEUED, CANCELLED),
        READY_FOR_TEST, Set.of(CANCELLED),
        CANCELLED, Set.of()
    );
    public boolean canMove(DefectState from, DefectState to) { return ALLOWED.get(from).contains(to); }
    public void requireMove(DefectState from, DefectState to) {
        if (!canMove(from, to)) throw new IllegalStateException("Illegal defect transition: " + from + " -> " + to);
    }
}
```

- [ ] **Step 4: Run state-machine tests**

Run: `mvn -pl control-plane test -Dtest=DefectStateMachineTest`

Expected: PASS and every enum state appears in the transition table.

- [ ] **Step 5: Commit**

```bash
git add control-plane/src/main/java/com/company/loopengine/defect/domain control-plane/src/test/java/com/company/loopengine/defect/domain
git commit -m "feat(defect): enforce explicit lifecycle transitions"
```

### Task 5: Consume deliveries into defects and Outbox actions

**Files:**
- Create: `control-plane/src/main/java/com/company/loopengine/defect/application/ProcessIssueDelivery.java`
- Create: `control-plane/src/main/java/com/company/loopengine/defect/infra/DefectRepository.java`
- Test: `control-plane/src/test/java/com/company/loopengine/defect/application/ProcessIssueDeliveryTest.java`

- [ ] **Step 1: Write a failing transaction-level test**

```java
@Test
void incompleteIssueBecomesNeedsInfoAndRequestsExactFields() {
    useCase.handle(delivery("evt-7", issue("## 项目标识\napi-a")));

    Defect defect = defects.find(9001, 12).orElseThrow();
    assertThat(defect.state()).isEqualTo(NEEDS_INFO);
    assertThat(defect.missingFields()).containsExactly("module", "steps", "expected", "actual");
    assertThat(outbox.findPending(10)).extracting(OutboxEvent::eventType)
        .contains("GITLAB_ISSUE_NEEDS_INFO");
}
```

- [ ] **Step 2: Run and confirm no use case exists**

Run: `mvn -pl control-plane test -Dtest=ProcessIssueDeliveryTest`

Expected: FAIL to compile.

- [ ] **Step 3: Implement stale-event protection and transactional conversion**

```java
@Service
public class ProcessIssueDelivery {
    @Transactional
    public void handle(IssueDelivery event) {
        Defect current = defects.find(event.projectId(), event.issueIid())
            .orElseGet(() -> Defect.createWithSourceTime(event, Instant.EPOCH));
        if (!event.updatedAt().isAfter(current.sourceUpdatedAt())) return;

        if (current.state() == DefectState.RUNNING || current.state() == DefectState.READY_FOR_TEST) {
            defects.saveSourceSnapshot(current.id(), event);
            outbox.append(GitLabActions.issueChangedDuringActiveWork(current, event));
            return;
        }
        if (current.state() == DefectState.CANCELLED) return;

        CompletenessResult result = parser.parse(event.description());
        DefectState target = result.complete() ? DefectState.QUEUED : DefectState.NEEDS_INFO;
        stateMachine.requireMove(current.state(), DefectState.TRIAGING);
        Defect triaging = defects.save(current.moveTo(DefectState.TRIAGING, event));
        transitions.append(triaging.id(), current.state(), DefectState.TRIAGING,
            "ISSUE_UPDATED", event.eventUuid());
        stateMachine.requireMove(DefectState.TRIAGING, target);
        Defect saved = defects.save(triaging.completeTriage(result, target));
        attachments.replaceReferences(saved.id(), result.facts().imageUrls(), event.updatedAt());
        transitions.append(saved.id(), DefectState.TRIAGING, target,
            result.complete() ? "INFORMATION_COMPLETE" : "INFORMATION_MISSING", event.eventUuid());
        outbox.append(GitLabActions.forTriage(saved, result));
    }
}
```

The repository update must use `where version = :version`; a zero-row update throws `OptimisticLockingFailureException`. The delivery worker retries that exception and marks deterministic parsing errors as `DEAD` after five attempts.

- [ ] **Step 4: Run duplicate, stale and complete/incomplete cases**

Run: `mvn -pl control-plane test -Dtest=ProcessIssueDeliveryTest`

Expected: PASS; duplicate and stale deliveries do not add transitions, complete issues end at `QUEUED`.

- [ ] **Step 5: Commit**

```bash
git add control-plane/src/main/java/com/company/loopengine/defect control-plane/src/test/java/com/company/loopengine/defect
git commit -m "feat(defect): triage issue deliveries transactionally"
```

### Task 6: Reliably synchronize labels and comments to GitLab

**Files:**
- Modify: `control-plane/pom.xml`
- Create: `control-plane/src/main/java/com/company/loopengine/gitlab/client/GitLabClient.java`
- Create: `control-plane/src/main/java/com/company/loopengine/gitlab/client/HttpGitLabClient.java`
- Create: `control-plane/src/main/java/com/company/loopengine/gitlab/outbox/GitLabOutboxHandler.java`
- Test: `control-plane/src/test/java/com/company/loopengine/gitlab/outbox/GitLabOutboxHandlerTest.java`

- [ ] **Step 1: Write failing WireMock assertions**

```java
@Test
void replacesOnlyRepairScopedLabelAndPostsIdempotentNote() {
    gitlab.stubFor(put(urlEqualTo("/api/v4/projects/9001/issues/12"))
        .willReturn(okJson("{}")));
    gitlab.stubFor(post(urlEqualTo("/api/v4/projects/9001/issues/12/notes"))
        .willReturn(okJson("{\"id\":8}")));

    handler.handle(needsInfoEvent("d-1", List.of("module", "steps")));

    gitlab.verify(putRequestedFor(urlEqualTo("/api/v4/projects/9001/issues/12"))
        .withRequestBody(containing("repair::needs-info")));
    gitlab.verify(postRequestedFor(urlEqualTo("/api/v4/projects/9001/issues/12/notes"))
        .withRequestBody(containing("loop-engine:event:")));
}
```

- [ ] **Step 2: Run and confirm missing handler**

Run: `mvn -pl control-plane test -Dtest=GitLabOutboxHandlerTest`

Expected: FAIL to compile.

- [ ] **Step 3: Implement the constrained GitLab port**

Add the stable test dependency:

```xml
<dependency>
  <groupId>org.wiremock</groupId>
  <artifactId>wiremock-standalone</artifactId>
  <version>3.13.2</version>
  <scope>test</scope>
</dependency>
```

```java
public interface GitLabClient {
    void updateRepairLabel(long projectId, long issueIid, String targetLabel);
    void ensureNote(long projectId, long issueIid, String marker, String markdown);
}
```

For `updateRepairLabel`, first GET the Issue, remove labels beginning with `repair::`, preserve every other label, append the target label, then PUT the complete label array. For `ensureNote`, list notes and skip POST when the hidden marker `<!-- loop-engine:event:{outboxId} -->` already exists. Configure connect timeout 3 seconds, response timeout 10 seconds, retry 429/5xx with `1m, 5m, 30m, 2h, 8h`, and never retry 401/403/404.

Needs-info comment body:

```text
自动处理尚缺少以下信息：
- 模块
- 复现步骤

请编辑 Issue 描述并保留标准二级标题；更新后系统会重新检查。
<!-- loop-engine:event:{outboxId} -->
```

- [ ] **Step 4: Run success, duplicate-note and retry tests**

Run: `mvn -pl control-plane test -Dtest=GitLabOutboxHandlerTest`

Expected: PASS; repeated handling performs no duplicate POST, 401 becomes terminal failure, 500 is rescheduled.

- [ ] **Step 5: Commit**

```bash
git add control-plane/pom.xml control-plane/src/main/java/com/company/loopengine/gitlab control-plane/src/test/java/com/company/loopengine/gitlab
git commit -m "feat(gitlab): synchronize defect labels and notes"
```

### Task 7: Reconcile missed hooks and prove the intake flow

**Files:**
- Create: `control-plane/src/main/java/com/company/loopengine/gitlab/reconcile/IssueReconciler.java`
- Create: `control-plane/src/test/java/com/company/loopengine/defect/DefectIntakeFlowTest.java`
- Create: `deploy/gitlab/defect-issue-template.md`
- Create: `docs/operations/gitlab-intake.md`

- [ ] **Step 1: Write a failing missed-webhook test**

```java
@Test
void reconciliationImportsAnIssueWhoseWebhookWasMissed() {
    gitlabReturnsIssue(9001, 12, "repair::new", completeDescription(), "2026-07-18T08:00:00Z");
    reconciler.runOnce();
    assertThat(defects.find(9001, 12)).get().extracting(Defect::state).isEqualTo(QUEUED);
}
```

- [ ] **Step 2: Run and confirm no reconciler exists**

Run: `mvn -pl control-plane test -Dtest=DefectIntakeFlowTest`

Expected: FAIL to compile.

- [ ] **Step 3: Implement a bounded reconciliation cursor**

Every five minutes, list Issues updated after the last successful cursor where one `repair::` scoped label exists. Convert each Issue into a synthetic delivery UUID `reconcile:{projectId}:{iid}:{updatedAt}` and pass it through the same delivery repository and processor. Advance the cursor only after the full page succeeds; request 50 Issues per page and stop after 20 pages per run.

Document these exact GitLab settings in `docs/operations/gitlab-intake.md`:

```text
URL: https://loop-engine.internal/internal/webhooks/gitlab
Trigger: Issues events
Secret token: injected as LOOP_GITLAB_WEBHOOK_TOKEN
SSL verification: enabled
Initial label: repair::new
Intake project: engineering/defect-intake
```

Create `deploy/gitlab/defect-issue-template.md` and copy it into the intake project's `.gitlab/issue_templates/缺陷.md`:

```markdown
## 项目标识
<!-- 必填，例如 backend-order -->

## 模块
<!-- 必填，例如 services/order -->

## 复现步骤
<!-- 必填，写出最小且可重复的步骤 -->
1.

## 期望结果
<!-- 必填 -->

## 实际结果
<!-- 必填 -->

## 环境与版本
<!-- 选填：分支、commit、部署环境、浏览器或客户端版本 -->

## 截图与附件
<!-- 选填：直接拖入图片、日志或最小样例；请勿上传密钥和个人数据 -->
```

Create these scoped labels in the intake project: `repair::new`, `repair::triaging`, `repair::needs-info`, `repair::queued`, `repair::running`, `repair::blocked`, `repair::failed`, `repair::ready-for-test`, `repair::cancelled`. Set the Issue template's default label to `repair::new`.

- [ ] **Step 4: Run the phase test suite**

```powershell
mvn -pl control-plane test -Dtest='*Defect*,*Webhook*,*GitLabOutbox*'
mvn -pl control-plane test
```

Expected: both commands exit 0; the end-to-end test covers valid, duplicate, stale, missed and incomplete events.

- [ ] **Step 5: Commit**

```bash
git add control-plane/src/main/java/com/company/loopengine/gitlab/reconcile control-plane/src/test/java/com/company/loopengine/defect deploy/gitlab/defect-issue-template.md docs/operations/gitlab-intake.md
git commit -m "feat(defect): reconcile missed issue hooks"
```

## Plan 02 completion gate

- Webhook responds in under 500 ms without calling GitLab synchronously.
- Duplicate `X-Gitlab-Event-UUID` produces one delivery, one transition and one logical comment.
- Missing fields are deterministic and visible in both PostgreSQL and the GitLab Issue.
- Complete Issues reach `QUEUED`; incomplete Issues reach `NEEDS_INFO`.
- GitLab outage does not lose business state; Outbox retry is observable and bounded.
- Reconciliation recovers an Issue even when its Webhook was lost.
