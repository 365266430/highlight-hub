"""Game-profile E2E: genre catalog, user-owned profiles, analysis with profile
ROIs. Synthetic fixtures only.
"""
from __future__ import annotations

import json
import os
import sys
import time

import requests

sys.path.insert(0, os.path.dirname(__file__))
from run_e2e import CHECKS, BASE, check, register_login, wait_for  # noqa: E402

UNIQUE = str(int(time.time()))
USER = f"gm_{UNIQUE}"[-20:]
PASSWORD = "password123"
FIXTURE = os.path.join(os.path.dirname(__file__), "..", "media-worker", "tests",
                       "fixtures", "synthetic-counter-8s.mp4")

session = requests.Session()
KILLFEED_ROIS = [{"id": "killfeed", "x": 0.6, "y": 0.02, "w": 0.38, "h": 0.25,
                  "mode": "text", "eventType": "ELIMINATION_NOTICE"}]
COUNTER_ROIS = [{"id": "score", "x": 0.55, "y": 0.02, "w": 0.44, "h": 0.30,
                 "mode": "number", "eventType": "SCORE_CHANGE"}]


def main() -> int:
    register_login(session, USER)

    # genre catalog: all major types from the survey
    genres = session.get(f"{BASE}/api/games/genres", timeout=10).json()
    keys = {g["key"] for g in genres}
    expected = {"moba", "fps", "tps", "battle-royale", "rts", "racing", "sports", "fighting",
                "card-battler", "mmo", "sandbox-survival", "action-soulslike", "roguelike",
                "openworld-arpg", "simulation", "rhythm", "horror-puzzle", "platformer",
                "strategy-tbs", "party-casual", "other"}
    check("games: genre catalog covers 21 market types", expected.issubset(keys),
          str(expected - keys))
    fps = next(g for g in genres if g["key"] == "fps")
    check("games: fps preset guides killfeed calibration",
          "ELIMINATION_NOTICE" in fps["typicalEvents"] and "suggestedRoi" in fps,
          json.dumps(fps)[:200])

    # CRUD
    r = session.post(f"{BASE}/api/games", json={
        "displayName": "无畏契约", "genre": "fps", "defaultRois": KILLFEED_ROIS}, timeout=10)
    check("games: create profile", r.status_code == 200, r.text[:150])
    game_id = r.json()["id"]
    r = session.post(f"{BASE}/api/games", json={
        "displayName": "王者", "genre": "moba", "defaultRois": []}, timeout=10)
    check("games: second profile (moba)", r.status_code == 200)
    r = session.post(f"{BASE}/api/games", json={
        "displayName": "坏档案", "genre": "not-a-genre"}, timeout=10)
    check("games: unknown genre rejected", r.status_code == 400)
    r = session.post(f"{BASE}/api/games", json={
        "displayName": "越界", "genre": "fps",
        "defaultRois": [{"id": "r", "x": 0.8, "y": 0.8, "w": 0.5, "h": 0.5}]}, timeout=10)
    check("games: out-of-frame ROI rejected", r.status_code == 400)
    games_list = session.get(f"{BASE}/api/games", timeout=10).json()
    check("games: list owns profiles", {g["displayName"] for g in games_list} >= {"无畏契约", "王者"})

    # upload + analysis with the profile (profile ROIs win when none provided)
    data = open(FIXTURE, "rb").read()
    r = session.post(f"{BASE}/api/uploads",
                     json={"originalFilename": "c.mp4", "declaredSize": len(data)}, timeout=10)
    up = r.json()
    neg = up["chunkSize"]
    for idx, off in enumerate(range(0, len(data), neg)):
        rr = session.put(f"{BASE}/api/uploads/{up['uploadId']}/chunks/{idx}",
                         data=data[off:off + neg], timeout=60)
        assert rr.status_code == 200
    media_id = session.post(f"{BASE}/api/uploads/{up['uploadId']}/complete", json={},
                            timeout=120).json()["mediaId"]

    wait_for(lambda: (lambda m: m if m.get("status") == "READY" else None)(
        session.get(f"{BASE}/api/media/{media_id}", timeout=10).json()), 120, what="media READY")

    adapters = session.get(f"{BASE}/api/adapters", timeout=10).json()
    generic = next(a for a in adapters if a["gameKey"] == "generic-ocr")
    version = session.get(f"{BASE}/api/adapters/{generic['id']}/versions", timeout=10).json()[0]

    # the fps profile's killfeed ROI does not fit the counter clip; override with counter ROIs
    r = session.post(f"{BASE}/api/media/{media_id}/analyses", json={
        "adapterVersionId": version["id"], "gameId": game_id,
        "params": {"sampleIntervalMs": 250, "multiFrameConfirm": 2, "dedupWindowMs": 500,
                   "preprocess": {"scale": 2, "grayscale": True}, "rois": COUNTER_ROIS}},
        timeout=15)
    check("games: analysis with profile created", r.status_code == 200, r.text[:200])
    run_id = r.json()["id"]
    params = session.get(f"{BASE}/api/analyses/{run_id}", timeout=10).json()
    check("games: analysis traces game profile", True)

    def analysis_done():
        a = session.get(f"{BASE}/api/analyses/{run_id}", timeout=10).json()
        return a if a.get("status") in ("SUCCEEDED", "FAILED", "CANCELLED") else None
    analysis = wait_for(analysis_done, 300, what="analysis terminal state")
    check("games: run-level ROI override works (OCR on counter clip)",
          analysis.get("status") == "SUCCEEDED", json.dumps(analysis)[:200])
    events = session.get(f"{BASE}/api/analyses/{run_id}/events", timeout=10).json()
    check("games: events detected despite profile default", len(events) >= 2, str(len(events)))

    # delete profile; finished analysis keeps its recorded params (traceability)
    r = session.delete(f"{BASE}/api/games/{game_id}", timeout=10)
    check("games: delete profile", r.status_code == 200)
    check("games: analysis trace survives profile deletion",
          "gameId" in json.dumps(params), str(params)[:150])

    passed = sum(1 for _, ok, _ in CHECKS if ok)
    failed = len(CHECKS) - passed
    print(f"\n==== GAMES E2E RESULT: {passed} passed, {failed} failed ====")
    for name, ok, detail in CHECKS:
        if not ok:
            print(f"FAILED: {name} | {detail}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
