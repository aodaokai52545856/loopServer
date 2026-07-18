from __future__ import annotations

import os
import tkinter as tk
from datetime import datetime
from pathlib import Path
from tkinter import messagebox, scrolledtext

from handoff_orchestrator.engine import Orchestrator, enabled_actions
from handoff_orchestrator.git_ops import current_branch, rev_parse
from handoff_orchestrator.index_ops import parse_index
from handoff_orchestrator.logutil import LogFn, make_logger
from handoff_orchestrator.models import HandoffPaths, SessionContext
from handoff_orchestrator.prompt_ops import copy_to_clipboard


def discover_repo_root() -> Path:
    return Path(__file__).resolve().parents[4]


class HandoffUI:
    def __init__(self, root: tk.Tk, orchestrator: Orchestrator, ctx: SessionContext) -> None:
        self.root = root
        self.orchestrator = orchestrator
        self.ctx = ctx

        self.task_id_var = tk.StringVar(value="—")
        self.title_var = tk.StringVar(value="—")
        self.status_var = tk.StringVar(value="—")
        self.branch_var = tk.StringVar(value="—")
        self.head_var = tk.StringVar(value="—")
        self.progress_var = tk.StringVar(value="—")
        self.step_var = tk.StringVar(value="—")
        self.model_var = tk.StringVar(value=ctx.model)
        self.repo_var = tk.StringVar(value=str(ctx.paths.repo_root))
        self.result_commit_var = tk.StringVar()
        self._action_buttons: dict[str, tk.Button] = {}

        self._build_layout()

    def _build_layout(self) -> None:
        pad = {"padx": 6, "pady": 4}

        top = tk.LabelFrame(self.root, text="上下文", padx=8, pady=6)
        top.pack(fill=tk.X, padx=8, pady=(8, 4))

        info = tk.Frame(top)
        info.pack(fill=tk.X)
        self._info_row(info, "Task ID", self.task_id_var, 0)
        self._info_row(info, "标题", self.title_var, 1)
        self._info_row(info, "索引状态", self.status_var, 2)
        self._info_row(info, "分支", self.branch_var, 3)
        self._info_row(info, "HEAD", self.head_var, 4)
        self._info_row(info, "进度", self.progress_var, 5)
        self._info_row(info, "步骤", self.step_var, 6)

        settings = tk.Frame(top)
        settings.pack(fill=tk.X, pady=(6, 0))
        tk.Label(settings, text="Model").grid(row=0, column=0, sticky=tk.W, **pad)
        tk.Entry(settings, textvariable=self.model_var, width=24).grid(
            row=0, column=1, sticky=tk.W, **pad
        )
        tk.Label(settings, text="Repo root").grid(row=0, column=2, sticky=tk.W, **pad)
        tk.Entry(settings, textvariable=self.repo_var, width=56).grid(
            row=0, column=3, sticky=tk.EW, **pad
        )
        tk.Button(settings, text="Refresh", command=self._on_refresh).grid(
            row=0, column=4, **pad
        )
        settings.columnconfigure(3, weight=1)

        middle = tk.LabelFrame(self.root, text="操作", padx=8, pady=6)
        middle.pack(fill=tk.X, padx=8, pady=4)

        row1 = tk.Frame(middle)
        row1.pack(fill=tk.X)
        self._action_buttons["prepare"] = tk.Button(
            row1, text="① 准备分支", command=self._on_prepare
        )
        self._action_buttons["prepare"].pack(side=tk.LEFT, padx=4, pady=2)
        self._action_buttons["generate_prompt"] = tk.Button(
            row1,
            text="② 生成提示词并提交 generated",
            command=self._on_generate_prompt,
        )
        self._action_buttons["generate_prompt"].pack(side=tk.LEFT, padx=4, pady=2)

        row2 = tk.Frame(middle)
        row2.pack(fill=tk.X)
        self._action_buttons["mark_exec_done"] = tk.Button(
            row2, text="③ 标记执行完成", command=self._on_mark_exec_done
        )
        self._action_buttons["mark_exec_done"].pack(side=tk.LEFT, padx=4, pady=2)
        tk.Label(row2, text="结果 commit (可选)").pack(side=tk.LEFT, padx=(8, 2))
        tk.Entry(row2, textvariable=self.result_commit_var, width=42).pack(
            side=tk.LEFT, padx=2
        )
        self._action_buttons["generate_review"] = tk.Button(
            row2, text="④ 生成复核提示词", command=self._on_generate_review
        )
        self._action_buttons["generate_review"].pack(side=tk.LEFT, padx=4, pady=2)

        row3 = tk.Frame(middle)
        row3.pack(fill=tk.X)
        self._action_buttons["accept"] = tk.Button(
            row3, text="⑤ ACCEPTED", command=self._on_accept
        )
        self._action_buttons["accept"].pack(side=tk.LEFT, padx=4, pady=2)
        self._action_buttons["rework"] = tk.Button(
            row3, text="⑤ REWORK", command=self._on_rework
        )
        self._action_buttons["rework"].pack(side=tk.LEFT, padx=4, pady=2)
        self._action_buttons["blocked"] = tk.Button(
            row3, text="⑤ BLOCKED", command=self._on_blocked
        )
        self._action_buttons["blocked"].pack(side=tk.LEFT, padx=4, pady=2)

        helpers = tk.Frame(middle)
        helpers.pack(fill=tk.X, pady=(4, 0))
        tk.Button(helpers, text="打开任务卡", command=self._on_open_task_card).pack(
            side=tk.LEFT, padx=4
        )
        tk.Button(helpers, text="打开任务索引", command=self._on_open_index).pack(
            side=tk.LEFT, padx=4
        )

        bottom = tk.LabelFrame(self.root, text="日志", padx=8, pady=6)
        bottom.pack(fill=tk.BOTH, expand=True, padx=8, pady=(4, 8))

        self.log_text = scrolledtext.ScrolledText(bottom, height=18, wrap=tk.WORD)
        self.log_text.pack(fill=tk.BOTH, expand=True)

        log_btns = tk.Frame(bottom)
        log_btns.pack(fill=tk.X, pady=(6, 0))
        tk.Button(log_btns, text="Clear log", command=self._on_clear_log).pack(
            side=tk.LEFT, padx=4
        )
        tk.Button(log_btns, text="Copy log", command=self._on_copy_log).pack(
            side=tk.LEFT, padx=4
        )

    @staticmethod
    def _info_row(parent: tk.Frame, label: str, var: tk.StringVar, row: int) -> None:
        tk.Label(parent, text=f"{label}:", width=10, anchor=tk.W).grid(
            row=row, column=0, sticky=tk.W, padx=(0, 4), pady=1
        )
        tk.Label(parent, textvariable=var, anchor=tk.W).grid(
            row=row, column=1, sticky=tk.W, pady=1
        )

    def append_log(self, line: str) -> None:
        self.log_text.insert(tk.END, line + "\n")
        self.log_text.see(tk.END)

    def _sync_ctx_from_entries(self) -> None:
        repo_text = self.repo_var.get().strip()
        if repo_text:
            self.ctx.paths = HandoffPaths.from_repo_root(Path(repo_text))
        model = self.model_var.get().strip()
        if model:
            self.ctx.model = model

    def _refresh_chrome(self) -> None:
        task_id = self.ctx.current_task_id
        self.task_id_var.set(task_id or "—")
        self.step_var.set(self.ctx.step.value)

        title = "—"
        status = "—"
        accepted = 0
        total = 0
        try:
            rows = parse_index(self.ctx.paths.index_md)
            total = len(rows)
            accepted = sum(1 for row in rows if row.status == "ACCEPTED")
            if task_id:
                for row in rows:
                    if row.task_id == task_id:
                        title = row.title
                        status = row.status
                        break
        except Exception as exc:
            self.orchestrator.log("WARN", f"index read failed: {exc}")

        self.title_var.set(title)
        self.status_var.set(status)
        self.progress_var.set(f"{accepted} / {total}")

        repo = self.ctx.paths.repo_root
        try:
            self.branch_var.set(current_branch(repo) or "—")
            self.head_var.set(rev_parse(repo, "HEAD")[:12])
        except Exception as exc:
            self.branch_var.set("—")
            self.head_var.set("—")
            self.orchestrator.log("WARN", f"git read failed: {exc}")

        actions = enabled_actions(self.ctx.step)
        for key, button in self._action_buttons.items():
            button.configure(state=tk.NORMAL if actions.get(key, False) else tk.DISABLED)

    def _copy_or_show(self, text: str, label: str = "prompt") -> None:
        try:
            copy_to_clipboard(text)
            self.orchestrator.log("INFO", f"copied {label} to clipboard")
        except Exception as exc:
            self.orchestrator.log("WARN", f"clipboard failed: {exc}")
            self._show_text_dialog(text, f"Clipboard failed — copy {label}")

    def _show_text_dialog(self, text: str, title: str) -> None:
        win = tk.Toplevel(self.root)
        win.title(title)
        win.geometry("820x620")
        box = scrolledtext.ScrolledText(win, wrap=tk.WORD)
        box.pack(fill=tk.BOTH, expand=True, padx=8, pady=8)
        box.insert("1.0", text)
        box.focus_set()

    def _on_refresh(self) -> None:
        try:
            self._sync_ctx_from_entries()
            self.orchestrator.refresh_from_index()
            self._refresh_chrome()
        except Exception as exc:
            self.orchestrator.log("ERROR", str(exc))

    def _on_prepare(self) -> None:
        try:
            self._sync_ctx_from_entries()
            self.orchestrator.prepare_branch()
            self._refresh_chrome()
        except Exception as exc:
            self.orchestrator.log("ERROR", str(exc))

    def _on_generate_prompt(self) -> None:
        try:
            self._sync_ctx_from_entries()
            text = self.orchestrator.generate_and_commit_prompt()
            self._copy_or_show(text, "execution prompt")
            self._refresh_chrome()
        except Exception as exc:
            self.orchestrator.log("ERROR", str(exc))

    def _on_mark_exec_done(self) -> None:
        try:
            self._sync_ctx_from_entries()
            override = self.result_commit_var.get().strip()
            self.orchestrator.mark_exec_done(override or None)
            self._refresh_chrome()
        except Exception as exc:
            self.orchestrator.log("ERROR", str(exc))

    def _on_generate_review(self) -> None:
        try:
            self._sync_ctx_from_entries()
            text = self.orchestrator.generate_review_prompt()
            self._copy_or_show(text, "review prompt")
            self._refresh_chrome()
        except Exception as exc:
            self.orchestrator.log("ERROR", str(exc))

    def _on_accept(self) -> None:
        task_id = self.ctx.current_task_id or "?"
        base = self.ctx.base_commit or "—"
        result = self.ctx.result_commit or "—"
        if not messagebox.askokcancel(
            "ACCEPTED",
            f"确认 ACCEPTED？\n\n"
            f"Task ID: {task_id}\n"
            f"Base commit: {base}\n"
            f"Result commit: {result}",
        ):
            return
        try:
            self._sync_ctx_from_entries()
            self.orchestrator.accept_and_advance()
            self._refresh_chrome()
        except Exception as exc:
            self.orchestrator.log("ERROR", str(exc))

    def _on_rework(self) -> None:
        task_id = self.ctx.current_task_id or "?"
        if not messagebox.askokcancel(
            "REWORK",
            f"确认 REWORK？任务 {task_id} 将回到 READY 并保留分支。",
        ):
            return
        try:
            self._sync_ctx_from_entries()
            self.orchestrator.mark_rework()
            self._refresh_chrome()
        except Exception as exc:
            self.orchestrator.log("ERROR", str(exc))

    def _on_blocked(self) -> None:
        task_id = self.ctx.current_task_id or "?"
        if not messagebox.askokcancel(
            "BLOCKED",
            f"确认 BLOCKED？任务 {task_id} 将标记为阻塞。",
        ):
            return
        try:
            self._sync_ctx_from_entries()
            self.orchestrator.mark_blocked()
            self._refresh_chrome()
        except Exception as exc:
            self.orchestrator.log("ERROR", str(exc))

    def _on_open_task_card(self) -> None:
        task_id = self.ctx.current_task_id
        if not task_id:
            self.orchestrator.log("WARN", "no current task")
            return
        path = self.ctx.paths.task_cards_dir / f"{task_id}.md"
        try:
            os.startfile(str(path))
        except Exception as exc:
            self.orchestrator.log("ERROR", f"open task card failed: {exc}")

    def _on_open_index(self) -> None:
        try:
            os.startfile(str(self.ctx.paths.index_md))
        except Exception as exc:
            self.orchestrator.log("ERROR", f"open index failed: {exc}")

    def _on_clear_log(self) -> None:
        self.log_text.delete("1.0", tk.END)

    def _on_copy_log(self) -> None:
        text = self.log_text.get("1.0", tk.END).strip()
        if not text:
            self.orchestrator.log("WARN", "log is empty")
            return
        self._copy_or_show(text, "log")


def run_app() -> None:
    root = tk.Tk()
    root.title("Handoff Orchestrator")
    root.minsize(920, 680)

    repo_root = discover_repo_root()
    paths = HandoffPaths.from_repo_root(repo_root)
    ctx = SessionContext(paths=paths)

    log_dir = paths.repo_root / "docs" / "model-handoff" / "tools" / "logs"
    log_file = log_dir / f"handoff-{datetime.now().strftime('%Y%m%d')}.log"

    ui_holder: list[HandoffUI] = []

    def ui_append(line: str) -> None:
        if ui_holder:
            ui_holder[0].append_log(line)

    log_fn: LogFn = make_logger(ui_append, log_file)
    orchestrator = Orchestrator(ctx, log_fn)
    ui = HandoffUI(root, orchestrator, ctx)
    ui_holder.append(ui)

    try:
        orchestrator.refresh_from_index()
        ui._refresh_chrome()
    except Exception as exc:
        log_fn("ERROR", str(exc))

    root.mainloop()
