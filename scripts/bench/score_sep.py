#!/usr/bin/env python3
"""SEP T0 bake-off (plan §5.1–5.2: SEP-0, SEP-1, SEP-2, SEP-7).

Replicates the app's htdemucs vocals path (chunking, STFT/iSTFT outside the graph, OLA)
and UVR MDX / sherpa-onnx GTCRN inference. Host ORT is CPU EP (no XNNPACK on mac).

From repo root, with `.venv-bench`:

  .venv-bench/bin/python scripts/bench/score_sep.py prepare
  .venv-bench/bin/python scripts/bench/score_sep.py quality
  .venv-bench/bin/python scripts/bench/score_sep.py vlog
  .venv-bench/bin/python scripts/bench/score_sep.py cost          # serial, one arm at a time
  .venv-bench/bin/python scripts/bench/score_sep.py quant         # SEP-1 INT8 if T0 rule holds
  .venv-bench/bin/python scripts/bench/score_sep.py all           # prepare+quality+vlog
  .venv-bench/bin/python scripts/bench/score_sep.py cost-worker --arm sep1 --wav input.wav --output vocals.wav
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import resource
import subprocess
import sys
import time
from pathlib import Path

import numpy as np
import onnxruntime as ort
import soundfile as sf

REPO = Path(__file__).resolve().parents[2]
ASSETS = REPO / "app/src/main/assets/models"
QA_MODELS = REPO / "qa-assets/models"
OUT = REPO / "qa-assets/bench-out/sep"
SRC = OUT / "src"
VLOG_44K = REPO / "qa-assets/vlog/vlog_44k.wav"
VLOG_16K = REPO / "qa-assets/vlog/vlog_16k.wav"
PY = REPO / ".venv-bench/bin/python"

SR = 44100
SR16 = 16000

# DemucsSeparator.kt — vocals mode, ungated (every chunk separated).
HT_SEG = 114_660
HT_STRIDE = 103_194
HT_MAX_SHIFT = 22_050
HT_NFFT = 4096
HT_HOP = 1024
HT_BINS = 2048
HT_LE = 112
HT_VOCALS = 3
HT_THREADS = min(os.cpu_count() or 6, 6)

YAM_FRAME = 15_600  # 0.975 s @ 16 kHz
YAM_THR = 0.15
YAM_MUSIC = list(range(132, 277)) + list(range(24, 33))  # inclusive
YAM_SPEECH = 0

SNR_BINS = (-5, 0, 5, 10)
CLIP_S = 10
COST_S = 60
COST_RUNS = 3

MDX_FILES = {
    "sep1": QA_MODELS / "UVR_MDXNET_3_9662.onnx",
    "sep2": QA_MODELS / "UVR_MDXNET_9482.onnx",
}
MDX_JSON = QA_MODELS / "model_data_new.json"
MDX_OVERLAP = 0.25  # python-audio-separator default; denoise off
UVR_HASH_TAIL = 10000 * 1024

GTCRN_PATH = QA_MODELS / "gtcrn_simple.onnx"
HT_PATH = ASSETS / "htdemucs_s26_f16.onnx"
YAM_PATH = ASSETS / "yamnet.onnx"


def log(msg: str) -> None:
    print(msg, flush=True)


def ensure_dir(p: Path) -> Path:
    p.mkdir(parents=True, exist_ok=True)
    return p


def cpu_session(path: Path, intra: int, *, arena: bool = False) -> ort.InferenceSession:
    """App-like session: CPU EP, spinning off. XNNPACK is not in this host ORT build."""
    so = ort.SessionOptions()
    so.intra_op_num_threads = intra
    so.inter_op_num_threads = 1
    so.add_session_config_entry("session.intra_op.allow_spinning", "0")
    so.enable_cpu_mem_arena = arena
    so.enable_mem_pattern = arena
    so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    so.log_severity_level = 3
    return ort.InferenceSession(str(path), so, providers=["CPUExecutionProvider"])


def finite_check(x: np.ndarray) -> int:
    return int(np.size(x) - np.isfinite(x).sum())


def softclip(x: np.ndarray) -> np.ndarray:
    a = np.abs(x)
    y = np.empty_like(x)
    mask = a <= 0.95
    y[mask] = x[mask]
    t = np.tanh((a[~mask] - 0.95) / 0.05) * 0.05
    y[~mask] = np.sign(x[~mask]) * (0.95 + t)
    return y


def mono_mix(x: np.ndarray) -> np.ndarray:
    """x: [2, T] or [T] -> [T]."""
    x = np.asarray(x, np.float32)
    if x.ndim == 1:
        return x
    return 0.5 * (x[0] + x[1])


def to_stereo(x: np.ndarray) -> np.ndarray:
    x = np.asarray(x, np.float32)
    if x.ndim == 1:
        return np.stack([x, x])
    if x.shape[0] == 2:
        return x
    if x.shape[-1] == 2:
        return x.T
    raise ValueError(f"bad audio shape {x.shape}")


def load_wav(path: Path, sr: int = SR) -> np.ndarray:
    y, file_sr = sf.read(str(path), always_2d=True, dtype="float32")
    y = y.T  # [C, T]
    if y.shape[0] == 1:
        y = np.concatenate([y, y], 0)
    elif y.shape[0] > 2:
        y = y[:2]
    if file_sr != sr:
        y = resample(y, file_sr, sr)
    return np.ascontiguousarray(y, np.float32)


def resample(x: np.ndarray, src: int, dst: int) -> np.ndarray:
    if src == dst:
        return x
    from math import gcd
    from scipy.signal import resample_poly
    g = gcd(src, dst)
    up, down = dst // g, src // g
    if x.ndim == 1:
        return resample_poly(x, up, down).astype(np.float32)
    return np.stack([resample_poly(ch, up, down) for ch in x]).astype(np.float32)


def resample_linear(x: np.ndarray, src: int, dst: int) -> np.ndarray:
    """MusicGate.kt linear interp (44.1k -> 16k). x is 1-D."""
    if len(x) <= 1:
        return np.zeros(0, np.float32)
    ratio = src / dst
    n = int((len(x) - 1) / ratio) + 1
    t = np.arange(n, dtype=np.float64) * ratio
    i0 = np.minimum(t.astype(np.int64), len(x) - 1)
    f = (t - i0).astype(np.float32)
    i1 = np.minimum(i0 + 1, len(x) - 1)
    return x[i0] + (x[i1] - x[i0]) * f


def hann_periodic(n: int) -> np.ndarray:
    return 0.5 * (1.0 - np.cos(2.0 * np.pi * np.arange(n) / n))


def reflect_pad_1d(x: np.ndarray, left: int, right: int) -> np.ndarray:
    # torch F.pad reflect: mirror excluding the edge sample.
    return np.concatenate([x[left:0:-1], x, x[-2:-2 - right:-1]])


# --- htdemucs STFT (scripts/stft_golden.py, production sizes) -----------------

def ht_forward(ch0: np.ndarray, ch1: np.ndarray, nfft=HT_NFFT, hop=HT_HOP, T=HT_SEG) -> np.ndarray:
    le = -(-T // hop)
    pad_l = hop // 2 * 3
    pad_r = pad_l + le * hop - T
    bins = nfft // 2
    scale = 1.0 / np.sqrt(nfft)
    win = hann_periodic(nfft)
    center = nfft // 2
    cac = np.zeros((4, bins, le), np.float32)
    for ci, ch in enumerate((ch0, ch1)):
        sig_a = reflect_pad_1d(ch.astype(np.float64), pad_l, pad_r)
        sig_b = reflect_pad_1d(sig_a, center, center)
        starts = np.arange(2, 2 + le) * hop
        frames = np.stack([sig_b[s:s + nfft] * win for s in starts])
        spec = np.fft.rfft(frames, n=nfft, axis=-1)
        cac[2 * ci] = (spec[:, :bins].real * scale).T
        cac[2 * ci + 1] = (spec[:, :bins].imag * scale).T
    return cac


def ht_inverse(cac: np.ndarray, nfft=HT_NFFT, hop=HT_HOP, T=HT_SEG) -> tuple[np.ndarray, np.ndarray]:
    le = -(-T // hop)
    pad_l = hop // 2 * 3
    pad_r = pad_l + le * hop - T
    bins = nfft // 2
    padded = T + pad_l + pad_r
    win = hann_periodic(nfft)
    nframes = padded // hop + 1
    unscale = np.sqrt(nfft)
    env = np.zeros(padded + nfft, np.float64)
    for f in range(nframes):
        env[f * hop:f * hop + nfft] += win * win
    offset = nfft // 2 + pad_l
    out = np.zeros((2, T), np.float32)
    for ci in range(2):
        ola = np.zeros(padded + nfft, np.float64)
        spec = np.zeros((le, bins + 1), np.complex128)
        spec[:, :bins] = (cac[2 * ci] + 1j * cac[2 * ci + 1]).T * unscale
        rec = np.fft.irfft(spec, n=nfft, axis=-1) * win
        for t, f in enumerate(range(2, 2 + le)):
            ola[f * hop:f * hop + nfft] += rec[t]
        out[ci] = (ola[offset:offset + T] / (env[offset:offset + T] + 1e-8)).astype(np.float32)
    return out[0], out[1]


def track_mean_std(stereo: np.ndarray) -> tuple[float, float]:
    m = mono_mix(stereo)
    mean = float(m.mean())
    std = float(m.std(ddof=1)) if m.size > 1 else 0.0
    return mean, max(std, 1e-8)


class HtDemucs:
    def __init__(self, path: Path = HT_PATH):
        self.sess = cpu_session(path, HT_THREADS, arena=False)
        self.in_wav = next(i.name for i in self.sess.get_inputs() if len(i.shape) == 3)
        self.in_spec = next(i.name for i in self.sess.get_inputs() if len(i.shape) == 4)
        self.out_spec = next(o.name for o in self.sess.get_outputs() if len(o.shape) == 5)
        self.out_wav = next(o.name for o in self.sess.get_outputs() if len(o.shape) == 4)
        self.path = path

    def separate(self, stereo: np.ndarray) -> np.ndarray:
        stereo = np.ascontiguousarray(stereo, np.float32)
        T = stereo.shape[1]
        mean, std = track_mean_std(stereo)
        normed = (stereo - mean) / std
        padded = np.concatenate([np.zeros((2, HT_MAX_SHIFT), np.float32), normed], 1)
        end_pos = HT_MAX_SHIFT + T
        out = np.zeros((2, end_pos + HT_SEG), np.float32)
        wsum = np.zeros(end_pos + HT_SEG, np.float32)
        i = np.arange(HT_SEG)
        weight = np.minimum(i + 1, HT_SEG - i).astype(np.float32) / np.float32(HT_SEG // 2)
        off = 0
        while off < end_pos:
            clen = min(HT_SEG, end_pos - off)
            delta = HT_SEG - clen
            read_start = off - delta // 2
            seg = np.zeros((2, HT_SEG), np.float32)
            src0 = max(read_start, 0)
            src1 = min(read_start + HT_SEG, padded.shape[1], end_pos)
            dst0 = src0 - read_start
            if src1 > src0:
                seg[:, dst0:dst0 + (src1 - src0)] = padded[:, src0:src1]
            spec = ht_forward(seg[0], seg[1])
            feeds = {
                self.in_wav: seg[None],
                self.in_spec: spec[None],
            }
            spec_out, time_out = self.sess.run([self.out_spec, self.out_wav], feeds)
            vspec = spec_out[0, HT_VOCALS]
            wave = ht_inverse(vspec)
            tl = time_out[0, HT_VOCALS, 0]
            tr = time_out[0, HT_VOCALS, 1]
            read = delta // 2
            sl = slice(off, off + clen)
            g = weight[:clen]
            out[0, sl] += g * (wave[0][read:read + clen] + tl[read:read + clen])
            out[1, sl] += g * (wave[1][read:read + clen] + tr[read:read + clen])
            wsum[sl] += g
            off += HT_STRIDE
        sl = slice(HT_MAX_SHIFT, end_pos)
        y = out[:, sl] / np.maximum(wsum[sl], 1e-8)
        y = y * std + mean
        return softclip(y)


# --- MDX (UVR / python-audio-separator) --------------------------------------

def uvr_hash(path: Path) -> str:
    size = path.stat().st_size
    with open(path, "rb") as f:
        if size < UVR_HASH_TAIL:
            data = f.read()
        else:
            f.seek(size - UVR_HASH_TAIL)
            data = f.read()
    return hashlib.md5(data).hexdigest()


def mdx_stft(mix: np.ndarray, n_fft: int, hop: int, dim_f: int) -> np.ndarray:
    """mix [2, T] -> [1, 4, dim_f, n_frames] matching torch.stft(center=True, hann periodic)."""
    win = hann_periodic(n_fft).astype(np.float32)
    pad = n_fft // 2
    padded = np.stack([reflect_pad_1d(mix[c], pad, pad) for c in range(2)])
    n_frames = 1 + (padded.shape[1] - n_fft) // hop
    idx = np.arange(n_fft)[None, :] + np.arange(n_frames)[:, None] * hop
    framed = padded[:, idx]  # [2, n_frames, n_fft]
    spec = np.fft.rfft(framed * win, n=n_fft, axis=-1)  # [2, n_frames, n_fft/2+1]
    spec = spec[:, :, :dim_f]
    out = np.empty((1, 4, dim_f, n_frames), np.float32)
    out[0, 0] = spec[0].real.T
    out[0, 1] = spec[0].imag.T
    out[0, 2] = spec[1].real.T
    out[0, 3] = spec[1].imag.T
    return out


def mdx_istft(spek: np.ndarray, n_fft: int, hop: int, dim_f: int, length: int) -> np.ndarray:
    """spek [1, 4, dim_f, n_frames] -> [2, length]."""
    win = hann_periodic(n_fft).astype(np.float64)
    n_bins = n_fft // 2 + 1
    n_frames = spek.shape[-1]
    full = np.zeros((2, n_frames, n_bins), np.complex128)
    full[0, :, :dim_f] = spek[0, 0].T + 1j * spek[0, 1].T
    full[1, :, :dim_f] = spek[0, 2].T + 1j * spek[0, 3].T
    rec = np.fft.irfft(full, n=n_fft, axis=-1) * win  # [2, n_frames, n_fft]
    ola_len = n_fft + hop * (n_frames - 1)
    ola = np.zeros((2, ola_len), np.float64)
    env = np.zeros(ola_len, np.float64)
    for t in range(n_frames):
        s = t * hop
        ola[:, s:s + n_fft] += rec[:, t]
        env[s:s + n_fft] += win * win
    ola /= env + 1e-8
    pad = n_fft // 2
    return ola[:, pad:pad + length].astype(np.float32)


class MdxSep:
    def __init__(self, path: Path, denoise: bool = False):
        meta = json.loads(MDX_JSON.read_text())
        h = uvr_hash(path)
        if h not in meta:
            raise RuntimeError(f"{path.name}: UVR hash {h} not in model_data_new.json")
        d = meta[h]
        self.compensate = float(d["compensate"])
        self.dim_f = int(d["mdx_dim_f_set"])
        self.dim_t = 2 ** int(d["mdx_dim_t_set"])
        self.n_fft = int(d["mdx_n_fft_scale_set"])
        self.primary = d["primary_stem"]
        self.hop = 1024
        self.segment_size = self.dim_t
        self.trim = self.n_fft // 2
        self.chunk_size = self.hop * (self.segment_size - 1)
        self.gen_size = self.chunk_size - 2 * self.trim
        self.denoise = denoise
        self.path = path
        self.hash = h
        self.sess = cpu_session(path, HT_THREADS, arena=False)
        self.iname = self.sess.get_inputs()[0].name
        self.oname = self.sess.get_outputs()[0].name

    def _run_spek(self, spek: np.ndarray) -> np.ndarray:
        spek = spek.copy()
        spek[:, :, :3, :] = 0  # UVR: zero first 3 bins
        if self.denoise:
            neg = self.sess.run([self.oname], {self.iname: -spek})[0]
            pos = self.sess.run([self.oname], {self.iname: spek})[0]
            return (-0.5 * neg) + (0.5 * pos)
        return self.sess.run([self.oname], {self.iname: spek})[0]

    def separate(self, stereo: np.ndarray) -> np.ndarray:
        mix = np.ascontiguousarray(stereo, np.float32)
        peak = float(np.abs(mix).max())
        if peak < 1e-8:
            return mix
        mix_n = mix / peak
        chunk_size = self.chunk_size
        trim = self.trim
        overlap = MDX_OVERLAP
        gen_size = chunk_size - 2 * trim
        pad = gen_size + trim - (mix_n.shape[-1] % gen_size)
        mixture = np.concatenate(
            [np.zeros((2, trim), np.float32), mix_n, np.zeros((2, pad), np.float32)], 1
        )
        step = int((1 - overlap) * chunk_size)
        result = np.zeros((2, mixture.shape[-1]), np.float32)
        divider = np.zeros(mixture.shape[-1], np.float32)
        for start in range(0, mixture.shape[-1], step):
            end = min(start + chunk_size, mixture.shape[-1])
            part = mixture[:, start:end]
            if end - start < chunk_size:
                part = np.concatenate(
                    [part, np.zeros((2, chunk_size - (end - start)), np.float32)], 1
                )
            win = np.hanning(end - start).astype(np.float32)
            spek = mdx_stft(part, self.n_fft, self.hop, self.dim_f)
            pred = self._run_spek(spek)
            waves = mdx_istft(pred, self.n_fft, self.hop, self.dim_f, chunk_size)
            result[:, start:end] += waves[:, : end - start] * win
            divider[start:end] += win
        tar = result / np.maximum(divider, 1e-8)
        tar = tar[:, trim:-trim][:, : mix.shape[-1]]
        vocals = tar * peak
        if self.primary != "Vocals":
            vocals = mix - vocals * self.compensate
        else:
            vocals = vocals * self.compensate
        return vocals.astype(np.float32)


# --- GTCRN (sherpa-onnx gtcrn_simple, 16 kHz streaming) ----------------------

class Gtcrn:
    def __init__(self, path: Path = GTCRN_PATH):
        self.path = path
        self.sess = cpu_session(path, HT_THREADS, arena=False)
        meta = self.sess.get_modelmeta().custom_metadata_map
        self.n_fft = int(meta.get("n_fft", "512"))
        self.hop = int(meta.get("hop_length", "256"))
        self.win_len = int(meta.get("window_length", "512"))
        self.sr = int(meta.get("sample_rate", "16000"))
        self.win = np.sqrt(hann_periodic(self.win_len)).astype(np.float32)

    def separate(self, stereo: np.ndarray) -> np.ndarray:
        mono = mono_mix(stereo)
        x16 = resample(mono, SR, self.sr)
        pad = self.n_fft // 2
        padded = np.pad(x16, (pad, pad))
        n_frames = 1 + max(0, (len(padded) - self.n_fft) // self.hop)
        if n_frames <= 0:
            return stereo
        idx = np.arange(self.n_fft)[None, :] + np.arange(n_frames)[:, None] * self.hop
        framed = padded[idx] * self.win
        spec = np.fft.rfft(framed, n=self.n_fft, axis=-1)  # [T, 257]
        conv = np.zeros((2, 1, 16, 16, 33), np.float32)
        tra = np.zeros((2, 3, 1, 1, 16), np.float32)
        inter = np.zeros((2, 1, 33, 16), np.float32)
        out_frames = np.empty((n_frames, spec.shape[1]), np.complex64)
        for i in range(n_frames):
            mix = np.stack([spec[i].real, spec[i].imag], -1).astype(np.float32)
            mix = mix[None, :, None, :]  # [1, 257, 1, 2]
            enh, conv, tra, inter = self.sess.run(
                None, {"mix": mix, "conv_cache": conv, "tra_cache": tra, "inter_cache": inter}
            )
            out_frames[i] = enh[0, :, 0, 0] + 1j * enh[0, :, 0, 1]
        rec = np.fft.irfft(out_frames, n=self.n_fft, axis=-1) * self.win
        ola = np.zeros(self.n_fft + self.hop * (n_frames - 1), np.float64)
        env = np.zeros_like(ola)
        for t in range(n_frames):
            s = t * self.hop
            ola[s:s + self.n_fft] += rec[t]
            env[s:s + self.n_fft] += self.win.astype(np.float64) ** 2
        y = (ola / (env + 1e-8))[pad:pad + len(x16)].astype(np.float32)
        y44 = resample(y, self.sr, SR)
        n = min(y44.shape[-1], stereo.shape[1])
        out = np.zeros_like(stereo)
        out[:, :n] = y44[:n]
        return out


# --- YAMNet judge ------------------------------------------------------------

class Yamnet:
    def __init__(self):
        self.sess = cpu_session(YAM_PATH, 1, arena=True)
        self.iname = self.sess.get_inputs()[0].name

    def scores(self, wave16: np.ndarray) -> np.ndarray:
        x = np.zeros(YAM_FRAME, np.float32)
        n = min(len(wave16), YAM_FRAME)
        x[:n] = wave16[:n]
        return self.sess.run(None, {self.iname: x})[0][0]  # [521]

    def music_score(self, wave16: np.ndarray) -> float:
        s = self.scores(wave16)
        return float(s[YAM_MUSIC].max())

    def speech_score(self, wave16: np.ndarray) -> float:
        return float(self.scores(wave16)[YAM_SPEECH])

    def residual_music_seconds(self, stereo_or_mono: np.ndarray, sr: int = SR) -> dict:
        """Non-overlapping 0.975 s frames (plan §5.2 A3 judge, YAMNet as judge)."""
        if stereo_or_mono.ndim == 2:
            mono = mono_mix(stereo_or_mono)
        else:
            mono = stereo_or_mono
        if sr != SR16:
            wave = resample_linear(mono.astype(np.float32), sr, SR16)
        else:
            wave = mono.astype(np.float32)
        n_frames = len(wave) // YAM_FRAME
        hits = 0
        peak = 0.0
        for i in range(n_frames):
            sc = self.music_score(wave[i * YAM_FRAME:(i + 1) * YAM_FRAME])
            peak = max(peak, sc)
            if sc >= YAM_THR:
                hits += 1
        return {
            "frames": n_frames,
            "hits": hits,
            "seconds": hits * (YAM_FRAME / SR16),
            "peak": peak,
            "duration_s": n_frames * (YAM_FRAME / SR16),
        }

    def find_vlog_speech(self, n_needed: int = 3) -> list[tuple[int, int]]:
        """10 s regions: YAMNet Speech high, music-block max < 0.02."""
        wave, sr = sf.read(str(VLOG_16K), dtype="float32")
        assert sr == SR16
        hop = YAM_FRAME
        speech, music = [], []
        for i in range(len(wave) // hop):
            s = self.scores(wave[i * hop:(i + 1) * hop])
            speech.append(float(s[YAM_SPEECH]))
            music.append(float(s[YAM_MUSIC].max()))
        speech, music = np.array(speech), np.array(music)
        win = int(round(CLIP_S / (YAM_FRAME / SR16)))  # ~10 frames
        cands = []
        for i in range(0, len(speech) - win + 1):
            if music[i:i + win].max() < 0.02 and speech[i:i + win].mean() >= 0.4:
                cands.append((i, float(speech[i:i + win].mean()), float(music[i:i + win].max())))
        cands.sort(key=lambda t: -t[1])
        picked = []
        used = set()
        for i, sp, mu in cands:
            if any(abs(i - u) < win for u in used):
                continue
            start = i * hop
            end = start + CLIP_S * SR16
            if end <= len(wave):
                picked.append((start, end, sp, mu))
                used.add(i)
            if len(picked) >= n_needed:
                break
        log(f"vlog speech regions: {[(p[0]/SR16, p[1]/SR16, p[2], p[3]) for p in picked]}")
        return [(p[0], p[1]) for p in picked]


# --- sources / mixes ---------------------------------------------------------

MUSIC_INSTR = [
    ("carefree", SRC / "music/km_Carefree.mp3", 20.0, "https://incompetech.com/music/royalty-free/mp3-royaltyfree/Carefree.mp3", "Kevin MacLeod — Carefree, CC BY 3.0 (incompetech.com)"),
    ("night_owl", SRC / "music/night_owl.mp3", 30.0, "https://files.freemusicarchive.org/storage-freemusicarchive-org/music/WFMU/Broke_For_Free/Directionless_EP/Broke_For_Free_-_01_-_Night_Owl.mp3", "Broke For Free — Night Owl, CC BY (Free Music Archive)"),
]
MUSIC_SUNG = [
    ("auld_lang_syne", SRC / "music/auld_lang_syne.ogg", 20.0, "https://commons.wikimedia.org/wiki/File:Auld_Lang_Syne.ogg", "Frank C. Stanley (1910) — Auld Lang Syne, public domain (UCSB Cylinder)"),
    ("battle_hymn", SRC / "music/Battle_Hymn_of_the_Republic,_Frank_C._Stanley,_Elise_Stevens", 10.0, "https://commons.wikimedia.org/wiki/File:Battle_Hymn_of_the_Republic,_Frank_C._Stanley,_Elise_Stevenson.ogg", "Frank C. Stanley & Elise Stevenson — Battle Hymn of the Republic, public domain"),
]
LIBRI_FLACS = [
    SRC / "LibriSpeech/dev-clean-2/8842/304647/8842-304647-0002.flac",
    SRC / "LibriSpeech/dev-clean-2/3000/15664/3000-15664-0041.flac",
    SRC / "LibriSpeech/dev-clean-2/174/168635/174-168635-0018.flac",
]


def ffmpeg_wav(src: Path, dest: Path, sr: int, ch: int, start: float, dur: float) -> None:
    ensure_dir(dest.parent)
    cmd = [
        "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
        "-ss", f"{start:.3f}", "-t", f"{dur:.3f}", "-i", str(src),
        "-ar", str(sr), "-ac", str(ch), "-acodec", "pcm_f32le", str(dest),
    ]
    subprocess.check_call(cmd)


def write_sources_md() -> None:
    ensure_dir(OUT)
    lines = [
        "# Sources used by `scripts/bench/score_sep.py` (SEP T0)",
        "",
        "## Speech",
        "",
        "- Mini LibriSpeech `dev-clean-2` (OpenSLR 31), a CC BY 4.0 subset of LibriSpeech (OpenSLR 12).",
        "  Tarball: https://www.openslr.org/resources/31/dev-clean-2.tar.gz (126 MB).",
        "  Utterances: `8842-304647-0002`, `3000-15664-0041`, `174-168635-0018` (first 10 s).",
        "- In-domain: 10 s regions of `qa-assets/vlog/vlog_16k.wav` where YAMNet Speech is high and",
        "  music-block max (classes 132–276 and 24–32) < 0.02. Offsets recorded in `mixes/manifest.json`.",
        "",
        "## Music",
        "",
    ]
    for name, path, start, url, lic in MUSIC_INSTR + MUSIC_SUNG:
        kind = "sung" if (name, path, start, url, lic) in MUSIC_SUNG else "instrumental"
        lines.append(f"- **{name}** ({kind}): {lic}")
        lines.append(f"  URL: {url}")
        lines.append(f"  local: `{path.relative_to(REPO)}`, clip start {start:.1f}s × {CLIP_S}s")
        lines.append("")
    lines += [
        "Unused downloads kept next to the chosen files (Discovery Hit, Wallpaper, Twinkle, etc.) were not mixed.",
        "",
        "## Models",
        "",
        "- SEP-0: `app/src/main/assets/models/htdemucs_s26_f16.onnx` (incumbent).",
        "- SEP-1/2: https://github.com/TRvlvr/model_repo/releases/tag/all_public_uvr_models",
        "  Geometry from https://raw.githubusercontent.com/TRvlvr/application_data/main/mdx_model_data/model_data_new.json",
        "  keyed by MD5 of the last 10000×1024 bytes (UVR / python-audio-separator).",
        "- SEP-7: https://github.com/k2-fsa/sherpa-onnx/releases/download/speech-enhancement-models/gtcrn_simple.onnx",
        "",
    ]
    (OUT / "SOURCES.md").write_text("\n".join(lines))


def prepare(yam: Yamnet | None = None) -> dict:
    ensure_dir(OUT / "mixes")
    ensure_dir(OUT / "clips")
    write_sources_md()
    clips = {"speech": [], "music": []}
    # LibriSpeech 10 s
    for i, flac in enumerate(LIBRI_FLACS):
        if not flac.exists():
            raise FileNotFoundError(flac)
        dest = OUT / "clips" / f"speech_libri_{i}.wav"
        ffmpeg_wav(flac, dest, SR, 2, 0.0, CLIP_S)
        clips["speech"].append({"id": f"libri_{i}", "path": str(dest), "sung": False, "source": str(flac)})
    # vlog in-domain
    yam = yam or Yamnet()
    regions = yam.find_vlog_speech(3)
    v44, _ = sf.read(str(VLOG_44K), always_2d=True, dtype="float32")
    v44 = v44.T
    for i, (a16, b16) in enumerate(regions):
        a44 = int(round(a16 * SR / SR16))
        b44 = a44 + CLIP_S * SR
        dest = OUT / "clips" / f"speech_vlog_{i}.wav"
        sf.write(str(dest), v44[:, a44:b44].T, SR, subtype="FLOAT")
        clips["speech"].append({
            "id": f"vlog_{i}", "path": str(dest), "sung": False,
            "vlog_16k": [a16, b16], "vlog_44k": [a44, b44],
        })
    for name, path, start, url, lic in MUSIC_INSTR + MUSIC_SUNG:
        if not path.exists():
            raise FileNotFoundError(path)
        dest = OUT / "clips" / f"music_{name}.wav"
        ffmpeg_wav(path, dest, SR, 2, start, CLIP_S)
        sung = (name, path, start, url, lic) in MUSIC_SUNG
        clips["music"].append({"id": name, "path": str(dest), "sung": sung, "url": url, "licence": lic})
    mixes = []
    mix_dir = ensure_dir(OUT / "mixes")
    for snr in SNR_BINS:
        k = 0
        for sp in clips["speech"]:
            for mu in clips["music"]:
                speech = load_wav(Path(sp["path"]))
                music = load_wav(Path(mu["path"]))
                n = min(speech.shape[1], music.shape[1], CLIP_S * SR)
                speech, music = speech[:, :n], music[:, :n]
                p_s = float(np.mean(speech ** 2) + 1e-12)
                p_m = float(np.mean(music ** 2) + 1e-12)
                target = p_s * (10 ** (snr / 10.0))
                music_s = music * np.sqrt(target / p_m)
                mix = speech + music_s
                peak = float(np.abs(mix).max())
                if peak > 0.99:
                    g = 0.99 / peak
                    mix, speech, music_s = mix * g, speech * g, music_s * g
                ident = f"snr{snr:+d}_{sp['id']}_{mu['id']}"
                base = mix_dir / ident
                sf.write(str(base) + "_mix.wav", mix.T, SR, subtype="FLOAT")
                sf.write(str(base) + "_speech.wav", speech.T, SR, subtype="FLOAT")
                sf.write(str(base) + "_music.wav", music_s.T, SR, subtype="FLOAT")
                mixes.append({
                    "id": ident, "snr_db": snr, "speech": sp["id"], "music": mu["id"],
                    "sung": mu["sung"], "n": n, "base": str(base),
                })
                k += 1
        log(f"SNR {snr:+d} dB: {k} mixes")
    man = {"clips": clips, "mixes": mixes, "n_per_bin": {str(s): sum(1 for m in mixes if m["snr_db"] == s) for s in SNR_BINS}}
    (OUT / "mixes/manifest.json").write_text(json.dumps(man, indent=2))
    log(f"wrote {len(mixes)} mixes -> {mix_dir}")
    return man


def load_manifest() -> dict:
    p = OUT / "mixes/manifest.json"
    if not p.exists():
        return prepare()
    return json.loads(p.read_text())


# --- metrics -----------------------------------------------------------------

def si_metrics(est: np.ndarray, speech: np.ndarray, music: np.ndarray) -> dict:
    import fast_bss_eval
    e = mono_mix(est).astype(np.float64)
    s = mono_mix(speech).astype(np.float64)
    m = mono_mix(music).astype(np.float64)
    n = min(len(e), len(s), len(m))
    e, s, m = e[:n], s[:n], m[:n]
    residual = e  # vocals estimate
    other = (s + m) - residual
    ref = np.stack([s, m])
    est2 = np.stack([residual, other])
    si_sdr = float(np.asarray(fast_bss_eval.si_sdr(s[None], residual[None], clamp_db=50)).reshape(-1)[0])
    sdr, sir, sar = fast_bss_eval.si_bss_eval_sources(ref, est2, compute_permutation=False, clamp_db=50)
    return {
        "si_sdr": si_sdr,
        "sir": float(np.asarray(sir).reshape(-1)[0]),
        "sar": float(np.asarray(sar).reshape(-1)[0]),
        "nan_inf": finite_check(est),
    }


def singing_retained(est: np.ndarray, singing: np.ndarray) -> dict:
    e = mono_mix(est).astype(np.float64)
    s = mono_mix(singing).astype(np.float64)
    n = min(len(e), len(s))
    e, s = e[:n], s[:n]
    es, ss = float(np.dot(e, e)), float(np.dot(s, s) + 1e-12)
    coef = float(np.dot(e, s) / ss)
    proj_e = (coef ** 2) * ss
    return {
        "energy_ratio": es / ss,
        "proj_fraction": proj_e / (es + 1e-12),
        "scale": coef,
    }


def summarize(xs: list[float]) -> dict:
    a = np.asarray(xs, np.float64)
    a = a[np.isfinite(a)]
    if a.size == 0:
        return {"n": 0, "mean": None, "median": None}
    return {"n": int(a.size), "mean": float(a.mean()), "median": float(np.median(a))}


# --- arms --------------------------------------------------------------------

def build_arm(name: str, denoise: bool = False):
    if name == "sep0":
        return HtDemucs()
    if name == "sep1":
        return MdxSep(MDX_FILES["sep1"], denoise=denoise)
    if name == "sep2":
        return MdxSep(MDX_FILES["sep2"], denoise=denoise)
    if name == "sep7":
        return Gtcrn()
    raise ValueError(name)


def arm_info(name: str, obj) -> dict:
    path = getattr(obj, "path", None)
    info = {"arm": name, "ep": "CPUExecutionProvider", "threads": HT_THREADS, "xnnpack": False}
    if path and Path(path).exists():
        info["file"] = str(path)
        info["file_mb"] = Path(path).stat().st_size / 1e6
    if isinstance(obj, MdxSep):
        info.update({
            "uvr_hash": obj.hash, "n_fft": obj.n_fft, "dim_f": obj.dim_f, "dim_t": obj.dim_t,
            "compensate": obj.compensate, "primary": obj.primary, "hop": obj.hop,
            "chunk_size": obj.chunk_size, "segment_s": obj.chunk_size / SR,
            "overlap": MDX_OVERLAP, "denoise": obj.denoise,
            "onnx_input": f"{obj.iname} {obj.sess.get_inputs()[0].shape} float32",
        })
    if isinstance(obj, HtDemucs):
        info.update({
            "seg": HT_SEG, "stride": HT_STRIDE, "nfft": HT_NFFT, "hop": HT_HOP,
            "stems": "vocals only", "onnx_input": "input [1,2,114660] f32 + x [1,4,2048,112] f32",
        })
    if isinstance(obj, Gtcrn):
        info.update({"n_fft": obj.n_fft, "hop": obj.hop, "sr": obj.sr, "window": "hann_sqrt"})
    return info


def run_quality(arms: list[str]) -> dict:
    man = load_manifest()
    results = {"arms": {}, "mixes_per_bin": man["n_per_bin"]}
    for name in arms:
        log(f"== quality {name} ==")
        try:
            sep = build_arm(name)
        except Exception as e:
            results["arms"][name] = {"error": repr(e)}
            log(f"FAILED {name}: {e}")
            continue
        info = arm_info(name, sep)
        per_bin = {str(s): {"si_sdr": [], "sir": [], "sar": []} for s in SNR_BINS}
        sung_ret = []
        n_ok = 0
        for mix in man["mixes"]:
            mix_w = load_wav(Path(mix["base"] + "_mix.wav"))
            sp_w = load_wav(Path(mix["base"] + "_speech.wav"))
            mu_w = load_wav(Path(mix["base"] + "_music.wav"))
            t0 = time.perf_counter()
            est = sep.separate(mix_w)
            wall = time.perf_counter() - t0
            met = si_metrics(est, sp_w, mu_w)
            b = str(mix["snr_db"])
            per_bin[b]["si_sdr"].append(met["si_sdr"])
            per_bin[b]["sir"].append(met["sir"])
            per_bin[b]["sar"].append(met["sar"])
            if mix["sung"]:
                sung_ret.append(singing_retained(est, mu_w))
            n_ok += 1
            if n_ok % 8 == 0:
                log(f"  {name} {n_ok}/{len(man['mixes'])} last si_sdr={met['si_sdr']:.2f} {wall:.2f}s")
        bins = {}
        for s in SNR_BINS:
            b = str(s)
            bins[b] = {k: summarize(per_bin[b][k]) for k in ("si_sdr", "sir", "sar")}
        results["arms"][name] = {
            "info": info,
            "bins": bins,
            "singing_energy": {
                "energy_ratio": summarize([x["energy_ratio"] for x in sung_ret]),
                "proj_fraction": summarize([x["proj_fraction"] for x in sung_ret]),
                "n": len(sung_ret),
            },
            "n_mixes": n_ok,
        }
        del sep
    (OUT / "quality.json").write_text(json.dumps(results, indent=2))
    return results


def run_vlog(arms: list[str]) -> dict:
    yam = Yamnet()
    mix = load_wav(VLOG_44K)
    out = {"duration_s": mix.shape[1] / SR, "arms": {}}
    for name in arms:
        log(f"== vlog {name} ==")
        wav_path = OUT / f"{name}_vlog.wav"
        try:
            sep = build_arm(name)
            t0 = time.perf_counter()
            est = sep.separate(mix)
            wall = time.perf_counter() - t0
            nans = finite_check(est)
            sf.write(str(wav_path), est.T, SR, subtype="PCM_16")
            judge = yam.residual_music_seconds(est, SR)
            info = arm_info(name, sep)
            rec = {"info": info, "wall_s": wall, "nan_inf": nans, "wav": str(wav_path), "yamnet": judge}
            log(f"  {name} residual-music {judge['seconds']:.2f}s / {judge['duration_s']:.2f}s peak={judge['peak']:.3f}")
            del sep
        except Exception as e:
            rec = {"error": repr(e)}
            log(f"FAILED {name}: {e}")
        out["arms"][name] = rec
    (OUT / "vlog.json").write_text(json.dumps(out, indent=2))
    return out


# --- cost (subprocess, one arm) ----------------------------------------------

def cost_worker(arm: str, wav: Path, runs: int, warmup: int, output: Path | None = None) -> dict:
    if runs < 1 or warmup < 0:
        raise ValueError("runs must be positive and warmup must be non-negative")
    if output is not None and output.resolve() == wav.resolve():
        raise ValueError("output must not overwrite the input audio")
    stereo = load_wav(wav)
    audio_s = stereo.shape[1] / SR
    sep = build_arm(arm)
    for _ in range(warmup):
        _ = sep.separate(stereo)
    times = []
    last = None
    for _ in range(runs):
        t0 = time.perf_counter()
        last = sep.separate(stereo)
        times.append((time.perf_counter() - t0) * 1000.0)
    ms_s = [t / audio_s for t in times]
    rss = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    # macOS ru_maxrss is bytes; Linux is KiB. Heuristic: values > 1e7 are bytes.
    rss_mb = rss / (1024 * 1024) if rss > 10_000_000 else rss / 1024
    med = float(np.median(ms_s))
    rec = {
        "arm": arm,
        "audio_s": audio_s,
        "warmup": warmup,
        "runs": runs,
        "ms_per_s": ms_s,
        "median_ms_per_s": med,
        "peak_rss_mb": float(rss_mb),
        "file_mb": Path(getattr(sep, "path")).stat().st_size / 1e6,
        "nan_inf": finite_check(last) if last is not None else None,
        "threads": HT_THREADS,
        "ep": "CPUExecutionProvider",
        "xnnpack": False,
        "info": arm_info(arm, sep),
    }
    assert last is not None and last.shape == stereo.shape, "separator changed audio length/channels"
    assert rec["nan_inf"] == 0, "separator produced non-finite samples"
    if output is not None:
        ensure_dir(output.parent)
        sf.write(str(output), last.T, SR, subtype="FLOAT")
        rec["wav"] = str(output)
    print(json.dumps(rec), flush=True)
    return rec


def run_cost(arms: list[str]) -> dict:
    clip = OUT / "cost_60s.wav"
    ensure_dir(OUT)
    if not clip.exists():
        ffmpeg_wav(VLOG_44K, clip, SR, 2, 0.0, COST_S)
    out = {"clip": str(clip), "contended": True, "anchor_htdemucs_ms_s": 152.3, "arms": {}}
    for name in arms:
        log(f"== cost {name} (subprocess) ==")
        cmd = [str(PY), str(REPO / "scripts/bench/score_sep.py"), "cost-worker",
               "--arm", name, "--wav", str(clip), "--runs", str(COST_RUNS), "--warmup", "1"]
        try:
            p = subprocess.run(cmd, check=True, capture_output=True, text=True)
            # last JSON line
            lines = [ln for ln in p.stdout.splitlines() if ln.startswith("{")]
            rec = json.loads(lines[-1])
            rec["command"] = " ".join(cmd)
        except subprocess.CalledProcessError as e:
            rec = {"error": e.stderr[-2000:] if e.stderr else repr(e), "command": " ".join(cmd)}
            log(f"FAILED {name}: {rec['error'][:400]}")
        out["arms"][name] = rec
        if "median_ms_per_s" in rec:
            log(f"  {name} median {rec['median_ms_per_s']:.1f} ms/s  RSS {rec['peak_rss_mb']:.0f} MB")
    (OUT / "cost.json").write_text(json.dumps(out, indent=2))
    return out


# --- quantization (SEP-1) ----------------------------------------------------

def parity_db(a: np.ndarray, b: np.ndarray) -> float:
    a, b = a.astype(np.float64), b.astype(np.float64)
    n = min(a.shape[-1], b.shape[-1])
    a, b = a[..., :n], b[..., :n]
    num = np.linalg.norm(a - b)
    den = np.linalg.norm(a)
    if den < 1e-12:
        return float("inf") if num < 1e-12 else -120.0
    return float(20 * np.log10(den / max(num, 1e-12)))


def quant_sep1() -> dict:
    from onnxruntime.quantization import (
        CalibrationDataReader, QuantFormat, QuantType, quantize_dynamic, quantize_static,
    )
    from onnxruntime.quantization.shape_inference import quant_pre_process

    src = MDX_FILES["sep1"]
    fp32 = MdxSep(src)
    vlog = load_wav(VLOG_44K)
    # ~20 vlog chunks of MDX geometry
    n_cal = 20
    starts = np.linspace(0, max(1, vlog.shape[1] - fp32.chunk_size), n_cal, dtype=int)
    specs = []
    for s in starts:
        chunk = vlog[:, s:s + fp32.chunk_size]
        if chunk.shape[1] < fp32.chunk_size:
            chunk = np.concatenate([chunk, np.zeros((2, fp32.chunk_size - chunk.shape[1]), np.float32)], 1)
        spek = mdx_stft(chunk, fp32.n_fft, fp32.hop, fp32.dim_f)
        spek[:, :, :3, :] = 0
        specs.append(spek)

    class Reader(CalibrationDataReader):
        def __init__(self):
            self.i = 0
        def get_next(self):
            if self.i >= len(specs):
                return None
            d = {fp32.iname: specs[self.i]}
            self.i += 1
            return d

    qdir = ensure_dir(OUT / "quant")
    pre = qdir / "sep1_pre.onnx"
    qdq = qdir / "sep1_qdq_int8.onnx"
    dyn = qdir / "sep1_dyn_matmul_int8.onnx"
    rec = {"src": str(src), "n_calib": n_cal}

    log("quant_pre_process")
    try:
        quant_pre_process(str(src), str(pre), skip_optimization=False)
        log("quantize_static QDQ")
        quantize_static(
            str(pre), str(qdq), Reader(),
            quant_format=QuantFormat.QDQ,
            activation_type=QuantType.QInt8,
            weight_type=QuantType.QInt8,
            per_channel=True,
        )
        rec["qdq"] = {"path": str(qdq), "file_mb": qdq.stat().st_size / 1e6}
    except Exception as e:
        rec["qdq"] = {"error": repr(e)}
        log(f"QDQ failed: {e}")

    log("quantize_dynamic MatMul-only")
    try:
        quantize_dynamic(str(src), str(dyn), weight_type=QuantType.QInt8, op_types_to_quantize=["MatMul"])
        rec["dynamic"] = {"path": str(dyn), "file_mb": dyn.stat().st_size / 1e6}
    except Exception as e:
        rec["dynamic"] = {"error": repr(e)}
        log(f"dynamic failed: {e}")

    # parity on one 10 s mix
    man = load_manifest()
    mix = load_wav(Path(man["mixes"][len(man["mixes"]) // 2]["base"] + "_mix.wav"))
    ref = fp32.separate(mix)
    rec["parity_clip"] = man["mixes"][len(man["mixes"]) // 2]["id"]
    rec["fp32_nan_inf"] = finite_check(ref)
    del fp32

    for tag, path in (("qdq", qdq), ("dynamic", dyn)):
        if not path.exists():
            continue
        try:
            q = MdxSep(path)
            est = q.separate(mix)
            rec[tag]["parity_db"] = parity_db(ref, est)
            rec[tag]["nan_inf"] = finite_check(est)
            rec[tag]["onnx_input"] = f"{q.iname} {q.sess.get_inputs()[0].shape} float32"
            del q
        except Exception as e:
            rec[tag]["parity_error"] = repr(e)
            log(f"parity {tag} failed: {e}")

    (OUT / "quant.json").write_text(json.dumps(rec, indent=2))
    return rec


# --- T0 rule -----------------------------------------------------------------

def verdicts(quality: dict, cost: dict | None, vlog: dict | None) -> dict:
    """plan §5.2: SI-SDR ≥ SEP-0 − 0.5 dB every bin; SIR ≥ SEP-0 at 0 and 5 dB; cost ≤ 0.7× SEP-0.
    SEP-7 fast-mode: cost ≤ 0.1× SEP-0 and residual-music seconds ≤ 2× SEP-0."""
    arms = quality.get("arms", {})
    if "sep0" not in arms or "bins" not in arms["sep0"]:
        return {"error": "SEP-0 missing"}
    base = arms["sep0"]["bins"]
    out = {}
    c0 = None
    if cost and "sep0" in cost.get("arms", {}) and "median_ms_per_s" in cost["arms"]["sep0"]:
        c0 = cost["arms"]["sep0"]["median_ms_per_s"]
    r0 = None
    if vlog and "sep0" in vlog.get("arms", {}) and "yamnet" in vlog["arms"]["sep0"]:
        r0 = vlog["arms"]["sep0"]["yamnet"]["seconds"]

    for name in arms:
        if name == "sep0" or "bins" not in arms[name]:
            continue
        b = arms[name]["bins"]
        sdr_ok = all(
            b[str(s)]["si_sdr"]["mean"] is not None
            and b[str(s)]["si_sdr"]["mean"] >= base[str(s)]["si_sdr"]["mean"] - 0.5
            for s in SNR_BINS
        )
        sir_ok = all(
            b[str(s)]["sir"]["mean"] is not None
            and b[str(s)]["sir"]["mean"] >= base[str(s)]["sir"]["mean"]
            for s in (0, 5)
        )
        cost_ok = None
        ratio = None
        if c0 and cost and name in cost["arms"] and "median_ms_per_s" in cost["arms"][name]:
            ratio = cost["arms"][name]["median_ms_per_s"] / c0
            cost_ok = ratio <= 0.7
        default = bool(sdr_ok and sir_ok and (cost_ok is True))
        hq = False
        if all(b[str(s)]["si_sdr"]["mean"] is not None and b[str(s)]["si_sdr"]["mean"] >= base[str(s)]["si_sdr"]["mean"] + 1.0 for s in SNR_BINS):
            if ratio is not None and ratio <= 1.2:
                hq = True
        fast = None
        if name == "sep7" and c0 and r0 is not None:
            r7 = vlog["arms"].get("sep7", {}).get("yamnet", {}).get("seconds")
            c7 = cost["arms"].get("sep7", {}).get("median_ms_per_s")
            if c7 is not None and r7 is not None:
                fast = (c7 <= 0.1 * c0) and (r7 <= 2 * r0)
        out[name] = {
            "sdr_ok": sdr_ok, "sir_ok": sir_ok, "cost_ok": cost_ok, "cost_ratio": ratio,
            "advance_default": default, "advance_hq": hq, "advance_fast_mode": fast,
        }
    return out


# --- CLI ---------------------------------------------------------------------

DEFAULT_ARMS = ["sep0", "sep1", "sep2", "sep7"]


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    def add_arms(sp):
        sp.add_argument("--arms", nargs="+", default=DEFAULT_ARMS)
    add_arms(sub.add_parser("prepare"))
    add_arms(sub.add_parser("quality"))
    add_arms(sub.add_parser("vlog"))
    add_arms(sub.add_parser("cost"))
    add_arms(sub.add_parser("all"))
    sub.add_parser("quant")
    w = sub.add_parser("cost-worker")
    w.add_argument("--arm", required=True)
    w.add_argument("--wav", type=Path, required=True)
    w.add_argument("--runs", type=int, default=COST_RUNS)
    w.add_argument("--warmup", type=int, default=1)
    w.add_argument("--output", type=Path, help="save the last vocals output as a float WAV")
    args = p.parse_args()
    ensure_dir(OUT)

    if args.cmd == "prepare":
        prepare()
        return
    if args.cmd == "cost-worker":
        cost_worker(args.arm, args.wav, args.runs, args.warmup, args.output)
        return
    if args.cmd == "quality":
        run_quality(args.arms)
        return
    if args.cmd == "vlog":
        run_vlog(args.arms)
        return
    if args.cmd == "cost":
        run_cost(args.arms)
        return
    if args.cmd == "quant":
        quant_sep1()
        return
    if args.cmd == "all":
        prepare()
        q = run_quality(args.arms)
        v = run_vlog(args.arms)
        # cost is a separate mode; still run once (contended) so the write-up has numbers
        c = run_cost(args.arms)
        ver = verdicts(q, c, v)
        (OUT / "verdicts.json").write_text(json.dumps(ver, indent=2))
        log("verdicts: " + json.dumps(ver))
        return


if __name__ == "__main__":
    main()
