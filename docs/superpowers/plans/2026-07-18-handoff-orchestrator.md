# Handoff Orchestrator GUI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Windows tkinter GUI that semi-automates the model-handoff git/prompt/index workflow for the remaining ~40 Tasks.

**Architecture:** A small Python package under `docs/model-handoff/tools/handoff_orchestrator/` with pure functions for index/git/prompt ops, an orchestration engine (state machine), and a tkinter UI. The existing `New-ModelTaskPrompt.ps1` is invoked via `pwsh`; OpenCode stays manual.

**Tech Stack:** Python 3.10+ stdlib (`tkinter`, `subprocess`, `pathlib`, `re`, `dataclasses`), PowerShell 7 (`pwsh`), Git. Tests via `pytest` (dev-only).

**Spec:** `docs/superpowers/specs/2026-07-18-handoff-orchestrator-design.md`

---

## File structure

| Path | Responsibility |
|---|---|
| `docs/model-handoff/tools/handoff_orchestrator.py` | Thin launcher: `python handoff_orchestrator.py` |
| `docs/model-handoff/tools/handoff_orchestrator/__init__.py` | Package marker / version |
| `docs/model-handoff/tools/handoff_orchestrator/models.py` | `TaskRow`, `HandoffPaths`, `SessionContext`, `Step` enum |
| `docs/model-handoff/tools/handoff_orchestrator/index_ops.py` | Parse/update `02-任务索引.md` |
| `docs/model-handoff/tools/handoff_orchestrator/git_ops.py` | Git wrappers + clean/status helpers |
| `docs/model-handoff/tools/handoff_orchestrator/prompt_ops.py` | Invoke PS1 + fill review template |
| `docs/model-handoff/tools/handoff_orchestrator/engine.py` | State machine + step runners |
| `docs/model-handoff/tools/handoff_orchestrator/ui.py` | tkinter layout |
| `docs/model-handoff/tools/handoff_orchestrator/logutil.py` | Logger callback + optional file log |
| `docs/model-handoff/tools/tests/test_index_ops.py` | Index parse/update tests |
| `docs/model-handoff/tools/tests/test_prompt_ops.py` | Review template fill tests |
| `docs/model-handoff/tools/tests/test_engine.py` | State transition / button enable tests |
| `docs/model-handoff/tools/logs/.gitignore` | Ignore `*.log`, keep directory |

---

### Task 1: Package skeleton + models

**Files:**
- Create: `docs/model-handoff/tools/handoff_orchestrator/__init__.py`
- Create: `docs/model-handoff/tools/handoff_orchestrator/models.py`
- Create: `docs/model-handoff/tools/handoff_orchestrator.py`
- Create: `docs/model-handoff/tools/logs/.gitignore`

- [ ] **Step 1: Create models**

```python
# docs/model-handoff/tools/handoff_orchestrator/models.py
from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from pathlib import Path


class Step(str, Enum):
    IDLE = "IDLE"
    PREPARE = "PREPARE"
    PROMPT_READY = "PROMPT_READY"
    WAIT_EXEC = "WAIT_EXEC"
    WAIT_REVIEW = "WAIT_REVIEW"
    ADVANCE = "ADVANCE"


VALID_STATUSES = frozenset(
    {"PENDING", "READY", "IN_PROGRESS", "BLOCKED", "REVIEW", "ACCEPTED"}
)


@dataclass(frozen=True)
class TaskRow:
    task_id: str
    title: str
    prerequisite: str
    source_plan: str
    status: str
    line_index: int  # 0-based line number in the index file


@dataclass(frozen=True)
class HandoffPaths:
    repo_root: Path
    handoff_root: Path

    @property
    def index_md(self) -> Path:
        return self.handoff_root / "02-任务索引.md"

    @property
    def review_template(self) -> Path:
        return self.handoff_root / "05-任务复核提示词.md"

    @property
    def prompt_ps1(self) -> Path:
        return self.handoff_root / "tools" / "New-ModelTaskPrompt.ps1"

    @property
    def generated_dir(self) -> Path:
        return self.handoff_root / "generated"

    @property
    def task_cards_dir(self) -> Path:
        return self.handoff_root / "task-cards"

    @classmethod
    def from_repo_root(cls, repo_root: Path) -> HandoffPaths:
        root = repo_root.resolve()
        return cls(repo_root=root, handoff_root=root / "docs" / "model-handoff")


@dataclass
class SessionContext:
    paths: HandoffPaths
    model: str = "Grok-4.5"
    main_branch: str = "main"
    current_task_id: str | None = None
    step: Step = Step.IDLE
    base_commit: str | None = None
    result_commit: str | None = None
    last_prompt_text: str = ""
    last_review_text: str = ""
    log_lines: list[str] = field(default_factory=list)
```

