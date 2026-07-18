"""Launch: python docs/model-handoff/tools/handoff_orchestrator.py"""
from __future__ import annotations

import sys
from pathlib import Path


def main() -> int:
    tools_dir = Path(__file__).resolve().parent
    if str(tools_dir) not in sys.path:
        sys.path.insert(0, str(tools_dir))
    from handoff_orchestrator.ui import run_app

    run_app()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
