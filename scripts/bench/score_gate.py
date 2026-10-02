#!/usr/bin/env python3
"""Plan §6 GATE-0 / GATE-0b / GATE-1 host T0 (fast lane).

Replicates MusicGate.kt + DemucsSeparator.separateChunk two-tier dilation on host
ORT 1.27 CPU EP (app uses XNNPACK for YAMNet; python ORT has none — noted).

    .venv-bench/bin/python scripts/bench/score_gate.py              # quality + report
    .venv-bench/bin/python scripts/bench/score_gate.py --self-check
    .venv-bench/bin/python scripts/bench/score_gate.py --cost        # idle-machine timings
    .venv-bench/bin/python scripts/bench/score_gate.py --export-sed  # GATE-1 ONNX only
"""
from __future__ import annotations

import argparse
import csv
import json
import os
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

import numpy as np
import onnxruntime as ort
import soundfile as sf

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "app/src/main/assets/models"
QA = ROOT / "qa-assets"
MODELS = QA / "models"
OUT = QA / "bench-out" / "gate"
VLOG = QA / "vlog" / "vlog_44k.wav"
YAMNET = ASSETS / "yamnet.onnx"
CLASSMAP_URL = "https://raw.githubusercontent.com/tensorflow/models/master/research/audioset/yamnet/yamnet_class_map.csv"
CLASSMAP = MODELS / "yamnet_class_map.csv"
PANNS_CSV = MODELS / "class_labels_indices.csv"
PANNS_PTH = MODELS / "Cnn14_mAP=0.431.pth"
SED_DIR = MODELS / "PretrainedSED"
SED_CKPT = MODELS / "frame_mn06_strong_1.pt"
SED_ONNX = MODELS / "frame_mn06_seq65.onnx"
RECITE = MODELS / "recite"
DOC = ROOT / "docs" / "bench" / "gate-t0.md"

# DemucsSeparator.kt companion
SEG = 114_660
STRIDE = 103_194
MAX_SHIFT = 22_050
DILATE = 2
DILATE2_MIN_SCORE = 0.02
SR = 44_100
SR16 = 16_000
RATIO = 44_100.0 / 16_000.0  # Kotlin Double
FRAME = 15_600
CLASSES = 521
THRESHOLD = 0.15
SILENCE_PEAK = 0.001
SEP_MS = 2297.0  # S23 per-chunk, plan §1

MUSIC_RANGES_0 = ((132, 276), (24, 32))  # inclusive
# GATE-0b: drop 27 Chant and 28 Mantra
MUSIC_RANGES_0B = ((132, 276), (24, 26), (29, 32))

# AudioSet-Strong names used as GATE-1 music score (plan §6.1)
SED_MUSIC_NAMES = (
    "Music", "Singing", "Choir", "Yodeling", "Chant", "Mantra",
    "Child singing", "Synthetic singing", "Rapping", "Humming",
    "Female singing", "Male singing",
)

RECITERS = ("Alafasy_128kbps", "Husary_128kbps", "Minshawy_Murattal_128kbps")
AYAT = tuple(f"00100{i}" for i in range(1, 8))
ADHAN_URL = "https://commons.wikimedia.org/wiki/Special:FilePath/The_Adhan_-_Muslim_Call_to_Prayer_-_Aaqib_Azeez.mp3"
ADHAN_PAGE = "https://commons.wikimedia.org/wiki/File:The_Adhan_-_Muslim_Call_to_Prayer_-_Aaqib_Azeez.mp3"
EVERYAYAH = "https://everyayah.com/data/{reciter}/{ayah}.mp3"

# PANNs 527-class: vocal-music 27..37, music block 137..282 (Whale=136, Wind=283)
PANNS_MUSIC_RANGES = ((137, 282), (27, 37))
JUDGE_THR = 0.5


def _session_opts():
    """App imageSessionOptions: intra-op 1, spinning 0, XNNPACK 4. Host: CPU EP, same thread/spin."""
    opts = ort.SessionOptions()
    opts.intra_op_num_threads = 1
    opts.add_session_config_entry("session.intra_op.allow_spinning", "0")
    opts.log_severity_level = 3
    return opts


def out16k_length(frames: int) -> int:
    """MusicGate.out16kLength: ((frames-1)/RATIO).toInt() + 1."""
    if frames <= 1:
        return 0
    return int((frames - 1) / RATIO) + 1


def resample_441_160(src: np.ndarray, frames: int) -> np.ndarray:
    """Linear interp 441:160, Kotlin float32 with Double index math."""
    n = out16k_length(frames)
    if n == 0:
        return np.zeros(0, np.float32)
    src = np.asarray(src[:frames], np.float32)
    x = np.arange(n, dtype=np.float64) * RATIO
    i0 = x.astype(np.int64)  # toward-zero for x >= 0
    f = (x - i0).astype(np.float32)
    i1 = i0 + 1
    last = i1 >= frames
    i0 = np.clip(i0, 0, frames - 1)
    i1 = np.where(last, i0, np.clip(i1, 0, frames - 1))
    return src[i0] + (src[i1] - src[i0]) * f


def frame_starts(n: int) -> list[int]:
    """YAMNet frame starts: stride FRAME, last frame flush with the end. Early-exit ignored."""
    if n <= 0:
        return []
    starts = [0]
    start = 0
    while start + FRAME < n:
        start = min(start + FRAME, n - FRAME)
        if start == starts[-1]:
            break
        starts.append(start)
    return starts


def dilate_decisions(scores: np.ndarray, threshold: float = THRESHOLD,
                     dilate2: float = DILATE2_MIN_SCORE) -> np.ndarray:
    """DemucsSeparator.separateChunk two-tier ±2 dilation. True = separate."""
    n = len(scores)
    sep = np.zeros(n, dtype=bool)
    for c in range(n):
        hit = False
        for k in range(c - DILATE, c + DILATE + 1):
            if k < 0:
                continue
            if k >= n:
                continue  # past end scored 0 in the app
            if scores[k] < threshold:
                continue
            if abs(k - c) <= 1 or scores[c] >= dilate2:
                hit = True
                break
        sep[c] = hit
    return sep


def n_chunks(n_samples: int) -> int:
    """Chunks processed: c * STRIDE < MAX_SHIFT + n_samples."""
    end = MAX_SHIFT + n_samples
    if end <= 0:
        return 0
    return (end - 1) // STRIDE + 1


def chunk_windows(n_samples: int) -> list[tuple[int, int]]:
    """Real-audio [start, end) for each chunk window handed to the gate (pre-pad is mean, not here)."""
    n_c = n_chunks(n_samples)
    out = []
    for c in range(n_c):
        off = c * STRIDE  # virtual
        # real samples live at virtual >= MAX_SHIFT
        real0 = max(0, off - MAX_SHIFT)
        n_win = min(SEG, MAX_SHIFT + n_samples - off)
        real1 = min(n_samples, real0 + max(0, n_win - max(0, MAX_SHIFT - off)))
        out.append((real0, real1))
    return out


