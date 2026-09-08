"""Worker entrypoint: uvicorn health endpoint + task loop in the same process."""
from __future__ import annotations

import logging
import threading

import uvicorn
from fastapi import FastAPI

from . import executors
from .client import JavaClient
from .worker import Worker

logging.basicConfig(level=logging.INFO,
                    format="%(asctime)s %(levelname)s %(name)s: %(message)s")

app = FastAPI(title="HighlightHub media-worker", version="0.1.0")
worker = Worker()
_last_status = {"state": "starting"}


@app.get("/health")
def health():
    java_up = worker.client.health()
    return {"ok": True, "workerId": worker.client.worker_id,
            "javaReachable": java_up, "state": _last_status["state"]}


def _loop():
    global _last_status
    try:
        _last_status["state"] = "running"
        worker.run_forever()
        _last_status["state"] = "stopped"
    except Exception:
        logging.getLogger("worker").exception("worker loop crashed")
        _last_status["state"] = "crashed"


def main():
    threading.Thread(target=_loop, daemon=True).start()
    uvicorn.run(app, host="127.0.0.1",
                port=int(executors.config.__dict__.get("HEALTH_PORT", 8123) or 8123),
                log_level="warning")


if __name__ == "__main__":
    main()
