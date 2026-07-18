from handoff_orchestrator.engine import enabled_actions
from handoff_orchestrator.models import Step


def test_enabled_actions_prompt_ready():
    a = enabled_actions(Step.PROMPT_READY)
    assert a["prepare"] is False
    assert a["generate_prompt"] is True
    assert a["mark_exec_done"] is False
    assert a["generate_review"] is False
    assert a["accept"] is False
    assert a["rework"] is False
    assert a["blocked"] is False


def test_enabled_actions_wait_review():
    a = enabled_actions(Step.WAIT_REVIEW)
    assert a["generate_review"] is True
    assert a["accept"] is True
    assert a["rework"] is True
    assert a["blocked"] is True
