# 模型任务交接编排器 — 使用说明

半自动 GUI，帮你把剩余 ~40 个 handoff Task 按固定流程推进，而不用手敲一整套 git / 提示词命令。  
**OpenCode / Grok 的执行与复核仍由你手动完成**；工具负责分支、生成提示词、提交 generated 文档、复制剪贴板、更新索引、ff-only 合并与日志。

更细的工具说明见：`docs/model-handoff/tools/README-orchestrator.md`

---

## 一分钟启动

```powershell
cd D:\idea_jidian_projects\loopServer
python docs\model-handoff\tools\handoff_orchestrator.py
```

需要：Python 3.10+、`pwsh`、Git。

---

## 每个 Task 怎么点

界面按钮按状态机启用。标准顺序：

| 顺序 | 你在界面点 | 你在 OpenCode 做 |
|------|------------|------------------|
| ① | **准备分支** | — |
| ② | **生成提示词并提交 generated**（自动复制到剪贴板，并把 `docs/model-handoff/generated/<TaskId>-*.md` commit 到当前任务分支） | 新开会话，选 Grok-4.5，粘贴提示词，等完整 READY 预检 |
| — | 核对 READY 无误后 | 发送：`开始执行 Task <ID>` |
| ③ | 模型完成测试 + Task commit 后点 **标记执行完成** | — |
| ④ | **生成复核提示词**（自动复制） | **新开**只读复核会话，粘贴复核提示词 |
| ⑤ | 仅当复核结论为 **ACCEPTED** 时点 **ACCEPTED**（会二次确认） | — |

### ⑤ 三种结论

- **ACCEPTED**（确认后自动做完整条链）  
  `ff-only` 合并到 `main` → 索引：当前 → ACCEPTED、下一 PENDING → READY → commit → 删旧 `task/<ID>` → 建下一 `task/<NEXT>`
- **REWORK**  
  保留当前任务分支；索引写回 **READY**；可重新从 ② 生成执行提示词
- **BLOCKED**  
  索引 → BLOCKED；停止，不建下一分支

---

## 路径与约定（容易忘）

| 项 | 值 |
|----|-----|
| Handoff 根目录 | `docs/model-handoff`（**不是**文档里偶尔写的 `outputs/model-handoff`） |
| 任务索引 | `docs/model-handoff/02-任务索引.md`（只有你或编排器改状态） |
| 执行提示词生成 | `docs/model-handoff/tools/New-ModelTaskPrompt.ps1` |
| 复核模板 | `docs/model-handoff/05-任务复核提示词.md` |
| 生成的提示词文件 | `docs/model-handoff/generated/`（② 成功后会 commit 进任务分支） |
| 操作日志 | `docs/model-handoff/tools/logs/`（已 gitignore） |
| 任务分支名 | `task/LE-Pxx-Txx` |
| 主分支 | `main` |
| 默认模型名 | `Grok-4.5` |

---

## 手工等价流程（GUI 坏了时对照）

以 `LE-P01-T02` 为例：

```powershell
cd D:\idea_jidian_projects\loopServer
git switch main
git status --short   # 必须干净

git switch -c task/LE-P01-T02   # 或已存在则 git switch task/LE-P01-T02

pwsh -NoProfile -File .\docs\model-handoff\tools\New-ModelTaskPrompt.ps1 `
  -TaskId LE-P01-T02 `
  -Model "Grok-4.5" `
  -AllowCommit

# 立刻把刚刚生成的 docs/model-handoff/generated/LE-P01-T02-*.md 提交到任务分支
git add docs/model-handoff/generated/LE-P01-T02-*.md
git commit -m "docs(handoff): add prompt for LE-P01-T02"
```

然后：OpenCode 粘贴执行 → 完成后独立会话只读复核 → 仅 ACCEPTED 才合并：

```powershell
git status --short
git log --oneline main..task/LE-P01-T02

git switch main
git merge --ff-only task/LE-P01-T02

# 人工改 02-任务索引.md：当前 ACCEPTED，下一 READY
git add docs/model-handoff/02-任务索引.md
git commit -m "chore(handoff): accept LE-P01-T02 and ready LE-P01-T03"

git branch -d task/LE-P01-T02
git switch -c task/LE-P01-T03
```

---

## 硬规则（不要违反）

1. 同一时间只推进 **一个** Task。  
2. 不要让执行模型改任务索引、push、merge。  
3. 合并只用 **`--ff-only`**，不要强推、不要 `reset --hard`。  
4. 工作区不干净时不要点「准备分支 / ACCEPTED」。  
5. 复核必须在**新会话**、只读；只有 ACCEPTED 才合并。  
6. 生成提示词成功后，**必须**把 `generated/*.md` 提交到任务分支（GUI 的 ② 已自动做）。  
7. 报错 `worktree not clean` **不是**要删 `.worktrees` 目录，而是工作区有未提交/未跟踪文件。常见是 `__pycache__` / `*.log`（已忽略）；工具也会自动忽略自己的 logs 与 pycache。  
8. 生成提示词后会弹出**可换行文本窗口**（并尝试复制剪贴板）；请从该窗口粘贴到 OpenCode，不要从乱码日志里抠。  
9. 请在 **main** 上启动 GUI（加载最新工具代码）；点「准备分支」会切到 `task/...`。改完工具后需**重启**编排器进程。

---

## 当前进度从哪看

打开 `docs/model-handoff/02-任务索引.md`：找状态为 `READY` 的那一行，就是下一个该做的 Task。  
GUI 顶部也会显示当前 Task、分支、HEAD 与 `ACCEPTED / 总数` 进度。
