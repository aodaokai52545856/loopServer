from __future__ import annotations

from datetime import datetime, timezone
from pathlib import Path
from typing import Callable


LogFn = Callable[[str, str], None]  # level, message


def make_logger(ui_append: Callable[[str], None], log_file: Path | None = None) -> LogFn:
    if log_file is not None:
        log_file.parent.mkdir(parents=True, exist_ok=True)

    def log(level: str, message: str) -> None:
        ts = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        line = f"[{ts}] {level}: {message}"
        ui_append(line)
        if log_file is not None:
            with log_file.open("a", encoding="utf-8") as f:
                f.write(line + "\n")

    return log
