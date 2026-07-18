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
