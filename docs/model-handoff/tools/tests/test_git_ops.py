# docs/model-handoff/tools/tests/test_git_ops.py
from handoff_orchestrator.git_ops import parse_status_paths, is_clean_status


def test_parse_status_paths():
    out = " M a.txt\n?? b.txt\n"
    assert parse_status_paths(out) == ["a.txt", "b.txt"]


def test_is_clean_status():
    assert is_clean_status("") is True
    assert is_clean_status("\n") is True
    assert is_clean_status("?? x") is False
