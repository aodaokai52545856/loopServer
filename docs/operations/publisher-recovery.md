# Publisher 发布失败恢复操作说明

控制面 Publisher 在 Artifact 校验、分支推送、MR 创建与 `ready-for-test` 终态之间任一步失败时，按下列代码处理。个人节点没有仓库写权限；只有服务器端 Publisher 机器人可写。人工重试一律创建**新 Attempt**，不得改写历史 Attempt。

## 失败码与动作

```text
ARTIFACT_INVALID: inspect failure code; never override digest mismatch; create a new attempt.
BASE_MOVED: accept the automatically created task revision on the new Base SHA.
PUSH_FAILED: rotate/restore Publisher robot credential, then retry the same publish record.
MR_FAILED: restore GitLab API access, then retry; create-or-find prevents duplicates.
JOB_NOT_FOUND: verify central project retention, then create a new attempt.
```

## 丢失 Job Hook 补偿

`RepairJobReconciler` 应按 **每 1 分钟** 调用一次 `runOnce()`：

- 查询 `RUNNING` / `ARTIFACT_PENDING`（及 `SUSPECT`）且上次更新早于 60 秒的 Attempt
- 单次运行最多处理 100 条
- 从 GitLab 读取 Job；终态合成 delivery 后走与 Job Hook 相同的 `RepairJobService`
- 节点离线且 Job 仍在运行：将 Attempt 标为 `SUSPECT`，**不改派**；先取消或观察到旧 Job 终态，避免双写同一任务
- Job 连续 30 分钟找不到：Attempt → `BLOCKED` / `JOB_NOT_FOUND`；运维从管理 API 重试时创建新 Attempt

## 硬门槛提醒

非空安全 Patch、必选测试通过、Artifact/manifest 校验、分支/commit/MR、完整 attempt/publish 事件全部存在，才可进入 `repair::ready-for-test`。禁止 force push；目标分支 Base 必须与测试基线一致。