- [ ] **Step 2: Create `__init__.py` and launcher stub**

```python
# docs/model-handoff/tools/handoff_orchestrator/__init__.py
__version__ = "0.1.0"
```

```python
# docs/model-handoff/tools/handoff_orchestrator.py
"""Launch: python docs/model-handoff/tools/handoff_orchestrator.py"""
from __future__ import annotations

import sys
from pathlib import Path


def main() -> int:
    tools_dir = Path(__file__).resolve().parent
    if str(tools_dir) not in sys.path:
        sys.path.insert(0, str(tools_dir))
    from handoff_orchestrator.ui import run_app

    run_app()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
```

```gitignore
# docs/model-handoff/tools/logs/.gitignore
*.log
```

- [ ] **Step 3: Commit** (only if user requested commits; otherwise skip until asked)

```bash
git add docs/model-handoff/tools/handoff_orchestrator.py docs/model-handoff/tools/handoff_orchestrator docs/model-handoff/tools/logs/.gitignore
git commit -m "chore(handoff): scaffold orchestrator package and models"
```

---

### Task 2: Index parse and update (TDD)

**Files:**
- Create: `docs/model-handoff/tools/handoff_orchestrator/index_ops.py`
- Create: `docs/model-handoff/tools/tests/test_index_ops.py`
- Create: `docs/model-handoff/tools/tests/conftest.py` (sys.path)

- [ ] **Step 1: Add conftest path bootstrap**

```python
# docs/model-handoff/tools/tests/conftest.py
import sys
from pathlib import Path

TOOLS = Path(__file__).resolve().parents[1]
if str(TOOLS) not in sys.path:
    sys.path.insert(0, str(TOOLS))
```

- [ ] **Step 2: Write failing tests**

```python
# docs/model-handoff/tools/tests/test_index_ops.py
from pathlib import Path

from handoff_orchestrator.index_ops import (
    find_first_ready,
    find_next_pending_after,
    parse_index,
    set_status,
)

SAMPLE = """# 任务索引

| ID | Task title | Prerequisite | Source plan | Status |
|---|---|---|---|---|
| [LE-P01-T01](./task-cards/LE-P01-T01.md) | Bootstrap A | 无 | 01 Task 1 | ACCEPTED |
| [LE-P01-T02](./task-cards/LE-P01-T02.md) | Add B | LE-P01-T01 | 01 Task 2 | READY |
| [LE-P01-T03](./task-cards/LE-P01-T03.md) | Add C | LE-P01-T02 | 01 Task 3 | PENDING |
"""


def test_parse_index_rows(tmp_path: Path):
    p = tmp_path / "02-任务索引.md"
    p.write_text(SAMPLE, encoding="utf-8")
    rows = parse_index(p)
    assert [r.task_id for r in rows] == ["LE-P01-T01", "LE-P01-T02", "LE-P01-T03"]
    assert rows[1].status == "READY"
    assert rows[1].title == "Add B"


def test_find_first_ready(tmp_path: Path):
    p = tmp_path / "02-任务索引.md"
    p.write_text(SAMPLE, encoding="utf-8")
    assert find_first_ready(parse_index(p)).task_id == "LE-P01-T02"


def test_set_status_and_next(tmp_path: Path):
    p = tmp_path / "02-任务索引.md"
    p.write_text(SAMPLE, encoding="utf-8")
    rows = parse_index(p)
    new_text = set_status(p.read_text(encoding="utf-8"), "LE-P01-T02", "ACCEPTED")
    new_text = set_status(new_text, "LE-P01-T03", "READY")
    p.write_text(new_text, encoding="utf-8")
    rows2 = parse_index(p)
    assert rows2[1].status == "ACCEPTED"
    assert rows2[2].status == "READY"
    assert find_next_pending_after(rows, "LE-P01-T02").task_id == "LE-P01-T03"


def test_set_status_unknown_raises(tmp_path: Path):
    p = tmp_path / "02-任务索引.md"
    p.write_text(SAMPLE, encoding="utf-8")
    try:
        set_status(p.read_text(encoding="utf-8"), "LE-P99-T99", "READY")
        assert False, "expected ValueError"
    except ValueError as e:
        assert "LE-P99-T99" in str(e)
```

