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
