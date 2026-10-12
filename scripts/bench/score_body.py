#!/usr/bin/env python3
"""Mac CPU person-detection cost, every frame or at --fps like the app's sampler. Does not time Android tracking/rendering or claim mask accuracy.

YOLO11n-seg decoded like PersonDetector.kt; --masks adds the precise-mode prototype fetch and mask decode.
"""
import argparse
import hashlib
import json
from pathlib import Path
import platform
import resource
import sys
import time

import cv2
import numpy as np
import onnxruntime as ort
from score_sep import cpu_session, REPO

sys.path.insert(0, str(REPO / "scripts"))
from export_person import decode  # noqa: E402


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("video", type=Path)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--fps", type=float, default=0, help="sample slots per second; 0 = every frame")
    parser.add_argument("--masks", action="store_true", help="precise body mode")
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    cv2.setNumThreads(1)
    model = REPO / "app/src/main/assets/models/yolo11n-seg.onnx"
    session = cpu_session(model, 4)
    outputs = ["output0", "output1"] if args.masks else ["output0"]
    session.run(outputs, {"images": np.zeros((1, 3, 640, 640), np.float32)})
    capture = cv2.VideoCapture(str(args.video))
    assert capture.isOpened(), f"Cannot decode {args.video}"
    fps = capture.get(cv2.CAP_PROP_FPS)
    assert fps > 0
    rows, inference, preprocess = [], [], []

    def infer(image):
        t0 = time.perf_counter()
        h, w = image.shape[:2]
        scale = 640 / max(w, h)
        nw, nh = round(w * scale), round(h * scale)
        left, top = (640 - nw) // 2, (640 - nh) // 2
        canvas = np.full((640, 640, 3), 114, np.uint8)
        canvas[top:top + nh, left:left + nw] = cv2.resize(image, (nw, nh), interpolation=cv2.INTER_NEAREST)
        tensor = np.ascontiguousarray(canvas[:, :, ::-1].transpose(2, 0, 1)[None] / 255, dtype=np.float32)
        t1 = time.perf_counter()
        result = session.run(outputs, {"images": tensor})
        out = result[0]
        assert out.shape == (1, 116, 8400) and np.isfinite(out).all()
        people = decode(out, result[1] if args.masks else np.zeros((1, 32, 160, 160), np.float32))
        t2 = time.perf_counter()
        inference.append(t2 - t1)
        preprocess.append(t1 - t0)
        return [[(b[0] - left) / (scale * w), (b[1] - top) / (scale * h),
                 (b[2] - left) / (scale * w), (b[3] - top) / (scale * h), s] for b, s, _ in people]

    decoded, next_slot = 0, 0.0
    started = time.perf_counter()
    try:
        while True:
            ok, frame = capture.read()
            if not ok:
                break
            pts = decoded * 1000 / fps
            decoded += 1
            # FrameSampler: decode every frame, keep the first one at or past each slot.
            if args.fps and pts < next_slot:
                continue
            next_slot += 1000 / args.fps if args.fps else 0
            boxes = [[*np.clip(b[:4], 0, 1).tolist(), float(b[4])] for b in infer(frame)]
            rows.append({"ptsMs": pts, "boxes": [b for b in boxes if b[2] > b[0] and b[3] > b[1]]})
    finally:
        capture.release()
    wall = time.perf_counter() - started
    assert rows, "No video frames decoded"
    report = {
        "video": str(args.video), "fps": fps, "sampleFps": args.fps or fps, "frames": decoded,
        "samples": len(rows), "masks": args.masks,
        "durationS": decoded / fps,
        "modelSha256": hashlib.sha256(model.read_bytes()).hexdigest(), "runtime": ort.__version__,
        "platform": platform.platform(), "provider": "CPUExecutionProvider", "threads": 4,
        "wallS": wall, "inferenceS": sum(inference), "preprocessS": sum(preprocess),
        "p50InferenceMs": float(np.median(inference) * 1000), "p90InferenceMs": float(np.percentile(inference, 90) * 1000),
        "samplesWithoutPerson": sum(not r["boxes"] for r in rows),
        "peakRssMiB": resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / (1024 ** 2),
        "scope": "Native Mac decode, letterbox, person inference and decode; excludes faces, tracking and rendering",
    }
    suffix = (f"-{args.fps:g}fps" if args.fps else "") + ("-masks" if args.masks else "")
    (args.out / f"mac-cost{suffix}.json").write_text(json.dumps(report, indent=2) + "\n")
    (args.out / f"mac-detections{suffix}.json").write_text(json.dumps(rows) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
