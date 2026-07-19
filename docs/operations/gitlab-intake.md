# GitLab 缺陷入口操作说明

## Webhook

在 intake 项目配置 Issue Webhook：

```text
URL: https://loop-engine.internal/internal/webhooks/gitlab
Trigger: Issues events
Secret token: injected as LOOP_GITLAB_WEBHOOK_TOKEN
SSL verification: enabled
Initial label: repair::new
Intake project: engineering/defect-intake
```

## Issue 模板

1. 将 `deploy/gitlab/defect-issue-template.md` 复制到 intake 项目路径 `.gitlab/issue_templates/缺陷.md`。
2. 将模板默认标签设为 `repair::new`。

## 状态标签

在 intake 项目创建以下互斥 scoped labels：

- `repair::new`
- `repair::triaging`
- `repair::needs-info`
- `repair::queued`
- `repair::running`
- `repair::blocked`
- `repair::failed`
- `repair::ready-for-test`
- `repair::cancelled`

## 丢失 Hook 补偿

控制面 `IssueReconciler` 应按 **每 5 分钟** 调用一次 `runOnce()`：

- 拉取 `updated_after` 游标之后、且带有任一 `repair::` 标签的 Issue
- 每页 50 条，单次运行最多 20 页
- 合成 delivery UUID：`reconcile:{projectId}:{iid}:{updatedAt}`
- 写入 `gitlab_webhook_delivery` 后走与 Webhook 相同的 `ProcessIssueDelivery`
- 仅在整页处理成功后推进游标
