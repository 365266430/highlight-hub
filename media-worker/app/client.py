"""Java service client for the internal worker API."""
from __future__ import annotations

import requests

from . import config


class JavaClient:
    def __init__(self, base_url: str | None = None, token: str | None = None, worker_id: str | None = None):
        self.base_url = (base_url or config.JAVA_BASE_URL).rstrip("/")
        self.token = token or config.WORKER_TOKEN
        self.worker_id = worker_id or config.WORKER_ID
        self.session = requests.Session()
        self.session.headers.update({
            "X-Worker-Token": self.token,
            "Content-Type": "application/json",
        })

    def claim(self, types: list[str], max_count: int = 1, lease_seconds: int | None = None) -> list[dict]:
        body = {
            "workerId": self.worker_id,
            "types": types,
            "maxCount": max_count,
            "leaseSeconds": lease_seconds or config.LEASE_SECONDS,
        }
        r = self.session.post(f"{self.base_url}/internal/tasks/claim", json=body, timeout=15)
        if r.status_code == 403:
            raise PermissionError("worker token rejected by Java service")
        r.raise_for_status()
        return r.json()

    def heartbeat(self, task_id: str, attempt_token: str, lease_seconds: int | None = None) -> bool:
        r = self.session.post(f"{self.base_url}/internal/tasks/{task_id}/heartbeat", json={
            "attemptToken": attempt_token,
            "leaseSeconds": lease_seconds or config.LEASE_SECONDS,
        }, timeout=15)
        return r.status_code == 200

    def progress(self, task_id: str, attempt_token: str, progress: int, phase: str | None = None) -> bool:
        r = self.session.post(f"{self.base_url}/internal/tasks/{task_id}/progress", json={
            "attemptToken": attempt_token,
            "progress": max(0, min(100, int(progress))),
            "phase": phase,
        }, timeout=15)
        return r.status_code == 200

    def complete(self, task_id: str, attempt_token: str, result: dict, output_ref: str | None = None) -> str | None:
        r = self.session.post(f"{self.base_url}/internal/tasks/{task_id}/complete", json={
            "attemptToken": attempt_token,
            "outputRef": output_ref,
            "result": result,
        }, timeout=60)
        if r.status_code == 200:
            return r.json().get("status", "SUCCEEDED")
        return None

    def fail(self, task_id: str, attempt_token: str, error_code: str,
             error_message: str, retryable: bool = True) -> bool:
        r = self.session.post(f"{self.base_url}/internal/tasks/{task_id}/fail", json={
            "attemptToken": attempt_token,
            "errorCode": error_code,
            "errorMessage": error_message[:480],
            "retryable": retryable,
        }, timeout=15)
        return r.status_code == 200

    def ping(self, active_task_id: str | None = None) -> None:
        """Worker-loop liveness ping (best effort; used for admin observability)."""
        import shutil
        import time
        body = {"workerId": self.worker_id, "activeTaskId": active_task_id}
        try:
            body["diskFreeBytes"] = shutil.disk_usage(config.STORAGE_ROOT).free
        except OSError:
            pass
        body["uptimeSeconds"] = int(time.time() - config.STARTED_AT)
        try:
            self.session.post(f"{self.base_url}/internal/tasks/ping", json=body, timeout=5)
        except requests.RequestException:
            pass

    def cancellation(self, task_id: str, attempt_token: str) -> bool:
        """True = stop the task. False = keep going. Stale token also aborts:
        the attempt no longer owns the task, so continuing would be wasted work."""
        try:
            r = self.session.get(
                f"{self.base_url}/internal/tasks/{task_id}/cancellation",
                params={"attemptToken": attempt_token}, timeout=10)
        except requests.RequestException:
            return False  # transient network issue: keep working
        if r.status_code == 200:
            return bool(r.json().get("cancelRequested"))
        return True  # 409/404: this attempt lost the task

    def health(self) -> bool:
        try:
            r = self.session.get(f"{self.base_url}/actuator/health", timeout=5)
            return r.status_code == 200
        except requests.RequestException:
            return False