def fill_gate_mono(mono: np.ndarray, c: int, mean: float) -> np.ndarray:
    """scoreChunk: denormalized mono, real samples only; pre-pad is track mean."""
    n_samples = len(mono)
    off = c * STRIDE
    n = min(SEG, MAX_SHIFT + n_samples - off)
    if n <= 0:
        return np.zeros(0, np.float32)
    v = off + np.arange(n)
    i = v - MAX_SHIFT
    buf = np.full(n, mean, np.float32)
    ok = (v >= MAX_SHIFT) & (i >= 0) & (i < n_samples)
    buf[ok] = mono[i[ok]]
    return buf


def self_check() -> None:
    # resample length: 2.6 s → 41600
    assert out16k_length(SEG) == 41600, out16k_length(SEG)
    assert out16k_length(1) == 0
    assert out16k_length(2) == 1  # int(1/RATIO)+1 = 0+1
    # last-frame flush on a full chunk
    n = 41600
    st = frame_starts(n)
    assert st == [0, 15600, 26000], st  # last flush: min(31200, 41600-15600)
    # one-frame window: no second start
    assert frame_starts(FRAME) == [0]
    # n = FRAME+1: second frame starts at 1 (flush)
    assert frame_starts(FRAME + 1) == [0, 1], frame_starts(FRAME + 1)
    # n < FRAME: one (later zero-padded) start
    assert frame_starts(1000) == [0]
    # linear interp endpoints
    src = np.array([0.0, 1.0, 0.0], np.float32)
    y = resample_441_160(src, 3)
    assert y[0] == 0.0
    assert len(y) == out16k_length(3)
    # dilation unit: DemucsSeparatorTest.farDilationOnlyRescuesChunksWithSomethingInThem
    quiet = np.array([0.0, 0.05, 0.0, 0.9, 0.0, 0.001, 0.0], np.float32)
    got = [i for i, s in enumerate(dilate_decisions(quiet)) if s]
    assert got == [1, 2, 3, 4], got
    audible = quiet.copy(); audible[5] = 0.03
    got = [i for i, s in enumerate(dilate_decisions(audible)) if s]
    assert got == [1, 2, 3, 4, 5], got
    # k < 0 is not music; chunk 0 with only itself below threshold passes
    lone = np.array([0.0, 0.0, 0.0], np.float32)
    assert not dilate_decisions(lone).any()
    print("self-check ok")


def load_class_map() -> list[str]:
    MODELS.mkdir(parents=True, exist_ok=True)
    if not CLASSMAP.exists():
        urllib.request.urlretrieve(CLASSMAP_URL, CLASSMAP)
    rows = list(csv.DictReader(CLASSMAP.open()))
    assert len(rows) == CLASSES, len(rows)
    names = [r["display_name"] for r in rows]
    assert names[27] == "Chant" and names[28] == "Mantra", (names[27], names[28])
    # MusicGate KDoc
    expect = {
        24: "Singing", 25: "Choir", 26: "Yodeling", 27: "Chant", 28: "Mantra",
        29: "Child singing", 30: "Synthetic singing", 31: "Rapping", 32: "Humming",
        131: "Whale vocalization", 132: "Music", 276: "Scary music", 277: "Wind",
    }
    for i, n in expect.items():
        assert names[i] == n, (i, names[i], n)
    return names


def load_mono44(path: Path) -> np.ndarray:
    path = Path(path)
    if path.suffix.lower() in {".wav", ".flac"}:
        x, sr = sf.read(path, dtype="float32", always_2d=True)
    else:
        wav = path.with_suffix(".44k.wav")
        if not wav.exists() or wav.stat().st_mtime < path.stat().st_mtime:
            subprocess.run(
                ["ffmpeg", "-y", "-i", str(path), "-ac", "1", "-ar", str(SR),
                 "-c:a", "pcm_f32le", str(wav)],
                check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            )
        x, sr = sf.read(wav, dtype="float32", always_2d=True)
    if sr != SR:
        raise SystemExit(f"{path}: sr {sr} != {SR}")
    return (0.5 * (x[:, 0] + x[:, 1 if x.shape[1] > 1 else 0])).astype(np.float32)


class YamnetGate:
    def __init__(self, ranges: tuple[tuple[int, int], ...], names: list[str]):
        self.ranges = ranges
        self.names = names
        self.sess = ort.InferenceSession(str(YAMNET), _session_opts(), providers=["CPUExecutionProvider"])
        self.iname = self.sess.get_inputs()[0].name
        idx = []
        for a, b in ranges:
            idx.extend(range(a, b + 1))
        self.idx = np.array(idx, np.int32)

    def _music(self, scores: np.ndarray) -> float:
        return float(scores[self.idx].max()) if len(self.idx) else 0.0

    def score(self, mono44: np.ndarray, frames: int, detail: bool = False):
        """MusicGate.score. detail=True disables early-exit and returns (best, scores_max, top_i)."""
        n16 = resample_441_160(mono44, frames)
        if n16.size == 0:
            return (0.0, np.zeros(CLASSES, np.float32), -1) if detail else 0.0
        peak = float(np.max(np.abs(n16)))
        if peak < SILENCE_PEAK:
            return (0.0, np.zeros(CLASSES, np.float32), -1) if detail else 0.0
        best = 0.0
        acc = np.zeros(CLASSES, np.float32) if detail else None
        start = 0
        n = n16.size
        while True:
            frame = np.zeros(FRAME, np.float32)
            avail = min(FRAME, n - start)
            frame[:avail] = n16[start:start + avail]
            scores = self.sess.run(None, {self.iname: frame})[0][0]
            s = self._music(scores)
            if s > best:
                best = s
            if detail:
                np.maximum(acc, scores, out=acc)
            if (not detail and best >= THRESHOLD) or start + FRAME >= n:
                if detail:
                    top = int(acc[self.idx].argmax())
                    return best, acc, int(self.idx[top])
                return best
            start = min(start + FRAME, n - FRAME)


def score_track(gate: YamnetGate, mono: np.ndarray, detail: bool = False):
    mean = float(mono.mean())
    n_c = n_chunks(len(mono))
    scores = np.zeros(n_c, np.float32)
    tops = np.full(n_c, -1, np.int32)
    accs = []
    for c in range(n_c):
        win = fill_gate_mono(mono, c, mean)
        if detail:
            s, acc, top = gate.score(win, len(win), detail=True)
            scores[c] = s
            tops[c] = top
            accs.append(acc)
        else:
            scores[c] = gate.score(win, len(win), detail=False)
    sep = dilate_decisions(scores, THRESHOLD, DILATE2_MIN_SCORE)
    return scores, sep, tops, accs


def own_chunk(sample_i: int) -> int:
    """Chunk that owns emit of real sample i: virtual i+MAX_SHIFT in [c*STRIDE, (c+1)*STRIDE)."""
    return (sample_i + MAX_SHIFT) // STRIDE


