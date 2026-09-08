"""Phase-1 end-to-end closed-loop verification.

Drives: register/login -> chunked upload -> probe/preview/thumbnail (worker) ->
manual events -> project + revisions -> render (worker) -> download + verify,
plus ownership and immutability checks.

Usage: .venv/Scripts/python.exe ../scripts/run_e2e.py
Requires the backend on JAVA_BASE_URL (default 127.0.0.1:18080) and the worker
looping against it. Synthetic fixtures only; no real game data is involved.
"""
from __future__ import annotations

import io
import json
import os
import subprocess
import sys
import time

import requests

BASE = os.environ.get("JAVA_BASE_URL", "http://127.0.0.1:8080")
FFPROBE = os.environ.get("FFPROBE_PATH", "ffprobe")
UNIQUE = str(int(time.time()))
USER = f"e2e_{UNIQUE}"[-20:]
OTHER = f"other_{UNIQUE}"[-20:]
PASSWORD = "password123"

FIXTURE = os.path.join(os.path.dirname(__file__), "..", "media-worker", "tests",
                       "fixtures", "synthetic-10s.mp4")

session = requests.Session()
other_session = requests.Session()
CHECKS: list[tuple[str, bool, str]] = []


def check(name: str, ok: bool, detail: str = ""):
    CHECKS.append((name, bool(ok), detail))
    print(("PASS " if ok else "FAIL ") + name + (f" | {detail}" if detail and not ok else ""))


def csrf(s: requests.Session):
    r = s.get(f"{BASE}/api/csrf", timeout=10)
    cookie = s.cookies.get("XSRF-TOKEN")
    token = cookie if cookie else r.json().get("token")
    s.headers["X-XSRF-TOKEN"] = token


def register_login(s: requests.Session, username: str):
    csrf(s)
    r = s.post(f"{BASE}/api/auth/register",
               json={"username": username, "password": PASSWORD}, timeout=10)
    assert r.status_code == 200, r.text
    r = s.post(f"{BASE}/api/auth/login",
               json={"username": username, "password": PASSWORD}, timeout=10)
    assert r.status_code == 200, r.text


def wait_for(fn, timeout_s: int, interval: float = 1.0, what: str = ""):
    deadline = time.time() + timeout_s
    last = None
    while time.time() < deadline:
        last = fn()
        if last:
            return last
        time.sleep(interval)
    raise TimeoutError(f"timeout waiting for {what}: last={last}")


