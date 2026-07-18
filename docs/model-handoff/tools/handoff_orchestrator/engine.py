from __future__ import annotations

from handoff_orchestrator.git_ops import (
    branch_exists,
    commit_paths,
    current_branch,
    delete_branch,
    log_oneline,
    merge_ff_only,
    require_clean,
    rev_parse,
    switch_branch,
)
from handoff_orchestrator.index_ops import (
    find_first_ready,
    find_next_pending_after,
    parse_index,
    set_status,
    write_index,
)
from handoff_orchestrator.logutil import LogFn
from handoff_orchestrator.models import SessionContext, Step
from handoff_orchestrator.prompt_ops import (
    fill_review_prompt,
    newest_generated_for_task,
    run_new_model_task_prompt,
)


def enabled_actions(step: Step) -> dict[str, bool]:
    return {
        "prepare": step in {Step.IDLE, Step.PREPARE},
        "generate_prompt": step == Step.PROMPT_READY,
        "mark_exec_done": step == Step.WAIT_EXEC,
        "generate_review": step == Step.WAIT_REVIEW,
        "accept": step == Step.WAIT_REVIEW,
        "rework": step == Step.WAIT_REVIEW,
        "blocked": step == Step.WAIT_REVIEW,
    }


class Orchestrator:
    def __init__(self, ctx: SessionContext, log: LogFn) -> None:
        self.ctx = ctx
        self.log = log

    def refresh_from_index(self) -> None:
        rows = parse_index(self.ctx.paths.index_md)
        ready = find_first_ready(rows)
        if ready is not None:
            self.ctx.current_task_id = ready.task_id
            self.ctx.step = Step.PREPARE
        else:
            self.ctx.current_task_id = None
            self.ctx.step = Step.IDLE

    def prepare_branch(self) -> None:
        repo = self.ctx.paths.repo_root
        task_id = self.ctx.current_task_id
        if not task_id:
            raise RuntimeError("no current task")
        self.log("INFO", f"prepare branch for {task_id}")
        require_clean(repo)
        switch_branch(repo, self.ctx.main_branch, create=False)
        require_clean(repo)
        # Base is always main tip at prepare time (not task-branch HEAD after prior commits).
        self.ctx.base_commit = rev_parse(repo, "HEAD")
        branch = f"task/{task_id}"
        if current_branch(repo) != branch:
            if branch_exists(repo, branch):
                switch_branch(repo, branch, create=False)
            else:
                switch_branch(repo, branch, create=True)
        self.ctx.step = Step.PROMPT_READY
        self.log("INFO", f"on {branch} base={self.ctx.base_commit[:12]}")

    def generate_and_commit_prompt(self) -> str:
        if not self.ctx.current_task_id:
            raise RuntimeError("no current task")
        require_clean(self.ctx.paths.repo_root)
        result = run_new_model_task_prompt(
            self.ctx.paths.prompt_ps1,
            self.ctx.current_task_id,
            self.ctx.model,
            self.ctx.paths.repo_root,
            allow_commit=True,
        )
        self.log("INFO", result.stdout[-2000:] if result.stdout else "(no stdout)")
        gen = newest_generated_for_task(self.ctx.paths.generated_dir, self.ctx.current_task_id)
        rel = gen.relative_to(self.ctx.paths.repo_root).as_posix()
        commit_paths(
            self.ctx.paths.repo_root,
            [rel],
            f"docs(handoff): add prompt for {self.ctx.current_task_id}",
        )
        prompt = result.stdout.strip() or gen.read_text(encoding="utf-8")
        self.ctx.last_prompt_text = prompt
        self.ctx.step = Step.WAIT_EXEC
        return prompt

    def mark_exec_done(self, result_commit: str | None = None) -> None:
        if result_commit is not None:
            self.ctx.result_commit = result_commit
        else:
            self.ctx.result_commit = rev_parse(self.ctx.paths.repo_root, "HEAD")
        self.ctx.step = Step.WAIT_REVIEW

    def generate_review_prompt(self) -> str:
        if not self.ctx.current_task_id:
            raise RuntimeError("no current task")
        if not self.ctx.base_commit:
            raise RuntimeError("base_commit not set")
        if not self.ctx.result_commit:
            raise RuntimeError("result_commit not set")
        template = self.ctx.paths.review_template.read_text(encoding="utf-8")
        text = fill_review_prompt(
            template,
            self.ctx.current_task_id,
            self.ctx.result_commit,
            self.ctx.base_commit,
        )
        self.ctx.last_review_text = text
        return text

    def accept_and_advance(self) -> str | None:
        """Returns next task id or None if finished."""
        if not self.ctx.current_task_id:
            raise RuntimeError("no current task")
        repo = self.ctx.paths.repo_root
        task_id = self.ctx.current_task_id
        branch = f"task/{task_id}"
        require_clean(repo)
        log_range = log_oneline(repo, f"{self.ctx.main_branch}..{branch}")
        self.log("INFO", f"commits to merge:\n{log_range or '(none)'}")
        switch_branch(repo, self.ctx.main_branch)
        merge_ff_only(repo, branch)
        text = self.ctx.paths.index_md.read_text(encoding="utf-8")
        rows = parse_index(self.ctx.paths.index_md)
        next_row = find_next_pending_after(rows, task_id)
        text = set_status(text, task_id, "ACCEPTED")
        next_id = None
        msg = f"chore(handoff): accept {task_id}"
        if next_row is not None:
            text = set_status(text, next_row.task_id, "READY")
            next_id = next_row.task_id
            msg = f"chore(handoff): accept {task_id} and ready {next_id}"
        write_index(self.ctx.paths.index_md, text)
        rel = self.ctx.paths.index_md.relative_to(repo).as_posix()
        commit_paths(repo, [rel], msg)
        delete_branch(repo, branch)
        if next_id:
            switch_branch(repo, f"task/{next_id}", create=True)
            self.ctx.current_task_id = next_id
            self.ctx.base_commit = rev_parse(repo, "HEAD")
            self.ctx.result_commit = None
            self.ctx.step = Step.PROMPT_READY
        else:
            self.ctx.current_task_id = None
            self.ctx.step = Step.IDLE
        return next_id

    def mark_rework(self) -> None:
        if not self.ctx.current_task_id:
            raise RuntimeError("no current task")
        repo = self.ctx.paths.repo_root
        task_id = self.ctx.current_task_id
        text = self.ctx.paths.index_md.read_text(encoding="utf-8")
        text = set_status(text, task_id, "READY")
        write_index(self.ctx.paths.index_md, text)
        rel = self.ctx.paths.index_md.relative_to(repo).as_posix()
        commit_paths(repo, [rel], f"chore(handoff): rework {task_id} (back to READY)")
        self.ctx.step = Step.PROMPT_READY

    def mark_blocked(self) -> None:
        if not self.ctx.current_task_id:
            raise RuntimeError("no current task")
        repo = self.ctx.paths.repo_root
        task_id = self.ctx.current_task_id
        text = self.ctx.paths.index_md.read_text(encoding="utf-8")
        text = set_status(text, task_id, "BLOCKED")
        write_index(self.ctx.paths.index_md, text)
        rel = self.ctx.paths.index_md.relative_to(repo).as_posix()
        commit_paths(repo, [rel], f"chore(handoff): block {task_id}")
        self.ctx.step = Step.IDLE
        self.ctx.current_task_id = None
        self.ctx.base_commit = None
        self.ctx.result_commit = None
