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


def test_parse_real_index():
    repo = Path(__file__).resolve().parents[4]
    p = repo / "docs" / "model-handoff" / "02-任务索引.md"
    rows = parse_index(p)
    assert len(rows) >= 40
    assert any(r.status in {"READY", "ACCEPTED", "PENDING"} for r in rows)
