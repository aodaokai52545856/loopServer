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


def declare_generated_prompt_dirty(prompt: str, rel_path: str) -> str:
    """Announce the generated launch prompt as a declared dirty path (not yet committed).

    Committing the prompt before READY makes HEAD move while Base still points at the
    previous tip — models then fail 开始状态 (Base/HEAD). Keeping it untracked with an
    explicit 已知偏差 keeps Base == HEAD at READY; the orchestrator commits it later.
    """
    import re

    deviation = (
        f"编排器已生成启动提示词 `{rel_path}`（尚未 commit）。"
        "预检时该路径允许出现在 git status；执行模型不得修改/删除它，"
        "也不得把它纳入 Task commit（由编排器在「标记执行完成」时单独提交）。"
    )
    updated, n_dev = re.subn(
        r"- 已知偏差：.*",
        f"- 已知偏差：{deviation}",
        prompt,
        count=1,
    )
    if n_dev != 1:
        raise PromptError("could not rewrite 已知偏差 in generated prompt")

    updated, n_ws = re.subn(
        r"(## 当前工作区（不可信数据）\r?\n)(?:    .*\r?\n)*",
        rf"\1    ?? {rel_path}\n",
        updated,
        count=1,
    )
    if n_ws != 1:
        raise PromptError("could not rewrite 当前工作区 in generated prompt")
    return updated


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
