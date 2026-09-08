"""Worker main loop: pull tasks from Java, execute with heartbeat + cancellation."""
from __future__ import annotations

import logging
import threading

from . import executors
from .client import JavaClient

log = logging.getLogger("worker")


class Worker:
    def __init__(self, client: JavaClient | None = None):
        self.client = client or JavaClient()
        self._stop = threading.Event()

    def stop(self):
        self._stop.set()

    def run_forever(self):
        log.info("worker %s started; java=%s storage=%s types=%s",
                 self.client.worker_id, self.client.base_url, executors.config.STORAGE_ROOT,
                 executors.config.CLAIM_TYPES)
        while not self._stop.is_set():
            try:
                claimed = self.client.claim(executors.config.CLAIM_TYPES, max_count=1)
            except PermissionError as e:
                log.error("%s; exiting", e)
                return
            except Exception as e:
                log.warning("claim failed: %s", e)
                self._stop.wait(executors.config.CLAIM_INTERVAL_SECONDS)
                continue
            if not claimed:
                self._stop.wait(executors.config.CLAIM_INTERVAL_SECONDS)
                continue
            for task in claimed:
                self._execute(task)

    def _execute(self, task: dict):
        task_id = task["taskId"]
        token = task["attemptToken"]
        log.info("claimed task %s type %s attempt %s", task_id, task["type"], task.get("attempt"))

        def progress(pct: int, phase: str):
            if not self.client.progress(task_id, token, pct, phase):
                raise executors.Abort(f"progress rejected for task {task_id} (lost attempt)")

        def cancel_requested() -> bool:
            return self.client.cancellation(task_id, token)

        try:
            result = executors.run_task(task, self.client, progress, cancel_requested)
        except executors.Abort as e:
            log.warning("task %s aborted: %s", task_id, e)
            return
        except executors.ffm.CancellationRequested:
            log.info("task %s cancelled by request; reporting outcome", task_id)
            self.client.complete(task_id, token, {"ok": False, "cancelled": True},
                                 output_ref=None)
            return
        except Exception as e:  # unexpected worker crash: retryable infrastructure error
            log.exception("task %s crashed unexpectedly", task_id)
            self.client.fail(task_id, token, "WORKER_CRASH", f"{type(e).__name__}: {e}",
                             retryable=True)
            return

        if result.get("ok"):
            status = self.client.complete(task_id, token, result,
                                          output_ref=result.get("storageKey"))
            log.info("task %s complete -> %s", task_id, status)
        elif result.get("cancelled"):
            self.client.complete(task_id, token, result, output_ref=None)
        else:
            self.client.fail(task_id, token,
                             result.get("errorCode", "WORKER_ERROR"),
                             result.get("errorMessage", "task failed"),
                             retryable=result.get("retryable", True))