- [ ] **Step 3: Run tests — expect fail**

```powershell
cd D:\idea_jidian_projects\loopServer
python -m pytest docs/model-handoff/tools/tests/test_index_ops.py -v
```

Expected: `ModuleNotFoundError` or import error for `index_ops`.

- [ ] **Step 4: Implement `index_ops.py`**

```python
# docs/model-handoff/tools/handoff_orchestrator/index_ops.py
from __future__ import annotations

import re
from pathlib import Path

from handoff_orchestrator.models import VALID_STATUSES, TaskRow

_ROW_RE = re.compile(
    r"^\| \[([A-Z0-9-]+)\]\([^)]+\) \| (.+?) \| (.+?) \| (.+?) \| ([A-Z_]+) \|$"
)


def parse_index(path: Path) -> list[TaskRow]:
    text = path.read_text(encoding="utf-8")
    rows: list[TaskRow] = []
    for i, line in enumerate(text.splitlines()):
        m = _ROW_RE.match(line.strip())
        if not m:
            continue
        status = m.group(5)
        if status not in VALID_STATUSES:
            raise ValueError(f"invalid status {status!r} on line {i + 1}")
        rows.append(
            TaskRow(
                task_id=m.group(1),
                title=m.group(2).strip(),
                prerequisite=m.group(3).strip(),
                source_plan=m.group(4).strip(),
                status=status,
                line_index=i,
            )
        )
    if not rows:
        raise ValueError(f"no task rows found in {path}")
    return rows


def find_first_ready(rows: list[TaskRow]) -> TaskRow | None:
    for row in rows:
        if row.status == "READY":
            return row
    return None


def find_next_pending_after(rows: list[TaskRow], task_id: str) -> TaskRow | None:
    seen = False
    for row in rows:
        if row.task_id == task_id:
            seen = True
            continue
        if seen and row.status == "PENDING":
            return row
    return None


def set_status(text: str, task_id: str, new_status: str) -> str:
    if new_status not in VALID_STATUSES:
        raise ValueError(f"invalid status: {new_status}")
    pattern = re.compile(
        rf"^(\| \[{re.escape(task_id)}\]\([^)]+\) \| .+? \| .+? \| .+? \| )([A-Z_]+)( \|)$",
        re.MULTILINE,
    )
    matches = list(pattern.finditer(text))
    if len(matches) != 1:
        raise ValueError(f"expected exactly one row for {task_id}, found {len(matches)}")
    m = matches[0]
    return text[: m.start()] + m.group(1) + new_status + m.group(3) + text[m.end() :]


def write_index(path: Path, text: str) -> None:
    path.write_text(text, encoding="utf-8", newline="\n")
```

- [ ] **Step 5: Run tests — expect pass**

```powershell
python -m pytest docs/model-handoff/tools/tests/test_index_ops.py -v
```

Expected: all PASS. If the real index line format differs slightly (spaces), adjust `_ROW_RE` until `parse_index` works on the real file too — add:

