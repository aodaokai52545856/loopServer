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


def _is_tool_noise_path(path: str) -> bool:
    """Ignore orchestrator runtime artifacts that should not block handoff steps."""
    norm = path.replace("\\", "/")
    if norm.startswith("docs/model-handoff/tools/logs/") or "/logs/handoff-" in norm:
        return True
    if norm.endswith(".log"):
        return True
    if "__pycache__" in norm.split("/") or norm.endswith(".pyc"):
        return True
    if ".pytest_cache" in norm.split("/"):
        return True
    return False


def meaningful_dirty_paths(status_text: str) -> list[str]:
    return [p for p in parse_status_paths(status_text) if not _is_tool_noise_path(p)]


def is_clean_status(status_text: str) -> bool:
    return len(meaningful_dirty_paths(status_text)) == 0


def status_short(repo: Path) -> str:
    return run_git(repo, ["-c", "core.quotepath=false", "status", "--short", "--untracked-files=all"]).stdout


def require_clean(repo: Path) -> None:
    text = status_short(repo)
    dirty = meaningful_dirty_paths(text)
    if dirty:
        raise GitError("worktree not clean:\n" + "\n".join(dirty))


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