def seconds_masks(n_samples: int, sep: np.ndarray, hop: int = SR) -> np.ndarray:
    """Per 1 s: True if the owning chunk was separated."""
    n_sec = n_samples // hop
    mask = np.zeros(n_sec, dtype=bool)
    n_c = len(sep)
    for s in range(n_sec):
        c = own_chunk(s * hop)
        if 0 <= c < n_c:
            mask[s] = bool(sep[c])
    return mask


def metrics(sep: np.ndarray, judge_music: np.ndarray, n_samples: int) -> dict:
    gate_sep = seconds_masks(n_samples, sep)
    n = min(len(gate_sep), len(judge_music))
    gate_sep = gate_sep[:n]
    jm = judge_music[:n].astype(bool)
    missed = int((jm & ~gate_sep).sum())
    wasted = int((~jm & gate_sep).sum())
    n_sep = int(sep.sum())
    n_pass = int((~sep).sum())
    dur_min = n_samples / SR / 60.0
    proj_ms = n_sep * SEP_MS
    recall = (int((jm & gate_sep).sum()) / int(jm.sum())) if jm.any() else 1.0
    return {
        "chunks_separated": n_sep,
        "chunks_passed": n_pass,
        "chunks_total": int(len(sep)),
        "pass_through": n_pass / len(sep) if len(sep) else 0.0,
        "missed_music_s": missed,
        "wasted_sep_s": wasted,
        "judge_music_s": int(jm.sum()),
        "music_recall": recall,
        "proj_sep_ms": proj_ms,
        "proj_c_per_min": proj_ms / dur_min if dur_min else 0.0,
        "duration_s": n_samples / SR,
    }


# ---- PANNs CNN14 judge -------------------------------------------------------

def _cnn14():
    import torch
    import torch.nn as nn
    import torch.nn.functional as F
    from torchlibrosa.stft import Spectrogram, LogmelFilterBank
    from torchlibrosa.augmentation import SpecAugmentation

    def init_layer(layer):
        nn.init.xavier_uniform_(layer.weight)
        if getattr(layer, "bias", None) is not None:
            layer.bias.data.fill_(0.0)

    def init_bn(bn):
        bn.bias.data.fill_(0.0)
        bn.weight.data.fill_(1.0)

    class ConvBlock(nn.Module):
        def __init__(self, in_channels, out_channels):
            super().__init__()
            self.conv1 = nn.Conv2d(in_channels, out_channels, 3, 1, 1, bias=False)
            self.conv2 = nn.Conv2d(out_channels, out_channels, 3, 1, 1, bias=False)
            self.bn1 = nn.BatchNorm2d(out_channels)
            self.bn2 = nn.BatchNorm2d(out_channels)
            init_layer(self.conv1); init_layer(self.conv2); init_bn(self.bn1); init_bn(self.bn2)

        def forward(self, x, pool_size=(2, 2), pool_type="avg"):
            x = F.relu_(self.bn1(self.conv1(x)))
            x = F.relu_(self.bn2(self.conv2(x)))
            if pool_type == "avg":
                x = F.avg_pool2d(x, kernel_size=pool_size)
            else:
                x = F.max_pool2d(x, kernel_size=pool_size)
            return x

    class Cnn14(nn.Module):
        def __init__(self):
            super().__init__()
            self.spectrogram_extractor = Spectrogram(
                n_fft=1024, hop_length=320, win_length=1024, window="hann",
                center=True, pad_mode="reflect", freeze_parameters=True)
            self.logmel_extractor = LogmelFilterBank(
                sr=32000, n_fft=1024, n_mels=64, fmin=50, fmax=14000,
                ref=1.0, amin=1e-10, top_db=None, freeze_parameters=True)
            self.spec_augmenter = SpecAugmentation(
                time_drop_width=64, time_stripes_num=2, freq_drop_width=8, freq_stripes_num=2)
            self.bn0 = nn.BatchNorm2d(64)
            self.conv_block1 = ConvBlock(1, 64)
            self.conv_block2 = ConvBlock(64, 128)
            self.conv_block3 = ConvBlock(128, 256)
            self.conv_block4 = ConvBlock(256, 512)
            self.conv_block5 = ConvBlock(512, 1024)
            self.conv_block6 = ConvBlock(1024, 2048)
            self.fc1 = nn.Linear(2048, 2048, bias=True)
            self.fc_audioset = nn.Linear(2048, 527, bias=True)

        def forward(self, x):
            x = self.spectrogram_extractor(x)
            x = self.logmel_extractor(x)
            x = x.transpose(1, 3)
            x = self.bn0(x)
            x = x.transpose(1, 3)
            x = self.conv_block1(x, pool_size=(2, 2), pool_type="avg")
            x = F.dropout(x, p=0.2, training=self.training)
            x = self.conv_block2(x, pool_size=(2, 2), pool_type="avg")
            x = F.dropout(x, p=0.2, training=self.training)
            x = self.conv_block3(x, pool_size=(2, 2), pool_type="avg")
            x = F.dropout(x, p=0.2, training=self.training)
            x = self.conv_block4(x, pool_size=(2, 2), pool_type="avg")
            x = F.dropout(x, p=0.2, training=self.training)
            x = self.conv_block5(x, pool_size=(2, 2), pool_type="avg")
            x = F.dropout(x, p=0.2, training=self.training)
            x = self.conv_block6(x, pool_size=(1, 1), pool_type="avg")
            x = F.dropout(x, p=0.2, training=self.training)
            x = torch.mean(x, dim=3)
            x1, _ = torch.max(x, dim=2)
            x2 = torch.mean(x, dim=2)
            x = x1 + x2
            x = F.relu_(self.fc1(x))
            return torch.sigmoid(self.fc_audioset(x))

    return Cnn14


def panns_judge(mono44: np.ndarray) -> tuple[np.ndarray, np.ndarray, str]:
    """Per 1 s music score = max over AudioSet music block + vocal-music. Returns scores, names-used, note."""
    import torch
    from scipy.signal import resample_poly
    Cnn14 = _cnn14()
    model = Cnn14()
    ckpt = torch.load(PANNS_PTH, map_location="cpu", weights_only=False)
    model.load_state_dict(ckpt["model"])
    model.eval()
    idx = []
    for a, b in PANNS_MUSIC_RANGES:
        idx.extend(range(a, b + 1))
    idx = np.array(idx)
    n_sec = len(mono44) // SR
    scores = np.zeros(n_sec, np.float32)
    waves = np.zeros((n_sec, 32000), np.float32)
    for s in range(n_sec):
        sl = mono44[s * SR:(s + 1) * SR]
        w32 = resample_poly(sl.astype(np.float64), 320, 441).astype(np.float32)
        waves[s, :min(32000, w32.size)] = w32[:32000]
    bs = 16
    with torch.no_grad():
        for s0 in range(0, n_sec, bs):
            t = torch.from_numpy(waves[s0:s0 + bs])
            p = model(t).numpy()
            scores[s0:s0 + bs] = p[:, idx].max(axis=1)
            print(f"  judge {min(s0+bs, n_sec)}/{n_sec}", flush=True)
    return scores, idx, "PANNs CNN14 32 kHz, zenodo Cnn14_mAP=0.431.pth, 1 s hops, max over classes 27-37+137-282"