```python
def test_parse_real_index():
    root = Path(__file__).resolve().parents[3]  # repo root from tools/tests
    # tools/tests -> tools -> model-handoff -> docs -> repo? 
    # Path: repo/docs/model-handoff/tools/tests -> parents[3] = docs? 
    # parents[0]=tests, [1]=tools, [2]=model-handoff, [3]=docs, [4]=repo
    repo = Path(__file__).resolve().parents[4]
    p = repo / "docs" / "model-handoff" / "02-任务索引.md"
    rows = parse_index(p)
    assert len(rows) >= 40
    assert any(r.status == "READY" for r in rows) or any(r.status == "ACCEPTED" for r in rows)
```

Fix parent count if needed by printing `Path(__file__).resolve().parents`.

- [ ] **Step 6: Commit** (if user requested)

```bash
git add docs/model-handoff/tools/handoff_orchestrator/index_ops.py docs/model-handoff/tools/tests
git commit -m "feat(handoff): parse and update task index statuses"
```

---

### Task 3: Git ops wrappers

**Files:**
- Create: `docs/model-handoff/tools/handoff_orchestrator/git_ops.py`
- Create: `docs/model-handoff/tools/tests/test_git_ops.py`

- [ ] **Step 1: Write failing tests for pure helpers**

```python
# docs/model-handoff/tools/tests/test_git_ops.py
from handoff_orchestrator.git_ops import parse_status_paths, is_clean_status


def test_parse_status_paths():
    out = " M a.txt\n?? b.txt\n"
    assert parse_status_paths(out) == ["a.txt", "b.txt"]


def test_is_clean_status():
    assert is_clean_status("") is True
    assert is_clean_status("\n") is True
    assert is_clean_status("?? x") is False
```

- [ ] **Step 2: Run — expect fail**

```powershell
python -m pytest docs/model-handoff/tools/tests/test_git_ops.py -v
```

- [ ] **Step 3: Implement `git_ops.py`**

```python
# docs/model-handoff/tools/handoff_orchestrator/git_ops.py
from __future__ import annotations

import subprocess
from dataclasses import dataclass
from pathlib import Path


class GitError(RuntimeError):
    pass


@dataclass
class CmdResult:
    args: list[str]
    returncode: int
    stdout: str
    stderr: str


def run_git(repo: Path, args: list[str], check: bool = True) -> CmdResult:
    full = ["git", "-C", str(repo), *args]
    proc = subprocess.run(
        full,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    result = CmdResult(full, proc.returncode, proc.stdout, proc.stderr)
    if check and proc.returncode != 0:
        raise GitError(
            f"git {' '.join(args)} failed ({proc.returncode}): "
            f"{(proc.stderr or proc.stdout).strip()}"
        )
    return result


def parse_status_paths(status_text: str) -> list[str]:
    paths: list[str] = []
    for line in status_text.splitlines():
        if not line.strip():
            continue
        if len(line) >= 4:
            path = line[3:].strip()
            if " -> " in path:
                path = path.split(" -> ")[-1]
            paths.append(path)
        else:
            paths.append(line.strip())
    return paths


def is_clean_status(status_text: str) -> bool:
    return len(parse_status_paths(status_text)) == 0


def status_short(repo: Path) -> str:
    return run_git(repo, ["-c", "core.quotepath=false", "status", "--short", "--untracked-files=all"]).stdout


def require_clean(repo: Path) -> None:
    text = status_short(repo)
    if not is_clean_status(text):
        raise GitError(f"worktree not clean:\n{text}")


def current_branch(repo: Path) -> str:
    return run_git(repo, ["branch", "--show-current"]).stdout.strip()


def rev_parse(repo: Path, ref: str = "HEAD") -> str:
    return run_git(repo, ["rev-parse", ref]).stdout.strip()


def switch_branch(repo: Path, branch: str, create: bool = False) -> CmdResult:
    if create:
        return run_git(repo, ["switch", "-c", branch])
    return run_git(repo, ["switch", branch])


def branch_exists(repo: Path, branch: str) -> bool:
    r = run_git(repo, ["show-ref", "--verify", "--quiet", f"refs/heads/{branch}"], check=False)
    return r.returncode == 0


def merge_ff_only(repo: Path, branch: str) -> CmdResult:
    return run_git(repo, ["merge", "--ff-only", branch])


def delete_branch(repo: Path, branch: str) -> CmdResult:
    return run_git(repo, ["branch", "-d", branch])


def commit_paths(repo: Path, paths: list[str], message: str) -> CmdResult:
    run_git(repo, ["add", "--", *paths])
    return run_git(repo, ["commit", "-m", message])


def log_oneline(repo: Path, range_expr: str) -> str:
    return run_git(repo, ["log", "--oneline", range_expr]).stdout
```

