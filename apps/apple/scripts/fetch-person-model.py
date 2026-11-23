#!/usr/bin/env python3
"""Stage the validated YOLO11 Core ML asset; Python packages are build-only.

uv run --python 3.12 --with ultralytics==8.4.29 --with torch==2.7.0 \
  --with torchvision==0.22.0 --with numpy==2.2.6 --with coremltools==9.0 \
  scripts/fetch-person-model.py

--package /path/to/yolo11n-seg.mlpackage reuses the benchmark export.
Both modes verify the same executable graph and weights. Metadata dates and
package UUIDs are excluded from the graph hash; no numerical operators are.
"""
import argparse
import hashlib
from importlib.metadata import version
import json
import os
from pathlib import Path
import shutil
import subprocess
import urllib.request

WEIGHT_URL = "https://github.com/ultralytics/assets/releases/download/v8.4.0/yolo11n-seg.pt"
WEIGHT_SHA = "55ed65c56c91713d23e8402371c6c49a6fd84f257f7dce452e8d70e41dcbe152"
GRAPH_SHA = "63c21165eb51d3f6c308df1a71667e2faa55c7b6b3e00117129b50c1b019d446"
COREML_WEIGHT_SHA = "8a574d6ee37d277a84103a5b50e6a25cf36defc08aaaefa999b0fc9583d05db7"


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--package", type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    work = root / "build.noindex/person-model"
    work.mkdir(parents=True, exist_ok=True)
    expected = {"coremltools": "9.0", "numpy": "2.2.6"}
    if args.package is None:
        expected.update(ultralytics="8.4.29", torch="2.7.0", torchvision="0.22.0")
    for name, exact in expected.items():
        if version(name) != exact:
            parser.error(f"{name} must be {exact}; use the isolated uv command above")
    import coremltools as ct

    package = args.package.resolve() if args.package else work / "yolo11n-seg.mlpackage"
    if args.package is None and not package.exists():
        checkpoint = work / "yolo11n-seg.pt"
        if not checkpoint.exists():
            temporary = checkpoint.with_suffix(".download")
            with urllib.request.urlopen(WEIGHT_URL, timeout=60) as response, temporary.open("wb") as output:
                shutil.copyfileobj(response, output)
            if sha(temporary) != WEIGHT_SHA:
                temporary.unlink()
                raise ValueError("YOLO11 checkpoint hash does not match the screened artifact")
            temporary.replace(checkpoint)
        if sha(checkpoint) != WEIGHT_SHA:
            raise ValueError("YOLO11 checkpoint hash does not match the screened artifact")
        os.environ["YOLO_CONFIG_DIR"] = str(work / "ultralytics-settings")
        from ultralytics import YOLO
        YOLO(str(checkpoint)).export(format="coreml", imgsz=640, half=True, nms=False, dynamic=False, device="cpu")

    spec = ct.models.MLModel(str(package), skip_model_load=True).get_spec()
    spec.description.ClearField("metadata")
    graph_sha = hashlib.sha256(spec.SerializeToString(deterministic=True)).hexdigest()
    weight_sha = sha(package / "Data/com.apple.CoreML/weights/weight.bin")
    if graph_sha != GRAPH_SHA or weight_sha != COREML_WEIGHT_SHA:
        raise ValueError("Core ML graph/weights differ from the validated benchmark export")
    compiled = work / "compiled"
    compiled.mkdir(exist_ok=True)
    subprocess.run(["xcrun", "coremlcompiler", "compile", str(package), str(compiled),
                    "--platform", "iOS", "--deployment-target", "18.0"], check=True)
    source = compiled / (package.stem + ".mlmodelc")
    destination = root / "naqi/Resources/Models/yolo11n_seg.mlmodelc"
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists():
        shutil.rmtree(destination)
    shutil.copytree(source, destination)
    provenance = dict(checkpoint_url=WEIGHT_URL, checkpoint_sha256=WEIGHT_SHA,
                      canonical_graph_sha256=graph_sha, coreml_weights_sha256=weight_sha,
                      export=dict(ultralytics="8.4.29", torch="2.7.0", coremltools="9.0", numpy="2.2.6",
                                  image=[640,640], half=True, nms=False, dynamic=False),
                      compiled_for="iOS18", compiled_asset=str(destination.relative_to(root)),
                      compiled_files=[dict(file=str(p.relative_to(destination)), sha256=sha(p))
                                      for p in sorted(destination.rglob("*")) if p.is_file()])
    (work / "provenance.json").write_text(json.dumps(provenance, indent=2) + "\n")
    print("Staged", destination)


if __name__ == "__main__":
    main()
