"""Export YAMNet (AudioSet, 521 classes) to a fixed-shape ONNX for the A1 music gate.

Why this exists: htdemucs runs on every 1.95 s of audio regardless of content
(video-performance-plan-v2.md §5.5). YAMNet decides whether a chunk contains music at ~1 % of the
separator's cost, so music-free chunks can pass through bit-exact. Google publishes YAMNet as a
TF2 SavedModel under Apache-2.0; there is no official ONNX, so we convert it here.

Three deliberate deviations from a plain `tf2onnx.convert`, each of which changes the artifact:

  1. INPUT SHAPE PINNED TO 15600. The SavedModel's `waveform` input is rank-1 dynamic and frames
     internally: 0.96 s window / 0.48 s hop over a 25 ms/10 ms STFT. 15600 samples (0.975 s) is the
     smallest length that yields exactly ONE patch (96 mel frames), so the output collapses to
     [1,521] and the driver never has to reduce over a frame axis. Every other ORT session in this
     app uses fixed shapes (`NaqiModel.smokeShapes`); a symbolic dim would be the only one.
  2. OUTPUTS TRIMMED TO output_0. Upstream also emits embeddings [N,1024] and the log-mel
     spectrogram [N,64]. Both are UPSTREAM of the scores, so dropping them saves no compute — it
     just means `session.run(...)` returns exactly one tensor. Parity-checked below.
  3. NOTHING ELSE. No fp16: fp16 is what broke htdemucs on device twice (XNNPACK's fp16 depthwise
     path, then NaN), and 16 MB of f32 is not worth relitigating that.

The class map ships inside the SavedModel (`assets/yamnet_class_map.csv`), so the index ranges the
gate hardcodes are checked against the artifact's own map rather than trusted. Verified 2026-08-02
against archive sha256 b80da2a1…e5e0: 24–32 = Singing…Humming, 132–276 = Music…Scary music, 277 =
Wind. Those are the two ranges §5.5 specifies; [CLASS_MAP_ANCHORS] asserts them on every run, so a
reordered upstream map fails here instead of silently mis-firing the gate on device.

tf2onnx needs TensorFlow, which does not build on the repo's python3.14 — that one step shells out to
`uv run --python 3.11`. Everything else runs in-process on onnx/onnxruntime.

NOT BYTE-REPRODUCIBLE: two runs of tf2onnx over the same SavedModel produce numerically identical
graphs (verified bit-exact on several waveforms) with different serialized bytes, so re-running this
prints a DIFFERENT sha256. The shipped file is the authority — if you re-export, update
`NaqiModel.MUSIC_GATE.sha256` from the sha this prints, or `ModelDownloader` rejects the download.

    python3 scripts/yamnet_export.py            # writes app/src/main/assets/models/yamnet.onnx
    python3 scripts/yamnet_export.py --keep     # also leave the intermediates in build/yamnet/
"""

import csv
import hashlib
import shutil
import subprocess
import sys
import tarfile
import time
import urllib.request
from pathlib import Path

import numpy as np
import onnx
import onnxruntime as ort

# Google's YAMNet TF2 SavedModel, Apache-2.0. This unauthenticated Kaggle endpoint is what
# tfhub.dev/google/yamnet/1?tf-hub-format=compressed redirects to; both serve the same bundle.
SAVED_MODEL_URL = "https://www.kaggle.com/api/v1/models/google/yamnet/tensorFlow2/yamnet/1/download"
SAVED_MODEL_SHA256 = "b80da2a1a56926fb0767205051a200dd7b3beaf3ea1ea126c42a53943996e5e0"

# 0.975 s @ 16 kHz — see deviation (1) above. Any other length changes the output frame count.
SAMPLES = 15600
SAMPLE_RATE = 16000

# The gate score is max over these, per §5.5. 24–32 is vocal music, which sits nowhere near the
# music block; including it is what catches a-cappella singing.
MUSIC_RANGES = ((132, 276), (24, 32))
# Sentinels: if the map ever reorders, these stop being true and the ranges above are wrong.
CLASS_MAP_ANCHORS = {24: "Singing", 32: "Humming", 132: "Music", 276: "Scary music", 277: "Wind"}

