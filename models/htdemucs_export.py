"""Export htdemucs to ONNX at a chosen segment length (STFT/iSTFT outside the graph).

The upstream sevagh/demucs.onnx converter hardcodes the checkpoint's 7.8 s training segment.
That segment sets the ONNX input width, and htdemucs' peak working set scales with it (the
cross-domain transformer is quadratic in the frame count) — 7.8 s peaks ~3.2 GB on device,
over the PRD's 1.5 GB budget. Shortening the segment is demucs' own documented memory knob
(`--segment`); the graph is otherwise identical, so the M2 driver only needs new constants.

Run with PYTHONPATH=<demucs.onnx>/demucs-for-onnx, inside the demucs venv:
    python htdemucs_export.py <out_dir> [segment_seconds]
Then feed the same out_dir + segment to htdemucs_post.py for f16 + parity.
"""
import sys
from pathlib import Path

import torch
from demucs.htdemucs import HTDemucs, standalone_magnitude, standalone_spec
from demucs.pretrained import get_model

out_dir = Path(sys.argv[1])
segment = float(sys.argv[2]) if len(sys.argv) > 2 else 3.9
out_dir.mkdir(parents=True, exist_ok=True)

model = get_model("htdemucs")
core = model if isinstance(model, HTDemucs) else model.models[0]
core.eval()

# The only deviation from upstream: override the checkpoint's training segment before the
# training_length that fixes the exported input width is derived from it.
core.segment = segment
T = int(core.segment * core.samplerate)

wav = torch.randn(1, 2, T)
spec = standalone_magnitude(standalone_spec(wav))
print(f"segment={segment}s T={T} le={spec.shape[-1]} spec={tuple(spec.shape)}")

onnx_path = out_dir / "htdemucs.onnx"
torch.onnx.export(
    core,
    (wav, spec),
    str(onnx_path),
    export_params=True,
    opset_version=18,
    do_constant_folding=True,
    input_names=["input", "x"],
    output_names=["out_spec", "out_wave"],
)
print(f"exported {onnx_path} ({onnx_path.stat().st_size / 1e6:.1f} MB)")
