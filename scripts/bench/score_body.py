#!/usr/bin/env python3
"""Mac CPU person-detection cost, every frame. Does not time Android tracking/rendering or claim mask accuracy."""
import argparse
import hashlib
import json
from pathlib import Path
import platform
import resource
import time

import cv2
import numpy as np
import onnxruntime as ort
from score_sep import cpu_session, REPO


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("video", type=Path)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    cv2.setNumThreads(1)
    model = REPO / "app/src/main/assets/models/yolo26n-person.onnx"
    session = cpu_session(model, 4)
    session.run(None, {"images": np.zeros((1, 3, 640, 640), np.float32)})
    capture = cv2.VideoCapture(str(args.video))
    assert capture.isOpened(), f"Cannot decode {args.video}"
    fps = capture.get(cv2.CAP_PROP_FPS)
    assert fps > 0
    rows, inference, preprocess = [], [], []
    started = time.perf_counter()
    try:
        while True:
            ok, frame = capture.read()
            if not ok:
                break
            t0 = time.perf_counter()
            h, w = frame.shape[:2]
            scale = 640 / max(w, h)
            nw, nh = round(w * scale), round(h * scale)
            left, top = (640 - nw) // 2, (640 - nh) // 2
            canvas = np.full((640, 640, 3), 114, np.uint8)
            canvas[top:top + nh, left:left + nw] = cv2.resize(frame, (nw, nh), interpolation=cv2.INTER_NEAREST)
            tensor = np.ascontiguousarray(canvas[:, :, ::-1].transpose(2, 0, 1)[None] / 255, dtype=np.float32)
            t1 = time.perf_counter()
            result = session.run(None, {"images": tensor})[0][0]
            t2 = time.perf_counter()
            assert result.shape == (300, 6) and np.isfinite(result).all()
            detected = result[(result[:, 4] >= .25) & (result[:, 5] == 0)].copy()
            boxes = []
            for x1, y1, x2, y2, score, _ in detected:
                box = np.clip([(x1 - left) / (scale * w), (y1 - top) / (scale * h),
                               (x2 - left) / (scale * w), (y2 - top) / (scale * h)], 0, 1)
                if box[2] > box[0] and box[3] > box[1]:
                    boxes.append([*box.tolist(), float(score)])
            rows.append({"ptsMs": len(rows) * 1000 / fps, "boxes": boxes})
            inference.append(t2 - t1)
            preprocess.append(t1 - t0)
    finally:
        capture.release()
    wall = time.perf_counter() - started
    assert rows, "No video frames decoded"
    report = {
        "video": str(args.video), "fps": fps, "frames": len(rows), "durationS": len(rows) / fps,
        "modelSha256": hashlib.sha256(model.read_bytes()).hexdigest(), "runtime": ort.__version__,
        "platform": platform.platform(), "provider": "CPUExecutionProvider", "threads": 4,
        "wallS": wall, "inferenceS": sum(inference), "preprocessS": sum(preprocess),
        "p50InferenceMs": float(np.median(inference) * 1000), "p90InferenceMs": float(np.percentile(inference, 90) * 1000),
        "framesWithoutPerson": sum(not r["boxes"] for r in rows),
        "peakRssMiB": resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / (1024 ** 2),
        "scope": "Native Mac decode, letterbox and person inference; excludes faces, tracking and rendering",
    }
    (args.out / "mac-cost.json").write_text(json.dumps(report, indent=2) + "\n")
    (args.out / "mac-detections.json").write_text(json.dumps(rows) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
