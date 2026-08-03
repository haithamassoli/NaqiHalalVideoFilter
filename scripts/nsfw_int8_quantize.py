"""Build the static-INT8 NSFW gate (`nsfw_mnv2_140_int8.onnx`) and prove it is trustworthy.

Produces `app/src/main/assets/models/nsfw_mnv2_140_int8.onnx` from the shipped
`nsfw_mnv2_140_f32.onnx`, per docs/video-performance-plan-v2.md §5.3:
  1. AveragePool[7,7] -> GlobalAveragePool (bit-parity; removes the only node that hard-codes 7x7,
     so the graph stays valid for symbolic H/W and ORT can fuse the head).
  2. quant_pre_process (symbolic shape inference + constant folding — quantize_static is unreliable
     without it).
  3. quantize_static(QDQ, QInt8/QInt8, per_channel=True) calibrated on 100 REAL frames.
Then evaluates INT8 vs fp32 on a DISJOINT 360-frame set: argmax agreement, single-inference
latency, NsfwGate.fires() flips, and interval recall after NsfwGate.intervals() hysteresis —
the last one is the metric that matters, because hysteresis absorbs most of the 8-bit drift.

Re-run:  python3 scripts/nsfw_int8_quantize.py            # extract + quantize + evaluate
         python3 scripts/nsfw_int8_quantize.py --eval-only # re-measure an existing artifact
Needs onnx + onnxruntime + numpy importable and ffmpeg/ffprobe on PATH, and the gitignored
qa-assets/test-video-1.webm. Frames are cached in the system temp dir (extraction is minutes,
the rest is seconds), keyed by video identity + the sampling constants below.
"""

from __future__ import annotations

import argparse
import hashlib
import math
import os
import subprocess
import tempfile
import time

import numpy as np
import onnx
import onnxruntime as ort
from onnxruntime.quantization import CalibrationDataReader, QuantFormat, QuantType, quantize_static
from onnxruntime.quantization.shape_inference import quant_pre_process

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
VIDEO = os.path.join(REPO, "qa-assets", "test-video-1.webm")
MODELS = os.path.join(REPO, "app", "src", "main", "assets", "models")
FP32 = os.path.join(MODELS, "nsfw_mnv2_140_f32.onnx")
INT8 = os.path.join(MODELS, "nsfw_mnv2_140_int8.onnx")

# --- sampling -------------------------------------------------------------------------------
# The film is split into SEGMENTS equal chunks; each contributes one eval window and one
# calibration window, disjoint BY CONSTRUCTION (eval ends at +9.0 s, calibration starts at +14 s).
# Spreading both across the whole film instead of taking one contiguous block keeps a single scene
# from dominating either the calibration ranges or the accuracy numbers.
SEGMENTS = 10
EVAL_OFFSET_MS, EVAL_PER_SEG, EVAL_STEP_MS = 2_000, 36, 200      # 360 frames at the app's 5 fps
CALIB_OFFSET_MS, CALIB_PER_SEG, CALIB_STEP_MS = 14_000, 10, 4_000  # 100 frames, 4 s apart

# --- NsfwGate.kt, reimplemented exactly (analysis/NsfwGate.kt) -------------------------------
NSFW_CLASSES = ["drawings", "hentai", "neutral", "porn", "sexy"]  # ml/Models.kt, index-locked
TABLE = {  # (thr at s=0, thr at s=100), linear in between
    "porn": (0.75, 0.10),
    "sexy": (0.90, 0.10),
    "hentai": (1.00, 0.50),
    "neutral": (0.30, 1.00),
    "drawings": (0.50, 0.50),
}
T0 = np.array([TABLE[c][0] for c in NSFW_CLASSES], np.float32)
T100 = np.array([TABLE[c][1] for c in NSFW_CLASSES], np.float32)
NSFW_IDX = [NSFW_CLASSES.index(c) for c in ("porn", "sexy", "hentai")]
SFW_IDX = [NSFW_CLASSES.index(c) for c in ("neutral", "drawings")]
PRE_MS, POST_MS = 500, 1500
DEFAULT_STRICTNESS = 50  # model/FilterOps.kt


