"""Generate SYNTHETIC test fixtures with FFmpeg/OpenCV.

Everything produced here is a synthetic clip for pipeline testing
(time cropping, OCR mechanics, task recovery). It must NOT be presented
as evidence of real-game recognition accuracy.
"""
import os
import sys

import cv2
import numpy as np

OUT = os.path.join(os.path.dirname(__file__), "..", "fixtures")
FFMPEG = os.environ.get("FFMPEG_PATH", "ffmpeg")


def counter_clip(path: str, seconds: int = 8):
    """White canvas with an incrementing counter each second (for OCR tests)."""
    fps = 20
    writer = cv2.VideoWriter(path, cv2.VideoWriter_fourcc(*"mp4v"), fps, (640, 360))
    value = 10
    for frame_idx in range(fps * seconds):
        second = frame_idx // fps
        if second != (frame_idx - 1) // fps:
            value += 1
        img = np.full((360, 640, 3), 255, dtype=np.uint8)
        cv2.putText(img, f"SCORE {value}", (400, 60), cv2.FONT_HERSHEY_SIMPLEX,
                    1.1, (0, 0, 0), 3, cv2.LINE_AA)
        writer.write(img)
    writer.release()


def moving_pattern(path: str, seconds: int = 10):
    """testsrc2 + sine beep: general trimming/render target."""
    os.system(f'"{FFMPEG}" -y -f lavfi -i testsrc2=size=640x360:rate=30:duration={seconds} '
              f'-f lavfi -i sine=frequency=440:duration={seconds} '
              '-c:v libx264 -preset veryfast -pix_fmt yuv420p -c:a aac -b:a 96k '
              f'-shortest "{path}"')


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    counter_clip(os.path.join(OUT, "synthetic-counter-8s.mp4"))
    moving_pattern(os.path.join(OUT, "synthetic-10s.mp4"))
    print("SYNTHETIC test fixtures written to", os.path.abspath(OUT))
