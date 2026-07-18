# Model Handoff Orchestrator GUI — Design

Date: 2026-07-18  
Status: Approved for planning (pending user review of this file)  
Approach: A — single-entry tkinter + subprocess (pwsh/git)

## Goal

Provide a Windows GUI that walks a human operator through the remaining ~40 model-handoff tasks without manually typing the git/prompt choreography. OpenCode/Grok execution and review stay human-driven; the tool automates git, prompt generation, clipboard, index updates, and logging.

## Decisions (locked)

| Topic | Choice |
|---|---|
| Automation level | Semi-auto orchestrator (option 1) |
| Review prompt | One-click generate + copy from `05-任务复核提示词.md` |
| After ACCEPTED | Confirm once, then full advance chain |
| REWORK / BLOCKED | Both: BLOCKED marks only; REWORK keeps branch + regenerate exec prompt |
| REWORK index status | Write `READY` (not a new status token) |
| UI stack | Python stdlib `tkinter` (no pip deps) |
| Handoff root | `docs/model-handoff` (not `outputs/model-handoff`) |

## Non-goals

- Launching or driving OpenCode automatically
- Pushing to remote
- Non-ff merges, rebase, reset, force-delete
- Parallel execution of two Tasks
- Modifying task cards, architecture docs, or allowlists

## Architecture

### Entry point

- `docs/model-handoff/tools/handoff_orchestrator.py`

If the file grows past ~400–500 lines, split into a package next to it:

- `handoff_orchestrator/git_ops.py` — git wrappers
- `handoff_orchestrator/index_ops.py` — parse/update `02-任务索引.md`
- `handoff_orchestrator/prompt_ops.py` — PS1 invoke + review template fill
- `handoff_orchestrator/ui.py` — tkinter layout
- `handoff_orchestrator/__main__.py` / thin launcher still named `handoff_orchestrator.py`

Prefer starting as one file and splitting only if needed.

### Components

```
┌─────────────────────────────────────────┐
│  UI (tkinter)                           │
│  context bar | step buttons | log panel │
└───────────────┬─────────────────────────┘
                │
┌───────────────▼─────────────────────────┐
│  Orchestrator state machine             │
│  IDLE → PREPARE → PROMPT_READY →        │
│  WAIT_EXEC → WAIT_REVIEW → ADVANCE      │
└───────────────┬─────────────────────────┘
                │
     ┌──────────┼──────────┐
     ▼          ▼          ▼
  git_ops   prompt_ops  index_ops
     │          │
     │          ├── New-ModelTaskPrompt.ps1
     │          └── 05-任务复核提示词.md
     ▼
  git / pwsh subprocess (captured → log)
```

### Defaults

- Repo root: discover via `git rev-parse --show-toplevel` from script location; editable in UI
- Model: `Grok-4.5`
- Branch pattern: `task/<TASK_ID>`
- Main branch name: `main` (detect; if absent, fail clearly — do not guess `master` silently)

## State machine

```
IDLE
  └─[refresh / auto-detect READY]→ PREPARE

PREPARE
  └─[① 准备分支 success]→ PROMPT_READY

PROMPT_READY
  └─[② 生成提示词并提交 generated]→ WAIT_EXEC
  └─[REWORK path: 重新生成执行提示词]→ WAIT_EXEC

WAIT_EXEC
  └─[③ 标记执行完成]→ WAIT_REVIEW

WAIT_REVIEW
  ├─[④ 生成复核提示词]→ (stay WAIT_REVIEW)
  ├─[⑤ ACCEPTED]→ ADVANCE → PREPARE (next) or IDLE (done)
  ├─[⑤ REWORK]→ PROMPT_READY (same branch; index → READY)
  └─[⑤ BLOCKED]→ IDLE (index → BLOCKED)
```

Button enablement follows the current state. Illegal clicks are no-ops with a log warning.

## UI layout

### Top — context

- Current Task ID, title, index status
- Current branch, HEAD short SHA
- Model entry (default `Grok-4.5`)
- Repo path
- Progress: `ACCEPTED count / total`

### Middle — actions

1. **准备分支** — clean check → `git switch main` → create/switch `task/<ID>`; record `base_commit = HEAD` after switch to main / before task work
2. **生成提示词并提交 generated** — run PS1 with `-AllowCommit` → commit generated md → copy prompt to clipboard
3. **标记执行完成** — capture `result_commit = HEAD` (allow override field)
4. **生成复核提示词** — fill template → clipboard
5. **复核结论** — ACCEPTED / REWORK / BLOCKED

Helpers: open task card md, open index md.

### Bottom — log

- Timestamped INFO/WARN/ERROR lines
- Append subprocess stdout/stderr
- Clear / copy all
- Optional file log under `docs/model-handoff/tools/logs/handoff-YYYYMMDD.log`

