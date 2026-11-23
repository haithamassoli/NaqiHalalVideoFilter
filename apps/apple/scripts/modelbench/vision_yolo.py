"""Pinned nano segmentation screening. Assets stay outside git.

python vision_yolo.py export --assets /path/to/vision
python vision_yolo.py run --assets /path/to/vision --compute reference|CPU_ONLY|CPU_AND_GPU|ALL
python vision_yolo.py --self-check
"""
import argparse
import hashlib
import json
import platform
import time
from pathlib import Path

import cv2
import numpy as np


def iou(a, b):
    lo = np.maximum(a[:2], b[:2])
    hi = np.minimum(a[2:], b[2:])
    intersection = np.prod(np.maximum(0, hi - lo))
    union = np.prod(np.subtract(a[2:], a[:2])) + np.prod(np.subtract(b[2:], b[:2])) - intersection
    return float(intersection / union) if union > 0 else 0.0


def associate_face(face, bodies):
    """Abstain on ambiguity: a face must lie mostly inside one body's top third."""
    candidates = []
    area = np.prod(np.subtract(face[2:], face[:2]))
    if area <= 0:
        return None
    for i, body in enumerate(bodies):
        intersection = np.prod(np.maximum(0, np.minimum(face[2:], body[2:]) - np.maximum(face[:2], body[:2])))
        if intersection / area >= 0.8 and (face[1] + face[3]) / 2 <= body[1] + (body[3] - body[1]) / 3:
            candidates.append(i)
    return candidates[0] if len(candidates) == 1 else None


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(args):
    import coremltools as ct
    import torch
    import ultralytics
    from ultralytics import YOLO

    torch.set_num_threads(1)
    out = args.assets / ("yolo-" + args.compute)
    out.mkdir(exist_ok=True)
    paths = sorted((args.assets / "frames").glob("*/*.png"))
    with (out / "results.jsonl").open("w") as log:
        for name in ("yolo26n-seg", "yolo11n-seg"):
            weight = args.assets / "models" / (name + ".pt")
            model_path = weight if args.compute == "reference" else weight.with_suffix(".mlpackage")
            model = YOLO(model_path, task="segment")
            first = cv2.imread(str(paths[0]))
            options = dict(imgsz=640, conf=0.25, iou=0.7, classes=[0], rect=False, device="cpu", verbose=False, retina_masks=True)
            if args.compute != "reference":
                # Instantiate the installed backend, then explicitly replace its Core ML model configuration.
                model.predict(first, **options)
                model.predictor.model.backend.model = ct.models.MLModel(str(model_path), compute_units=getattr(ct.ComputeUnit, args.compute))
            for _ in range(3):
                model.predict(first, **options)
            for frame in paths[:args.limit]:
                started = time.perf_counter()
                image = cv2.imread(str(frame))
                if image is None:
                    raise ValueError(f"Unreadable frame: {frame}")
                decoded = time.perf_counter()
                result = model.predict(image, **options)[0]
                predicted = time.perf_counter()
                if not np.isfinite(result.boxes.data.cpu().numpy()).all():
                    raise ValueError(f"Non-finite detection output: {name} {frame}")
                if result.masks is not None and not np.isfinite(result.masks.data.cpu().numpy()).all():
                    raise ValueError(f"Non-finite mask output: {name} {frame}")
                height, width = image.shape[:2]
                boxes = (result.boxes.xyxy.cpu().numpy() / [width, height, width, height]).tolist()
                masks = []
                if result.masks is not None:
                    for i, raw in enumerate(result.masks.data.cpu().numpy()):
                        mask = cv2.resize(raw.astype(np.float32), (width, height), interpolation=cv2.INTER_NEAREST) > 0.5
                        path = f"{frame.parent.name}_{frame.stem}_{name}_{i}.png"
                        if not cv2.imwrite(str(out / path), mask.astype(np.uint8) * 255):
                            raise OSError(f"Could not write mask {path}")
                        masks.append(path)
                row = dict(video=frame.parent.name, frame=frame.name,
                           timestamp_s=int(frame.stem) - 0.5, candidate=name, runtime="PyTorch" if args.compute == "reference" else "Native Core ML (Python host)",
                           compute_requested="CPU" if args.compute == "reference" else args.compute,
                           placement_verified=False, host="Apple M3 24GB", os=platform.mac_ver()[0],
                           frame_decode_ms=(decoded-started)*1000, prediction_wall_ms=(predicted-decoded)*1000,
                           infer_mask_export_ms=(time.perf_counter()-decoded)*1000, stage_ms=result.speed,
                           boxes=boxes, confidence=result.boxes.conf.cpu().tolist(), masks=masks,
                           model_sha256=digest(weight), input_sha256=digest(frame),
                           ultralytics=ultralytics.__version__, torch=torch.__version__, coremltools=ct.__version__,
                           input_size=[640,640], threshold=0.25, nms_iou=0.7, mask_threshold=0.5,
                           export_precision="reference-FP32" if args.compute == "reference" else "MLProgram-FP16")
                log.write(json.dumps(row, sort_keys=True) + "\n")
                log.flush()
            print(name, args.compute, "done", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", nargs="?", choices=["export", "run"])
    parser.add_argument("--assets", type=Path)
    parser.add_argument("--compute", choices=["reference", "CPU_ONLY", "CPU_AND_GPU", "ALL"], default="ALL")
    parser.add_argument("--limit", type=int, default=100000)
    parser.add_argument("--self-check", action="store_true")
    args = parser.parse_args()
    if args.self_check:
        assert iou([0,0,1,1], [0.5,0.5,1.5,1.5]) == 1/7
        assert iou([0,0,1,1], [1,1,2,2]) == 0
        face, body = [0.1,0.1,0.3,0.25], [0,0,0.6,0.9]
        assert associate_face(face, [body]) == 0
        assert associate_face(face, [body, body]) is None
        assert associate_face([0.1,0.7,0.3,0.8], [body]) is None
        print("Box/association checks passed")
        return
    if not args.action or args.assets is None:
        parser.error("action and --assets are required")
    if args.action == "export":
        from ultralytics import YOLO
        for name in ("yolo26n-seg", "yolo11n-seg"):
            YOLO(args.assets / "models" / (name + ".pt")).export(format="coreml", imgsz=640, half=True, nms=False, dynamic=False, device="cpu")
    else:
        run(args)


if __name__ == "__main__":
    main()
