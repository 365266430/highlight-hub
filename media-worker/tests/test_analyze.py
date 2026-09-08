"""SYNTHETIC TEST fixtures for the OCR analyzer.

The clip is generated with OpenCV: a white canvas whose score region shows
an incrementing counter every second. This validates the OCR pipeline
mechanics ONLY - sampling, confirmation, deduplication, evidence output.
It says nothing about real-game recognition accuracy (documented limitation).
"""
from __future__ import annotations

import os

import cv2
import numpy as np
import pytest

from app import analyze, config

FIXTURES = os.path.join(os.path.dirname(__file__), "fixtures")


@pytest.fixture(scope="session")
def counter_clip() -> str:
    """8s 640x360 clip; a counter increments every second inside a known ROI."""
    path = os.path.join(FIXTURES, "synthetic-counter-8s.mp4")
    if os.path.exists(path):
        return path
    os.makedirs(FIXTURES, exist_ok=True)
    fps = 20
    writer = cv2.VideoWriter(path, cv2.VideoWriter_fourcc(*"mp4v"), fps, (640, 360))
    assert writer.isOpened()
    value = 10
    for frame_idx in range(fps * 8):
        second = frame_idx // fps
        if second != (frame_idx - 1) // fps:
            value += 1
        img = np.full((360, 640, 3), 255, dtype=np.uint8)
        cv2.putText(img, f"SCORE {value}", (400, 60), cv2.FONT_HERSHEY_SIMPLEX,
                    1.1, (0, 0, 0), 3, cv2.LINE_AA)
        writer.write(img)
    writer.release()
    return path


def test_analyzer_detects_counter_increments(counter_clip, tmp_path):
    cfg = {
        "sampleIntervalMs": 250,
        "multiFrameConfirm": 2,
        "dedupWindowMs": 500,
        "preprocess": {"scale": 2, "grayscale": True},
        "rois": [
            {"id": "score", "x": 0.55, "y": 0.02, "w": 0.44, "h": 0.30,
             "mode": "number", "eventType": "SCORE_CHANGE"},
        ],
    }
    events = []
    analyze.analyze_stream(counter_clip, cfg,
                           lambda pct, phase=None: None,
                           lambda: False,
                           lambda ev, path: events.append((ev, path)),
                           str(tmp_path))
    # the counter goes 11..18 (7 increments) at 1s boundaries
    assert 5 <= len(events) <= 8, f"got {len(events)} events"
    times = [ev["startMs"] for ev, _ in events]
    assert all(b > a for a, b in zip(times, times[1:])), "events must be time-ordered"
    first = events[0][0]
    assert first["type"] == "SCORE_CHANGE"
    assert first["confidence"] > 0.5
    assert os.path.exists(events[0][1]) and os.path.getsize(events[0][1]) > 500
    # attributes capture the raw transition, no actor attribution invented
    assert "to" in first["attributes"] and first["attributes"]["to"].strip() != ""


def test_analyzer_dedupes_repeated_announcements(counter_clip, tmp_path):
    cfg = {
        "sampleIntervalMs": 250,
        "multiFrameConfirm": 2,
        "dedupWindowMs": 10000,  # suppresses nearly everything within 8s
        "preprocess": {"scale": 2, "grayscale": True},
        "rois": [{"id": "score", "x": 0.55, "y": 0.02, "w": 0.44, "h": 0.30,
                  "mode": "number", "eventType": "SCORE_CHANGE"}],
    }
    events = []
    analyze.analyze_stream(counter_clip, cfg,
                           lambda pct, phase=None: None,
                           lambda: False,
                           lambda ev, path: events.append((ev, path)),
                           str(tmp_path))
    assert len(events) <= 2, f"dedup window should suppress repeats, got {len(events)}"


def test_analyzer_requires_rois(counter_clip, tmp_path):
    result = analyze.analyze_stream(counter_clip, {"rois": []},
                                    lambda pct, phase=None: None,
                                    lambda: False,
                                    lambda ev, path: None,
                                    str(tmp_path))
    assert result["ok"] is False
    assert result["errorCode"] == "NO_ROIS_CONFIGURED"


def test_analyzer_respects_cancellation(counter_clip, tmp_path):
    cfg = {"sampleIntervalMs": 250,
           "rois": [{"id": "score", "x": 0.55, "y": 0.02, "w": 0.44, "h": 0.30,
                     "mode": "number"}]}
    result = analyze.analyze_stream(counter_clip, cfg,
                                    lambda pct, phase=None: None,
                                    lambda: True,  # cancel immediately
                                    lambda ev, path: None,
                                    str(tmp_path))
    assert result["ok"] is False and result.get("cancelled") is True


def test_ocr_engine_initializes_and_reads_digits(counter_clip):
    cap = cv2.VideoCapture(counter_clip)
    ok, frame = cap.read()
    cap.release()
    assert ok
    roi = frame[7:108, 352:632]
    texts = analyze.ocr_text(analyze.preprocess(roi, 2, True))
    joined = " ".join(t for t, _ in texts)
    assert any(ch.isdigit() for ch in joined), f"no digits recognized in: {joined}"
