#!/usr/bin/env python3
"""SEP-5 SCNet Tran, SEP-6 DTTNet, SEP-9 Spleeter (plan §5.1 extra candidates).

STFT/iSTFT outside the graph. Host ORT is CPU EP (no XNNPACK on mac). Vocals stem;
keeping singing is GOOD (owner 2026-09-30).

From repo root, with `.venv-bench`:

  .venv-bench/bin/python scripts/bench/sep_light.py export
  .venv-bench/bin/python scripts/bench/sep_light.py parity
  .venv-bench/bin/python scripts/bench/sep_light.py song
  .venv-bench/bin/python scripts/bench/sep_light.py quality
  .venv-bench/bin/python scripts/bench/sep_light.py cost          # serial, one arm at a time
  .venv-bench/bin/python scripts/bench/sep_light.py device
  .venv-bench/bin/python scripts/bench/sep_light.py all
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

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import score_sep as S  # noqa: E402

REPO = S.REPO
QA_MODELS = S.QA_MODELS
OUT = S.OUT
SONG = OUT / "song"
PY = S.PY
SR = S.SR
HT_THREADS = S.HT_THREADS
SNR_BINS = S.SNR_BINS
MSST = REPO / "qa-assets/bench-out/sep_light/msst"

# --- files -------------------------------------------------------------------
SP_VOC_SRC = QA_MODELS / "spleeter_vocals.onnx"
SP_ACC_SRC = QA_MODELS / "spleeter_accompaniment.onnx"
SP_VOC = QA_MODELS / "spleeter_vocals_static.onnx"
SP_ACC = QA_MODELS / "spleeter_accompaniment_static.onnx"
DTT_CKPT = QA_MODELS / "dttnet_vocalsg32_ep4082_fix.ckpt"
DTT_CFG = QA_MODELS / "config_DTTNet_vocals.yaml"
DTT_ONNX = QA_MODELS / "dttnet_vocals_spec.onnx"
SCN_CKPT = QA_MODELS / "model_scnet_tran_sdr_8.9272.ckpt"
SCN_CFG = QA_MODELS / "config_musdb18_scnet_tran.yaml"
SCN_ONNX = QA_MODELS / "scnet-tran-core-2.75s-v1.onnx"

CLIPS = {
    "vlog_music30": SONG / "vlog_music30.wav",
    "battle_hymn30": SONG / "battle_hymn30.wav",
}

# Spleeter (sherpa-onnx, Deezer 2-stems): n_fft 4096 hop 1024, center=False, 512 frames.
SP_NFFT, SP_HOP, SP_F, SP_T = 4096, 1024, 1024, 512
SP_CHUNK = SP_NFFT + (SP_T - 1) * SP_HOP  # 527360
SP_STEP = SP_T * SP_HOP  # 524288

# DTTNet MSST vocals: n_fft 6144 hop 1024 dim_f 2048 dim_t 256, overlap 4.
DTT_NFFT, DTT_HOP, DTT_DIMF, DTT_DIMT = 6144, 1024, 2048, 256
DTT_CHUNK = 261120  # hop * (dim_t - 1)
DTT_OVERLAP = 4

# SCNet Tran ONNX (t4t2k1m 2.75 s core): n_fft 4096 hop 1024, rectangular, normalized.
SCN_NFFT, SCN_HOP, SCN_WIN = 4096, 1024, 4096
SCN_CHUNK = 121275  # 2.75 s
SCN_OVERLAP = 2
SCN_BINS = SCN_NFFT // 2 + 1  # 2049
SCN_VOCALS = 3  # drums, bass, other, vocals

COST_RUNS = 3
QUALITY_TIMEOUT_S = 40 * 60


def log(msg: str) -> None:
    print(msg, flush=True)


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def file_mb(path: Path) -> float:
    return path.stat().st_size / 1e6


def parity_db(a: np.ndarray, b: np.ndarray) -> float:
    return S.parity_db(a, b)


def device_line(arm: str, path: Path, shape: str, chunk_s: float, new_s: float, extra: str = "") -> str:
    line = f"DEVICE {arm} file={path} shape={shape} chunk_s={chunk_s} new_s={new_s}"
    if extra:
        line += " " + extra
    return line


def print_device() -> None:
    sp_chunk = SP_CHUNK / SR
    sp_new = SP_STEP / SR
    print(device_line("SEP-9", SP_VOC, "2,1,512,1024", sp_chunk, sp_new,
                      extra="(vocals U-Net; Wiener mask also needs accompaniment)"))
    print(device_line("SEP-9-acc", SP_ACC, "2,1,512,1024", sp_chunk, sp_new,
                      extra="(2nd graph, 1 input each)"))
    print(device_line("SEP-6", DTT_ONNX, "1,4,2048,256", DTT_CHUNK / SR, (DTT_CHUNK // DTT_OVERLAP) / SR))
    print(device_line("SEP-5", SCN_ONNX, "1,4,2049,120", SCN_CHUNK / SR, (SCN_CHUNK // SCN_OVERLAP) / SR))


# --- MSST generic overlap-add (DTTNet / SCNet Tran) --------------------------

def msst_ola(stereo: np.ndarray, chunk_size: int, num_overlap: int, infer_fn) -> np.ndarray:
    fade_size = chunk_size // 10
    step = chunk_size // num_overlap
    border = chunk_size - step
    mix = np.ascontiguousarray(stereo, np.float32)
    length_init = mix.shape[-1]
    if length_init > 2 * border and border > 0:
        mix = np.pad(mix, ((0, 0), (border, border)), mode="reflect")
    window = np.ones(chunk_size, np.float32)
    window[:fade_size] = np.linspace(0, 1, fade_size, dtype=np.float32)
    window[-fade_size:] = np.linspace(1, 0, fade_size, dtype=np.float32)
    result = np.zeros((2, mix.shape[-1]), np.float32)
    counter = np.zeros(mix.shape[-1], np.float32)
    starts = list(range(0, mix.shape[1], step))
    n = len(starts)
    for k, start in enumerate(starts):
        part = mix[:, start:start + chunk_size]
        chunk_len = part.shape[-1]
        pad = chunk_size - chunk_len
        if pad:
            if chunk_len > chunk_size // 2:
                part = np.pad(part, ((0, 0), (0, pad)), mode="reflect")
            else:
                part = np.pad(part, ((0, 0), (0, pad)), mode="constant")
        waves = infer_fn(part)
        win = window.copy()
        if k == 0:
            win[:fade_size] = 1
        if k == n - 1:
            win[-fade_size:] = 1
        sl = slice(start, start + chunk_len)
        result[:, sl] += waves[:, :chunk_len] * win[:chunk_len]
        counter[sl] += win[:chunk_len]
    y = result / np.maximum(counter, 1e-8)
    if length_init > 2 * border and border > 0:
        y = y[:, border:border + length_init]
    return y[:, :length_init]


# --- Spleeter ----------------------------------------------------------------

def spleeter_stft(stereo: np.ndarray) -> tuple[np.ndarray, np.ndarray, int]:
    """center=False periodic Hann. Returns (complex [2, frames, 2049], net [2, splits, 512, 1024], n_frames)."""
    win = S.hann_periodic(SP_NFFT).astype(np.float32)
    mix = np.ascontiguousarray(stereo, np.float32)
    T = mix.shape[1]
    if T < SP_NFFT:
        mix = np.pad(mix, ((0, 0), (0, SP_NFFT - T)))
        T = mix.shape[1]
    n_frames = 1 + (T - SP_NFFT) // SP_HOP
    idx = np.arange(SP_NFFT)[None, :] + np.arange(n_frames)[:, None] * SP_HOP
    framed = mix[:, idx] * win  # [2, frames, n_fft]
    spec = np.fft.rfft(framed, n=SP_NFFT, axis=-1)
    mag = np.abs(spec[:, :, :SP_F]).astype(np.float32)
    pad_f = (SP_T - n_frames % SP_T) % SP_T
    if pad_f:
        mag = np.concatenate([mag, np.zeros((2, pad_f, SP_F), np.float32)], 1)
    splits = mag.shape[1] // SP_T
    net = mag.reshape(2, splits, SP_T, SP_F)
    return spec, net, n_frames


def spleeter_istft(spec: np.ndarray, length: int) -> np.ndarray:
    """spec [2, frames, 2049] complex → [2, length]."""
    win = S.hann_periodic(SP_NFFT).astype(np.float64)
    n_frames = spec.shape[1]
    rec = np.fft.irfft(spec, n=SP_NFFT, axis=-1) * win
    ola_len = SP_NFFT + SP_HOP * (n_frames - 1)
    ola = np.zeros((2, ola_len), np.float64)
    env = np.zeros(ola_len, np.float64)
    for t in range(n_frames):
        s = t * SP_HOP
        ola[:, s:s + SP_NFFT] += rec[:, t]
        env[s:s + SP_NFFT] += win * win
    y = (ola / (env + 1e-8))[:, :length]
    if y.shape[1] < length:
        y = np.pad(y, ((0, 0), (0, length - y.shape[1])))
    return y.astype(np.float32)


class SpleeterSep:
    def __init__(self):
        self.voc = S.cpu_session(SP_VOC, HT_THREADS)
        self.acc = S.cpu_session(SP_ACC, HT_THREADS)
        self.path = SP_VOC
        self.iname = self.voc.get_inputs()[0].name
        self.oname = self.voc.get_outputs()[0].name

    def separate(self, stereo: np.ndarray) -> np.ndarray:
        mix = np.ascontiguousarray(stereo, np.float32)
        spec, net, n_frames = spleeter_stft(mix)
        splits = net.shape[1]
        v_est = np.empty_like(net)
        a_est = np.empty_like(net)
        for s in range(splits):
            x = np.ascontiguousarray(net[:, s:s + 1])
            v_est[:, s:s + 1] = self.voc.run([self.oname], {self.iname: x})[0]
            a_est[:, s:s + 1] = self.acc.run([self.oname], {self.iname: x})[0]
        v = v_est.reshape(2, -1, SP_F)[:, :n_frames]
        a = a_est.reshape(2, -1, SP_F)[:, :n_frames]
        tot = v * v + a * a + 1e-10
        mask = (v * v + 5e-11) / tot
        full = np.zeros_like(spec)
        full[:, :, :SP_F] = mask * spec[:, :, :SP_F]
        return spleeter_istft(full, mix.shape[1])


# --- DTTNet ------------------------------------------------------------------

class DttSep:
    def __init__(self):
        self.sess = S.cpu_session(DTT_ONNX, HT_THREADS)
        self.path = DTT_ONNX
        self.iname = self.sess.get_inputs()[0].name
        self.oname = self.sess.get_outputs()[0].name
        self.n_fft = DTT_NFFT
        self.hop = DTT_HOP
        self.dim_f = DTT_DIMF
        self.chunk = DTT_CHUNK

    def _chunk(self, part: np.ndarray) -> np.ndarray:
        spek = S.mdx_stft(part, self.n_fft, self.hop, self.dim_f)
        pred = self.sess.run([self.oname], {self.iname: spek})[0]  # [1,1,4,F,T]
        spek_v = pred[0, 0]  # [4, F, T]
        return S.mdx_istft(spek_v[None], self.n_fft, self.hop, self.dim_f, self.chunk)

    def separate(self, stereo: np.ndarray) -> np.ndarray:
        return msst_ola(stereo, DTT_CHUNK, DTT_OVERLAP, self._chunk)


# --- SCNet Tran (2.75 s ONNX core) -------------------------------------------

def scnet_pad_len(T: int) -> int:
    padding = SCN_HOP - (T % SCN_HOP)
    if (T + padding) // SCN_HOP % 2 == 0:
        padding += SCN_HOP
    return padding


def scnet_stft(mix: np.ndarray) -> tuple[np.ndarray, int]:
    """[2, T] → spec [1, 4, 2049, n_frames] matching torch.stft(center, window=None, normalized)."""
    T = mix.shape[1]
    padding = scnet_pad_len(T)
    mix_p = np.pad(mix, ((0, 0), (0, padding)))
    n_fft, hop = SCN_NFFT, SCN_HOP
    pad = n_fft // 2
    padded = np.stack([S.reflect_pad_1d(mix_p[c], pad, pad) for c in range(2)])
    n_frames = 1 + (padded.shape[1] - n_fft) // hop
    idx = np.arange(n_fft)[None, :] + np.arange(n_frames)[:, None] * hop
    framed = padded[:, idx]
    spec = np.fft.rfft(framed, n=n_fft, axis=-1) * (1.0 / np.sqrt(n_fft))
    out = np.zeros((1, 4, SCN_BINS, 120), np.float32)
    n_use = min(n_frames, 120)
    out[0, 0, :, :n_use] = spec[0, :n_use].real.T
    out[0, 1, :, :n_use] = spec[0, :n_use].imag.T
    out[0, 2, :, :n_use] = spec[1, :n_use].real.T
    out[0, 3, :, :n_use] = spec[1, :n_use].imag.T
    return out, padding


def scnet_istft(spek: np.ndarray, length: int, padding: int) -> np.ndarray:
    """spek [8, F, T, 2] vocals at stems 6,7 (ch0, ch1). Returns [2, length]."""
    n_fft, hop = SCN_NFFT, SCN_HOP
    n_frames = spek.shape[2]
    unscale = np.sqrt(n_fft)
    rec = np.empty((2, n_frames, n_fft), np.float64)
    for ch, si in enumerate((6, 7)):
        cplx = spek[si, :, :, 0] + 1j * spek[si, :, :, 1]  # [F, T]
        rec[ch] = np.fft.irfft(cplx.T * unscale, n=n_fft, axis=-1)
    ola_len = n_fft + hop * (n_frames - 1)
    ola = np.zeros((2, ola_len), np.float64)
    env = np.zeros(ola_len, np.float64)
    for t in range(n_frames):
        s = t * hop
        ola[:, s:s + n_fft] += rec[:, t]
        env[s:s + n_fft] += 1.0
    ola /= env + 1e-8
    pad = n_fft // 2
    y = ola[:, pad:pad + length + padding]
    return y[:, :length].astype(np.float32)


class ScnetSep:
    def __init__(self):
        self.sess = S.cpu_session(SCN_ONNX, HT_THREADS)
        self.path = SCN_ONNX
        self.iname = self.sess.get_inputs()[0].name
        self.oname = self.sess.get_outputs()[0].name
        self.chunk = SCN_CHUNK

    def _chunk(self, part: np.ndarray) -> np.ndarray:
        spec, padding = scnet_stft(part)
        pred = self.sess.run([self.oname], {self.iname: spec})[0]
        return scnet_istft(pred, part.shape[1], padding)

    def separate(self, stereo: np.ndarray) -> np.ndarray:
        return msst_ola(stereo, SCN_CHUNK, SCN_OVERLAP, self._chunk)


def build_arm(name: str):
    if name in ("sep9", "SEP-9"):
        return SpleeterSep()
    if name in ("sep6", "SEP-6"):
        return DttSep()
    if name in ("sep5", "SEP-5"):
        return ScnetSep()
    raise ValueError(name)


def norm_arm(name: str) -> str:
    return {"SEP-5": "sep5", "SEP-6": "sep6", "SEP-9": "sep9", "sep5": "sep5", "sep6": "sep6", "sep9": "sep9"}[name]


def wav_tag(name: str) -> str:
    return {"sep5": "SEP-5", "sep6": "SEP-6", "sep9": "SEP-9"}[norm_arm(name)]


# --- export ------------------------------------------------------------------

def freeze_spleeter() -> None:
    import onnx
    from onnxruntime.tools.onnx_model_utils import make_dim_param_fixed, fix_output_shapes
    for src, dst in ((SP_VOC_SRC, SP_VOC), (SP_ACC_SRC, SP_ACC)):
        if not src.exists():
            raise FileNotFoundError(src)
        m = onnx.load(str(src))
        make_dim_param_fixed(m.graph, "num_splits", 1)
        fix_output_shapes(m)
        onnx.save(m, str(dst))
        log(f"froze {src.name} -> {dst.name} {file_mb(dst):.3f} MB sha256={sha256(dst)}")


def export_dttnet() -> None:
    import torch
    import torch.nn as nn
    sys.path.insert(0, str(MSST))
    from utils.settings import get_model_from_config

    model, _cfg = get_model_from_config("dttnet", str(DTT_CFG))
    sd = torch.load(str(DTT_CKPT), map_location="cpu", weights_only=False)
    model.load_state_dict(sd)
    model.eval()

    class SpecNet(nn.Module):
        def __init__(self, net):
            super().__init__()
            self.net = net

        def forward(self, x):
            net = self.net
            x = net.first_conv(x)
            x = x.transpose(-1, -2)
            ds_outputs = []
            for i in range(net.n):
                x = net.encoding_blocks[i](x)
                ds_outputs.append(x)
                x = net.ds[i](x)
            x = net.bottleneck_block1(x)
            x = net.bottleneck_block2(x)
            for i in range(net.n):
                x = net.us[i](x)
                x = x * ds_outputs[-i - 1]
                x = net.decoding_blocks[i](x)
            x = x.transpose(-1, -2)
            x = net.final_conv(x)
            b, c, f, t = x.shape
            return x.reshape(b, net.num_target_instruments, -1, f, t)

    specnet = SpecNet(model).eval()
    dummy = torch.zeros(1, 4, DTT_DIMF, DTT_DIMT)
    torch.onnx.export(
        specnet, dummy, str(DTT_ONNX),
        input_names=["spec"], output_names=["vocals_spec"],
        opset_version=17, dynamo=False,
    )
    log(f"exported {DTT_ONNX.name} {file_mb(DTT_ONNX):.3f} MB sha256={sha256(DTT_ONNX)}")


def run_export() -> None:
    S.ensure_dir(QA_MODELS)
    freeze_spleeter()
    if not DTT_ONNX.exists() or DTT_ONNX.stat().st_size < 1_000_000:
        export_dttnet()
    else:
        log(f"keep existing {DTT_ONNX.name} {file_mb(DTT_ONNX):.3f} MB sha256={sha256(DTT_ONNX)}")
    if not SCN_ONNX.exists():
        raise FileNotFoundError(SCN_ONNX)
    log(f"SCNet ONNX {SCN_ONNX.name} {file_mb(SCN_ONNX):.3f} MB sha256={sha256(SCN_ONNX)}")
    # load + run one dummy each
    for p, shp in (
        (SP_VOC, (2, 1, 512, 1024)),
        (SP_ACC, (2, 1, 512, 1024)),
        (DTT_ONNX, (1, 4, 2048, 256)),
        (SCN_ONNX, (1, 4, 2049, 120)),
    ):
        sess = S.cpu_session(p, 1)
        x = np.zeros(shp, np.float32)
        y = sess.run(None, {sess.get_inputs()[0].name: x})[0]
        log(f"  load-ok {p.name} in={list(shp)} out={list(y.shape)} nan={S.finite_check(y)}")
        ins = sess.get_inputs()
        log(f"    inputs={[ (i.name, i.shape, i.type) for i in ins ]}")
    print_device()


# --- pytorch refs for parity -------------------------------------------------

def dttnet_pytorch_chunk(wav: np.ndarray) -> np.ndarray:
    import torch
    sys.path.insert(0, str(MSST))
    from utils.settings import get_model_from_config
    model, _ = get_model_from_config("dttnet", str(DTT_CFG))
    sd = torch.load(str(DTT_CKPT), map_location="cpu", weights_only=False)
    model.load_state_dict(sd)
    model.eval()
    x = torch.from_numpy(np.ascontiguousarray(wav[None]))
    with torch.no_grad():
        y = model(x)[0, 0].numpy()  # [2, T] vocals
    return y.astype(np.float32)


def scnet_pytorch_chunk(wav: np.ndarray) -> np.ndarray:
    import torch
    sys.path.insert(0, str(MSST))
    from utils.settings import get_model_from_config
    model, _ = get_model_from_config("scnet_tran", str(SCN_CFG))
    sd = torch.load(str(SCN_CKPT), map_location="cpu", weights_only=False)
    model.load_state_dict(sd)
    model.eval()
    x = torch.from_numpy(np.ascontiguousarray(wav[None]))
    with torch.no_grad():
        y = model(x)[0, SCN_VOCALS].numpy()
    return y.astype(np.float32)


def run_parity() -> dict:
    clip = S.load_wav(CLIPS["vlog_music30"])
    rec: dict = {"clip": str(CLIPS["vlog_music30"]), "commands": []}

    # SEP-9: static vs original sherpa ONNX (official PyTorch→ONNX export)
    log("== parity SEP-9 spleeter freeze vs original ==")
    chunk = clip[:, :SP_CHUNK]
    spec, net, n_frames = spleeter_stft(chunk)
    x = np.ascontiguousarray(net[:, :1])
    so_orig = S.cpu_session(SP_VOC_SRC, HT_THREADS)
    so_st = S.cpu_session(SP_VOC, HT_THREADS)
    y0 = so_orig.run(None, {so_orig.get_inputs()[0].name: x})[0]
    y1 = so_st.run(None, {so_st.get_inputs()[0].name: x})[0]
    rec["sep9"] = {
        "note": "sherpa-onnx official ONNX is the PyTorch export; no separate .pt shipped. Freeze vs original.",
        "input_shape": list(x.shape),
        "snr_db_static_vs_orig": parity_db(y0, y1),
        "max_abs": float(np.abs(y0 - y1).max()),
    }
    log(f"  SEP-9 freeze vs orig SNR {rec['sep9']['snr_db_static_vs_orig']} dB")

    # SEP-6 DTTNet: one native chunk, pytorch waveform vs ONNX spec path
    log("== parity SEP-6 DTTNet pytorch vs ONNX ==")
    dchunk = clip[:, :DTT_CHUNK]
    t0 = time.perf_counter()
    pt = dttnet_pytorch_chunk(dchunk)
    rec["sep6_pt_s"] = time.perf_counter() - t0
    dtt = DttSep()
    t0 = time.perf_counter()
    spek = S.mdx_stft(dchunk, DTT_NFFT, DTT_HOP, DTT_DIMF)
    pred = dtt.sess.run([dtt.oname], {dtt.iname: spek})[0]
    onnx_w = S.mdx_istft(pred[0, 0][None], DTT_NFFT, DTT_HOP, DTT_DIMF, DTT_CHUNK)
    rec["sep6_onnx_s"] = time.perf_counter() - t0
    rec["sep6"] = {
        "chunk_samples": DTT_CHUNK,
        "snr_db_vocals": parity_db(pt, onnx_w),
        "pt_nan": S.finite_check(pt),
        "onnx_nan": S.finite_check(onnx_w),
        "onnx_in": f"{dtt.iname} {dtt.sess.get_inputs()[0].shape} float32",
    }
    log(f"  SEP-6 vocals SNR {rec['sep6']['snr_db_vocals']:.3f} dB")
    del dtt

    # SEP-5 SCNet Tran: one 2.75 s chunk
    log("== parity SEP-5 SCNet Tran pytorch vs ONNX ==")
    schunk = clip[:, :SCN_CHUNK]
    t0 = time.perf_counter()
    pt = scnet_pytorch_chunk(schunk)
    rec["sep5_pt_s"] = time.perf_counter() - t0
    scn = ScnetSep()
    t0 = time.perf_counter()
    onnx_w = scn._chunk(schunk)
    rec["sep5_onnx_s"] = time.perf_counter() - t0
    rec["sep5"] = {
        "chunk_samples": SCN_CHUNK,
        "snr_db_vocals": parity_db(pt, onnx_w),
        "pt_nan": S.finite_check(pt),
        "onnx_nan": S.finite_check(onnx_w),
        "onnx_in": f"{scn.iname} {scn.sess.get_inputs()[0].shape} float32",
    }
    log(f"  SEP-5 vocals SNR {rec['sep5']['snr_db_vocals']:.3f} dB")
    del scn

    S.ensure_dir(OUT)
    (OUT / "sep_light_parity.json").write_text(json.dumps(rec, indent=2))
    return rec


# --- song / quality / cost ---------------------------------------------------

def arm_info(name: str, obj) -> dict:
    name = norm_arm(name)
    info = {"arm": name, "ep": "CPUExecutionProvider", "threads": HT_THREADS, "xnnpack": False,
            "file": str(obj.path), "file_mb": file_mb(Path(obj.path))}
    if name == "sep9":
        info.update({
            "n_fft": SP_NFFT, "hop": SP_HOP, "center": False, "window": "hann_periodic",
            "frames": SP_T, "bins": SP_F, "chunk_s": SP_CHUNK / SR, "overlap_splits": 0,
            "onnx_input": "x [2,1,512,1024] f32 (vocals + accompaniment graphs)",
            "licence": "MIT (Deezer Spleeter; sherpa-onnx export Apache-2.0 conversion)",
        })
    if name == "sep6":
        info.update({
            "n_fft": DTT_NFFT, "hop": DTT_HOP, "dim_f": DTT_DIMF, "dim_t": DTT_DIMT,
            "chunk": DTT_CHUNK, "chunk_s": DTT_CHUNK / SR, "num_overlap": DTT_OVERLAP,
            "onnx_input": "spec [1,4,2048,256] f32",
            "licence": "Apache-2.0 code; MUSDB18-HQ training data (amber)",
        })
    if name == "sep5":
        info.update({
            "n_fft": SCN_NFFT, "hop": SCN_HOP, "window": "rectangular", "normalized": True,
            "chunk": SCN_CHUNK, "chunk_s": SCN_CHUNK / SR, "num_overlap": SCN_OVERLAP,
            "onnx_input": "spec [1,4,2049,120] f32",
            "stems": "drums,bass,other,vocals — take vocals",
            "licence": "MIT code; MUSDB18-HQ training data (amber)",
        })
    return info


def run_song(arms: list[str]) -> dict:
    rec = {"arms": {}}
    for name in arms:
        name = norm_arm(name)
        log(f"== song {name} ==")
        try:
            sep = build_arm(name)
            info = arm_info(name, sep)
            files = {}
            for cid, path in CLIPS.items():
                wav = S.load_wav(path)
                t0 = time.perf_counter()
                est = sep.separate(wav)
                wall = time.perf_counter() - t0
                dest = SONG / f"{cid}_{wav_tag(name)}.wav"
                sf.write(str(dest), est.T, SR, subtype="PCM_16")
                files[cid] = {"wav": str(dest), "wall_s": wall, "nan_inf": S.finite_check(est)}
                log(f"  {cid} {wall:.2f}s -> {dest.name} nan={files[cid]['nan_inf']}")
            rec["arms"][name] = {"info": info, "files": files}
            del sep
        except Exception as e:
            rec["arms"][name] = {"error": repr(e)}
            log(f"FAILED {name}: {e}")
    (OUT / "sep_light_song.json").write_text(json.dumps(rec, indent=2))
    return rec


def sep0_bins() -> dict | None:
    p = OUT / "quality.json"
    if not p.exists():
        return None
    q = json.loads(p.read_text())
    return q.get("arms", {}).get("sep0", {}).get("bins")


def run_quality(arms: list[str], subset: int | None = None) -> dict:
    man = S.load_manifest()
    mixes = man["mixes"]
    every = subset or 1
    if every == "balanced":
        seen = set()
        chosen = []
        for m in mixes:
            key = (m["snr_db"], m["music"])
            if key in seen:
                continue
            seen.add(key)
            chosen.append(m)
        every = "balanced"
    else:
        every = int(every)
        chosen = mixes[::every]
    base = sep0_bins()
    t_all0 = time.perf_counter()
    names = [norm_arm(a) for a in arms]
    for i, name in enumerate(names):
        log(f"== quality {name} n={len(chosen)} every={every} ==")
        t_arm0 = time.perf_counter()
        try:
            sep = build_arm(name)
        except Exception as e:
            rec = {"error": repr(e)}
            (OUT / f"{name}_quality.json").write_text(json.dumps(rec, indent=2))
            log(f"FAILED {name}: {e}")
            continue
        info = arm_info(name, sep)
        per_bin = {str(s): {"si_sdr": [], "sir": [], "sar": []} for s in SNR_BINS}
        sung_ret = []
        n_ok = 0
        too_slow = False
        for mix in chosen:
            mix_w = S.load_wav(Path(mix["base"] + "_mix.wav"))
            sp_w = S.load_wav(Path(mix["base"] + "_speech.wav"))
            mu_w = S.load_wav(Path(mix["base"] + "_music.wav"))
            t0 = time.perf_counter()
            est = sep.separate(mix_w)
            wall = time.perf_counter() - t0
            met = S.si_metrics(est, sp_w, mu_w)
            b = str(mix["snr_db"])
            per_bin[b]["si_sdr"].append(met["si_sdr"])
            per_bin[b]["sir"].append(met["sir"])
            per_bin[b]["sar"].append(met["sar"])
            if mix["sung"]:
                sung_ret.append(S.singing_retained(est, mu_w))
            n_ok += 1
            if n_ok % 4 == 0:
                log(f"  {name} {n_ok}/{len(chosen)} last si_sdr={met['si_sdr']:.2f} {wall:.2f}s")
            elapsed = time.perf_counter() - t_arm0
            if n_ok == 4 and every == 1:
                eta = elapsed / n_ok * len(chosen)
                if eta > QUALITY_TIMEOUT_S:
                    log(f"  {name} eta {eta/60:.1f} min > 40; this arm only, balanced 16-mix subset")
                    too_slow = True
                    break
        if too_slow:
            del sep
            run_quality([name], subset="balanced")
            continue
        bins = {str(s): {k: S.summarize(per_bin[str(s)][k]) for k in ("si_sdr", "sir", "sar")} for s in SNR_BINS}
        delta = None
        if base:
            delta = {}
            for s in SNR_BINS:
                b = str(s)
                delta[b] = {
                    k: (None if bins[b][k]["mean"] is None or base[b][k]["mean"] is None
                        else bins[b][k]["mean"] - base[b][k]["mean"])
                    for k in ("si_sdr", "sir", "sar")
                }
        rec = {
            "info": info,
            "bins": bins,
            "delta_vs_sep0": delta,
            "singing_energy": {
                "energy_ratio": S.summarize([x["energy_ratio"] for x in sung_ret]),
                "proj_fraction": S.summarize([x["proj_fraction"] for x in sung_ret]),
                "n": len(sung_ret),
            },
            "n_mixes": n_ok,
            "n_total": len(mixes),
            "subset": None if every == 1 else (every if every == "balanced" else f"every_{every}"),
            "wall_s": time.perf_counter() - t_arm0,
        }
        (OUT / f"{name}_quality.json").write_text(json.dumps(rec, indent=2))
        log(f"  wrote {name}_quality.json n={n_ok} wall={rec['wall_s']:.1f}s")
        del sep
        if time.perf_counter() - t_all0 > QUALITY_TIMEOUT_S * 2:
            log("quality overall budget exceeded")
    return {}


def cost_worker(arm: str, wav: Path, runs: int, warmup: int) -> dict:
    stereo = S.load_wav(wav)
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
    rss_mb = rss / (1024 * 1024) if rss > 10_000_000 else rss / 1024
    rec = {
        "arm": norm_arm(arm),
        "audio_s": audio_s,
        "warmup": warmup,
        "runs": runs,
        "ms_per_s": ms_s,
        "median_ms_per_s": float(np.median(ms_s)),
        "peak_rss_mb": float(rss_mb),
        "file_mb": file_mb(Path(getattr(sep, "path"))),
        "nan_inf": S.finite_check(last) if last is not None else None,
        "threads": HT_THREADS,
        "ep": "CPUExecutionProvider",
        "xnnpack": False,
        "info": arm_info(arm, sep),
        "contended": True,
    }
    print(json.dumps(rec), flush=True)
    return rec


def run_cost(arms: list[str]) -> dict:
    clip = OUT / "cost_60s.wav"
    S.ensure_dir(OUT)
    if not clip.exists():
        S.ffmpeg_wav(S.VLOG_44K, clip, SR, 2, 0.0, S.COST_S)
    out = {"clip": str(clip), "contended": True, "arms": {}}
    for name in arms:
        name = norm_arm(name)
        log(f"== cost {name} (subprocess) ==")
        cmd = [str(PY), str(HERE / "sep_light.py"), "cost-worker",
               "--arm", name, "--wav", str(clip), "--runs", str(COST_RUNS), "--warmup", "1"]
        try:
            p = subprocess.run(cmd, check=True, capture_output=True, text=True)
            lines = [ln for ln in p.stdout.splitlines() if ln.startswith("{")]
            rec = json.loads(lines[-1])
            rec["command"] = " ".join(cmd)
        except subprocess.CalledProcessError as e:
            rec = {"error": (e.stderr or e.stdout or repr(e))[-2000:], "command": " ".join(cmd)}
            log(f"FAILED {name}: {str(rec.get('error'))[:400]}")
        out["arms"][name] = rec
        if "median_ms_per_s" in rec:
            log(f"  {name} median {rec['median_ms_per_s']:.1f} ms/s  RSS {rec['peak_rss_mb']:.0f} MB")
    (OUT / "sep_light_cost.json").write_text(json.dumps(out, indent=2))
    return out


DEFAULT_ARMS = ["sep9", "sep6", "sep5"]


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)

    def add_arms(sp):
        sp.add_argument("--arms", nargs="+", default=DEFAULT_ARMS)

    sub.add_parser("export")
    sub.add_parser("parity")
    sub.add_parser("device")
    add_arms(sub.add_parser("song"))
    q = sub.add_parser("quality")
    add_arms(q)
    q.add_argument("--every", default="1", help="1 | 4 | balanced (one of each music per SNR bin)")
    add_arms(sub.add_parser("cost"))
    add_arms(sub.add_parser("all"))
    w = sub.add_parser("cost-worker")
    w.add_argument("--arm", required=True)
    w.add_argument("--wav", type=Path, required=True)
    w.add_argument("--runs", type=int, default=COST_RUNS)
    w.add_argument("--warmup", type=int, default=1)
    args = p.parse_args()
    S.ensure_dir(OUT)
    S.ensure_dir(SONG)

    if args.cmd == "export":
        run_export()
        return
    if args.cmd == "parity":
        run_parity()
        return
    if args.cmd == "device":
        print_device()
        return
    if args.cmd == "cost-worker":
        cost_worker(args.arm, args.wav, args.runs, args.warmup)
        return
    if args.cmd == "song":
        run_song(args.arms)
        return
    if args.cmd == "quality":
        ev = args.every
        subset = None if str(ev) == "1" else (ev if ev == "balanced" else int(ev))
        run_quality(args.arms, subset=subset)
        return
    if args.cmd == "cost":
        run_cost(args.arms)
        return
    if args.cmd == "all":
        run_export()
        run_parity()
        run_song(args.arms)
        run_quality(args.arms)
        run_cost(args.arms)
        print_device()
        return


if __name__ == "__main__":
    main()