- [ ] **Step 4: Run tests — expect pass**

```powershell
python -m pytest docs/model-handoff/tools/tests/test_git_ops.py -v
```

- [ ] **Step 5: Commit** (if user requested)

```bash
git add docs/model-handoff/tools/handoff_orchestrator/git_ops.py docs/model-handoff/tools/tests/test_git_ops.py
git commit -m "feat(handoff): add git operation wrappers"
```

---

### Task 4: Prompt ops (PS1 + review template)

**Files:**
- Create: `docs/model-handoff/tools/handoff_orchestrator/prompt_ops.py`
- Create: `docs/model-handoff/tools/tests/test_prompt_ops.py`

- [ ] **Step 1: Write failing tests for template fill + newest generated file**

```python
# docs/model-handoff/tools/tests/test_prompt_ops.py
from pathlib import Path

from handoff_orchestrator.prompt_ops import fill_review_prompt, newest_generated_for_task


def test_fill_review_prompt():
    template = "Task {{TASK_ID}} commit {{COMMIT}} base {{BASE_COMMIT}}"
    out = fill_review_prompt(template, "LE-P01-T02", "aaa", "bbb")
    assert out == "Task LE-P01-T02 commit aaa base bbb"


def test_newest_generated_for_task(tmp_path: Path):
    d = tmp_path / "generated"
    d.mkdir()
    (d / "LE-P01-T02-20260101T000000Z.md").write_text("old", encoding="utf-8")
    newer = d / "LE-P01-T02-20260102T000000Z.md"
    newer.write_text("new", encoding="utf-8")
    (d / "LE-P01-T03-20260103T000000Z.md").write_text("other", encoding="utf-8")
    assert newest_generated_for_task(d, "LE-P01-T02") == newer
```

- [ ] **Step 2: Run — expect fail**

```powershell
python -m pytest docs/model-handoff/tools/tests/test_prompt_ops.py -v
```

- [ ] **Step 3: Implement `prompt_ops.py`**

```python
# docs/model-handoff/tools/handoff_orchestrator/prompt_ops.py
from __future__ import annotations

import subprocess
from pathlib import Path

from handoff_orchestrator.git_ops import CmdResult


class PromptError(RuntimeError):
    pass


def fill_review_prompt(template: str, task_id: str, commit: str, base_commit: str) -> str:
    return (
        template.replace("{{TASK_ID}}", task_id)
        .replace("{{COMMIT}}", commit)
        .replace("{{BASE_COMMIT}}", base_commit)
    )


def newest_generated_for_task(generated_dir: Path, task_id: str) -> Path:
    matches = sorted(generated_dir.glob(f"{task_id}-*.md"))
    if not matches:
        raise PromptError(f"no generated prompt for {task_id} in {generated_dir}")
    return matches[-1]


def run_new_model_task_prompt(
    ps1: Path,
    task_id: str,
    model: str,
    repo_root: Path,
    allow_commit: bool = True,
) -> CmdResult:
    args = [
        "pwsh",
        "-NoProfile",
        "-File",
        str(ps1),
        "-TaskId",
        task_id,
        "-Model",
        model,
        "-RepositoryRoot",
        str(repo_root),
    ]
    if allow_commit:
        args.append("-AllowCommit")
    proc = subprocess.run(
        args,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        cwd=str(repo_root),
    )
    result = CmdResult(args, proc.returncode, proc.stdout, proc.stderr)
    if proc.returncode != 0:
        raise PromptError(
            f"New-ModelTaskPrompt failed ({proc.returncode}): "
            f"{(proc.stderr or proc.stdout).strip()}"
        )
    return result


def copy_to_clipboard(text: str) -> None:
    """Best-effort clipboard via tkinter; caller may fall back to dialog."""
    import tkinter as tk

    root = tk.Tk()
    root.withdraw()
    try:
        root.clipboard_clear()
        root.clipboard_append(text)
        root.update()
    finally:
        root.destroy()
```

