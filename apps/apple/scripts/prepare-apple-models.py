#!/usr/bin/env python3
"""Build the four ONNX artifacts the Apple app actually ships."""

import argparse
import hashlib
import shutil
from pathlib import Path

import numpy as np
import onnx
from onnx import TensorProto, numpy_helper
from onnxruntime.tools.onnx_model_utils import fix_output_shapes, make_input_shape_fixed


def fixed(src: Path, dst: Path, input_name: str, shape: list[int]) -> None:
    model = onnx.load(src)
    make_input_shape_fixed(model.graph, input_name, shape)
    fix_output_shapes(model)
    onnx.checker.check_model(model)
    onnx.save(model, dst)


def demucs_fp32(src: Path, dst: Path) -> None:
    model = onnx.load(src)
    graph = model.graph
    for initializer in graph.initializer:
        if initializer.data_type == TensorProto.FLOAT16:
            initializer.CopyFrom(numpy_helper.from_array(
                numpy_helper.to_array(initializer).astype(np.float32), initializer.name))
    for value in [*graph.value_info, *graph.input, *graph.output]:
        if value.type.tensor_type.elem_type == TensorProto.FLOAT16:
            value.type.tensor_type.elem_type = TensorProto.FLOAT
    for node in graph.node:
        if node.op_type == "Cast":
            for attribute in node.attribute:
                if attribute.name == "to" and attribute.i == TensorProto.FLOAT16:
                    attribute.i = TensorProto.FLOAT
    onnx.checker.check_model(model)
    onnx.save(model, dst)


def digest(path: Path) -> str:
    sha = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            sha.update(block)
    return sha.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()
    args.destination.mkdir(parents=True, exist_ok=True)

    demucs_fp32(args.source / "htdemucs_s26_f16.onnx",
                 args.destination / "htdemucs_s26_f32.onnx")
    fixed(args.source / "nsfw_mnv2_140_f32.onnx",
          args.destination / "nsfw_mnv2_140_f32_static.onnx", "input", [1, 3, 224, 224])
    fixed(args.source / "genderage.onnx",
          args.destination / "genderage_static.onnx", "data", [1, 3, 96, 96])
    shutil.copy2(args.source / "yamnet.onnx", args.destination / "yamnet.onnx")

    shipping = {
        "htdemucs_s26_f32.onnx",
        "nsfw_mnv2_140_f32_static.onnx",
        "genderage_static.onnx",
        "yamnet.onnx",
    }
    for stale in ["htdemucs_s26_f16.onnx", "nsfw_mnv2_140_f32.onnx", "genderage.onnx"]:
        (args.destination / stale).unlink(missing_ok=True)
    for name in sorted(shipping):
        path = args.destination / name
        print(f"model {name} {path.stat().st_size / 1_000_000:.1f} MB sha256={digest(path)}")


if __name__ == "__main__":
    main()