def main() -> int:
    assert os.path.exists(FIXTURE), f"fixture missing: {FIXTURE}"

    # ---- 1. auth ----
    register_login(session, USER)
    register_login(other_session, OTHER)
    me = session.get(f"{BASE}/api/me", timeout=10).json()
    check("auth: register+login+me", me.get("username") == USER)

    # ---- 2. chunked upload with resume ----
    data = open(FIXTURE, "rb").read()
    declared = len(data)
    chunk_size = 512 * 1024
    r = session.post(f"{BASE}/api/uploads",
                     json={"originalFilename": "synthetic-10s.mp4",
                           "declaredSize": declared}, timeout=10)
    check("upload: session created", r.status_code == 200, r.text[:200])
    up = r.json()
    upload_id = up["uploadId"]
    negotiated = up["chunkSize"]

    chunks = [data[i:i + negotiated] for i in range(0, declared, negotiated)]
    # simulate a dropped connection: upload all but the first chunk first
    for idx in range(1, len(chunks)):
        r = session.put(f"{BASE}/api/uploads/{upload_id}/chunks/{idx}",
                        data=chunks[idx], timeout=60)
        assert r.status_code == 200, r.text
    state = session.get(f"{BASE}/api/uploads/{upload_id}", timeout=10).json()
    received = state["receivedChunks"]
    check("upload: partial state query", received == list(range(1, len(chunks))), str(received))
    # resume: upload only the missing chunk
    r = session.put(f"{BASE}/api/uploads/{upload_id}/chunks/0", data=chunks[0], timeout=60)
    check("upload: resume final chunk", r.status_code == 200, r.text[:200])

    r = session.post(f"{BASE}/api/uploads/{upload_id}/complete", json={}, timeout=120)
    check("upload: complete merges", r.status_code == 200, r.text[:200])
    media_id = r.json()["mediaId"]
    r2 = session.post(f"{BASE}/api/uploads/{upload_id}/complete", json={}, timeout=10)
    check("upload: complete idempotent",
          r2.status_code == 200 and r2.json().get("mediaId") == media_id, r2.text[:200])

    # ---- 3. probe -> READY -> preview/thumbnails ----
    def ready():
        m = session.get(f"{BASE}/api/media/{media_id}", timeout=10).json()
        return m if m.get("status") == "READY" else None
    media = wait_for(ready, 120, what="media READY")
    check("probe: media READY", media is not None, str(media))
    check("probe: duration parsed",
          media.get("durationMs") and 9000 <= media["durationMs"] <= 11000,
          str(media.get("durationMs")))
    check("probe: dimensions parsed", media.get("width") == 640 and media.get("height") == 360)

    def assets_ready():
        m = session.get(f"{BASE}/api/media/{media_id}", timeout=10).json()
        return m.get("hasPreview") and m.get("hasThumbnails") and m or None
    media = wait_for(assets_ready, 180, what="preview+thumbnails")
    check("worker: preview + thumbnails generated", bool(media))

    # preview streams with Range support
    r = session.get(f"{BASE}/api/media/{media_id}/preview",
                    headers={"Range": "bytes=0-1023"}, timeout=30)
    check("preview: HTTP Range works",
          r.status_code == 206 and int(r.headers.get("Content-Length", 0)) == 1024,
          f"status={r.status_code}")

    # ---- 4. ownership: other user cannot read ----
    r = other_session.get(f"{BASE}/api/media/{media_id}", timeout=10)
    check("ownership: cross-user media hidden", r.status_code == 404, f"status={r.status_code}")
    r = other_session.get(f"{BASE}/api/media/{media_id}/preview", timeout=10)
    check("ownership: cross-user preview blocked", r.status_code == 404, f"status={r.status_code}")

    # ---- 5. manual events ----
    r = session.post(f"{BASE}/api/media/{media_id}/events",
                     json={"type": "MANUAL_MARKER", "startMs": 2000, "endMs": 4000,
                           "note": "团战开始"}, timeout=10)
    check("events: manual marker created", r.status_code == 200, r.text[:200])
    event = r.json()
    r = session.patch(f"{BASE}/api/events/{event['id']}",
                      json={"startMs": 1500, "note": "调整起点"}, timeout=10)
    check("events: patch adjusts time",
          r.status_code == 200 and r.json()["startMs"] == 1500, r.text[:200])
    r = session.post(f"{BASE}/api/media/{media_id}/events",
                     json={"type": "MANUAL_MARKER", "startMs": 7000, "endMs": 8500}, timeout=10)
    ev2 = r.json()
    r = session.post(f"{BASE}/api/events/{ev2['id']}/reject", json={}, timeout=10)
    check("events: reject marks REJECTED",
          r.status_code == 200 and r.json()["status"] == "REJECTED", r.text[:200])
    events = session.get(f"{BASE}/api/media/{media_id}/events", timeout=10).json()
    check("events: rejected hidden from active list",
          all(e["id"] != ev2["id"] for e in events) and len(events) == 1, str(len(events)))

    # ---- 6. project with revisions ----
    r = session.post(f"{BASE}/api/projects", json={"mediaId": media_id, "name": "E2E 工程"},
                     timeout=10)
    check("project: created", r.status_code == 200, r.text[:200])
    project = r.json()
    project_id = project["id"]

    edl = {
        "schemaVersion": 1,
        "sourceMediaId": media_id,
        "segments": [
            {"id": "s1", "sourceInMs": 1000, "sourceOutMs": 3500,
             "caption": "这波配合成功了", "sourceVolume": 1.0},
            {"id": "s2", "sourceInMs": 6000, "sourceOutMs": 8000,
             "caption": "", "sourceVolume": 0.6},
        ],
        "output": {"aspectMode": "SOURCE", "width": 640, "height": 360, "fps": 30},
    }
    save = {"expectedRevision": 0, "name": "E2E 工程", **edl}
    r = session.put(f"{BASE}/api/projects/{project_id}", json=save, timeout=10)
    check("project: save revision 1", r.status_code == 200 and r.json().get("revision") == 1,
          r.text[:200])
    # stale revision -> 409
    r = session.put(f"{BASE}/api/projects/{project_id}", json=save, timeout=10)
    check("project: stale revision 409", r.status_code == 409, f"status={r.status_code}")
    # immutability: revision 1 still returns original EDL after edits
    save2 = {**save, "expectedRevision": 1,
             "segments": [{"id": "s1", "sourceInMs": 0, "sourceOutMs": 9000,
                           "caption": "", "sourceVolume": 1.0}]}
    r = session.put(f"{BASE}/api/projects/{project_id}", json=save2, timeout=10)
    check("project: save revision 2", r.status_code == 200, r.text[:200])
    rev1 = session.get(f"{BASE}/api/projects/{project_id}/revisions/1", timeout=10).json()
    seg1 = rev1["edl"]["segments"]
    check("project: revision 1 immutable",
          seg1[0]["sourceInMs"] == 1000 and len(seg1) == 2, json.dumps(seg1)[:150])

    # ---- 7. render bound to revision 1 ----
    r = session.post(f"{BASE}/api/projects/{project_id}/renders", json={"projectRevision": 1}, timeout=15)
    check("render: submitted", r.status_code == 200, r.text[:200])
    render = r.json()
    render_id = render["id"]
    check("render: bound to revision 1", render.get("projectRevision") == 1,
          str(render.get("projectRevision")))

    def render_done():
        j = session.get(f"{BASE}/api/renders/{render_id}", timeout=10).json()
        return j if j.get("status") in ("SUCCEEDED", "FAILED", "CANCELLED") else None
    render = wait_for(render_done, 300, what="render terminal state")
    check("render: SUCCEEDED", render.get("status") == "SUCCEEDED", json.dumps(render)[:300])

    # ---- 8. download + verify ----
    r = session.get(f"{BASE}/api/renders/{render_id}/download", timeout=60)
    check("download: works for owner", r.status_code == 200 and len(r.content) > 10000,
          f"status={r.status_code} size={len(r.content)}")
    out = os.path.join(os.path.dirname(os.path.abspath(__file__)), f"e2e-{UNIQUE}.mp4")
    with open(out, "wb") as f:
        f.write(r.content)
    probe = json.loads(subprocess.run(
        [FFPROBE, "-v", "quiet", "-print_format", "json", "-show_format", "-show_streams", out],
        capture_output=True, text=True, shell=False).stdout)
    vstream = next((s for s in probe.get("streams", []) if s.get("codec_type") == "video"), {})
    duration = float(probe.get("format", {}).get("duration", 0))
    check("download: playable H.264 output", vstream.get("codec_name") == "h264",
          str(vstream.get("codec_name")))
    check("download: duration ~4.5s (frame-accurate trim)",
          abs(duration - 4.5) <= 0.5, f"duration={duration:.2f}s")
    r = other_session.get(f"{BASE}/api/renders/{render_id}/download", timeout=10)
    check("download: cross-user blocked", r.status_code == 404, f"status={r.status_code}")

    # range download
    r = session.get(f"{BASE}/api/renders/{render_id}/download",
                    headers={"Range": "bytes=0-1023"}, timeout=30)
    check("download: Range works", r.status_code == 206, f"status={r.status_code}")
    os.remove(out)

    # ---- 9. private storage keys never leak ----
    body = json.dumps(session.get(f"{BASE}/api/media/{media_id}", timeout=10).json())
    body += json.dumps(session.get(f"{BASE}/api/renders/{render_id}", timeout=10).json())
    check("security: no physical paths in APIs", "original/" not in body and "data" not in body.lower().replace("database", ""))

    passed = sum(1 for _, ok, _ in CHECKS if ok)
    failed = len(CHECKS) - passed
    print(f"\n==== E2E RESULT: {passed} passed, {failed} failed ====")
    for name, ok, detail in CHECKS:
        if not ok:
            print(f"FAILED: {name} | {detail}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
