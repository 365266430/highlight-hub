"""FFmpeg/FFprobe wrappers. All invocations use argument arrays with shell=False."""
from __future__ import annotations

import json
import os
import subprocess
import threading
import time
from typing import Callable

from . import config


class FFmpegError(RuntimeError):
    pass


class CancellationRequested(RuntimeError):
    pass


def _env() -> dict | None:
    """Environment for ffmpeg subprocesses: point fontconfig at our generated
    config so drawtext never crashes on Windows builds without one."""
    if not config.FONTCONFIG_FILE:
        return None
    env = os.environ.copy()
    env["FONTCONFIG_FILE"] = config.FONTCONFIG_FILE
    return env


def run(args: list[str], timeout: int | None = None, cwd: str | None = None) -> subprocess.CompletedProcess:
    try:
        return subprocess.run(args, capture_output=True, text=True, timeout=timeout,
                              shell=False, encoding="utf-8", errors="replace", cwd=cwd,
                              env=_env())
    except FileNotFoundError as e:
        raise FFmpegError(f"binary not found: {e}") from e


def probe(path: str) -> dict:
    """Return the raw ffprobe JSON for a media file."""
    args = [config.FFPROBE_PATH, "-v", "quiet", "-print_format", "json",
            "-show_format", "-show_streams", path]
    proc = run(args, timeout=120)
    if proc.returncode != 0:
        raise FFmpegError(f"ffprobe failed: {proc.stderr[:400]}")
    try:
        return json.loads(proc.stdout)
    except json.JSONDecodeError as e:
        raise FFmpegError("ffprobe returned invalid JSON") from e


def parse_probe(probe_json: dict) -> dict:
    """Extract the normalized media descriptor Java persists for a READY media."""
    fmt = probe_json.get("format") or {}
    streams = probe_json.get("streams") or []
    video = next((s for s in streams if s.get("codec_type") == "video"), None)
    if video is None:
        raise FFmpegError("no video stream found; the file is not a usable video")

    duration_raw = video.get("duration") or fmt.get("duration")
    try:
        duration_ms = int(round(float(duration_raw) * 1000))
    except (TypeError, ValueError):
        duration_ms = None
    if duration_ms is None or duration_ms <= 0:
        raise FFmpegError("video duration is missing or zero")

    def rate(value):
        if not value or value in ("0/0", "N/A"):
            return None
        try:
            num, _, den = str(value).partition("/")
            den_f = float(den) if den else 1.0
            if den_f == 0:
                return None
            return round(float(num) / den_f, 4)
        except (TypeError, ValueError):
            return None

    avg_fps = rate(video.get("avg_frame_rate"))
    nominal_fps = rate(video.get("r_frame_rate"))
    vfr = bool(avg_fps and nominal_fps and abs(avg_fps - nominal_fps) > 0.01)

    rotation = 0
    for sd in video.get("side_data_list") or []:
        if "rotation" in sd:
            rotation = int(sd["rotation"]) % 360
    tags = video.get("tags") or {}
    if tags.get("rotate"):
        rotation = int(tags["rotate"]) % 360

    audio_streams = [s for s in streams if s.get("codec_type") == "audio"]

    return {
        "ok": True,
        "durationMs": duration_ms,
        "width": int(video.get("width") or 0),
        "height": int(video.get("height") or 0),
        "videoCodec": video.get("codec_name"),
        "audioCodec": audio_streams[0].get("codec_name") if audio_streams else None,
        "frameRate": nominal_fps or avg_fps,
        "variableFrameRate": vfr,
        "rotation": rotation,
        "audioStreamCount": len(audio_streams),
        "container": fmt.get("format_name"),
        "fileSize": int(fmt.get("size") or 0),
    }


def probe_media(path: str) -> dict:
    return parse_probe(probe(path))


def preview_args(src: str, dst: str, max_height: int, crf: int) -> list[str]:
    """Proxy preview: 720p-class H.264/AAC, faststart, browser playable."""
    return [
        config.FFMPEG_PATH, "-y", "-i", src,
        "-vf", f"scale=-2:'min({max_height},ih)'",
        "-c:v", "libx264", "-preset", config.RENDER_PRESET, "-crf", str(crf),
        "-pix_fmt", "yuv420p",
        "-c:a", "aac", "-b:a", "128k", "-ac", "2",
        "-movflags", "+faststart",
        dst,
    ]


