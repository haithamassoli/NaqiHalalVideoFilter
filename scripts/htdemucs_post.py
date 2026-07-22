"""Post-export: dump IO, convert to f16, parity-check torch reference vs ORT f32/f16.

Run with PYTHONPATH=<demucs.onnx>/demucs-for-onnx, inside the demucs venv.
Usage: python htdemucs_post.py <out_dir_with_htdemucs.onnx>
"""
import os
import sys

import numpy as np
import onnx
import onnxruntime as ort
import torch
from onnxconverter_common import float16

from demucs.htdemucs import standalone_magnitude, standalone_spec
from demucs.pretrained import get_model

out_dir = sys.argv[1]
f32_path = os.path.join(out_dir, "htdemucs.onnx")
f16_path = os.path.join(out_dir, "htdemucs_f16.onnx")

m = onnx.load(f32_path)
def dims(v):
    return [d.dim_value or d.dim_param for d in v.type.tensor_type.shape.dim]
print("INPUTS:", [(i.name, dims(i)) for i in m.graph.input])
print("OUTPUTS:", [(o.name, dims(o)) for o in m.graph.output])
print("opset:", [f"{o.domain or 'ai.onnx'}:{o.version}" for o in m.opset_import])

m16 = float16.convert_float_to_float16(m, keep_io_types=True)
onnx.save(m16, f16_path)
print("sizes MB: f32=%.1f f16=%.1f" % (os.path.getsize(f32_path) / 1e6, os.path.getsize(f16_path) / 1e6))

# --- torch reference on deterministic synthetic audio (7.8 s stereo) ---
model = get_model("htdemucs")
core = model.models[0] if hasattr(model, "models") else model
core.eval()
sr = 44100
T = int(core.segment * sr)
print("training_length:", T)
rng = np.random.default_rng(0)
t = np.arange(T) / sr
base = (
    0.4 * np.sin(2 * np.pi * 220 * t)
    + 0.2 * np.sin(2 * np.pi * 554.37 * t)
    + 0.1 * np.sin(2 * np.pi * 110 * (t + 0.1 * np.sin(2 * np.pi * 0.5 * t)))
    + 0.05 * rng.standard_normal(T)
)
mix = np.stack([base, 0.9 * base + 0.05 * rng.standard_normal(T)]).astype(np.float32)[None]
wav = torch.from_numpy(mix)
spec = standalone_magnitude(standalone_spec(wav))
print("spec shape:", tuple(spec.shape))
with torch.no_grad():
    ref_spec, ref_wav = core(wav, spec)
ref_by_ndim = {ref_spec.ndim: ref_spec.numpy(), ref_wav.ndim: ref_wav.numpy()}

for path, tag in [(f32_path, "f32"), (f16_path, "f16")]:
    sess = ort.InferenceSession(path, providers=["CPUExecutionProvider"])
    feeds = {}
    for i in sess.get_inputs():
        feeds[i.name] = mix if len(i.shape) == 3 else spec.numpy()
    outs = sess.run(None, feeds)
    for got in outs:
        r = ref_by_ndim[got.ndim]
        num = np.linalg.norm(got - r)
        den = np.linalg.norm(r)
        kind = "spec" if got.ndim == 5 else "wave"
        print(
            "%s %s shape=%s rel=%.3e snr=%.1fdB max|d|=%.3e"
            % (tag, kind, got.shape, num / max(den, 1e-9), 20 * np.log10(den / max(num, 1e-12)), np.abs(got - r).max())
        )
    del sess
print("PARITY_DONE")
