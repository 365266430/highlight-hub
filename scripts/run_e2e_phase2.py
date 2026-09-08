"""Phase-2 E2E: real OCR analysis + candidate rule engine over a SYNTHETIC
counter clip. This validates the auto-detection pipeline mechanics only.
Real-game recognition accuracy is NOT claimed (see docs/adapter-development.md).
"""
from __future__ import annotations

import json
import os
import sys
import time

import requests

sys.path.insert(0, os.path.dirname(__file__))
from run_e2e import CHECKS, BASE, check, csrf, register_login, wait_for  # noqa: E402

UNIQUE = str(int(time.time()))
USER = f"p2_{UNIQUE}"[-20:]
PASSWORD = "password123"
FIXTURE = os.path.join(os.path.dirname(__file__), "..", "media-worker", "tests",
                       "fixtures", "synthetic-counter-8s.mp4")

session = requests.Session()


def main() -> int:
    register_login(session, USER)

    data = open(FIXTURE, "rb").read()
    r = session.post(f"{BASE}/api/uploads",
                     json={"originalFilename": "synthetic-counter-8s.mp4",
                           "declaredSize": len(data)}, timeout=10)
    up = r.json()
    upload_id = up["uploadId"]
    negotiated = up["chunkSize"]
    chunks = [data[i:i + negotiated] for i in range(0, len(data), negotiated)]
    for idx, chunk in enumerate(chunks):
        r = session.put(f"{BASE}/api/uploads/{upload_id}/chunks/{idx}", data=chunk, timeout=60)
        assert r.status_code == 200
    media_id = session.post(f"{BASE}/api/uploads/{upload_id}/complete", json={},
                            timeout=120).json()["mediaId"]

    def ready():
        m = session.get(f"{BASE}/api/media/{media_id}", timeout=10).json()
        return m if m.get("status") == "READY" else None
    wait_for(ready, 120, what="media READY")
    check("p2: media ready for analysis", True)

    # adapters registry: generic OCR must be EXPERIMENTAL, never VERIFIED
    adapters = session.get(f"{BASE}/api/adapters", timeout=10).json()
    generic = next((a for a in adapters if a.get("gameKey") == "generic-ocr"), None)
    check("p2: generic adapter registered", generic is not None, json.dumps(adapters)[:200])
    if generic:
        check("p2: generic adapter NOT marked verified (no real-game validation)",
              generic.get("verified") is False, json.dumps(generic))
    versions = session.get(f"{BASE}/api/adapters/{generic['id']}/versions", timeout=10).json()
    check("p2: adapter version EXPERIMENTAL",
          versions and versions[0].get("status") == "EXPERIMENTAL", json.dumps(versions)[:200])
    adapter_version_id = versions[0]["id"]

    # ---- auto analysis with user-calibrated ROI (relative coords) ----
    r = session.post(f"{BASE}/api/media/{media_id}/analyses", json={
        "adapterVersionId": adapter_version_id,
        "params": {
            "sampleIntervalMs": 250,
            "multiFrameConfirm": 2,
            "dedupWindowMs": 500,
            "preprocess": {"scale": 2, "grayscale": True},
            "rois": [{"id": "score", "x": 0.55, "y": 0.02, "w": 0.44, "h": 0.30,
                      "mode": "number", "eventType": "SCORE_CHANGE"}],
        },
    }, timeout=15)
    check("p2: analysis queued", r.status_code == 200, r.text[:200])
    run_id = r.json()["id"]

    def analysis_done():
        a = session.get(f"{BASE}/api/analyses/{run_id}", timeout=10).json()
        return a if a.get("status") in ("SUCCEEDED", "FAILED", "CANCELLED") else None
    analysis = wait_for(analysis_done, 300, what="analysis terminal state")
    check("p2: analysis SUCCEEDED (real OCR on synthetic clip)",
          analysis.get("status") == "SUCCEEDED", json.dumps(analysis)[:300])

    events = session.get(f"{BASE}/api/analyses/{run_id}/events", timeout=10).json()
    check("p2: OCR produced SCORE_CHANGE events", 4 <= len(events) <= 8,
          f"count={len(events)}")
    if events:
        ev = events[0]
        check("p2: events are AUTO + evidence-backed",
              ev.get("source") == "AUTO" and ev.get("attributes", {}).get("to", "").strip() != "",
              json.dumps(ev)[:250])
        check("p2: confidence is recognition info, not attribution",
              ev.get("confidence") is None or 0 < ev["confidence"] <= 1.0)

    # ---- candidate rule engine ----
    r = session.post(f"{BASE}/api/analyses/{run_id}/highlight-runs", json={
        "eventType": "SCORE_CHANGE", "windowMs": 6000, "minimumCount": 3,
        "paddingBeforeMs": 1500, "paddingAfterMs": 1000,
        "mergeGapMs": 1000, "maxSegmentDurationMs": 30000,
    }, timeout=15)
    check("p2: highlight run created", r.status_code == 200, r.text[:250])
    highlight_run_id = r.json()["id"]
    candidates = session.get(f"{BASE}/api/highlight-runs/{highlight_run_id}/candidates",
                             timeout=10).json()
    check("p2: candidates generated with evidence reason",
          len(candidates) >= 1 and "SCORE_CHANGE" in candidates[0]["reasonText"],
          json.dumps(candidates)[:250])
    bounds_ok = all(c["startMs"] >= 0 and c["endMs"] > c["startMs"] and c["endMs"] <= 8000
                    for c in candidates)
    check("p2: candidate bounds within video duration", bounds_ok,
          json.dumps(candidates)[:150])

    if candidates:
        cid = candidates[0]["id"]
        r = session.patch(f"{BASE}/api/highlight-candidates/{cid}",
                          json={"status": "ACCEPTED"}, timeout=10)
        check("p2: candidate accepted", r.status_code == 200 and r.json()["status"] == "ACCEPTED",
              r.text[:150])

    passed = sum(1 for _, ok, _ in CHECKS if ok)
    failed = len(CHECKS) - passed
    print(f"\n==== PHASE-2 E2E RESULT: {passed} passed, {failed} failed ====")
    for name, ok, detail in CHECKS:
        if not ok:
            print(f"FAILED: {name} | {detail}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