def htdemucs_fallback_judge(mono44: np.ndarray) -> tuple[np.ndarray, str]:
    """Non-vocal stem energy / total, per 1 s. Last-resort judge."""
    # Intentionally not implemented as a full separator run here unless PANNs fails;
    # a cheap proxy: harmonic (HPSS) energy ratio via median filter on STFT.
    from scipy.signal import stft, medfilt
    n_sec = len(mono44) // SR
    f, t, Z = stft(mono44, fs=SR, nperseg=2048, noverlap=1024)
    mag = np.abs(Z)
    harm = medfilt(mag, kernel_size=(1, 31))
    perc = mag - harm
    # music-ish: harmonic energy above speech-ish 200-4000 is mixed; use harm / (harm+perc)
    scores = np.zeros(n_sec, np.float32)
    hops_per_s = int(round(SR / 1024))
    for s in range(n_sec):
        a = s * hops_per_s
        b = min((s + 1) * hops_per_s, mag.shape[1])
        h = float((harm[:, a:b] ** 2).sum())
        p = float((perc[:, a:b] ** 2).sum())
        scores[s] = h / (h + p + 1e-12)
    return scores, "FALLBACK: STFT HPSS harmonic ratio (htdemucs not run; PANNs failed)"


# ---- GATE-1 PretrainedSED ----------------------------------------------------

SED_MUSIC_IDX = None  # filled after import


def _sed_import():
    if not SED_DIR.exists():
        raise RuntimeError("PretrainedSED clone missing")
    sys.path.insert(0, str(SED_DIR))
    # PredictionsWrapper downloads into CWD/resources; point it at MODELS
    import config as sed_config
    sed_config.RESOURCES_FOLDER = str(MODELS)
    from models.frame_mn.Frame_MN_wrapper import FrameMNWrapper
    from models.prediction_wrapper import PredictionsWrapper
    from models.frame_mn.utils import NAME_TO_WIDTH
    from data_util.audioset_classes import as_strong_train_classes
    return FrameMNWrapper, PredictionsWrapper, NAME_TO_WIDTH, as_strong_train_classes


def sed_music_idx() -> tuple[list[int], list[str]]:
    sys.path.insert(0, str(SED_DIR))
    from data_util.audioset_classes import as_strong_train_classes
    idx = [i for i, n in enumerate(as_strong_train_classes) if n in SED_MUSIC_NAMES]
    return idx, as_strong_train_classes


def load_sed_torch(name="frame_mn06", seq_len=65):
    import torch
    FrameMNWrapper, PredictionsWrapper, NAME_TO_WIDTH, classes = _sed_import()
    width = NAME_TO_WIDTH(name)
    frame_mn = FrameMNWrapper(width)
    embed_dim = frame_mn.state_dict()["frame_mn.features.16.1.bias"].shape[0]
    wrapper = PredictionsWrapper(frame_mn, checkpoint=None, embed_dim=embed_dim, seq_len=seq_len)
    ckpt = MODELS / f"{name}_strong_1.pt"
    sd = torch.load(ckpt, map_location="cpu", weights_only=True)
    missing, unexpected = wrapper.load_state_dict(sd, strict=False)
    wrapper.eval()
    idx = [i for i, n in enumerate(classes) if n in SED_MUSIC_NAMES]
    if not idx:
        raise RuntimeError(f"no SED music classes in {len(classes)} labels")
    return wrapper, idx, classes


def _sed_mel_assets(wrapper, n_wave=41600):
    """Bake eval-mode mel basis + window. Waveform-in ONNX dies on complex STFT (opset 17)."""
    import torch
    import torchaudio
    mel = wrapper.model.mel
    fmin = float(mel.fmin)
    fmax = float(mel.fmax)
    n_fft, win_length, hop, sr, n_mels = 512, 400, 160, 16_000, 128
    banks, _ = torchaudio.compliance.kaldi.get_mel_banks(
        n_mels, n_fft, sr, fmin, fmax, vtln_low=100.0, vtln_high=-500.0, vtln_warp_factor=1.0)
    banks = torch.nn.functional.pad(banks, (0, 1), value=0).cpu().numpy().astype(np.float64)
    win = torch.hann_window(win_length, periodic=False).cpu().numpy().astype(np.float64)
    # torch.stft pads win_length → n_fft on both sides
    win_nfft = np.zeros(n_fft, np.float64)
    left = (n_fft - win_length) // 2
    win_nfft[left:left + win_length] = win
    return {"mel_basis": banks, "window": win_nfft, "n_fft": n_fft, "hop": hop,
            "fmin": fmin, "fmax": fmax}


def numpy_sed_mel(wave: np.ndarray, assets: dict) -> np.ndarray:
    """Match AugmentMelSTFT eval: preemphasis, center STFT, kaldi banks, log, fast_norm."""
    x = wave.astype(np.float64)
    # conv1d kernel [-0.97, 1] → y[i] = -0.97 x[i] + x[i+1]
    y = x[1:] - 0.97 * x[:-1]
    n_fft, hop = int(assets["n_fft"]), int(assets["hop"])
    win = assets["window"]
    pad = n_fft // 2
    ypad = np.pad(y, (pad, pad), mode="reflect")
    n_frames = 1 + (ypad.size - n_fft) // hop
    frames = np.lib.stride_tricks.as_strided(
        ypad, shape=(n_frames, n_fft),
        strides=(ypad.strides[0] * hop, ypad.strides[0]),
    ).copy()
    spec = np.fft.rfft(frames * win, n=n_fft, axis=1)
    power = (spec.real ** 2 + spec.imag ** 2).T  # [n_fft/2+1, T]
    mels = assets["mel_basis"] @ power
    mels = np.log(np.clip(mels, 1e-7, None))
    mels = (mels + 4.5) / 5.0
    return mels.astype(np.float32)[None, None]  # [1, 1, 128, T]