ROOT = Path(__file__).resolve().parent.parent
WORK = ROOT / "build" / "yamnet"
OUT = ROOT / "app/src/main/assets/models/yamnet.onnx"


def fetch_saved_model() -> Path:
    """Download + verify + extract the SavedModel; returns the directory tf2onnx wants."""
    WORK.mkdir(parents=True, exist_ok=True)
    archive = WORK / "yamnet-tf2.tar.gz"
    if not archive.exists():
        print(f"downloading {SAVED_MODEL_URL}")
        urllib.request.urlretrieve(SAVED_MODEL_URL, archive)
    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    if digest != SAVED_MODEL_SHA256:
        raise SystemExit(f"SavedModel sha256 {digest} != expected {SAVED_MODEL_SHA256} — upstream moved")
    saved = WORK / "savedmodel"
    if not (saved / "saved_model.pb").exists():
        shutil.rmtree(saved, ignore_errors=True)
        with tarfile.open(archive) as tf:
            tf.extractall(saved)  # noqa: S202 — verified by sha256 above
    return saved


def verify_class_map(saved: Path) -> list[str]:
    """Assert the index ranges the gate hardcodes against the SavedModel's own class map.

    The map itself is deliberately NOT shipped: the gate needs two hardcoded ranges, not 15 kB of
    CSV in the APK, and this check plus the names printed by validate() are what make them auditable.
    """
    rows = list(csv.DictReader((saved / "assets/yamnet_class_map.csv").open()))
    if len(rows) != 521:
        raise SystemExit(f"class map has {len(rows)} rows, expected 521")
    names = [r["display_name"] for r in rows]
    if any(int(r["index"]) != i for i, r in enumerate(rows)):
        raise SystemExit("class map index column is not 0..520 — row order is not output order")
    for index, expected in CLASS_MAP_ANCHORS.items():
        if names[index] != expected:
            raise SystemExit(f"class {index} is {names[index]!r}, expected {expected!r} — MUSIC_RANGES is stale")
    print(f"class map verified: {len(names)} classes, anchors {CLASS_MAP_ANCHORS} hold")
    return names


def convert(saved: Path) -> Path:
    """tf2onnx in an isolated 3.11 env — TF has no python3.14 wheels, and tf2onnx 1.16 needs TF < 2.16."""
    raw = WORK / "yamnet_raw.onnx"
    if not raw.exists():
        subprocess.run(
            ["uv", "run", "--python", "3.11",
             "--with", "tensorflow==2.15.1", "--with", "tf2onnx==1.16.1",
             "--with", "onnx==1.16.1", "--with", "protobuf<5",
             "python", "-m", "tf2onnx.convert",
             "--saved-model", str(saved), "--output", str(raw), "--opset", "15"],
            check=True, cwd=WORK,
        )
    return raw


def pin_and_trim(raw: Path) -> Path:
    """Pin `waveform` to [15600] and drop the two unused outputs. See deviations (1) and (2)."""
    pinned = WORK / "yamnet_pinned.onnx"
    subprocess.run(
        [sys.executable, "-m", "onnxruntime.tools.make_dynamic_shape_fixed",
         "--input_name", "waveform", "--input_shape", str(SAMPLES), str(raw), str(pinned)],
        check=True,
    )
    model = onnx.load(pinned)
    keep = [o for o in model.graph.output if o.name == "output_0"]
    if len(keep) != 1:
        raise SystemExit(f"expected an output_0, got {[o.name for o in model.graph.output]}")
    # Both dropped outputs feed the scores, so no node becomes dead — parity is asserted in validate().
    del model.graph.output[:]
    model.graph.output.extend(keep)
    # The frame count stays symbolic in the proto because it comes out of a Range on a folded shape.
    # With the input pinned it is deterministically 1, so declare it — otherwise the file says
    # [unk,521] while ORT says [1,521], and the driver has to guess which to believe.
    # (Mutate through graph.output, not `keep`: extend() copies, so the originals are detached.)
    model.graph.output[0].type.tensor_type.shape.dim[0].dim_value = 1
    onnx.checker.check_model(model)
    OUT.parent.mkdir(parents=True, exist_ok=True)
    onnx.save(model, OUT)
    return pinned


