"""ROI-based OCR event extraction.

Sampling uses container timestamps (CAP_PROP_POS_MSEC) while reading frames
sequentially - never frameIndex/guessedFps, which breaks on variable frame
rate sources. Multi-frame confirmation filters OCR jitter; a deduplication
window merges repeated announcements. Events are evidence-backed: each gets a
cropped frame screenshot stored for the user to inspect.
"""
from __future__ import annotations

import os
import re
import shutil
import threading

import cv2
import numpy as np

from . import config

_ocr_lock = threading.Lock()
_ocr = None


def get_ocr():
    global _ocr
    if _ocr is None:
        from rapidocr_onnxruntime import RapidOCR
        _ocr = RapidOCR()
    return _ocr


def ocr_text(image: np.ndarray) -> list[tuple[str, float]]:
    """Return [(text, confidence)] for an image; empty list on nothing found."""
    engine = get_ocr()
    with _ocr_lock:  # the onnxruntime session is not thread-safe
        result, _ = engine(image)
    if not result:
        return []
    out = []
    for box, text, conf in result:
        text = (text or "").strip()
        if text:
            out.append((text, float(conf)))
    return out


def preprocess(roi_img: np.ndarray, scale: int, grayscale: bool) -> np.ndarray:
    img = roi_img
    if grayscale:
        img = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
        img = cv2.cvtColor(img, cv2.COLOR_GRAY2BGR)
    if scale and scale > 1:
        img = cv2.resize(img, None, fx=scale, fy=scale, interpolation=cv2.INTER_CUBIC)
    return img


class RoiState:
    """Tracks per-ROI confirmation state for change detection."""

    def __init__(self, roi: dict):
        self.roi = roi
        self.mode = roi.get("mode", "text")
        self.current_text = None
        self.pending_text = None
        self.pending_since_ms = None
        self.pending_count = 0
        self.last_event_ms = -10**12

    def observe(self, text: str, t_ms: int, cfg: dict) -> dict | None:
        """Feed one OCR observation; returns an event dict when confirmed."""
        confirm = int(self.roi.get("multiFrameConfirm", cfg.get("multiFrameConfirm", 2)))
        dedup_ms = int(self.roi.get("dedupWindowMs", cfg.get("dedupWindowMs", 5000)))
        event = None
        if text == self.current_text:
            self.pending_text = None
            self.pending_count = 0
            return None
        if text == self.pending_text:
            self.pending_count += 1
        else:
            self.pending_text = text
            self.pending_since_ms = t_ms
            self.pending_count = 1
        if self.pending_count >= confirm and self.pending_text:
            new_text = self.pending_text
            old_text = self.current_text
            self.current_text = new_text
            self.pending_text = None
            self.pending_count = 0
            first_seen = self.pending_since_ms if self.pending_since_ms is not None else t_ms
            self.pending_since_ms = None
            if self._significant(old_text, new_text) and t_ms - self.last_event_ms > dedup_ms:
                self.last_event_ms = t_ms
                event = {
                    "type": self.roi.get("eventType", "SCORE_CHANGE"),
                    "startMs": int(first_seen),
                    "endMs": int(t_ms),
                    "confidence": None,
                    "attributes": {"from": old_text, "to": new_text, "roiId": self.roi.get("id")},
                }
        return event

    def _significant(self, old: str | None, new: str) -> bool:
        if self.mode == "number":
            digits_old = re.sub(r"\D", "", old or "")
            digits_new = re.sub(r"\D", "", new or "")
            if not digits_new:
                return False
            return digits_old != digits_new
        pattern = self.roi.get("pattern")
        if pattern:
            try:
                return re.search(pattern, new) is not None and (old is None or old != new)
            except re.error:
                return False
        return old is None or old != new


def analyze_stream(src: str, cfg: dict, progress, cancel_requested,
                   on_event, evidence_dir: str) -> dict:
    """Stream through the video, OCR sampled frames, emit confirmed events.

    on_event(event, evidence_path) receives each confirmed event. Returns a
    summary with sample count.
    """
    rois = cfg.get("rois") or []
    if not rois:
        return {"ok": False, "errorCode": "NO_ROIS_CONFIGURED",
                "errorMessage": "adapter has no ROI configured; calibrate first",
                "retryable": False}
    interval_ms = int(cfg.get("sampleIntervalMs", 500))
    pp = cfg.get("preprocess") or {}
    scale = int(pp.get("scale", 2))
    grayscale = bool(pp.get("grayscale", True))

    states = [RoiState(r) for r in rois]
    cap = cv2.VideoCapture(src)
    if not cap.isOpened():
        return {"ok": False, "errorCode": "INVALID_MEDIA",
                "errorMessage": "cannot open video stream", "retryable": False}

    samples = 0
    emitted = 0
    next_sample_ms = -1
    cancelled = False
    try:
        while True:
            if cancel_requested():
                cancelled = True
                break
            ok, frame = cap.read()
            if not ok:
                break
            t_ms = cap.get(cv2.CAP_PROP_POS_MSEC)
            if t_ms < next_sample_ms:
                continue
            next_sample_ms = (int(t_ms) // interval_ms) * interval_ms + interval_ms
            samples += 1
            h, w = frame.shape[:2]
            for idx, state in enumerate(states):
                r = state.roi
                x0 = max(0, min(int(r.get("x", 0) * w), w - 1))
                y0 = max(0, min(int(r.get("y", 0) * h), h - 1))
                x1 = max(x0 + 1, min(int((r.get("x", 0) + r.get("w", 0)) * w), w))
                y1 = max(y0 + 1, min(int((r.get("y", 0) + r.get("h", 0)) * h), h))
                crop = frame[y0:y1, x0:x1]
                if crop.size == 0:
                    continue
                img = preprocess(crop, scale, grayscale)
                texts = ocr_text(img)
                joined = " ".join(t for t, _ in texts)
                conf = min((c for _, c in texts), default=0.0)
                ev = state.observe(joined, int(t_ms), cfg)
                if ev is not None:
                    ev["confidence"] = round(conf, 4)
                    evidence_path = os.path.join(evidence_dir, f"evidence-{emitted}.jpg")
                    cv2.imwrite(evidence_path, crop)
                    on_event(ev, evidence_path)
                    emitted += 1
            if samples % 20 == 0:
                progress(min(99, samples), "analyzing")
    finally:
        cap.release()

    if cancelled:
        return {"ok": False, "cancelled": True}
    return {"ok": True, "samplesProcessed": samples, "eventsEmitted": emitted}