def thumbnail_args(src: str, dst: str, interval: int, columns: int, rows: int,
                   frame_width: int) -> list[str]:
    """Fixed-interval contact sheet; one frame per interval, tiled."""
    return [
        config.FFMPEG_PATH, "-y", "-i", src,
        "-vf", f"fps=1/{interval},scale={frame_width}:-2,"
               f"tile={columns}x{rows}:padding=2:color=black",
        "-frames:v", "1", "-q:v", "5", dst,
    ]


def _q(path: str) -> str:
    """Quote a value for use inside a filtergraph (single quotes)."""
    return "'" + path + "'"


def render_args(src: str, dst: str, edl: dict, caption_files: list[tuple[str, float, float]],
                font_file: str | None, source_width: int = 0, source_height: int = 0) -> list[str]:
    """Build the frame-accurate render command (decode + filter + re-encode).

    Mask regions are relative to the SOURCE frame (0..1, validated by Java)
    and applied BEFORE any trim/crop/pad, so they track the original picture.
    aspectMode: SOURCE = fit inside output box + black pad; CROP = center
    crop to fill the output box. Captions use relative textfile/font names
    (ffmpeg runs with cwd=tempdir).
    """
    segments = edl["segments"]
    out = edl["output"]
    has_audio = bool(edl.get("hasAudio", True))
    n = len(segments)

    width = int(out["width"])
    height = int(out["height"])
    fps = int(out.get("fps", 30))

    parts: list[str] = []
    # static source-frame masks (drawbox fill black) applied once, upstream of trims
    masks = edl.get("masks") or []
    base_v = "[0:v]"
    if masks:
        sw = source_width or 1920
        sh = source_height or 1080
        label_in = "[0:v]"
        for mi, m in enumerate(masks):
            x = int(round(m["x"] * sw))
            y = int(round(m["y"] * sh))
            w = int(round(m["w"] * sw))
            h = int(round(m["h"] * sh))
            next_label = f"[vm{mi}]"
            parts.append(f"{label_in}drawbox=x={x}:y={y}:w={w}:h={h}:color=black:t=fill{next_label}")
            label_in = next_label
        base_v = label_in
    for i, seg in enumerate(segments):
        s = seg["sourceInMs"] / 1000.0
        e = seg["sourceOutMs"] / 1000.0
        parts.append(f"{base_v}trim=start={s:.3f}:end={e:.3f},setpts=PTS-STARTPTS[v{i}]")
        if has_audio:
            vol = max(0.0, min(1.5, float(seg.get("sourceVolume", 1.0))))
            parts.append(
                f"[0:a]atrim=start={s:.3f}:end={e:.3f},asetpts=PTS-STARTPTS,volume={vol:.4f}[a{i}]"
            )

    # concat consumes inputs segment by segment: [v0][a0][v1][a1]... (a=1)
    concat_in = ""
    for i in range(n):
        concat_in += f"[v{i}]"
        if has_audio:
            concat_in += f"[a{i}]"
    concat_out = "[cv][ca]" if has_audio else "[cv]"
    parts.append(f"{concat_in}concat=n={n}:v=1:a={1 if has_audio else 0}{concat_out}")

    if out.get("aspectMode", "SOURCE") == "CROP":
        # center-crop the source to fill the output box (no letterboxing)
        parts.append(
            f"[cv]scale={width}:{height}:force_original_aspect_ratio=increase,"
            f"crop={width}:{height}[vfit]"
        )
    else:
        parts.append(
            f"[cv]scale={width}:{height}:force_original_aspect_ratio=decrease,"
            f"pad={width}:{height}:(ow-iw)/2:(oh-ih)/2[vfit]"
        )

    final_v = "[vfit]"
    offset = 0.0
    label = "[vfit]"
    for i, (text_file, _, _) in enumerate(caption_files):
        seg = segments[i]
        dur = (seg["sourceOutMs"] - seg["sourceInMs"]) / 1000.0
        start, end = offset, offset + dur
        offset += dur
        next_label = f"[vt{i}]"
        font_part = ":fontfile=font.ttf" if font_file else ""
        parts.append(
            f"{label}drawtext=textfile={os.path.basename(text_file)}{font_part}:"
            f"fontsize=48:fontcolor=white:borderw=2:bordercolor=black:"
            f"x=(w-text_w)/2:y=h-text_h-60:"
            f"enable={_q(f'between(t,{start:.3f},{end:.3f})')}{next_label}"
        )
        label = next_label
        final_v = next_label

    args = [config.FFMPEG_PATH, "-y", "-i", src, "-filter_complex", ";".join(parts),
            "-map", final_v]
    if has_audio:
        args += ["-map", "[ca]"]
    args += [
        "-c:v", "libx264", "-preset", config.RENDER_PRESET, "-crf", str(config.RENDER_CRF),
        "-pix_fmt", "yuv420p",
        "-r", str(fps),
    ]
    if has_audio:
        args += ["-c:a", "aac", "-b:a", config.RENDER_AUDIO_BITRATE, "-ac", "2"]
    else:
        args += ["-an"]
    args += ["-movflags", "+faststart", dst]
    return args