def signals() -> dict[str, np.ndarray]:
    """Silence / white noise / a C-major triad, one 0.975 s frame each."""
    t = np.arange(SAMPLES, dtype=np.float32) / SAMPLE_RATE
    rng = np.random.default_rng(0)
    chord = np.zeros(SAMPLES, dtype=np.float32)
    for f0 in (261.63, 329.63, 392.00):  # C4 E4 G4
        # 6 Hz / 0.4 % vibrato and a 1/k harmonic stack — a bare sine reads as "Sine wave" (class 507),
        # not as music, so the test tone has to look like an instrument.
        phase = 2 * np.pi * f0 * (t + 0.004 * np.sin(2 * np.pi * 6.0 * t) / (2 * np.pi * 6.0))
        for k in (1, 2, 3, 4):
            chord += (np.sin(k * phase) / k).astype(np.float32)
    chord *= 0.2 / np.max(np.abs(chord))
    return {
        "silence": np.zeros(SAMPLES, dtype=np.float32),
        "white-noise": (0.2 * rng.standard_normal(SAMPLES)).astype(np.float32),
        "chord": chord.astype(np.float32),
    }


def gate_score(scores: np.ndarray) -> float:
    return float(max(scores[a:b + 1].max() for a, b in MUSIC_RANGES))


def validate(pinned: Path, names: list[str]) -> None:
    """Run the shipped artifact, prove the trim was lossless, and print the numbers §5.5 is judged on."""
    opts = ort.SessionOptions()
    opts.log_severity_level = 3
    shipped = ort.InferenceSession(str(OUT), opts, providers=["CPUExecutionProvider"])
    reference = ort.InferenceSession(str(pinned), opts, providers=["CPUExecutionProvider"])

    print(f"\ninput  {shipped.get_inputs()[0].name} {shipped.get_inputs()[0].shape} {shipped.get_inputs()[0].type}")
    print(f"output {shipped.get_outputs()[0].name} {shipped.get_outputs()[0].shape}")

    for name, wave in signals().items():
        scores = shipped.run(None, {"waveform": wave})[0]
        if scores.shape != (1, 521):
            raise SystemExit(f"{name}: output {scores.shape}, expected (1, 521) — SAMPLES is wrong")
        untrimmed = reference.run(None, {"waveform": wave})[0]
        if not np.array_equal(scores, untrimmed):
            raise SystemExit(f"{name}: trimming the unused outputs changed the scores")
        top = int(scores[0].argmax())
        print(f"{name:>12}  gate={gate_score(scores[0]):.4f}  top={names[top]!r} ({top}, {scores[0][top]:.3f})")

    wave = signals()["chord"]
    laps = []
    for i in range(21):
        t0 = time.perf_counter()
        shipped.run(None, {"waveform": wave})
        if i:  # drop the first — it pays for allocator warm-up
            laps.append(time.perf_counter() - t0)
    laps.sort()
    print(f"\nlatency (CPU EP, this host): median {laps[len(laps) // 2] * 1e3:.1f} ms, min {laps[0] * 1e3:.1f} ms")
    print(f"\n{OUT}  {OUT.stat().st_size} bytes")
    print(f"sha256 {hashlib.sha256(OUT.read_bytes()).hexdigest()}")


if __name__ == "__main__":
    saved = fetch_saved_model()
    OUT.parent.mkdir(parents=True, exist_ok=True)
    class_names = verify_class_map(saved)
    pinned_path = pin_and_trim(convert(saved))
    validate(pinned_path, class_names)
    if "--keep" not in sys.argv:
        shutil.rmtree(WORK, ignore_errors=True)
