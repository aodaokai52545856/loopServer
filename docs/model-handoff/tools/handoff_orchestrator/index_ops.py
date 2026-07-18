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
