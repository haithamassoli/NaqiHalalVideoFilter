"""Convert GantMan nsfw_model (TF2 SavedModel) to ONNX (NCHW) and parity-check TF vs ORT.

Runs inside the TF venv. Usage: python gantman_tf_convert.py <workdir with unzipped release>
"""
import glob
import os
import subprocess
import sys

import numpy as np

work = sys.argv[1]
sm = None
for p in sorted(glob.glob(os.path.join(work, "**", "saved_model.pb"), recursive=True)):
    sm = os.path.dirname(p)
    break
print("saved_model dir:", sm)
print("h5 files:", glob.glob(os.path.join(work, "**", "*.h5"), recursive=True))
if not sm:
    sys.exit("no SavedModel found")

out_f32 = os.path.join(work, "nsfw_f32.onnx")
PY = sys.executable

# pass 1: plain conversion to discover the graph input name
subprocess.run(
    [PY, "-m", "tf2onnx.convert", "--saved-model", sm, "--output", out_f32, "--opset", "17"],
    check=True,
)
import onnx

m = onnx.load(out_f32)
inp = m.graph.input[0].name


def dims(v):
    return [d.dim_value or d.dim_param for d in v.type.tensor_type.shape.dim]


print("pass1 input:", inp, dims(m.graph.input[0]))

# pass 2: same conversion with the input transposed to NCHW
r = subprocess.run(
    [PY, "-m", "tf2onnx.convert", "--saved-model", sm, "--output", out_f32, "--opset", "17",
     "--inputs-as-nchw", inp],
    check=False,
)
if r.returncode != 0:
    subprocess.run(
        [PY, "-m", "tf2onnx.convert", "--saved-model", sm, "--output", out_f32, "--opset", "17",
         "--inputs-as-nchw", inp + ":0"],
        check=True,
    )
m = onnx.load(out_f32)
print("final inputs:", [(i.name, dims(i)) for i in m.graph.input])
print("final outputs:", [(o.name, dims(o)) for o in m.graph.output])

# --- parity: TF serving signature vs ORT on random [0,1] images ---
import tensorflow as tf
import onnxruntime as ort

loaded = tf.saved_model.load(sm)
sig = loaded.signatures["serving_default"]
key = list(sig.structured_input_signature[1].keys())[0]
rng = np.random.default_rng(0)
x = rng.random((8, 224, 224, 3), dtype=np.float32)
tf_arr = list(sig(**{key: tf.constant(x)}).values())[0].numpy()

sess = ort.InferenceSession(out_f32, providers=["CPUExecutionProvider"])
i0 = sess.get_inputs()[0]
feed = np.transpose(x, (0, 3, 1, 2)) if (len(i0.shape) == 4 and i0.shape[1] == 3) else x
o = sess.run(None, {i0.name: feed})[0]
print("tf-vs-onnx max|d|=%.3e argmax agree=%d/8" % (np.abs(o - tf_arr).max(), int((o.argmax(1) == tf_arr.argmax(1)).sum())))
print("row0 tf:  ", np.round(tf_arr[0], 4))
print("row0 onnx:", np.round(o[0], 4))
print("TF_CONVERT_DONE")
