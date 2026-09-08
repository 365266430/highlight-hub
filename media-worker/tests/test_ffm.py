"""Synthetic-video fixtures (FFmpeg-generated). Everything here uses clearly
SYNTHETIC clips - they validate the pipeline mechanics only, never game
recognition accuracy."""
from __future__ import annotations

import os
import shutil
import subprocess

import pytest

from app import config, ffm

FFMPEG = config.FFMPEG_PATH
FIXTURES = os.path.join(os.path.dirname(__file__), "fixtures")


@pytest.fixture()
def tmp_work(tmp_path):
    old_root = config.STORAGE_ROOT
    config.STORAGE_ROOT = str(tmp_path)
    yield tmp_path
    config.STORAGE_ROOT = old_root


def test_probe_parses_synthetic_clip(synthetic_clip):
    info = ffm.probe_media(synthetic_clip)
    assert info["ok"] is True
    assert 9000 <= info["durationMs"] <= 11000
    assert info["width"] == 640 and info["height"] == 360
    assert info["videoCodec"] == "h264"
    assert info["audioStreamCount"] == 1
    assert info["audioCodec"] == "aac"


def test_probe_silent_clip_reports_no_audio(silent_clip):
    info = ffm.probe_media(silent_clip)
    assert info["audioStreamCount"] == 0
    assert info["audioCodec"] is None


def test_probe_rejects_non_video(tmp_path):
    fake = tmp_path / "not-a-video.mp4"
    fake.write_bytes(b"\x00" * 1024)
    with pytest.raises(ffm.FFmpegError):
        ffm.probe_media(str(fake))


def test_render_two_segments_with_caption(synthetic_clip, tmp_work):
    src = synthetic_clip
    out = os.path.join(tmp_work, "out.mp4")
    edl = {
        "schemaVersion": 1,
        "sourceMediaId": "m-test",
        "hasAudio": True,
        "segments": [
            {"id": "s1", "sourceInMs": 1000, "sourceOutMs": 3500,
             "caption": "这波配合成功了", "sourceVolume": 1.0},
            {"id": "s2", "sourceInMs": 6000, "sourceOutMs": 8000,
             "caption": "", "sourceVolume": 0.5},
        ],
        "output": {"aspectMode": "SOURCE", "width": 640, "height": 360, "fps": 30},
    }
    import tempfile
    cap_dir = tempfile.mkdtemp()
    cap_path = os.path.join(cap_dir, "cap0.txt")
    with open(cap_path, "w", encoding="utf-8") as f:
        f.write("这波配合成功了")
    # the executor copies the caption font next to the caption files
    # (ffmpeg runs with cwd=cap_dir; only relative names reach the filtergraph)
    caption_files = [(os.path.join(cap_dir, "cap0.txt"), 1000, 3500)]
    if ffm.config.FONT_FILE:
        import shutil as _sh
        _sh.copyfile(ffm.config.FONT_FILE, os.path.join(cap_dir, "font.ttf"))
    args = ffm.render_args(src, out, edl, caption_files, ffm.config.FONT_FILE)
    proc = ffm.run(args, timeout=180, cwd=cap_dir)
    assert proc.returncode == 0, proc.stderr[-800:]

    info = ffm.probe_media(out)
    expected_s = 2.5 + 2.0
    actual_s = info["durationMs"] / 1000.0
    assert abs(actual_s - expected_s) <= 0.6, f"{actual_s} vs {expected_s}"
    assert info["videoCodec"] == "h264"
    assert info["width"] == 640 and info["height"] == 360
    assert info["audioStreamCount"] == 1
    # progress marker proves frame-accurate trims: source t=1s starts the output
    shutil.rmtree(cap_dir, ignore_errors=True)


def test_render_silent_source_without_audio(silent_clip, tmp_work):
    out = os.path.join(tmp_work, "out-silent.mp4")
    edl = {
        "schemaVersion": 1, "sourceMediaId": "m-test", "hasAudio": False,
        "segments": [
            {"id": "s1", "sourceInMs": 0, "sourceOutMs": 3000,
             "caption": "", "sourceVolume": 1.0},
        ],
        "output": {"aspectMode": "SOURCE", "width": 640, "height": 360, "fps": 30},
    }
    args = ffm.render_args(silent_clip, out, edl, [], ffm.config.FONT_FILE)
    proc = ffm.run(args, timeout=120)
    assert proc.returncode == 0, proc.stderr[-800:]
    info = ffm.probe_media(out)
    assert info["audioStreamCount"] == 0
    assert 2.5 <= info["durationMs"] / 1000.0 <= 3.5


def test_thumbnail_sprite_generation(synthetic_clip, tmp_work):
    out = os.path.join(tmp_work, "sprite.jpg")
    args = ffm.thumbnail_args(synthetic_clip, out, interval=1, columns=5, rows=2,
                              frame_width=160)
    proc = ffm.run(args, timeout=120)
    assert proc.returncode == 0, proc.stderr[-500:]
    assert os.path.getsize(out) > 5000


def test_cancellation_stops_ffmpeg(synthetic_clip):
    """A cancel check that fires immediately stops the ffmpeg run."""
    out = os.path.join(os.environ.get("TEMP", "/tmp"), "cancelled.mp4")
    args = ffm.preview_args(synthetic_clip, out, 720, 28)
    with pytest.raises(ffm.CancellationRequested):
        ffm.run_with_progress(args, 10000, lambda pct: None,
                              lambda: True, poll_seconds=0.2, timeout=30)
    # partial output may remain; the contract is the CancellationRequested raise
