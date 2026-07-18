# Handoff Orchestrator 操作指南

半自动 GUI，协助操作员按任务索引推进 model-handoff 流程。OpenCode/Grok 的执行与复核仍由人工完成。

## 前置条件

- Python 3.10+
- PowerShell（`pwsh`）
- Git

## 启动

在**仓库根目录**执行：

```bash
python docs/model-handoff/tools/handoff_orchestrator.py
```

UI 会显示当前任务上下文、可用按钮与操作日志。

## 单任务按钮顺序

| 步骤 | 操作 | 说明 |
|------|------|------|
| ① | **准备分支** | 检出/创建 `task/<TASK_ID>` 分支 |
| ② | **生成提示词并提交 generated** | 调用 PS1 生成执行提示词，写入剪贴板；**提示词文件自动 commit 到任务分支** |
| — | **OpenCode 粘贴执行** | 人工：将剪贴板内容粘贴到 OpenCode，完成实现 |
| ③ | **标记执行完成** | 可选填写结果 commit SHA |
| ④ | **生成复核提示词** | 生成并复制复核提示词 |
| — | **新会话复核** | 人工：在新会话中粘贴复核提示词，给出结论 |
| ⑤ | **ACCEPTED / REWORK / BLOCKED** | 见下文 |

## 重要说明

- **OpenCode 始终手动**：工具不启动、不驱动 OpenCode；仅自动化 git、提示词生成、剪贴板、索引更新与日志。
- **ACCEPTED**：确认后对 `main` 执行 **fast-forward only** 合并，并推进索引到下一任务。
- **REWORK**：保留当前任务分支，索引状态写回 **READY**，可重新走 ②→③→④→⑤。
- **BLOCKED**：仅标记索引为 BLOCKED，不合并。
- **Handoff 根目录**为 `docs/model-handoff`（不是 `outputs/`）。

## 日志

每次操作写入 `docs/model-handoff/tools/logs/`（按时间戳命名，已 gitignore）。

## 开发 / worktree 注意

若在 worktree（如 `.worktrees/handoff-orchestrator`）中开发，请在 UI 中将 **Repo root** 指向你**实际打算合并来源**的真实 checkout，而非误用临时 worktree 路径。合并与索引更新以该 repo root 为准。
