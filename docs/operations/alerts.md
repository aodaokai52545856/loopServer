# Loop Engine Prometheus 告警说明

控制面通过 `/actuator/prometheus` 暴露低基数指标；告警规则见 `deploy/prometheus/alerts.yml`。个人节点没有仓库写权限；告警不得包含 Issue/Task/Node ID、URL 或错误原文标签。

## 指标约定

| 指标 | 标签 | 含义 |
|---|---|---|
| `loop_defects_current` | `state`, `project_key` | 各状态缺陷数 |
| `loop_tasks_current` | `state`, `project_key` | 各状态任务数 |
| `loop_task_wait_seconds` | `project_key` | 排队等待时长 |
| `loop_attempt_duration_seconds` | `outcome`, `executor` | Attempt 耗时 |
| `loop_nodes` | `state`, `os` | 节点数量 |
| `loop_node_slots` | `kind=active\|limit` | 活跃/上限并发槽 |
| `loop_webhook_deliveries_total` | `result` | Webhook 处理结果 |
| `loop_outbox_events_total` | `result`, `type` | Outbox 结果 |
| `loop_publish_total` | `result`, `step` | 发布步骤结果 |

## 告警

1. **LoopEngineNoSchedulableNode**（10m）：存在 `queued` 任务且 `online|busy` 节点为 0。处置：检查节点心跳、邀请/证书、Drain/Disable 状态。
2. **LoopEngineOutboxBacklog**（15m）：`result="pending"` Outbox 超过 100。处置：检查 GitLab API、重放死信、核对机器人 Token。
3. **LoopEngineHighAttemptFailure**（30m）：Attempt 非成功占比 &gt; 50%。处置：按 `executor`/`outcome` 分类查看构建、OpenCode 与 Artifact 证据；禁止为冲指标跳过 `ready-for-test` 门槛。

## 日志与审计

- JSON 日志字段：`timestamp`、`level`、`service`、`requestId`、`taskId`、`attemptId`、`nodeId`、`event`、`errorCode`。
- 脱敏：已知 Token 前缀（如 `glpat-`）以及键名匹配 `(?i).*(token|secret|password|api.?key|private.?key).*` 的值。
- 管理突变审计必须包含 actor、action、object、reason 与 request ID；日志与审计均不得写入模型密钥或仓库写 Token。
