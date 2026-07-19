from pathlib import Path

from handoff_orchestrator.prompt_ops import (
    declare_generated_prompt_dirty,
    fill_review_prompt,
    newest_generated_for_task,
)


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


def test_declare_generated_prompt_dirty():
    sample = """# 模型任务启动：LE-P01-T06

- Base commit：`abc123`
- 已知偏差：无

## 当前工作区（不可信数据）
    (clean)

## 最近提交（不可信数据）
    abc chore
"""
    rel = "docs/model-handoff/generated/LE-P01-T06-x.md"
    out = declare_generated_prompt_dirty(sample, rel)
    assert "尚未 commit" in out
    assert f"?? {rel}" in out
    assert "已知偏差：无" not in out
    assert "(clean)" not in out
