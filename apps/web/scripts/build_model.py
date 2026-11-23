# Vendored from Musicless tools/build_model.py (unchanged below this header).
#
# MIT License
#
# Copyright (c) 2026 الكتب المُيسّرة
#
# Permission is hereby granted, free of charge, to any person obtaining a copy
# of this software and associated documentation files (the "Software"), to deal
# in the Software without restriction, including without limitation the rights
# to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
# copies of the Software, and to permit persons to whom the Software is
# furnished to do so, subject to the following conditions:
#
# The above copyright notice and this permission notice shall be included in all
# copies or substantial portions of the Software.
#
# THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
# IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
# FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
# AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
# LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
# OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
# SOFTWARE.

"""Build the extension's single Spleeter model file.

Merges sherpa-onnx's two Spleeter 2-stem networks (vocals and accompaniment,
MIT-licensed weights from Deezer) into one graph that outputs the vocal soft
mask directly, and stores the weights as fp16 with casts back to fp32 at load,
which halves the download without changing the fp32 arithmetic.

The mask is v / (v + 2a) of the two networks' magnitude estimates, where
Spleeter's own is v^2 / (v^2 + a^2). It removes more of the music that plays
under speech and scores better on voice quality (PESQ), at the cost of some
signal fidelity (see docs/how-it-works.md).

Usage: build_model.py <dir containing vocals.onnx and accompaniment.onnx> <out.onnx>
"""

from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import onnx
from onnx import compose, helper, numpy_helper

EPS = 1e-10
MUSIC_WEIGHT = 2.0


def prefixed(path: Path, prefix: str) -> onnx.GraphProto:
    model = onnx.load(path)
    return compose.add_prefix(model, prefix).graph


def main() -> None:
    src, out = Path(sys.argv[1]), Path(sys.argv[2])
    vocals = prefixed(src / "vocals.onnx", "v_")
    accomp = prefixed(src / "accompaniment.onnx", "a_")
    x = helper.make_tensor_value_info("x", onnx.TensorProto.FLOAT, [2, "num_splits", 512, 1024])

    nodes = []
    for graph in (vocals, accomp):
        for node in graph.node:
            node.input[:] = ["x" if name == graph.input[0].name else name for name in node.input]
            nodes.append(node)

    # mask = (v + eps/2) / (v + MUSIC_WEIGHT * a + eps)
    v, a = vocals.output[0].name, accomp.output[0].name
    nodes += [
        helper.make_node("Mul", [a, "music_weight"], ["wa"]),
        helper.make_node("Add", [v, "half_eps"], ["num"]),
        helper.make_node("Add", [v, "wa"], ["sum"]),
        helper.make_node("Add", ["sum", "eps"], ["den"]),
        helper.make_node("Div", ["num", "den"], ["mask"]),
    ]
    initializers = list(vocals.initializer) + list(accomp.initializer) + [
        numpy_helper.from_array(np.array(MUSIC_WEIGHT, np.float32), "music_weight"),
        numpy_helper.from_array(np.array(EPS / 2, np.float32), "half_eps"),
        numpy_helper.from_array(np.array(EPS, np.float32), "eps"),
    ]

    # fp16 storage for every weight tensor, cast back to fp32 when the session loads.
    casts = []
    for init in initializers:
        if init.data_type == onnx.TensorProto.FLOAT and len(init.dims) > 1:
            name = init.name
            init.CopyFrom(numpy_helper.from_array(numpy_helper.to_array(init).astype(np.float16), f"{name}_fp16"))
            casts.append(helper.make_node("Cast", [f"{name}_fp16"], [name], to=onnx.TensorProto.FLOAT))

    mask = helper.make_tensor_value_info("mask", onnx.TensorProto.FLOAT, [2, "num_splits", 512, 1024])
    graph = helper.make_graph(casts + nodes, "spleeter_2stems_mask", [x], [mask], initializers)
    model = helper.make_model(graph, opset_imports=list(onnx.load(src / "vocals.onnx").opset_import))
    model.ir_version = 8
    onnx.checker.check_model(model)
    out.parent.mkdir(parents=True, exist_ok=True)
    onnx.save(model, out)
    print(f"wrote {out} ({out.stat().st_size / 2**20:.1f} MiB)")


if __name__ == "__main__":
    main()
