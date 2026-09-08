"""Worker configuration from environment variables (see .env.example)."""
import os
import time


def _int(name: str, default: int) -> int:
    try:
        return int(os.environ.get(name, "") or default)
    except ValueError:
        return default


JAVA_BASE_URL = os.environ.get("JAVA_BASE_URL", "http://127.0.0.1:8080")
WORKER_TOKEN = os.environ.get("WORKER_TOKEN", "dev-worker-token-change-me")
WORKER_ID = os.environ.get("WORKER_ID", "") or f"worker-{os.getpid()}"
STARTED_AT = time.time()

STORAGE_ROOT = os.path.abspath(os.environ.get("STORAGE_ROOT", os.path.join(os.getcwd(), "..", "data")))

FFMPEG_PATH = os.environ.get("FFMPEG_PATH", "ffmpeg")
FFPROBE_PATH = os.environ.get("FFPROBE_PATH", "ffprobe")

# keep the lease comfortably ahead of the heartbeat so a stalled heartbeat
# never causes a live task to be reclaimed
LEASE_SECONDS = _int("LEASE_SECONDS", 120)
HEARTBEAT_INTERVAL = max(5, LEASE_SECONDS // 3)
CLAIM_INTERVAL_SECONDS = _int("CLAIM_INTERVAL_SECONDS", 3)
CLAIM_TYPES = [t for t in os.environ.get(
    "CLAIM_TYPES",
    "PROBE,PREVIEW,THUMBNAIL,RENDER,CLEANUP,ANALYZE,GENERATE_CANDIDATES",
).split(",") if t]
MAX_CONCURRENT_TASKS = _int("MAX_CONCURRENT_TASKS", 1)

RENDER_PRESET = os.environ.get("RENDER_PRESET", "veryfast")
RENDER_CRF = _int("RENDER_CRF", 20)
RENDER_AUDIO_BITRATE = os.environ.get("RENDER_AUDIO_BITRATE", "192k")
# render progress ticks are also cancellation poll points
PROGRESS_POLL_SECONDS = _int("PROGRESS_POLL_SECONDS", 2)
RENDER_MAX_OUTPUT_BYTES = _int("RENDER_MAX_OUTPUT_BYTES", 2147483648)
RENDER_TIMEOUT_SECONDS = _int("RENDER_TIMEOUT_SECONDS", 3600)

# caption font: must be configurable and license-clear in the deployment
CAPTION_FONT_FILE = os.environ.get("CAPTION_FONT_FILE", "")
FONT_CANDIDATES = [
    CAPTION_FONT_FILE,
    "C:/Windows/Fonts/msyh.ttc",
    "C:/Windows/Fonts/msyh.ttf",
    "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
]
FONT_FILE = next((c for c in FONT_CANDIDATES if c and os.path.exists(c)), None)

# The gyan.dev Windows FFmpeg builds crash in drawtext when fontconfig has no
# config. Generate a minimal one under the storage root (writable) and pass it
# to every ffmpeg subprocess via FONTCONFIG_FILE.
FONTCONFIG_FILE = None
try:
    _fc_dir = os.path.join(STORAGE_ROOT, "tmp", "fontconfig")
    os.makedirs(_fc_dir, exist_ok=True)
    _fonts_source = os.path.dirname(FONT_FILE) if FONT_FILE else (
        "C:/Windows/Fonts" if os.name == "nt" else "/usr/share/fonts")
    _fc_file = os.path.join(_fc_dir, "fonts.conf")
    with open(_fc_file, "w", encoding="utf-8") as _f:
        _f.write(
            '<?xml version="1.0"?>\n'
            '<!DOCTYPE fontconfig SYSTEM "fonts.dtd">\n'
            '<fontconfig>\n'
            f'  <dir>{_fonts_source}</dir>\n'
            f'  <cachedir>{os.path.join(_fc_dir, "cache")}</cachedir>\n'
            '</fontconfig>\n'
        )
    FONTCONFIG_FILE = _fc_file
    os.makedirs(os.path.join(_fc_dir, "cache"), exist_ok=True)
except OSError:
    FONTCONFIG_FILE = None


def storage_path(logical_key: str) -> str:
    """Resolve a logical storage key ('original/<id>/source') to a local path.

    The worker only ever touches paths under STORAGE_ROOT; logical keys come
    from the Java service, never raw filesystem paths from users.
    """
    logical = logical_key.replace("\\", "/").strip("/")
    if not logical or ".." in logical.split("/") or ":" in logical:
        raise ValueError(f"rejected storage key: {logical_key!r}")
    return os.path.join(STORAGE_ROOT, *logical.split("/"))