def run_with_progress(args: list[str], total_output_ms: int | None,
                      on_progress: Callable[[int], None],
                      on_cancel_check: Callable[[], bool],
                      poll_seconds: int = 2,
                      timeout: int | None = None,
                      cwd: str | None = None) -> subprocess.CompletedProcess:
    """Run ffmpeg parsing -progress output; report %; poll cancellation.

    on_cancel_check() returning True aborts the process tree and raises
    CancellationRequested. on_progress is throttled to ~poll_seconds.
    """
    args = list(args) + ["-progress", "pipe:1", "-nostats"]
    try:
        proc = subprocess.Popen(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                text=True, encoding="utf-8", errors="replace", shell=False,
                                cwd=cwd, env=_env())
    except FileNotFoundError as e:
        raise FFmpegError(f"binary not found: {e}") from e

    stderr_tail: list[str] = []
    cancelled = {"flag": False}
    last_progress = {"t": 0.0}

    def drain_err():
        assert proc.stderr is not None
        for line in proc.stderr:
            stderr_tail.append(line)
            if len(stderr_tail) > 50:
                stderr_tail.pop(0)

    def watchdog():
        while proc.poll() is None:
            time.sleep(poll_seconds)
            if on_cancel_check():
                cancelled["flag"] = True
                kill_tree(proc.pid)
                return

    threading.Thread(target=drain_err, daemon=True).start()
    threading.Thread(target=watchdog, daemon=True).start()

    assert proc.stdout is not None
    for line in proc.stdout:
        line = line.strip()
        if not line.startswith("out_time_ms="):
            continue
        try:
            out_time_ms = int(line.split("=", 1)[1])
        except ValueError:
            continue
        now = time.monotonic()
        if total_output_ms and out_time_ms > 0 and now - last_progress["t"] >= poll_seconds:
            last_progress["t"] = now
            pct = max(0, min(99, int(out_time_ms / 1000.0 / total_output_ms * 100)))
            if not cancelled["flag"]:
                on_progress(pct)

    proc.wait(timeout=timeout)
    if cancelled["flag"]:
        raise CancellationRequested()
    if proc.returncode != 0:
        raise FFmpegError(f"ffmpeg exit {proc.returncode}: " + " | ".join(stderr_tail[-6:]))
    return proc


def kill_tree(pid: int):
    """Kill a whole subprocess tree across platforms (arg-array, no shell)."""
    import os
    import signal
    import sys
    if sys.platform == "win32":
        try:
            subprocess.run(["taskkill", "/F", "/T", "/PID", str(pid)],
                           capture_output=True, shell=False, timeout=15)
        except Exception:
            pass
    else:
        try:
            os.killpg(os.getpgid(pid), signal.SIGKILL)
        except Exception:
            try:
                os.kill(pid, signal.SIGKILL)
            except Exception:
                pass
