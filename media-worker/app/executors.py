"""Task executors: PROBE / PREVIEW / THUMBNAIL / RENDER / CLEANUP / ANALYZE."""
from __future__ import annotations

import hashlib
import os
import shutil

from . import config, ffm
from .client import JavaClient


class Abort(RuntimeError):
    """Raised when the attempt must stop (cancel or lost lease)."""


def _heartbeat_loop(client: JavaClient, task_id: str, token: str, stop):
    import time
    while not stop.wait(timeout=config.HEARTBEAT_INTERVAL):
        if not client.heartbeat(task_id, token):
            # lost the lease: the task was reclaimed elsewhere; stop wasting work
            raise Abort(f"heartbeat rejected for task {task_id}")


def _tmp_dir(task_id: str) -> str:
    d = os.path.join(config.STORAGE_ROOT, "tmp", "worker", task_id)
    os.makedirs(d, exist_ok=True)
    return d


def _cleanup_tmp(task_id: str):
    shutil.rmtree(os.path.join(config.STORAGE_ROOT, "tmp", "worker", task_id),
                  ignore_errors=True)


def _sha256(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def run_task(task: dict, client: JavaClient, progress, cancel_requested) -> dict:
    """Execute one claimed task and return the completion result payload.

    progress(percent, phase) reports progress; cancel_requested() is polled by
    the executors between steps (and inside ffmpeg progress loops).
    """
    task_id = task["taskId"]
    attempt_token = task["attemptToken"]
    task_type = task["type"]
    payload = task.get("payload") or {}

    import threading
    stop = threading.Event()
    hb = threading.Thread(target=_heartbeat_loop, args=(client, task_id, attempt_token, stop),
                          daemon=True)
    hb.start()
    try:
        try:
            if task_type == "PROBE":
                return _probe(payload, progress)
            if task_type == "PREVIEW":
                return _preview(task_id, payload, progress, cancel_requested)
            if task_type == "THUMBNAIL":
                return _thumbnail(task_id, payload, progress, cancel_requested)
            if task_type == "RENDER":
                return _render(task_id, payload, progress, cancel_requested)
            if task_type == "CLEANUP":
                return _cleanup(payload)
            if task_type == "ANALYZE":
                return _analyze(task_id, payload, progress, cancel_requested)
            return {"ok": False, "error": f"unsupported task type {task_type}"}
        except Abort:
            raise
        except ffm.CancellationRequested:
            raise
        except ffm.FFmpegError as e:
            code = "INVALID_MEDIA" if "not a usable video" in str(e) or "no video stream" in str(e) else "FFMPEG_ERROR"
            return {"ok": False, "errorCode": code, "errorMessage": str(e),
                    "retryable": code == "FFMPEG_ERROR"}
    finally:
        stop.set()


def _source_path(payload: dict) -> str:
    media_key = payload.get("mediaStorageKey") or f"original/{payload['mediaId']}/source"
    return config.storage_path(media_key)


def _probe(payload: dict, progress) -> dict:
    progress(5, "probing")
    src = _source_path(payload)
    if not os.path.exists(src):
        return {"ok": False, "errorCode": "INVALID_INPUT", "errorMessage": "source file missing",
                "retryable": False}
    info = ffm.probe_media(src)
    progress(100, "probed")
    return info


def _preview(task_id: str, payload: dict, progress, cancel_requested) -> dict:
    progress(5, "transcoding preview")
    src = _source_path(payload)
    if not os.path.exists(src):
        return {"ok": False, "errorCode": "INVALID_INPUT", "errorMessage": "source file missing",
                "retryable": False}
    media_id = payload["mediaId"]
    params = payload.get("preview") or {}
    out_key = f"preview/{media_id}/preview.mp4"
    dst = config.storage_path(out_key)
    tmp = os.path.join(_tmp_dir(task_id), "preview.mp4")
    args = ffm.preview_args(src, tmp, int(params.get("maxHeight", 720)),
                            int(params.get("crf", 28)))
    info = ffm.probe_media(src)
    ffm.run_with_progress(args, info["durationMs"],
                          lambda pct: progress(pct, "transcoding preview"),
                          cancel_requested, config.PROGRESS_POLL_SECONDS)
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    shutil.move(tmp, dst)
    progress(100, "preview ready")
    return {"ok": True, "storageKey": out_key, "size": os.path.getsize(dst)}


def _thumbnail(task_id: str, payload: dict, progress, cancel_requested) -> dict:
    progress(5, "building contact sheet")
    src = _source_path(payload)
    if not os.path.exists(src):
        return {"ok": False, "errorCode": "INVALID_INPUT", "errorMessage": "source file missing",
                "retryable": False}
    media_id = payload["mediaId"]
    params = payload.get("thumbnail") or {}
    interval = int(params.get("intervalSeconds", 5))
    columns = int(params.get("columns", 10))
    rows = int(params.get("rows", 10))
    frame_width = int(params.get("frameWidth", 320))
    info = ffm.probe_media(src)
    duration_s = max(1, info["durationMs"] // 1000)
    count = min(columns * rows, max(1, duration_s // interval + 1))
    out_key = f"thumbnail/{media_id}/sprite.jpg"
    dst = config.storage_path(out_key)
    tmp = os.path.join(_tmp_dir(task_id), "sprite.jpg")
    args = ffm.thumbnail_args(src, tmp, interval, columns, rows, frame_width)
    ffm.run(args, timeout=600)
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    shutil.move(tmp, dst)
    frame_height = int(info["height"] and frame_width * info["height"] / info["width"]) or 180
    progress(100, "thumbnails ready")
    return {
        "ok": True,
        "storageKey": out_key,
        "size": os.path.getsize(dst),
        "intervalSeconds": interval,
        "columns": columns,
        "rows": rows,
        "frameWidth": frame_width,
        "frameHeight": frame_height,
        "count": count,
        "spriteVersion": f"v1-{interval}s-{frame_width}w",
    }


def _render(task_id: str, payload: dict, progress, cancel_requested) -> dict:
    progress(2, "preparing render")
    src = _source_path(payload)
    if not os.path.exists(src):
        return {"ok": False, "errorCode": "INVALID_INPUT", "errorMessage": "source file missing",
                "retryable": False}
    edl = payload["edl"]
    output_key = payload["outputKey"]
    tmp_dir = _tmp_dir(task_id)

    info = ffm.probe_media(src)
    edl["hasAudio"] = info["audioStreamCount"] > 0

    # captions go into textfiles inside the task temp dir; ffmpeg runs with
    # cwd=tempdir so the filtergraph only ever sees relative file names
    # (keeps Windows drive letters out of the filtergraph entirely)
    caption_files: list[tuple[str, float, float]] = []
    for i, seg in enumerate(edl.get("segments", [])):
        caption = (seg.get("caption") or "").strip()
        if caption:
            path = os.path.join(tmp_dir, f"caption-{i}.txt")
            with open(path, "w", encoding="utf-8") as f:
                f.write(caption.replace("\r\n", "\n"))
            caption_files.append((path, seg["sourceInMs"], seg["sourceOutMs"]))
    if caption_files and config.FONT_FILE:
        shutil.copyfile(config.FONT_FILE, os.path.join(tmp_dir, "font.ttf"))

    total_ms = sum(seg["sourceOutMs"] - seg["sourceInMs"] for seg in edl["segments"])
    tmp_out = os.path.join(tmp_dir, "output.mp4")
    args = ffm.render_args(src, tmp_out, edl, caption_files, config.FONT_FILE,
                           source_width=info["width"], source_height=info["height"])
    progress(5, "rendering")
    ffm.run_with_progress(
        args, total_ms,
        lambda pct: progress(pct, "rendering"),
        cancel_requested, config.PROGRESS_POLL_SECONDS,
        timeout=config.RENDER_TIMEOUT_SECONDS,
        cwd=tmp_dir,
    )

    # output validation before the result can be trusted
    if not os.path.exists(tmp_out) or os.path.getsize(tmp_out) == 0:
        return {"ok": False, "errorCode": "RENDER_INVALID_OUTPUT",
                "errorMessage": "output missing or empty", "retryable": True}
    if os.path.getsize(tmp_out) > config.RENDER_MAX_OUTPUT_BYTES:
        return {"ok": False, "errorCode": "RENDER_OUTPUT_TOO_LARGE",
                "errorMessage": "output exceeds configured limit", "retryable": False}
    out_info = ffm.probe_media(tmp_out)
    expected_s = total_ms / 1000.0
    actual_s = out_info["durationMs"] / 1000.0
    if abs(actual_s - expected_s) > max(2.0, expected_s * 0.05):
        return {"ok": False, "errorCode": "RENDER_DURATION_MISMATCH",
                "errorMessage": f"expected ~{expected_s:.2f}s got {actual_s:.2f}s",
                "retryable": True}
    if not out_info["videoCodec"]:
        return {"ok": False, "errorCode": "RENDER_INVALID_OUTPUT",
                "errorMessage": "output has no video stream", "retryable": True}

    dst = config.storage_path(output_key)
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    shutil.move(tmp_out, dst)
    checksum = _sha256(dst)
    _cleanup_tmp(task_id)
    progress(100, "render complete")
    return {
        "ok": True,
        "storageKey": output_key,
        "size": os.path.getsize(dst),
        "checksum": checksum,
        "durationMs": out_info["durationMs"],
        "width": out_info["width"],
        "height": out_info["height"],
    }


def _analyze(task_id: str, payload: dict, progress, cancel_requested) -> dict:
    from . import analyze
    progress(2, "analyzing")
    src = _source_path(payload)
    if not os.path.exists(src):
        return {"ok": False, "errorCode": "INVALID_INPUT", "errorMessage": "source file missing",
                "retryable": False}
    cfg = payload.get("config") or {}
    run_id = payload["analysisRunId"]
    evidence_rel = f"evidence/{run_id}"
    evidence_abs = config.storage_path(evidence_rel)
    os.makedirs(evidence_abs, exist_ok=True)
    events: list[dict] = []

    def on_event(ev, evidence_path):
        ev = dict(ev)
        rel_key = f"{evidence_rel}/{os.path.basename(evidence_path)}"
        ev["evidenceStorageKey"] = rel_key
        try:
            ev["evidenceSize"] = os.path.getsize(evidence_path)
        except OSError:
            ev["evidenceSize"] = 0
        events.append(ev)

    summary = analyze.analyze_stream(src, cfg, progress, cancel_requested, on_event, evidence_abs)
    if summary.get("cancelled"):
        return {"ok": False, "cancelled": True}
    if not summary.get("ok"):
        return summary
    _cleanup_tmp(task_id)
    progress(100, "analysis complete")
    return {"ok": True, "events": events,
            "samplesProcessed": summary.get("samplesProcessed", 0),
            "eventsEmitted": summary.get("eventsEmitted", 0)}


def _cleanup(payload: dict) -> dict:
    media_id = payload.get("mediaId")
    removed = []
    if media_id:
        for prefix in (f"original/{media_id}", f"preview/{media_id}",
                       f"thumbnail/{media_id}", f"tmp/upload", "tmp/worker"):
            path = config.storage_path(prefix) if "/" in prefix else os.path.join(config.STORAGE_ROOT, prefix)
            if prefix in (f"tmp/upload", "tmp/worker"):
                continue  # these are swept wholesale below
            if os.path.isdir(path):
                shutil.rmtree(path, ignore_errors=True)
                removed.append(prefix)
            elif os.path.exists(path):
                try:
                    os.remove(path)
                    removed.append(prefix)
                except OSError:
                    pass
    # expire stale worker temp dirs (older than 1 day)
    tmp_worker = os.path.join(config.STORAGE_ROOT, "tmp", "worker")
    if os.path.isdir(tmp_worker):
        import time
        cutoff = time.time() - 86400
        for name in os.listdir(tmp_worker):
            p = os.path.join(tmp_worker, name)
            try:
                if os.path.getmtime(p) < cutoff:
                    shutil.rmtree(p, ignore_errors=True)
            except OSError:
                pass
    return {"ok": True, "removed": removed}


# font for burned-in captions; must be configurable and license-clear