def fires(probs: np.ndarray, strictness: int) -> np.ndarray:
    """Vectorised NsfwGate.fires over [N,5] probs: fire iff nsfwMax >= 0 AND nsfwMax > sfwMax."""
    s = min(max(strictness, 0), 100)
    thr = T0 + (T100 - T0) * np.float32(s) / np.float32(100)
    d = probs.astype(np.float32) - thr
    nsfw_max = d[:, NSFW_IDX].max(1)
    sfw_max = d[:, SFW_IDX].max(1)
    return (nsfw_max >= 0) & (nsfw_max > sfw_max)


def intervals(firings_ms, duration_ms: int):
    """NsfwGate.intervals: expand to [t-PRE, t+POST], merge overlapping/adjacent, clamp. Inclusive."""
    if len(firings_ms) == 0:
        return []
    clamp = lambda v: min(max(v, 0), duration_ms)  # noqa: E731
    s = sorted(int(t) for t in firings_ms)
    out = []
    start, end = clamp(s[0] - PRE_MS), clamp(s[0] + POST_MS)
    for t in s[1:]:
        a, b = clamp(t - PRE_MS), clamp(t + POST_MS)
        if a <= end + 1:  # overlapping or gap-free adjacent -> merge
            end = max(end, b)
        else:
            out.append((start, end))
            start, end = a, b
    out.append((start, end))
    return out


def covered_ms(spans) -> int:
    """Total length of inclusive LongRange spans (Kotlin a..b covers b - a + 1 ms)."""
    return sum(b - a + 1 for a, b in spans)


def overlap_ms(x, y) -> int:
    """Total intersection of two disjoint, time-ordered inclusive span lists."""
    total, j = 0, 0
    for a, b in x:
        while j < len(y) and y[j][1] < a:
            j += 1
        k = j
        while k < len(y) and y[k][0] <= b:
            total += min(b, y[k][1]) - max(a, y[k][0]) + 1
            k += 1
    return total


# --- frames ---------------------------------------------------------------------------------
def duration_ms(video: str) -> int:
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", video],
        check=True, capture_output=True, text=True,
    ).stdout.strip()
    return int(float(out) * 1000)