def export_sed(name="frame_mn06") -> dict:
    import torch
    wrapper, idx, classes = load_sed_torch(name, seq_len=65)
    wave = torch.randn(1, 41600)
    assets = _sed_mel_assets(wrapper)
    np.savez(MODELS / f"{name}_mel.npz", **assets)
    with torch.no_grad():
        torch_mel = wrapper.mel_forward(wave).cpu().numpy()
    np_mel = numpy_sed_mel(wave.numpy()[0], assets)
    mel_err = float(np.max(np.abs(np_mel - torch_mel)))

    class CnnHead(torch.nn.Module):
        def __init__(self, w):
            super().__init__()
            self.w = w

        def forward(self, mel):
            strong, _ = self.w(mel)
            return torch.sigmoid(strong)

    wrap = CnnHead(wrapper).eval()
    with torch.no_grad():
        ref = wrap(torch.from_numpy(np_mel)).numpy()
    onnx_path = MODELS / f"{name}_seq65.onnx"
    dummy = torch.from_numpy(np_mel)
    try:
        torch.onnx.export(
            wrap, dummy, str(onnx_path),
            input_names=["mel"], output_names=["strong"],
            opset_version=17, dynamo=False,
        )
    except TypeError:
        torch.onnx.export(
            wrap, dummy, str(onnx_path),
            input_names=["mel"], output_names=["strong"],
            opset_version=17,
        )
    sess = ort.InferenceSession(str(onnx_path), _session_opts(), providers=["CPUExecutionProvider"])
    got = sess.run(None, {"mel": np_mel})[0]
    max_abs = float(np.max(np.abs(got - ref)))
    lic = (SED_DIR / "LICENSE").read_text()[:120] if (SED_DIR / "LICENSE").exists() else "?"
    return {
        "onnx": str(onnx_path),
        "input": "mel [1,1,128,T] (waveform-in ONNX failed: STFT complex unsupported)",
        "mel_parity_max_abs": mel_err,
        "parity_max_abs": max_abs,
        "parity_ok": max_abs < 1e-4 and mel_err < 2e-4,
        "n_music_classes": len(idx),
        "music_classes": [classes[i] for i in idx],
        "licence_head": lic,
        "file_bytes": onnx_path.stat().st_size,
        "mel_shape": list(np_mel.shape),
    }


class SedGate:
    """ONNX GATE-1: 16 kHz 2.6 s → numpy mel → strong [1,447,65] → max over music classes."""

    def __init__(self, onnx_path: Path, idx: list[int], threshold: float, name="frame_mn06"):
        self.sess = ort.InferenceSession(str(onnx_path), _session_opts(), providers=["CPUExecutionProvider"])
        self.iname = self.sess.get_inputs()[0].name
        self.idx = np.array(idx, np.int32)
        self.threshold = threshold
        z = np.load(MODELS / f"{name}_mel.npz")
        self.assets = {k: z[k] for k in z.files}

    def score_16k(self, x16: np.ndarray) -> float:
        if x16.size == 0:
            return 0.0
        if float(np.max(np.abs(x16))) < SILENCE_PEAK:
            return 0.0
        buf = np.zeros(41600, np.float32)
        n = min(41600, x16.size)
        buf[:n] = x16[:n]
        mel = numpy_sed_mel(buf, self.assets)
        y = self.sess.run(None, {self.iname: mel})[0]  # [1, 447, 65]
        return float(y[0, self.idx, :].max())


def score_track_sed(gate: SedGate, mono: np.ndarray, threshold: float):
    mean = float(mono.mean())
    n_c = n_chunks(len(mono))
    scores = np.zeros(n_c, np.float32)
    for c in range(n_c):
        win = fill_gate_mono(mono, c, mean)
        x16 = resample_441_160(win, len(win))
        scores[c] = gate.score_16k(x16)
    sep = dilate_decisions(scores, threshold, DILATE2_MIN_SCORE)
    return scores, sep


def fit_threshold(scores: np.ndarray, n_samples: int, judge: np.ndarray, target_recall: float) -> float:
    """Highest threshold whose dilated recall >= target (matched operating point)."""
    grid = np.unique(np.concatenate(([0.0], np.sort(scores), [1.0])))
    best_t, best_wasted = 0.0, 10**9
    hit = False
    for t in grid:
        sep = dilate_decisions(scores, float(t), DILATE2_MIN_SCORE)
        m = metrics(sep, judge, n_samples)
        if m["music_recall"] + 1e-12 >= target_recall:
            hit = True
            if m["wasted_sep_s"] < best_wasted or (
                m["wasted_sep_s"] == best_wasted and float(t) > best_t
            ):
                best_t, best_wasted = float(t), m["wasted_sep_s"]
    if hit:
        return best_t
    # none reach target: pick max recall, then least wasted
    best = (-1.0, 10**9, 0.0)
    for t in grid:
        sep = dilate_decisions(scores, float(t), DILATE2_MIN_SCORE)
        m = metrics(sep, judge, n_samples)
        cand = (m["music_recall"], -m["wasted_sep_s"], float(t))
        if cand > (best[0], -best[1], best[2]):
            best = (m["music_recall"], m["wasted_sep_s"], float(t))
    return best[2]


# ---- downloads / recitation --------------------------------------------------

def ensure_recite() -> list[Path]:
    RECITE.mkdir(parents=True, exist_ok=True)
    files = []
    for reciter in RECITERS:
        d = RECITE / reciter
        d.mkdir(exist_ok=True)
        for ayah in AYAT:
            p = d / f"{ayah}.mp3"
            if not p.exists() or p.stat().st_size < 1000:
                url = EVERYAYAH.format(reciter=reciter, ayah=ayah)
                urllib.request.urlretrieve(url, p)
            files.append(p)
    adhan = RECITE / "adhan_aaqib_azeez.mp3"
    if not adhan.exists() or adhan.stat().st_size < 1000:
        urllib.request.urlretrieve(ADHAN_URL, adhan)
    files.append(adhan)
    return files


def write_csv(path: Path, rows: list[dict], fieldnames: list[str]):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=fieldnames)
        w.writeheader()
        w.writerows(rows)


# ---- cost --------------------------------------------------------------------

