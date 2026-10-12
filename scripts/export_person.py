#!/usr/bin/env python3
"""Restore the optional body model. Use the benchmark venv with ultralytics==8.4.171, onnx==1.23.1, torch==2.14.0.

YOLO11n-seg, the same checkpoint iOS screens. After exporting, the ONNX graph is checked against the
PyTorch model on ultralytics' sample images, decoded the way analysis/PersonDetector.kt decodes it.
"""
from pathlib import Path
import hashlib
import numpy as np
import onnx
import onnxruntime as ort
import torch
import ultralytics
from ultralytics import YOLO
from ultralytics.data.augment import LetterBox
from ultralytics.utils import ASSETS
import cv2

ROOT = Path(__file__).resolve().parents[1]
WEIGHT_SHA = "55ed65c56c91713d23e8402371c6c49a6fd84f257f7dce452e8d70e41dcbe152"
MODEL_SHA = "a119f46d1eb44acefb071c9733b2f12fc9bd89cfc581f7df4151cdb6ba758c41"
CONF, IOU = 0.25, 0.7  # iOS PersonDetector.swift


def decode(out, proto):
    """Person boxes (letterbox xyxy, score) and 32-cell masks, as PersonDetector.kt computes them."""
    rows = out[0]
    person = rows[4]
    best = person >= rows[5:84].max(0)
    keep = np.flatnonzero((person > CONF) & best)
    keep = keep[np.lexsort((keep, -person[keep]))]
    boxes = np.stack([rows[0] - rows[2] / 2, rows[1] - rows[3] / 2, rows[0] + rows[2] / 2, rows[1] + rows[3] / 2], 1)
    kept = []
    for i in keep:
        b = boxes[i]
        if all(iou(b, boxes[j]) <= IOU for j in kept):
            kept.append(i)
    result = []
    for i in kept:
        b = boxes[i]
        long = max(b[2] - b[0], b[3] - b[1])
        w = max(1, round(32 * (b[2] - b[0]) / long))
        h = max(1, round(32 * (b[3] - b[1]) / long))
        ys = b[1] + (np.arange(h) + 0.5) * (b[3] - b[1]) / h
        xs = b[0] + (np.arange(w) + 0.5) * (b[2] - b[0]) / w
        py = np.clip((ys / 4).astype(int), 0, 159)
        px = np.clip((xs / 4).astype(int), 0, 159)
        logits = np.einsum("c,cyx->yx", rows[84:116, i], proto[0][:, py][:, :, px])
        result.append((b, person[i], logits > 0))
    return result


def iou(a, b):
    w = max(0, min(a[2], b[2]) - max(a[0], b[0]))
    h = max(0, min(a[3], b[3]) - max(a[1], b[1]))
    inter = w * h
    return inter / ((a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter)


def parity(model, onnx_path):
    session = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
    worst_raw = worst_box = 0.0
    agreement, reference = [], []
    for name in ("bus.jpg", "zidane.jpg"):
        image = cv2.imread(str(ASSETS / name))
        boxed = LetterBox((640, 640), auto=False)(image=image)
        tensor = np.ascontiguousarray(boxed[:, :, ::-1].transpose(2, 0, 1)[None] / 255, dtype=np.float32)
        with torch.no_grad():
            ref = [t.numpy() for t in model.model.eval()(torch.from_numpy(tensor))[0]]
        got = session.run(None, {"images": tensor})
        worst_raw = max(worst_raw, *(float(np.abs(a - b).max()) for a, b in zip(ref, got)))
        mine, theirs = decode(*got), decode(*ref)
        assert len(mine) == len(theirs), f"{name}: {len(mine)} vs {len(theirs)} people"
        # ultralytics' own post-processing on the same letterboxed picture.
        predicted = model.predict(boxed, imgsz=640, conf=CONF, iou=IOU, classes=[0], verbose=False)[0]
        assert len(predicted.boxes) == len(mine), f"{name}: ultralytics kept {len(predicted.boxes)}, decoder {len(mine)}"
        for (b, s, m), (rb, rs, rm), um in zip(mine, theirs, predicted.masks.data.numpy()):
            worst_box = max(worst_box, float(np.abs(b - rb).max()), abs(float(s - rs)))
            agreement.append(float((m == rm).mean()))
            # Cell-centre samples of ultralytics' own upsampled mask: same test, coarser grid.
            h, w = m.shape
            ys = (b[1] + (np.arange(h) + 0.5) * (b[3] - b[1]) / h).astype(int).clip(0, 639)
            xs = (b[0] + (np.arange(w) + 0.5) * (b[2] - b[0]) / w).astype(int).clip(0, 639)
            reference.append(float((m == (um[np.ix_(ys, xs)] > 0.5)).mean()))
        print(f"{name}: {len(mine)} people")
    print(f"max |pytorch - onnx| raw {worst_raw:.3g}, boxes/scores {worst_box:.3g}, "
          f"mask cells agreeing {min(agreement) * 100:.2f}%, with ultralytics' masks {min(reference) * 100:.2f}% "
          f"(worst person)")
    assert worst_box < 1e-2 and min(agreement) > 0.99 and min(reference) > 0.9


def main():
    assert ultralytics.__version__ == "8.4.171", "Use ultralytics==8.4.171 for the pinned export"
    torch.set_num_threads(4)
    weights = ROOT / "qa-assets/models/yolo11n-seg.pt"
    weights.parent.mkdir(parents=True, exist_ok=True)
    model = YOLO(str(weights))
    assert hashlib.sha256(weights.read_bytes()).hexdigest() == WEIGHT_SHA, "Wrong YOLO11n-seg checkpoint"
    exported = Path(model.export(format="onnx", imgsz=640, opset=17, simplify=False,
                                 dynamic=False, half=False, device="cpu"))
    graph = onnx.load(exported)
    shapes = [[d.dim_value for d in o.type.tensor_type.shape.dim] for o in graph.graph.output]
    assert shapes == [[1, 116, 8400], [1, 32, 160, 160]], shapes
    # Export timestamps change bytes without changing inference. Keep the artifact reproducible.
    metadata = [p for p in graph.metadata_props if p.key != "date"]
    graph.ClearField("metadata_props")
    graph.metadata_props.extend(metadata)
    onnx.checker.check_model(graph)
    data = graph.SerializeToString()
    digest = hashlib.sha256(data).hexdigest()
    exported.write_bytes(data)
    parity(YOLO(str(weights)), exported)
    assert digest == MODEL_SHA, f"Export changed ({digest}); validate parity before updating the model hash"
    target = ROOT / "app/src/main/assets/models/yolo11n-seg.onnx"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.with_suffix(".tmp").write_bytes(data)
    target.with_suffix(".tmp").replace(target)
    print(f"{target}: {MODEL_SHA}")


if __name__ == "__main__":
    main()