- [ ] **Step 4: Run tests — expect pass**

```powershell
python -m pytest docs/model-handoff/tools/tests/test_prompt_ops.py -v
```

- [ ] **Step 5: Commit** (if user requested)

```bash
git add docs/model-handoff/tools/handoff_orchestrator/prompt_ops.py docs/model-handoff/tools/tests/test_prompt_ops.py
git commit -m "feat(handoff): add prompt generation and review fill helpers"
```

---

### Task 5: Orchestration engine (state machine)

**Files:**
- Create: `docs/model-handoff/tools/handoff_orchestrator/engine.py`
- Create: `docs/model-handoff/tools/handoff_orchestrator/logutil.py`
- Create: `docs/model-handoff/tools/tests/test_engine.py`

- [ ] **Step 1: Implement logutil**

```python
# docs/model-handoff/tools/handoff_orchestrator/logutil.py
from __future__ import annotations

from datetime import datetime, timezone
from pathlib import Path
from typing import Callable


LogFn = Callable[[str, str], None]  # level, message


def make_logger(ui_append: Callable[[str], None], log_file: Path | None = None) -> LogFn:
    if log_file is not None:
        log_file.parent.mkdir(parents=True, exist_ok=True)

    def log(level: str, message: str) -> None:
        ts = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        line = f"[{ts}] {level}: {message}"
        ui_append(line)
        if log_file is not None:
            with log_file.open("a", encoding="utf-8") as f:
                f.write(line + "\n")

    return log
```

- [ ] **Step 2: Write failing enablement tests**

```python
# docs/model-handoff/tools/tests/test_engine.py
from handoff_orchestrator.engine import enabled_actions
from handoff_orchestrator.models import Step


def test_enabled_actions_prompt_ready():
    a = enabled_actions(Step.PROMPT_READY)
    assert a["prepare"] is False
    assert a["generate_prompt"] is True
    assert a["mark_exec_done"] is False
    assert a["generate_review"] is False
    assert a["accept"] is False
    assert a["rework"] is False
    assert a["blocked"] is False


def test_enabled_actions_wait_review():
    a = enabled_actions(Step.WAIT_REVIEW)
    assert a["generate_review"] is True
    assert a["accept"] is True
    assert a["rework"] is True
    assert a["blocked"] is True
```

- [ ] **Step 3: Implement `engine.py` core (enablement + step methods)**

Implement class `Orchestrator` with:

- `__init__(self, ctx: SessionContext, log: LogFn)`
- `refresh_from_index()` → set `current_task_id` from first READY, `step=PREPARE` if found else IDLE
- `enabled_actions(step) -> dict[str, bool]` (also module-level for tests)
- `prepare_branch()`
- `generate_and_commit_prompt()`
- `mark_exec_done(result_commit: str | None = None)`
- `generate_review_prompt() -> str`
- `accept_and_advance()`  # full chain
- `mark_rework()`  # index → READY, commit, step=PROMPT_READY
- `mark_blocked()`  # index → BLOCKED, commit, step=IDLE

Concrete `prepare_branch` body:

```python
def prepare_branch(self) -> None:
    repo = self.ctx.paths.repo_root
    task_id = self.ctx.current_task_id
    if not task_id:
        raise RuntimeError("no current task")
    self.log("INFO", f"prepare branch for {task_id}")
    require_clean(repo)
    switch_branch(repo, self.ctx.main_branch, create=False)
    require_clean(repo)
    branch = f"task/{task_id}"
    if current_branch(repo) != branch:
        if branch_exists(repo, branch):
            switch_branch(repo, branch, create=False)
        else:
            switch_branch(repo, branch, create=True)
    self.ctx.base_commit = rev_parse(repo, "HEAD")
    self.ctx.step = Step.PROMPT_READY
    self.log("INFO", f"on {branch} base={self.ctx.base_commit[:12]}")
```