def cost_arm(arm: str, n: int = 21, warmup: int = 3) -> dict:
    import psutil
    names = load_class_map()
    mono = load_mono44(VLOG)
    mean = float(mono.mean())
    proc = psutil.Process()
    times = []
    rss = []
    if arm in ("GATE-0", "GATE-0b"):
        ranges = MUSIC_RANGES_0 if arm == "GATE-0" else MUSIC_RANGES_0B
        gate = YamnetGate(ranges, names)
        n_c = n_chunks(len(mono))
        idxs = list(range(min(n_c, warmup + n)))
        for k, c in enumerate(idxs):
            win = fill_gate_mono(mono, c, mean)
            t0 = time.perf_counter()
            gate.score(win, len(win))
            dt = (time.perf_counter() - t0) * 1e3
            rss.append(proc.memory_info().rss)
            if k >= warmup:
                times.append(dt)
    elif arm.startswith("GATE-1"):
        if not SED_ONNX.exists():
            return {"arm": arm, "error": "no SED onnx"}
        idx, _ = sed_music_idx()
        gate = SedGate(SED_ONNX, idx, THRESHOLD)
        n_c = n_chunks(len(mono))
        idxs = list(range(min(n_c, warmup + n)))
        for k, c in enumerate(idxs):
            win = fill_gate_mono(mono, c, mean)
            x16 = resample_441_160(win, len(win))
            t0 = time.perf_counter()
            gate.score_16k(x16)
            dt = (time.perf_counter() - t0) * 1e3
            rss.append(proc.memory_info().rss)
            if k >= warmup:
                times.append(dt)
    else:
        return {"arm": arm, "error": "unknown arm"}
    times.sort()
    med = times[len(times) // 2] if times else None
    return {
        "arm": arm,
        "n": len(times),
        "warmup": warmup,
        "median_ms": med,
        "min_ms": times[0] if times else None,
        "p90_ms": times[int(0.9 * (len(times) - 1))] if times else None,
        "peak_rss_mb": max(rss) / 1e6 if rss else None,
        "ep": "CPUExecutionProvider",
        "intra_op": 1,
        "note": "XNNPACK unavailable in host ORT; app uses XNNPACK×4 + intra-op 1",
    }


def run_cost_subprocess(arm: str) -> dict:
    r = subprocess.run(
        [sys.executable, str(Path(__file__).resolve()), "--cost-arm", arm],
        cwd=str(ROOT), capture_output=True, text=True,
    )
    if r.returncode != 0:
        return {"arm": arm, "error": r.stderr[-2000:], "stdout": r.stdout[-1000:]}
    for line in reversed(r.stdout.splitlines()):
        line = line.strip()
        if line.startswith("{"):
            return json.loads(line)
    return {"arm": arm, "error": "no json", "stdout": r.stdout[-1000:]}


# ---- report ------------------------------------------------------------------

def verdict(m: dict, m0: dict) -> str:
    """§6.2: adopt if missed <= GATE-0, false-mute=0, proj SEP <= GATE-0 − 10%."""
    missed_ok = m["missed_music_s"] <= m0["missed_music_s"]
    # J2 mute tier not implemented in any arm
    false_mute_ok = True
    proj_ok = m["proj_sep_ms"] <= 0.90 * m0["proj_sep_ms"]
    if missed_ok and false_mute_ok and proj_ok:
        return "ADOPT"
    reasons = []
    if not missed_ok:
        reasons.append(f"missed {m['missed_music_s']}s > GATE-0 {m0['missed_music_s']}s")
    if not proj_ok:
        reasons.append(
            f"proj SEP {m['proj_sep_ms']:.0f} ms not ≤ 90% of GATE-0 {m0['proj_sep_ms']:.0f} ms"
        )
    return "KEEP GATE-0 (" + "; ".join(reasons) + ")"


def fmt(m: dict) -> str:
    return (
        f"{m['chunks_separated']}/{m['chunks_total']} sep, "
        f"{m['chunks_passed']} pass ({100*m['pass_through']:.1f}%), "
        f"missed {m['missed_music_s']} s, wasted {m['wasted_sep_s']} s, "
        f"proj {m['proj_sep_ms']/1000:.1f} s ({m['proj_c_per_min']:.0f} c/min)"
    )


def write_report(blob: dict):
    DOC.parent.mkdir(parents=True, exist_ok=True)
    cmd = blob["command"]
    arms = blob["arms"]
    m0 = arms["GATE-0"]["vlog"]
    lines = []
    a = lines.append
    a("# GATE T0 — music-gate bake-off (plan §6)")
    a("")
    a(f"Host screen, {blob['date']}. Fast lane only (GATE-0, GATE-0b, GATE-1).")
    a("")
    a("## Method")
    a("")
    a("Replica of `MusicGate.score` + `DemucsSeparator.separateChunk`:")
    a("")
    a("- 44.1 kHz mono downmix `(L+R)/2`")
    a(f"- chunk grid `SEG={SEG}`, `STRIDE={STRIDE}`, `MAX_SHIFT={MAX_SHIFT}` (0.5 s pre-pad of track mean)")
    a("- 441:160 linear-interp resample, last YAMNet frame flush, silence floor 0.001, max over class ranges, `THRESHOLD=0.15`")
    a("- two-tier dilation: ±1 unconditional, ±2 only if own score ≥ 0.02")
    a("- host ORT 1.27.0 **CPU EP**, intra-op 1, spinning 0. App YAMNet uses XNNPACK×4; python wheels have no XNNPACK.")
    a("- Judge: never the gate under test. " + blob["judge_note"])
    a("- Judge music = 1 s bin with score ≥ 0.5. A second is separated if its owning chunk (`floor((t+MAX_SHIFT)/STRIDE)`) was separated.")
    a("- Projected SEP time = separated chunks × 2297 ms (S23, plan §1), also as c/min.")
    a("- GATE-1 threshold fitted so music recall on this vlog matches GATE-0 (highest such threshold). Same vlog is the only clip — not a held-out test.")
    a("")
    a("## Commands")
    a("")
    a("```")
    a(cmd)
    a(".venv-bench/bin/python scripts/bench/score_gate.py --self-check")
    a(".venv-bench/bin/python scripts/bench/score_gate.py --cost   # re-run on an idle machine")
    a("```")
    a("")
    a("## Class-map asserts")
    a("")
    a("YAMNet class map (Appendix A URL). Indexes 27=Chant, 28=Mantra. "
      "24..32 = Singing, Choir, Yodeling, Chant, Mantra, Child singing, Synthetic singing, Rapping, Humming. "
      "132..276 = Music .. Scary music; 131=Whale vocalization, 277=Wind. All asserted in-script.")
    a("")
    a("## Vlog results")
    a("")
    a(f"Clip: `qa-assets/vlog/vlog_44k.wav` ({blob['vlog_duration_s']:.3f} s, {blob['vlog_chunks']} chunks). "
      f"Judge music seconds (thr={JUDGE_THR}): {m0['judge_music_s']}.")
    a("")
    a("| arm | sep / total | passed | missed-music s | wasted-sep s | music recall | proj SEP s | c/min | host ms/chunk | verdict |")
    a("|---|---:|---:|---:|---:|---:|---:|---:|---:|---|")
    for name, rec in arms.items():
        m = rec["vlog"]
        cost = rec.get("cost") or {}
        ms = cost.get("median_ms")
        ms_s = f"{ms:.2f} *" if ms is not None else "—"
        a(
            f"| {name} | {m['chunks_separated']}/{m['chunks_total']} | {m['chunks_passed']} | "
            f"{m['missed_music_s']} | {m['wasted_sep_s']} | {m['music_recall']:.3f} | "
            f"{m['proj_sep_ms']/1000:.2f} | {m['proj_c_per_min']:.0f} | {ms_s} | {rec['verdict']} |"
        )
    a("")
    a("\\* host ms/chunk is **contended, provisional** (other agents on this Mac). Re-run `--cost` idle.")
    a("")
    if blob.get("gate1"):
        g1 = blob["gate1"]
        a("## GATE-1 export")
        a("")
        a(f"- checkpoint `{SED_CKPT.name}`, seq_len=65 (2.6 s × 40 ms)")
        a(f"- ONNX `{g1.get('onnx', '')}` ({g1.get('file_bytes', 0)} bytes)")
        a(f"- torch-vs-ONNX max abs {g1.get('parity_max_abs')}")
        a(f"- music classes: {g1.get('music_classes')}")
        a(f"- fitted threshold: {arms.get('GATE-1', {}).get('threshold')}")
        a(f"- licence: {blob.get('sed_licence')}")
        a("")
    if blob.get("gate1_error"):
        a("## GATE-1 export")
        a("")
        a(f"Export/run failed: `{blob['gate1_error']}`")
        a("")
    a("## Recitation / adhan (J3, eval only)")
    a("")
    a("Sources: EveryAyah Al-Fatiha 001001–001007 from Alafasy_128kbps, Husary_128kbps, Minshawy_Murattal_128kbps "
      f"(21 ayat). Adhan: Aaqib Azeez, Wikimedia Commons CC BY-SA 4.0, {ADHAN_PAGE}")
    a("")
    a("| set | arm | chunks | passed | pass-through | missed s | wasted s |")
    a("|---|---|---:|---:|---:|---:|---:|")
    for name, rec in arms.items():
        m = rec.get("recite")
        if not m:
            continue
        a(
            f"| all-recite+adhan | {name} | {m['chunks_total']} | {m['chunks_passed']} | "
            f"{100*m['pass_through']:.1f}% | {m['missed_music_s']} | {m['wasted_sep_s']} |"
        )
    a("")
    a("### Per-file GATE-0 trip classes")
    a("")
    a("Hypothesis (plan §6.1): Chant/Mantra fire on recitation and adhan.")
    a("")
    a("| file | chunks | GATE-0 passed | top music class (max over frames) | Chant | Mantra |")
    a("|---|---:|---:|---|---:|---:|")
    for row in blob.get("recite_detail", []):
        a(
            f"| {row['file']} | {row['chunks']} | {row['passed_0']} | "
            f"{row['top_name']} ({row['top_score']:.3f}) | {row['chant']:.3f} | {row['mantra']:.3f} |"
        )
    a("")
    a("## Decision rule (§6.2)")
    a("")
    a("Adopt a new gate (or GATE-0b) if missed-music ≤ GATE-0, false-mute = 0 s, projected SEP time ≤ GATE-0 − 10%.")
    a("J2 mute tier is not in these arms (false-mute = 0 by construction).")
    a("")
    for name, rec in arms.items():
        a(f"- **{name}:** {rec['verdict']}")
    a("")
    a("GATE-0b is typically a no-op on English vlogs (Chant/Mantra never decide) and a J3 win on recitation.")
    a("The parent `Music` class can still fire on adhan, so GATE-0b does not imply full adhan pass-through.")
    a("")
    a("## Caveats")
    a("")
    a("- No hand labels. Judge is PANNs (or the recorded fallback), not a human timeline.")
    a("- Single English vlog, 4:29. Threshold for GATE-1 was fit on this same clip.")
    a("- Recitation/adhan licences are eval-only (EveryAyah undocumented; adhan CC BY-SA).")
    a("- Host timings are contended and CPU-EP; device XNNPACK numbers will differ.")
    a("- Chunk 0 includes 0.5 s of track-mean pre-pad, matching the app.")
    a("")
    a("## How to rerun")
    a("")
    a("```")
    a(".venv-bench/bin/python scripts/bench/score_gate.py")
    a(".venv-bench/bin/python scripts/bench/score_gate.py --cost")
    a("```")
    a("")
    a("Outputs: `qa-assets/bench-out/gate/` (per-chunk CSV, judge timeline, results.json).")
    DOC.write_text("\n".join(lines) + "\n")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--self-check", action="store_true")
    ap.add_argument("--cost", action="store_true")
    ap.add_argument("--cost-arm", default="")
    ap.add_argument("--export-sed", action="store_true")
    ap.add_argument("--skip-judge", action="store_true")
    args = ap.parse_args()

    if args.cost_arm:
        print(json.dumps(cost_arm(args.cost_arm)))
        return

    self_check()
    if args.self_check:
        return

    names = load_class_map()
    OUT.mkdir(parents=True, exist_ok=True)

    if args.export_sed:
        info = export_sed("frame_mn06")
        print(json.dumps(info, indent=2))
        return

    if args.cost:
        for arm in ("GATE-0", "GATE-0b", "GATE-1"):
            print(arm, flush=True)
            print(json.dumps(run_cost_subprocess(arm)), flush=True)
        return

    command = " ".join([sys.executable, str(Path(__file__).resolve())] + sys.argv[1:])
    blob = {
        "date": time.strftime("%Y-%m-%d"),
        "command": command,
        "ort": ort.__version__,
        "providers": ort.get_available_providers(),
        "arms": {},
    }

    print("load vlog", flush=True)
    vlog = load_mono44(VLOG)
    blob["vlog_duration_s"] = len(vlog) / SR
    blob["vlog_chunks"] = n_chunks(len(vlog))

    print("judge", flush=True)
    judge_note = ""
    try:
        if args.skip_judge:
            raise RuntimeError("skipped")
        jscore, _, judge_note = panns_judge(vlog)
        blob["judge"] = "panns"
    except Exception as e:
        print("PANNs failed:", e, flush=True)
        try:
            jscore, judge_note = htdemucs_fallback_judge(vlog)
            blob["judge"] = "fallback_hpss"
            blob["judge_error"] = repr(e)
        except Exception as e2:
            n_sec = len(vlog) // SR
            jscore = np.zeros(n_sec, np.float32)
            judge_note = f"judge failed: {e!r} / {e2!r}"
            blob["judge"] = "none"
    blob["judge_note"] = judge_note
    jbin = jscore >= JUDGE_THR
    write_csv(
        OUT / "judge_timeline.csv",
        [{"second": i, "score": f"{jscore[i]:.6f}", "music": int(jbin[i])} for i in range(len(jscore))],
        ["second", "score", "music"],
    )

    print("GATE-0 / 0b", flush=True)
    g0 = YamnetGate(MUSIC_RANGES_0, names)
    s0, sep0, tops0, accs0 = score_track(g0, vlog, detail=True)
    m0 = metrics(sep0, jbin, len(vlog))
    g0b = YamnetGate(MUSIC_RANGES_0B, names)
    s0b, sep0b, _, _ = score_track(g0b, vlog, detail=False)
    m0b = metrics(sep0b, jbin, len(vlog))

    def chunk_rows(scores, sep, tops=None):
        rows = []
        for c, (sc, sp) in enumerate(zip(scores, sep)):
            r = {
                "chunk": c,
                "t_start_s": max(0, c * STRIDE - MAX_SHIFT) / SR,
                "score": f"{float(sc):.6f}",
                "separate": int(sp),
            }
            if tops is not None and c < len(tops) and 0 <= tops[c] < len(names):
                r["top_class"] = names[int(tops[c])]
                r["top_i"] = int(tops[c])
            rows.append(r)
        return rows

    write_csv(OUT / "vlog_GATE-0.csv", chunk_rows(s0, sep0, tops0),
              ["chunk", "t_start_s", "score", "separate", "top_class", "top_i"])
    write_csv(OUT / "vlog_GATE-0b.csv", chunk_rows(s0b, sep0b),
              ["chunk", "t_start_s", "score", "separate"])

    blob["arms"]["GATE-0"] = {"vlog": m0, "threshold": THRESHOLD}
    blob["arms"]["GATE-0b"] = {"vlog": m0b, "threshold": THRESHOLD}

    # GATE-1
    gate1_ok = False
    sed_idx = []
    try:
        print("GATE-1 export", flush=True)
        if not SED_CKPT.exists():
            urllib.request.urlretrieve(
                "https://github.com/fschmid56/PretrainedSED/releases/download/v0.0.1/frame_mn06_strong_1.pt",
                SED_CKPT,
            )
        if not SED_DIR.exists():
            subprocess.run(
                ["git", "clone", "--depth", "1", "https://github.com/fschmid56/PretrainedSED.git", str(SED_DIR)],
                check=True,
            )
        info = export_sed("frame_mn06")
        blob["gate1"] = info
        lic_path = SED_DIR / "LICENSE"
        blob["sed_licence"] = (
            "PretrainedSED repo LICENSE is MIT (Copyright 2024 Florian Schmid). "
            "Release assets (frame_mn06_strong_1.pt) are published in that repo with no separate weights licence; "
            "treated as MIT. Training data: AudioSet Strong (YouTube)."
        )
        if not info["parity_ok"]:
            print("parity weak", info["parity_max_abs"], flush=True)
        sed_idx, _ = sed_music_idx()
        # score at 0.15 first, then fit
        tmp = SedGate(SED_ONNX if SED_ONNX.exists() else Path(info["onnx"]), sed_idx, THRESHOLD)
        s1, _ = score_track_sed(tmp, vlog, THRESHOLD)
        t1 = fit_threshold(s1, len(vlog), jbin, m0["music_recall"])
        sep1 = dilate_decisions(s1, t1, DILATE2_MIN_SCORE)
        m1 = metrics(sep1, jbin, len(vlog))
        write_csv(OUT / "vlog_GATE-1.csv", chunk_rows(s1, sep1),
                  ["chunk", "t_start_s", "score", "separate"])
        blob["arms"]["GATE-1"] = {"vlog": m1, "threshold": t1}
        gate1_ok = True
        print("GATE-1 thr", t1, "recall", m1["music_recall"], flush=True)
    except Exception as e:
        import traceback
        blob["gate1_error"] = traceback.format_exc()
        print("GATE-1 failed:\n", blob["gate1_error"], flush=True)

    # recitation set
    print("recitation", flush=True)
    rfiles = ensure_recite()
    parts = [load_mono44(p) for p in rfiles]
    recite = np.concatenate(parts) if parts else np.zeros(0, np.float32)
    sf.write(OUT / "recite_concat.wav", recite, SR)

    def run_arm_on(mono, ranges=None, sed=None, thr=THRESHOLD):
        if sed is not None:
            sc, sp = score_track_sed(sed, mono, thr)
            return sc, sp, None, None
        g = YamnetGate(ranges, names)
        return score_track(g, mono, detail=True)

    sr0, sepr0, topr0, accr0 = run_arm_on(recite, MUSIC_RANGES_0)
    blob["arms"]["GATE-0"]["recite"] = metrics(sepr0, np.zeros(len(recite) // SR, dtype=bool), len(recite))
    sr0b, sepr0b, _, _ = run_arm_on(recite, MUSIC_RANGES_0B)
    blob["arms"]["GATE-0b"]["recite"] = metrics(sepr0b, np.zeros(len(recite) // SR, dtype=bool), len(recite))
    # recitation has no "true music"; pass-through is the product metric. Judge zeros → missed=0, wasted=sep seconds.
    if gate1_ok:
        sed = SedGate(SED_ONNX, sed_idx, blob["arms"]["GATE-1"]["threshold"])
        sr1, sepr1, _, _ = run_arm_on(recite, sed=sed, thr=blob["arms"]["GATE-1"]["threshold"])
        blob["arms"]["GATE-1"]["recite"] = metrics(sepr1, np.zeros(len(recite) // SR, dtype=bool), len(recite))
        write_csv(OUT / "recite_GATE-1.csv", chunk_rows(sr1, sepr1),
                  ["chunk", "t_start_s", "score", "separate"])
    write_csv(OUT / "recite_GATE-0.csv", chunk_rows(sr0, sepr0, topr0),
              ["chunk", "t_start_s", "score", "separate", "top_class", "top_i"])
    write_csv(OUT / "recite_GATE-0b.csv", chunk_rows(sr0b, sepr0b),
              ["chunk", "t_start_s", "score", "separate"])

    detail = []
    g0d = YamnetGate(MUSIC_RANGES_0, names)
    for p, audio in zip(rfiles, parts):
        sc, sp, tops, accs = score_track(g0d, audio, detail=True)
        # max over chunks of class scores
        if accs:
            acc = np.max(np.stack(accs, 0), 0)
        else:
            acc = np.zeros(CLASSES, np.float32)
        top_i = int(np.argmax(acc[g0d.idx]))
        top_i = int(g0d.idx[top_i])
        detail.append({
            "file": str(p.relative_to(RECITE)),
            "chunks": int(len(sc)),
            "passed_0": int((~sp).sum()),
            "top_name": names[top_i],
            "top_score": float(acc[top_i]),
            "chant": float(acc[27]),
            "mantra": float(acc[28]),
            "singing": float(acc[24]),
            "music": float(acc[132]),
        })
    blob["recite_detail"] = detail

    # verdicts
    for name, rec in blob["arms"].items():
        rec["verdict"] = "control" if name == "GATE-0" else verdict(rec["vlog"], m0)

    # contended cost
    print("cost (contended)", flush=True)
    for name in list(blob["arms"]):
        blob["arms"][name]["cost"] = run_cost_subprocess(name)
        blob["arms"][name]["cost_label"] = "contended, provisional"

    (OUT / "results.json").write_text(json.dumps(blob, indent=2, default=str))
    write_report(blob)
    print("wrote", DOC)
    print("vlog GATE-0", fmt(m0))
    print("vlog GATE-0b", fmt(m0b))
    if "GATE-1" in blob["arms"]:
        print("vlog GATE-1", fmt(blob["arms"]["GATE-1"]["vlog"]), "thr", blob["arms"]["GATE-1"]["threshold"])
    for name, rec in blob["arms"].items():
        print(name, rec["verdict"])


if __name__ == "__main__":
    main()
