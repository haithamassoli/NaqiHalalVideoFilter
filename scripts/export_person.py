#!/usr/bin/env python3
"""Restore the optional body model. Use the benchmark venv with ultralytics==8.4.171, onnx==1.23.1, torch==2.14.0."""
from pathlib import Path
import hashlib
import onnx
import torch
import ultralytics
from ultralytics import YOLO

ROOT = Path(__file__).resolve().parents[1]
WEIGHT_SHA = "9b09cc8bf347f0fc8a5f7657480587f25db09b34bf33b0652110fb03a8ad4fef"
MODEL_SHA = "35519e399739f1d02821f6479f5a804c5da64b4f1dc20fa0db6f9822c4417265"


def main():
    assert ultralytics.__version__ == "8.4.171", "Use ultralytics==8.4.171 for the pinned export"
    torch.set_num_threads(4)
    weights = ROOT / "qa-assets/models/yolo26n.pt"
    weights.parent.mkdir(parents=True, exist_ok=True)
    model = YOLO(str(weights))
    assert hashlib.sha256(weights.read_bytes()).hexdigest() == WEIGHT_SHA, "Wrong YOLO26n checkpoint"
    exported = Path(model.export(format="onnx", imgsz=640, opset=17, simplify=False,
                                 dynamic=False, half=False, end2end=True, device="cpu"))
    graph = onnx.load(exported)
    # Export timestamps change bytes without changing inference. Keep the artifact reproducible.
    metadata = [p for p in graph.metadata_props if p.key != "date"]
    graph.ClearField("metadata_props")
    graph.metadata_props.extend(metadata)
    onnx.checker.check_model(graph)
    data = graph.SerializeToString()
    assert hashlib.sha256(data).hexdigest() == MODEL_SHA, "Export changed; validate parity before updating the model hash"
    target = ROOT / "app/src/main/assets/models/yolo26n-person.onnx"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.with_suffix(".tmp").write_bytes(data)
    target.with_suffix(".tmp").replace(target)
    print(f"{target}: {MODEL_SHA}")


if __name__ == "__main__":
    main()
