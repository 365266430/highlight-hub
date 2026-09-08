"""Shared synthetic fixtures (clearly marked SYNTHETIC - pipeline tests only)."""
from __future__ import annotations

import os
import subprocess

import pytest

from app import config

FFMPEG = config.FFMPEG_PATH
FIXTURES = os.path.join(os.path.dirname(__file__), "fixtures")


@pytest.fixture(scope="session")
def synthetic_clip() -> str:
    """10s 640x360 30fps test pattern + beep track (SYNTHETIC TEST)."""
    os.makedirs(FIXTURES, exist_ok=True)
    path = os.path.join(FIXTURES, "synthetic-10s.mp4")
    if os.path.exists(path):
        return path
    args = [
        FFMPEG, "-y",
        "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=30:duration=10",
        "-f", "lavfi", "-i", "sine=frequency=440:duration=10",
        "-c:v", "libx264", "-preset", "veryfast", "-pix_fmt", "yuv420p",
        "-c:a", "aac", "-b:a", "96k",
        "-shortest", path,
    ]
    proc = subprocess.run(args, capture_output=True, text=True, shell=False)
    assert proc.returncode == 0, proc.stderr[-500:]
    return path


@pytest.fixture(scope="session")
def silent_clip(synthetic_clip) -> str:
    path = os.path.join(FIXTURES, "synthetic-10s-silent.mp4")
    if os.path.exists(path):
        return path
    proc = subprocess.run(
        [FFMPEG, "-y", "-i", synthetic_clip, "-an", "-c:v", "copy", path],
        capture_output=True, text=True, shell=False)
    assert proc.returncode == 0, proc.stderr[-500:]
    return path
