"""Phase-3 E2E: SSE task-event push (user-isolated) + CROP/mask render path.

SYNTHETIC fixtures only. Run with the worker venv python (has requests+cv2).
"""
from __future__ import annotations

import json
import os
import sys
import threading
import time

import cv2
import numpy as np
import requests

sys.path.insert(0, os.path.dirname(__file__))
from run_e2e import CHECKS, BASE, check, register_login, wait_for  # noqa: E402

UNIQUE = str(int(time.time()))
USER = f"p3_{UNIQUE}"[-20:]
PASSWORD = "password123"
FIXTURE = os.path.join(os.path.dirname(__file__), "..", "media-worker", "tests",
                       "fixtures", "synthetic-10s.mp4")

session = requests.Session()
sse_events: list[dict] = []
sse_status = {"connected": False}


def sse_listener():
    """Read the SSE stream in a thread; record task events for this user."""
    try:
        with session.get(f"{BASE}/api/tasks/stream", stream=True, timeout=60) as resp:
            event_name = None
            for line in resp.iter_lines(decode_unicode=True):
                if line is None:
                    continue
                if line.startswith("event:"):
                    event_name = line.split(":", 1)[1].strip()
                elif line.startswith("data:") and event_name:
                    try:
                        payload = json.loads(line.split(":", 1)[1].strip())
                    except json.JSONDecodeError:
                        payload = {}
                    if event_name == "connected":
                        sse_status["connected"] = True
                    if event_name == "task":
                        sse_events.append(payload)
                    event_name = None
    except requests.RequestException:
        pass


def main() -> int:
    register_login(session, USER)
    listener = threading.Thread(target=sse_listener, daemon=True)
    listener.start()

    # wait for the connected event
    wait_for(lambda: sse_status["connected"], 20, what="SSE connected event")
    check("p3: SSE stream connected with session auth", True)

    # uploading triggers a PROBE task; its terminal event must arrive via SSE
    data = open(FIXTURE, "rb").read()
    r = session.post(f"{BASE}/api/uploads",
                     json={"originalFilename": "s.mp4", "declaredSize": len(data)}, timeout=10)
    up = r.json()
    negotiated = up["chunkSize"]
    for idx, off in enumerate(range(0, len(data), negotiated)):
        rr = session.put(f"{BASE}/api/uploads/{up['uploadId']}/chunks/{idx}",
                         data=data[off:off + negotiated], timeout=60)
        assert rr.status_code == 200
    media_id = session.post(f"{BASE}/api/uploads/{up['uploadId']}/complete", json={},
                            timeout=120).json()["mediaId"]
    wait_for(lambda: any(e.get("type") == "PROBE" and e.get("status") == "SUCCEEDED"
                         for e in sse_events), 60, what="PROBE SUCCEEDED via SSE")
    check("p3: task terminal event pushed over SSE", True, json.dumps(sse_events[:3]))

    def ready():
        m = session.get(f"{BASE}/api/media/{media_id}", timeout=10).json()
        return m if m.get("status") == "READY" else None
    wait_for(ready, 120, what="media READY")

    # progress events should also stream (preview/transcode reports percentages)
    def progress_seen():
        return any(e.get("type") == "PREVIEW" and e.get("progress", 0) > 0 for e in sse_events)
    try:
        wait_for(progress_seen, 60, what="PREVIEW progress via SSE")
        check("p3: live progress percentages pushed over SSE", True)
    except TimeoutError:
        check("p3: live progress percentages pushed over SSE", False, "no progress events observed")

    # ---- CROP + mask render through the full stack ----
    project = session.post(f"{BASE}/api/projects",
                           json={"mediaId": media_id, "name": "crop-mask"}, timeout=10).json()
    edl = {
        "schemaVersion": 1,
        "sourceMediaId": media_id,
        "segments": [{"id": "s1", "sourceInMs": 0, "sourceOutMs": 3000,
                      "caption": "", "sourceVolume": 1.0}],
        "output": {"aspectMode": "CROP", "width": 400, "height": 400, "fps": 30},
        "masks": [{"x": 0.25, "y": 0.25, "w": 0.25, "h": 0.25}],
    }
    save = {"expectedRevision": 0, "name": "crop-mask", **edl}
    r = session.put(f"{BASE}/api/projects/{project['id']}", json=save, timeout=10)
    check("p3: CROP+mask EDL accepted by validator", r.status_code == 200, r.text[:200])
    # invalid mask rejected server-side
    bad = {**save, "expectedRevision": 1, "masks": [{"x": 0.9, "y": 0.9, "w": 0.5, "h": 0.5}]}
    r = session.put(f"{BASE}/api/projects/{project['id']}", json=bad, timeout=10)
    check("p3: out-of-frame mask rejected 400", r.status_code == 400, str(r.status_code))

    render = session.post(f"{BASE}/api/projects/{project['id']}/renders",
                          json={"projectRevision": 1}, timeout=15).json()
    def render_done():
        j = session.get(f"{BASE}/api/renders/{render['id']}", timeout=10).json()
        return j if j.get("status") in ("SUCCEEDED", "FAILED", "CANCELLED") else None
    job = wait_for(render_done, 300, what="crop render terminal state")
    check("p3: crop+mask render SUCCEEDED", job.get("status") == "SUCCEEDED",
          json.dumps(job)[:250])

    mp4 = session.get(f"{BASE}/api/renders/{render['id']}/download", timeout=60).content
    tmp = os.path.join(os.path.dirname(os.path.abspath(__file__)), f"p3-{UNIQUE}.mp4")
    with open(tmp, "wb") as f:
        f.write(mp4)
    cap = cv2.VideoCapture(tmp)
    ok, frame = cap.read()
    cap.release()
    check("p3: output decoded", ok and frame is not None)
    if ok:
        h, w = frame.shape[:2]
        check("p3: CROP output is 400x400 (filled)", (w, h) == (400, 400), f"{w}x{h}")
        gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
        # mask 0.25..0.5 in source space maps through scale-to-fill + center
        # crop to output x 22..200, y 100..200 (640x360 -> 711x400 -> 400x400)
        region = gray[110:190, 40:190]
        check("p3: masked region renders black", float(region.mean()) < 3,
              f"mean={region.mean():.2f}")
    os.remove(tmp)

    passed = sum(1 for _, ok, _ in CHECKS if ok)
    failed = len(CHECKS) - passed
    print(f"\n==== PHASE-3 E2E RESULT: {passed} passed, {failed} failed ====")
    for name, ok, detail in CHECKS:
        if not ok:
            print(f"FAILED: {name} | {detail}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
