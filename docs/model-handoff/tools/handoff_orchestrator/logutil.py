from __future__ import annotations

from datetime import datetime, timezone
from pathlib import Path
from typing import Callable


LogFn = Callable[[str, str], None]  # level, message


def make_logger(ui_append: Callable[[str], None], log_file: Path | None = None) -> LogFn:
    def log(level: str, message: str) -> None:
        ts = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        # Keep multi-line messages readable: prefix only the first line.
        parts = message.splitlines() or [""]
        lines = [f"[{ts}] {level}: {parts[0]}"]
        lines.extend(parts[1:])
        block = "\n".join(lines)
        ui_append(block)
        if log_file is not None:
            log_file.parent.mkdir(parents=True, exist_ok=True)
            with log_file.open("a", encoding="utf-8", newline="\n") as f:
                f.write(block + "\n")

    return log
