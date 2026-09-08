"""SYNTHETIC-clip verification of CROP mode and source-frame masking."""
from __future__ import annotations

import os
import shutil
import tempfile

import cv2

from app import ffm, config


def test_crop_mode_fills_output_box(synthetic_clip, tmp_path):
    """16:9 source cropped into a 1:1 output: CROP must fill, not letterbox."""
    out = str(tmp_path / "crop.mp4")
    edl = {
        "schemaVersion": 1, "sourceMediaId": "m", "hasAudio": False,
        "segments": [{"id": "s1", "sourceInMs": 0, "sourceOutMs": 2000,
                      "caption": "", "sourceVolume": 1.0}],
        "output": {"aspectMode": "CROP", "width": 400, "height": 400, "fps": 30},
        "masks": [],
    }
    proc = ffm.run(ffm.render_args(synthetic_clip, out, edl, [], None), timeout=120)
    assert proc.returncode == 0, proc.stderr[-400:]
    info = ffm.probe_media(out)
    assert (info["width"], info["height"]) == (400, 400)
    # decode a frame: every row must contain non-black content (filled, no bars)
    cap = cv2.VideoCapture(out)
    ok, frame = cap.read()
    cap.release()
    assert ok
    gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
    assert gray[0:10, :].mean() > 10 and gray[-10:, :].mean() > 10, "top/bottom must not be black bars"


def test_source_mask_turns_region_black(synthetic_clip, tmp_path):
    """A 0.0-0.3 / 0.0-0.3 source mask must be black after render (mask is in
    source coordinates and survives the CROP transform)."""
    out = str(tmp_path / "masked.mp4")
    edl = {
        "schemaVersion": 1, "sourceMediaId": "m", "hasAudio": False,
        "segments": [{"id": "s1", "sourceInMs": 0, "sourceOutMs": 2000,
                      "caption": "", "sourceVolume": 1.0}],
        "output": {"aspectMode": "SOURCE", "width": 640, "height": 360, "fps": 30},
        "masks": [{"x": 0.0, "y": 0.0, "w": 0.3, "h": 0.3}],
    }
    proc = ffm.run(ffm.render_args(synthetic_clip, out, edl, [], None), timeout=120)
    assert proc.returncode == 0, proc.stderr[-400:]
    cap = cv2.VideoCapture(out)
    ok, frame = cap.read()
    cap.release()
    assert ok
    h, w = frame.shape[:2]
    region = cv2.cvtColor(frame[0:int(h * 0.3), 0:int(w * 0.3)], cv2.COLOR_BGR2GRAY)
    assert region.mean() < 3, f"masked region must be black, mean={region.mean():.2f}"
    outside = cv2.cvtColor(frame[int(h * 0.4):, int(w * 0.4):], cv2.COLOR_BGR2GRAY)
    assert outside.mean() > 10, "area outside the mask must retain picture"


def test_two_masks_and_crop_combined(synthetic_clip, tmp_path):
    out = str(tmp_path / "combined.mp4")
    edl = {
        "schemaVersion": 1, "sourceMediaId": "m", "hasAudio": False,
        "segments": [{"id": "s1", "sourceInMs": 0, "sourceOutMs": 1500,
                      "caption": "", "sourceVolume": 1.0}],
        "output": {"aspectMode": "CROP", "width": 480, "height": 480, "fps": 30},
        "masks": [
            {"x": 0.0, "y": 0.0, "w": 0.2, "h": 0.2},
            {"x": 0.8, "y": 0.8, "w": 0.2, "h": 0.2},
        ],
    }
    proc = ffm.run(ffm.render_args(synthetic_clip, out, edl, [], None), timeout=120)
    assert proc.returncode == 0, proc.stderr[-400:]
    info = ffm.probe_media(out)
    assert (info["width"], info["height"]) == (480, 480)