def grab(video: str, start_ms: int, count: int, step_ms: int):
    """One accurate seek per window, then `count` frames `step_ms` apart, stretched to 224x224 RGB.

    Stretch with no aspect preservation and bilinear filtering because that is exactly what
    Infer.nsfw does (`Bitmap.createScaledBitmap(frame, 224, 224, true)`).

    ponytail: ffmpeg's YUV->RGB and its `fps` resampler are not byte-identical to the app's own
    YUV walk + FrameSampler slots, and the returned timestamps are nominal (start + i*step)
    rather than decoded PTS. Ceiling: calibration ranges and absolute timestamps are approximate
    by a pixel value or two and a few ms. It does not bias the comparison — fp32 and INT8 see the
    exact same frames — and the only thing that needs true device pixels is the on-device
    validation the plan already demands. Upgrade path: dump frames from the app itself.
    """
    dur_s = (count * step_ms + step_ms // 2) / 1000.0
    raw = subprocess.run(
        ["ffmpeg", "-v", "error", "-ss", f"{start_ms / 1000:.3f}", "-i", video, "-t", f"{dur_s:.3f}",
         "-vf", f"fps={1000.0 / step_ms},scale=224:224:flags=bilinear",
         "-pix_fmt", "rgb24", "-f", "rawvideo", "-"],
        check=True, capture_output=True,
    ).stdout
    px = 224 * 224 * 3
    n = min(len(raw) // px, count)
    frames = np.frombuffer(raw, np.uint8, count=n * px).reshape(n, 224, 224, 3)
    return frames, [start_ms + i * step_ms for i in range(n)]


def load_frames(video: str):
    """(calib u8[N,224,224,3], eval u8[M,...], eval timestamps ms, video duration ms), tmp-cached."""
    st = os.stat(video)
    key = hashlib.sha1(
        f"{video}|{st.st_size}|{st.st_mtime_ns}|{SEGMENTS}|{EVAL_OFFSET_MS}|{EVAL_PER_SEG}|"
        f"{EVAL_STEP_MS}|{CALIB_OFFSET_MS}|{CALIB_PER_SEG}|{CALIB_STEP_MS}".encode()
    ).hexdigest()[:16]
    cache = os.path.join(tempfile.gettempdir(), f"naqi-nsfw-frames-{key}.npz")
    if os.path.exists(cache):
        z = np.load(cache)
        return z["calib"], z["ev"], z["ts"].tolist(), int(z["dur"])

    dur = duration_ms(video)
    seg = dur // SEGMENTS
    calib, ev, ts = [], [], []
    for k in range(SEGMENTS):
        base = k * seg
        f, _ = grab(video, base + CALIB_OFFSET_MS, CALIB_PER_SEG, CALIB_STEP_MS)
        calib.append(f)
        f, t = grab(video, base + EVAL_OFFSET_MS, EVAL_PER_SEG, EVAL_STEP_MS)
        ev.append(f)
        ts += t
        print(f"  segment {k + 1}/{SEGMENTS}: +{len(calib[-1])} calib, +{len(f)} eval")
    calib, ev = np.concatenate(calib), np.concatenate(ev)
    np.savez(cache, calib=calib, ev=ev, ts=np.array(ts, np.int64), dur=dur)
    return calib, ev, ts, dur


def nchw(u8: np.ndarray) -> np.ndarray:
    """uint8 [N,H,W,3] -> f32 [N,3,H,W] scaled 1/255, no mean/std — Infer.chwFloat's contract."""
    return np.ascontiguousarray(u8.transpose(0, 3, 1, 2)).astype(np.float32) / 255.0


# --- build ----------------------------------------------------------------------------------
class Frames(CalibrationDataReader):
    def __init__(self, name: str, x: np.ndarray):
        self.it = iter([{name: x[i:i + 1]} for i in range(len(x))])

    def get_next(self):
        return next(self.it, None)


def globalize_avgpool(src: str, dst: str) -> None:
    """AveragePool[7,7]/stride 1 on a 7x7 feature map IS a GlobalAveragePool — identical output,
    but with no baked-in kernel size, so the graph no longer asserts a spatial extent the
    quantizer would have to re-derive. Measured bit-exact here (max|delta| 0.0 over 32 real
    frames; the plan reported 7.3e-11)."""
    m = onnx.load(src)
    hits = [n for n in m.graph.node if n.op_type == "AveragePool"]
    assert len(hits) == 1, f"expected exactly one AveragePool, found {len(hits)}"
    ks = next(a.ints for a in hits[0].attribute if a.name == "kernel_shape")
    assert list(ks) == [7, 7], f"unexpected kernel_shape {list(ks)}"
    hits[0].op_type = "GlobalAveragePool"
    del hits[0].attribute[:]
    onnx.save(m, dst)


def build(calib_u8: np.ndarray, eval_u8: np.ndarray) -> None:
    work = tempfile.mkdtemp(prefix="naqi-nsfw-int8-")
    gap, pre = os.path.join(work, "gap.onnx"), os.path.join(work, "pre.onnx")

    globalize_avgpool(FP32, gap)
    x = nchw(eval_u8[:32])
    a, b = (run_all(FP32, x), run_all(gap, x))
    print(f"  GlobalAveragePool rewrite: max|delta| {np.abs(a - b).max():.3e} over 32 frames")

    # skip_symbolic_shape because only the BATCH dim is symbolic here — H/W are static 224, so
    # onnx's own shape inference resolves everything and the sympy dependency buys nothing.
    # Measured both ways on this model: symbolic gives 74 fused nodes / 347 of 360 agreeing vs
    # 75 / 346 — one node and one frame. Not worth a dependency.
    quant_pre_process(gap, pre, skip_symbolic_shape=True)
    name = ort.InferenceSession(pre, providers=["CPUExecutionProvider"]).get_inputs()[0].name
    quantize_static(
        pre, INT8, Frames(name, nchw(calib_u8)),
        quant_format=QuantFormat.QDQ,
        activation_type=QuantType.QInt8,
        weight_type=QuantType.QInt8,
        per_channel=True,
    )


def run_all(model: str, x: np.ndarray) -> np.ndarray:
    """Batch-1 inference over every frame — the app never batches (plan §5.2: batch 2 is 0.65x)."""
    s = session(model)
    name = s.get_inputs()[0].name
    return np.concatenate([s.run(None, {name: x[i:i + 1]})[0] for i in range(len(x))])


_SESSIONS: dict[str, ort.InferenceSession] = {}


def session(model: str) -> ort.InferenceSession:
    if model not in _SESSIONS:
        _SESSIONS[model] = ort.InferenceSession(model, providers=["CPUExecutionProvider"])
    return _SESSIONS[model]


def fused_ops(model: str) -> tuple[int, int]:
    """(node count, QLinearConv count) AFTER ORT's graph optimisations — the only counts that
    matter, since QDQ pairs only become integer kernels if the QDQ transformer fuses them."""
    out = os.path.join(tempfile.mkdtemp(prefix="naqi-nsfw-opt-"), "opt.onnx")
    o = ort.SessionOptions()
    o.optimized_model_filepath = out
    ort.InferenceSession(model, o, providers=["CPUExecutionProvider"])
    g = onnx.load(out).graph
    return len(g.node), sum(1 for n in g.node if n.op_type == "QLinearConv")


def bench(models: dict[str, str], x: np.ndarray, rounds: int = 9, reps: int = 10) -> dict[str, float]:
    """Best-of-`rounds` median-of-`reps` single inferences, round-robin between models so any
    thermal drift or scheduler noise hits every model equally."""
    best = {k: math.inf for k in models}
    for r in range(rounds):
        for k, path in models.items():
            s = session(path)
            name = s.get_inputs()[0].name
            t = []
            for i in range(reps):
                f = x[(r * reps + i) % len(x)][None]
                t0 = time.perf_counter()
                s.run(None, {name: f})
                t.append((time.perf_counter() - t0) * 1e3)
            best[k] = min(best[k], float(np.median(t)))
    return best


def sha256(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def evaluate(eval_u8: np.ndarray, ts: list[int], dur: int) -> None:
    x = nchw(eval_u8)
    p32, p8 = run_all(FP32, x), run_all(INT8, x)
    n = len(x)

    agree = int((p32.argmax(1) == p8.argmax(1)).sum())
    print(f"argmax agreement    {agree}/{n} = {100.0 * agree / n:.1f}%")
    print(f"max|delta prob|     {np.abs(p32 - p8).max():.4f}   mean {np.abs(p32 - p8).mean():.4f}")

    ms = bench({"fp32": FP32, "int8": INT8}, x)
    print(f"latency (best-of-9 median-of-10, CPU EP, batch 1)")
    print(f"  fp32 {ms['fp32']:.2f} ms    int8 {ms['int8']:.2f} ms    {ms['fp32'] / ms['int8']:.2f}x")
    for tag, path in (("fp32", FP32), ("int8", INT8)):
        nodes, qconv = fused_ops(path)
        print(f"  {tag}: {os.path.getsize(path) / 1e6:.2f} MB, {nodes} fused nodes, {qconv} QLinearConv")

    print(f"strict  flips/{n}  fp32 fires  int8 fires  fp32 timeline  interval recall")
    for s in (0, 25, DEFAULT_STRICTNESS, 75, 100):
        f32, f8 = fires(p32, s), fires(p8, s)
        i32 = intervals([t for t, v in zip(ts, f32) if v], dur)
        i8 = intervals([t for t, v in zip(ts, f8) if v], dur)
        tot = covered_ms(i32)
        rec = f"{100.0 * overlap_ms(i32, i8) / tot:.1f}% of {tot / 1000:.1f}s" if tot else "n/a (no fp32 fires)"
        mark = " <- default" if s == DEFAULT_STRICTNESS else ""
        print(f"{s:>6}  {int((f32 != f8).sum()):>9}  {int(f32.sum()):>10}  {int(f8.sum()):>10}  "
              f"{tot / 1000:>12.1f}s  {rec}{mark}")

    print(f"artifact  {INT8}")
    print(f"  bytes   {os.path.getsize(INT8)}")
    print(f"  sha256  {sha256(INT8)}")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--eval-only", action="store_true", help="measure the existing artifact, do not rebuild")
    args = ap.parse_args()

    print("frames…")
    calib, ev, ts, dur = load_frames(VIDEO)
    print(f"  calib {len(calib)}  eval {len(ev)}  video {dur / 1000:.1f}s")

    if not args.eval_only:
        print("quantize…")
        build(calib, ev)
    evaluate(ev, ts, dur)


if __name__ == "__main__":
    main()