Dangerous actions use `messagebox.askokcancel`.

## Git / prompt sequences

### Prepare

```
git status --short          # must be empty
git switch main
git switch -c task/<ID>     # or git switch task/<ID> if exists
# store base_commit = git rev-parse HEAD  (after landing on task branch start;
# equivalently: main tip at branch creation time)
```

If already on `task/<ID>` with clean tree and base known, skip recreate.

### Generate exec prompt + commit generated

```
pwsh -NoProfile -File docs/model-handoff/tools/New-ModelTaskPrompt.ps1 `
  -TaskId <ID> -Model <Model> -AllowCommit
```

- Capture stdout as the prompt text (also saved by PS1 under `docs/model-handoff/generated/<ID>-<UTC>.md`)
- Locate the newest matching generated file for this TaskId
- `git add` that file only
- `git commit -m "docs(handoff): add prompt for <ID>"`
- Copy prompt to clipboard
- Fail if worktree has other dirty paths after PS1 (PS1 itself requires clean/declared dirty)

### Review prompt

Read `docs/model-handoff/05-任务复核提示词.md`, replace:

- `{{TASK_ID}}`
- `{{COMMIT}}` → result commit
- `{{BASE_COMMIT}}` → base commit

Copy full text to clipboard. Do not write a generated review file unless useful later (out of scope v1).

### ACCEPTED advance (after confirm)

```
git status --short                              # must be clean
git log --oneline main..task/<ID>               # log for operator visibility
git switch main
git merge --ff-only task/<ID>
# update index: current → ACCEPTED; next PENDING → READY (if next exists)
git add docs/model-handoff/02-任务索引.md
git commit -m "chore(handoff): accept <ID> and ready <NEXT>"
# if no next: message "chore(handoff): accept <ID>"
git branch -d task/<ID>
git switch -c task/<NEXT>                       # only if next exists
```

Then UI selects next READY task and returns to PREPARE/PROMPT_READY as appropriate.

### REWORK

- Keep `task/<ID>`
- Set index status for current task to `READY`
- Commit index: `chore(handoff): rework <ID> (back to READY)`
- Unlock regenerate exec prompt (step ②)

### BLOCKED

- Set index status to `BLOCKED`
- Commit: `chore(handoff): block <ID>`
- Stay IDLE; do not create next branch

## Index file operations

File: `docs/model-handoff/02-任务索引.md`

- Parse markdown table rows for Task IDs and Status
- Detect the first `READY` task as default current (operator may override via dropdown if multiple — normally one READY)
- Update **only** the Status cell for targeted rows
- Status vocabulary: `PENDING`, `READY`, `IN_PROGRESS`, `BLOCKED`, `REVIEW`, `ACCEPTED`
- REWORK operator action writes `READY`
- Next task after accept: first subsequent row that is `PENDING` → `READY` (prerequisite already satisfied by linear chain)

## Error handling

| Condition | Behavior |
|---|---|
| Non-zero git/pwsh exit | Abort step; ERROR log; no further commands in chain |
| Dirty worktree when clean required | Abort; show dirty paths |
| `merge --ff-only` fails | Abort; leave on current branch state after failed merge attempt — prefer detecting divergence before merge; if merge fails, instruct manual recovery |
| Unknown TaskId / missing card | Abort prepare |
| Index row not found / ambiguous | Abort write |
| No READY task | IDLE with message |
| Clipboard unavailable | Still succeed file/git ops; WARN and show prompt in a scrollable dialog |

Never use `--force`, `reset --hard`, or `push`.

## Logging

- All commands logged with cwd and args
- Combined stdout/stderr captured
- UI ScrolledText + optional rotating/daily file under `tools/logs/`
- `tools/logs/` may be gitignored (add to local ignore or document; do not commit log noise)

## Testing (manual acceptance)

1. Launch GUI against this repo; shows LE-P01-T02 READY (or current READY)
2. Prepare creates/switches `task/LE-P01-T02`
3. Generate prompt creates file under `generated/`, commits it, clipboard has prompt
4. Mark exec complete + review prompt contains correct IDs/SHAs
5. Dry-run mindset: ACCEPTED path tested carefully; prefer a throwaway clone if needed for first merge test
6. REWORK leaves branch, sets READY, allows regenerate
7. BLOCKED sets BLOCKED and stops

## Risks

- Index table format drift breaks regex updates — keep parser strict and fail loud
- Docs historically mention `outputs/model-handoff`; tool must use `docs/model-handoff`
- Operator may click ACCEPTED without real review — confirm dialog must show Task ID + commits
- Concurrent manual git in another terminal can race — document “use one workspace”

## Out of scope for v1 (possible later)

- KnownDirtyPath / KnownDeviation UI fields
- Auto-open OpenCode
- Multi-select / batch queue beyond linear next
- Packaging as `.exe`