Concrete `generate_and_commit_prompt` body:

```python
def generate_and_commit_prompt(self) -> str:
    require_clean(self.ctx.paths.repo_root)
    result = run_new_model_task_prompt(
        self.ctx.paths.prompt_ps1,
        self.ctx.current_task_id,
        self.ctx.model,
        self.ctx.paths.repo_root,
        allow_commit=True,
    )
    self.log("INFO", result.stdout[-2000:] if result.stdout else "(no stdout)")
    gen = newest_generated_for_task(self.ctx.paths.generated_dir, self.ctx.current_task_id)
    rel = gen.relative_to(self.ctx.paths.repo_root).as_posix()
    commit_paths(
        self.ctx.paths.repo_root,
        [rel],
        f"docs(handoff): add prompt for {self.ctx.current_task_id}",
    )
    prompt = result.stdout.strip() or gen.read_text(encoding="utf-8")
    self.ctx.last_prompt_text = prompt
    self.ctx.step = Step.WAIT_EXEC
    return prompt
```

Concrete `accept_and_advance` body (must match design):

```python
def accept_and_advance(self) -> str | None:
    """Returns next task id or None if finished."""
    repo = self.ctx.paths.repo_root
    task_id = self.ctx.current_task_id
    branch = f"task/{task_id}"
    require_clean(repo)
    log_range = log_oneline(repo, f"{self.ctx.main_branch}..{branch}")
    self.log("INFO", f"commits to merge:\n{log_range or '(none)'}")
    switch_branch(repo, self.ctx.main_branch)
    merge_ff_only(repo, branch)
    text = self.ctx.paths.index_md.read_text(encoding="utf-8")
    rows = parse_index(self.ctx.paths.index_md)
    next_row = find_next_pending_after(rows, task_id)
    text = set_status(text, task_id, "ACCEPTED")
    next_id = None
    msg = f"chore(handoff): accept {task_id}"
    if next_row is not None:
        text = set_status(text, next_row.task_id, "READY")
        next_id = next_row.task_id
        msg = f"chore(handoff): accept {task_id} and ready {next_id}"
    write_index(self.ctx.paths.index_md, text)
    rel = self.ctx.paths.index_md.relative_to(repo).as_posix()
    commit_paths(repo, [rel], msg)
    delete_branch(repo, branch)
    if next_id:
        switch_branch(repo, f"task/{next_id}", create=True)
        self.ctx.current_task_id = next_id
        self.ctx.base_commit = rev_parse(repo, "HEAD")
        self.ctx.result_commit = None
        self.ctx.step = Step.PROMPT_READY
    else:
        self.ctx.current_task_id = None
        self.ctx.step = Step.IDLE
    return next_id
```

`mark_rework`: set_status → READY, commit index, `step=PROMPT_READY`, keep branch.

`mark_blocked`: set_status → BLOCKED, commit index, `step=IDLE`.

`enabled_actions`:

```python
def enabled_actions(step: Step) -> dict[str, bool]:
    return {
        "prepare": step in {Step.IDLE, Step.PREPARE},
        "generate_prompt": step == Step.PROMPT_READY,
        "mark_exec_done": step == Step.WAIT_EXEC,
        "generate_review": step == Step.WAIT_REVIEW,
        "accept": step == Step.WAIT_REVIEW,
        "rework": step == Step.WAIT_REVIEW,
        "blocked": step == Step.WAIT_REVIEW,
    }
```

Note: after `refresh_from_index`, set `step=PREPARE` so Prepare is enabled. After ACCEPTED creates next branch, `step=PROMPT_READY` (prepare already done).

- [ ] **Step 4: Run unit tests**

```powershell
python -m pytest docs/model-handoff/tools/tests/test_engine.py -v
```

- [ ] **Step 5: Commit** (if user requested)

```bash
git add docs/model-handoff/tools/handoff_orchestrator/engine.py docs/model-handoff/tools/handoff_orchestrator/logutil.py docs/model-handoff/tools/tests/test_engine.py
git commit -m "feat(handoff): add orchestrator state machine"
```

---

### Task 6: tkinter UI

