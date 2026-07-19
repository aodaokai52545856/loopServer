from __future__ import annotations

import os
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


def append_base_head_reconcile_note(prompt: str, task_id: str) -> str:
    """Explain that HEAD may be the docs(handoff) prompt commit whose parent is Base.

    We must commit the generated prompt so the worktree is clean for execution, but that
    moves HEAD while Base stays at the previous tip. Models that require Base==HEAD would
    otherwise BLOCK. This trusted note is part of the dynamic launch prompt.
    """
    if "## 编排器附注" in prompt:
        return prompt
    note = f"""
## 编排器附注（可信，优先于下方「不可信 Git 数据」与含糊的 Base/HEAD 字面比对）
- Base commit 是 Task 起点（accept/建分支时 tip），**不是**提示词提交自身。
- 编排器已创建（或将创建）提交：`docs(handoff): add prompt for {task_id}`，其父提交等于 Base；工作区在 READY 时应干净。
- **READY / 开始状态：** 分支名须与提示词一致；HEAD 允许为 Base，**或**为上述提示词提交（`git rev-parse HEAD^` 等于 Base，且 subject 匹配 `docs(handoff): add prompt for {task_id}`）。不得仅因 HEAD≠Base 就判定「Base/HEAD 不符」。
- **执行期：** 保持工作区干净；不得 reset/删除该提示词提交。
- **COMPLETED：** Task commit 的父提交必须等于该提示词提交（开始执行时的 HEAD），不要直接把 Task commit 挂在 Base 上。
"""
    return prompt.rstrip() + "\n" + note


def run_new_model_task_prompt(
    ps1: Path,
    task_id: str,
    model: str,
    repo_root: Path,
    allow_commit: bool = True,
) -> CmdResult:
    # Force UTF-8 pipeline so Chinese prompt text is not decoded as the OEM code page.
    allow = " -AllowCommit" if allow_commit else ""
    script = (
        "[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false); "
        "$OutputEncoding = [Console]::OutputEncoding; "
        f"& '{ps1.as_posix()}' -TaskId '{task_id}' -Model '{model}' "
        f"-RepositoryRoot '{repo_root}'{allow}"
    )
    args = ["pwsh", "-NoProfile", "-Command", script]
    env = os.environ.copy()
    env["PYTHONUTF8"] = "1"
    proc = subprocess.run(
        args,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        cwd=str(repo_root),
        env=env,
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
