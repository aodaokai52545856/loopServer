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