**Files:**
- Create: `docs/model-handoff/tools/handoff_orchestrator/ui.py`
- Modify: `docs/model-handoff/tools/handoff_orchestrator.py` (already launches `run_app`)

- [ ] **Step 1: Implement `run_app()` UI**

Layout:
- Top frame: labels for task/branch/HEAD/progress; Entry for model; Entry for repo root; Refresh button
- Middle: buttons wired to `Orchestrator` methods; disable via `enabled_actions`
- Review result: three buttons ACCEPTED / REWORK / BLOCKED with `askokcancel`
- Bottom: `ScrolledText` log; Clear / Copy log buttons
- On generate prompt / review: call `copy_to_clipboard`; on failure show `Toplevel` Text with content

Discover repo root:

```python
def discover_repo_root() -> Path:
    here = Path(__file__).resolve()
    # .../docs/model-handoff/tools/handoff_orchestrator/ui.py
    return here.parents[4]
```

Wire buttons to run long ops on the main thread first (v1); wrap each action in try/except → `log("ERROR", ...)`.

After each successful action, call `_refresh_chrome()` to update labels and button states.

Open helpers:

```python
os.startfile(str(paths.task_cards_dir / f"{task_id}.md"))  # Windows
os.startfile(str(paths.index_md))
```

- [ ] **Step 2: Manual smoke launch**

```powershell
cd D:\idea_jidian_projects\loopServer
python docs\model-handoff\tools\handoff_orchestrator.py
```

Expected: window opens; shows READY task (e.g. LE-P01-T02); log panel works; buttons enable/disable with state.

**Do not** click ACCEPTED against the real repo during first smoke unless the operator intends a real merge.

- [ ] **Step 3: Commit** (if user requested)

```bash
git add docs/model-handoff/tools/handoff_orchestrator/ui.py
git commit -m "feat(handoff): add tkinter orchestrator UI with live log"
```

---

### Task 7: Integration checklist + README blurb

**Files:**
- Create: `docs/model-handoff/tools/README-orchestrator.md` (short operator guide)

- [ ] **Step 1: Write operator guide** (Chinese, concise)

Contents:
1. Prerequisites: Python 3.10+, pwsh, git
2. Launch command
3. Per-task button order matching design
4. Reminder: OpenCode stays manual; ACCEPTED runs ff-only merge
5. REWORK keeps branch and sets index READY
6. Logs under `tools/logs/`

- [ ] **Step 2: Run full pytest suite**

```powershell
python -m pytest docs/model-handoff/tools/tests -v
```

Expected: all PASS.

- [ ] **Step 3: Spec coverage self-check**

Confirm implemented:
- [x] Semi-auto prepare / prompt+commit generated / review copy / ACCEPTED chain / REWORK / BLOCKED
- [x] Log panel + optional file log
- [x] `docs/model-handoff` paths
- [x] No push / no force

- [ ] **Step 4: Commit** (if user requested)

```bash
git add docs/model-handoff/tools/README-orchestrator.md
git commit -m "docs(handoff): add orchestrator operator guide"
```

---

## Self-review (plan vs spec)

| Spec requirement | Task |
|---|---|
| tkinter semi-auto GUI | 6 |
| Prepare branch + clean check | 5 |
| PS1 generate + commit generated | 4+5 |
| Clipboard exec prompt | 4+6 |
| Review prompt from `05-...md` | 4+5 |
| ACCEPTED full advance chain | 5 |
| REWORK → READY, keep branch | 5 |
| BLOCKED mark + stop | 5 |
| Live log + file log | 5+6 |
| `docs/model-handoff` paths | 1 models |
| No OpenCode automation | N/A (non-goal) |

No TBD placeholders. Type names consistent (`Step`, `SessionContext`, `HandoffPaths`, `Orchestrator`).

---

## Execution handoff

Plan complete and saved to `docs/superpowers/plans/2026-07-18-handoff-orchestrator.md`.

**Two execution options:**

1. **Subagent-Driven (recommended)** — fresh subagent per task, review between tasks  
2. **Inline Execution** — implement in this session with checkpoints  

Which approach?
