#!/usr/bin/env python3
"""SEP-R2 / SEP-R3 / Moises-Light T0 (plan §5.1 extra candidates).

STFT/iSTFT stay outside the ONNX graph (spec-in / mask-out). Host ORT is CPU EP
(no XNNPACK on mac). Vocals stem is the score target; keeping singing is good.

From repo root, with `.venv-bench`:

  .venv-bench/bin/python scripts/bench/sep_roformer.py download
  .venv-bench/bin/python scripts/bench/sep_roformer.py export
  .venv-bench/bin/python scripts/bench/sep_roformer.py parity
  .venv-bench/bin/python scripts/bench/sep_roformer.py song
  .venv-bench/bin/python scripts/bench/sep_roformer.py quality
  .venv-bench/bin/python scripts/bench/sep_roformer.py cost          # serial, one arm
  .venv-bench/bin/python scripts/bench/sep_roformer.py device
  .venv-bench/bin/python scripts/bench/sep_roformer.py all
"""
from __future__ import annotations

import argparse
import hashlib
import inspect
import json
import os
import resource
import subprocess
import sys
import time
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import onnxruntime as ort
import soundfile as sf

sys.path.insert(0, str(Path(__file__).resolve().parent))
import score_sep as S  # noqa: E402

REPO = S.REPO
PY = S.PY
QA_MODELS = S.QA_MODELS
OUT = S.OUT
SONG = OUT / "song"
SR = S.SR
HT_THREADS = S.HT_THREADS
SNR_BINS = S.SNR_BINS
COST_S = S.COST_S
COST_RUNS = S.COST_RUNS

MSST = QA_MODELS / "msst"
OPSET = 17
QUALITY_BUDGET_S = 40 * 60


def log(msg: str) -> None:
    print(msg, flush=True)


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def curl(url: str, dest: Path) -> None:
    S.ensure_dir(dest.parent)
    part = dest.with_suffix(dest.suffix + ".part")
    cmd = ["curl", "-L", "--fail", "--retry", "5", "-C", "-", "-o", str(part), url]
    log(" ".join(cmd))
    subprocess.check_call(cmd)
    part.replace(dest)


# --- catalog -----------------------------------------------------------------

@dataclass(frozen=True)
class Arm:
    name: str          # sepr2 / sepr3
    kind: str          # bs / mel
    ckpt: Path
    cfg: Path
    onnx: Path
    url: str
    cfg_url: str
    licence: str
    # filled from yaml after load
    chunk: int = 0
    hop: int = 0
    n_fft: int = 2048
    overlap: int = 2
    notes: str = ""


SEPR3 = Arm(
    name="sepr3",
    kind="mel",
    ckpt=QA_MODELS / "MelBandRoformer.ckpt",
    cfg=QA_MODELS / "config_vocals_mel_band_roformer_kj.yaml",
    onnx=QA_MODELS / "sepr3_melband_kim_T801.onnx",
    url="https://huggingface.co/KimberleyJSN/melbandroformer/resolve/main/MelBandRoformer.ckpt",
    cfg_url="https://raw.githubusercontent.com/ZFTurbo/Music-Source-Separation-Training/main/configs/KimberleyJensen/config_vocals_mel_band_roformer_kj.yaml",
    licence="MIT (HF model card KimberleyJSN/melbandroformer)",
    notes="KimberleyJensen Mel-Band RoFormer vocals, MSST config_vocals_mel_band_roformer_kj",
)
SEPR2_ANVUEW = Arm(
    name="sepr2",
    kind="bs",
    ckpt=QA_MODELS / "bs_roformer_ft1_anvuew_sdr_12.55.ckpt",
    cfg=QA_MODELS / "bs_roformer_anvuew.yaml",
    onnx=QA_MODELS / "sepr2_bsroformer_anvuew.onnx",
    url="https://huggingface.co/anvuew/BS-RoFormer/resolve/main/bs_roformer_ft1_anvuew_sdr_12.55.ckpt",
    cfg_url="https://huggingface.co/anvuew/BS-RoFormer/resolve/main/config.yaml",
    licence="GPL-3.0 (HF card anvuew/BS-RoFormer)",
    notes="anvuew BS-RoFormer vocals ft1 SDR 12.55; target_instrument=vocals",
)
SEPR2_VIPERX = Arm(
    name="sepr2",
    kind="bs",
    ckpt=QA_MODELS / "model_bs_roformer_ep_317_sdr_12.9755.ckpt",
    cfg=QA_MODELS / "model_bs_roformer_ep_317_sdr_12.9755.yaml",
    onnx=QA_MODELS / "sepr2_bsroformer_viperx_T801.onnx",
    url="https://github.com/TRvlvr/model_repo/releases/download/all_public_uvr_models/model_bs_roformer_ep_317_sdr_12.9755.ckpt",
    cfg_url="https://raw.githubusercontent.com/ZFTurbo/Music-Source-Separation-Training/main/configs/viperx/model_bs_roformer_ep_317_sdr_12.9755.yaml",
    licence="none stated on TRvlvr/model_repo; owner said licence is not a constraint",
    notes="viperx BS-RoFormer vocals model_bs_roformer_ep_317_sdr_12.9755 (fallback)",
)

ARMS_DEFAULT = ["sepr3", "sepr2"]
META = OUT / "roformer_meta.json"


def load_yaml(path: Path):
    import yaml
    from ml_collections import ConfigDict
    with open(path) as f:
        return ConfigDict(yaml.load(f, Loader=yaml.FullLoader))


def arm_from_cfg(arm: Arm) -> Arm:
    cfg = load_yaml(arm.cfg)
    chunk = int(cfg.audio.chunk_size)
    hop = int(cfg.model.stft_hop_length)
    n_fft = int(cfg.model.stft_n_fft)
    overlap = int(cfg.inference.num_overlap)
    return Arm(
        name=arm.name, kind=arm.kind, ckpt=arm.ckpt, cfg=arm.cfg, onnx=arm.onnx,
        url=arm.url, cfg_url=arm.cfg_url, licence=arm.licence, notes=arm.notes,
        chunk=chunk, hop=hop, n_fft=n_fft, overlap=overlap,
    )


def spec_frames(chunk: int, hop: int) -> int:
    return chunk // hop + 1


def spec_bins(n_fft: int) -> int:
    return (n_fft // 2 + 1) * 2  # stereo interleaved (f s)


# --- numpy STFT / OLA (MSST geometry; torch.stft center=True, periodic Hann) -

def hann_periodic(n: int) -> np.ndarray:
    return 0.5 * (1.0 - np.cos(2.0 * np.pi * np.arange(n) / n))


def stft_stereo(audio: np.ndarray, n_fft: int, hop: int) -> np.ndarray:
    """[2, N] -> [1, (F*2), T, 2] float32, channel interleaved inside frequency."""
    window = hann_periodic(n_fft).astype(np.float64)
    pad = n_fft // 2
    padded = np.pad(audio.astype(np.float64), ((0, 0), (pad, pad)), mode="reflect")
    n_frames = 1 + (padded.shape[1] - n_fft) // hop
    idx = np.arange(n_fft)[None, :] + np.arange(n_frames)[:, None] * hop
    framed = padded[:, idx] * window  # [2, T, n_fft]
    spec = np.fft.rfft(framed, n=n_fft, axis=-1)  # [2, T, F]
    real = np.stack([spec.real, spec.imag], -1)  # [2, T, F, 2]
    # (f s) with s fastest: [F, 2, T, 2] -> [(F*2), T, 2]
    interleaved = real.transpose(2, 0, 1, 3).reshape(-1, n_frames, 2)
    return np.ascontiguousarray(interleaved[None].astype(np.float32))


def istft_stereo(spec: np.ndarray, n_fft: int, hop: int, length: int) -> np.ndarray:
    """[(F*2), T, 2] -> [2, length]."""
    window = hann_periodic(n_fft).astype(np.float64)
    n_freq = n_fft // 2 + 1
    n_frames = spec.shape[1]
    per = spec.reshape(n_freq, 2, n_frames, 2).transpose(1, 0, 2, 3)
    cspec = per[..., 0] + 1j * per[..., 1]  # [2, F, T]
    rec = np.fft.irfft(cspec, n=n_fft, axis=1) * window[None, :, None]  # [2, n_fft, T]
    ola_len = n_fft + hop * (n_frames - 1)
    ola = np.zeros((2, ola_len), np.float64)
    env = np.zeros(ola_len, np.float64)
    for t in range(n_frames):
        s = t * hop
        ola[:, s:s + n_fft] += rec[:, :, t]
        env[s:s + n_fft] += window * window
    start = n_fft // 2
    stop = min(start + length, ola_len)
    out = ola[:, start:stop] / (env[start:stop] + 1e-8)
    if out.shape[1] < length:
        out = np.pad(out, ((0, 0), (0, length - out.shape[1])))
    return out.astype(np.float32)


def complex_mul(spec: np.ndarray, mask: np.ndarray) -> np.ndarray:
    ar, ai = spec[..., 0], spec[..., 1]
    br, bi = mask[..., 0], mask[..., 1]
    return np.stack([ar * br - ai * bi, ar * bi + ai * br], -1)


def zero_dc(masked: np.ndarray, channels: int = 2) -> np.ndarray:
    masked = masked.copy()
    masked[..., :channels, :, :] = 0.0
    return masked


def windowing_array(chunk: int, fade: int) -> np.ndarray:
    w = np.ones(chunk, np.float64)
    w[-fade:] = np.linspace(1, 0, fade)
    w[:fade] = np.linspace(0, 1, fade)
    return w


@dataclass
class Plan:
    chunk: int
    step: int
    fade: int
    border: int
    padded: int
    original: int
    was_padded: bool


def plan_chunks(length: int, chunk: int, overlap: int) -> Plan:
    fade = chunk // 10
    step = chunk // overlap
    border = chunk - step
    was = length > 2 * border and border > 0
    padded = length + 2 * border if was else length
    return Plan(chunk, step, fade, border, padded, length, was)


def pad_mix(mix: np.ndarray, plan: Plan) -> np.ndarray:
    if not plan.was_padded:
        return mix
    return np.pad(mix, ((0, 0), (plan.border, plan.border)), mode="reflect")


# --- ONNX arm ----------------------------------------------------------------

class RoformerOrt:
    def __init__(self, arm: Arm):
        arm = arm_from_cfg(arm)
        self.arm = arm
        self.path = arm.onnx
        self.sess = S.cpu_session(arm.onnx, HT_THREADS, arena=False)
        self.iname = self.sess.get_inputs()[0].name
        self.oname = self.sess.get_outputs()[0].name
        self.in_shape = tuple(self.sess.get_inputs()[0].shape)
        self.chunk = arm.chunk
        self.hop = arm.hop
        self.n_fft = arm.n_fft
        self.overlap = arm.overlap

    def run_spec(self, spec: np.ndarray) -> np.ndarray:
        return self.sess.run([self.oname], {self.iname: spec})[0]

    def separate_chunk(self, chunk: np.ndarray) -> np.ndarray:
        spec = stft_stereo(chunk, self.n_fft, self.hop)
        mask = self.run_spec(spec)
        masked = complex_mul(spec[:, None], mask)[0]  # [N, F*C, T, 2]
        masked = zero_dc(masked, 2)
        return istft_stereo(masked[0], self.n_fft, self.hop, chunk.shape[1])

    def separate(self, stereo: np.ndarray) -> np.ndarray:
        mix = np.ascontiguousarray(stereo, np.float32)
        plan = plan_chunks(mix.shape[1], self.chunk, self.overlap)
        padded = pad_mix(mix, plan)
        result = np.zeros((2, padded.shape[1]), np.float64)
        counter = np.zeros(padded.shape[1], np.float64)
        total = padded.shape[1]
        n = 0
        for start in range(0, total, plan.step):
            part = padded[:, start:start + plan.chunk]
            seg_len = part.shape[1]
            pad_mode = "reflect" if seg_len > plan.chunk // 2 else "constant"
            if seg_len < plan.chunk:
                part = np.pad(part, ((0, 0), (0, plan.chunk - seg_len)), mode=pad_mode)
            win = windowing_array(plan.chunk, plan.fade)
            if start == 0:
                win[:plan.fade] = 1.0
            elif start + plan.step >= total:
                win[-plan.fade:] = 1.0
            stems = self.separate_chunk(part.astype(np.float32))
            result[:, start:start + seg_len] += stems[:, :seg_len] * win[:seg_len]
            counter[start:start + seg_len] += win[:seg_len]
            n += 1
        with np.errstate(invalid="ignore", divide="ignore"):
            out = result / np.maximum(counter, 1e-8)
        out = np.nan_to_num(out, nan=0.0)
        if plan.was_padded:
            out = out[:, plan.border:-plan.border]
        return out.astype(np.float32)


# --- torch load / spec-in wrapper (export + parity only) ---------------------

def _msst_path() -> None:
    p = str(MSST)
    if p not in sys.path:
        sys.path.insert(0, p)


def load_cfg_kwargs(arm: Arm, cls) -> dict:
    cfg = load_yaml(arm.cfg)
    raw = dict(cfg.model)
    raw["flash_attn"] = False
    raw["use_torch_checkpoint"] = False
    if "freqs_per_bands" in raw and not isinstance(raw["freqs_per_bands"], tuple):
        raw["freqs_per_bands"] = tuple(raw["freqs_per_bands"])
    if "multi_stft_resolutions_window_sizes" in raw and not isinstance(
        raw["multi_stft_resolutions_window_sizes"], tuple
    ):
        raw["multi_stft_resolutions_window_sizes"] = tuple(raw["multi_stft_resolutions_window_sizes"])
    sig = inspect.signature(cls.__init__)
    return {k: v for k, v in raw.items() if k in sig.parameters}


def load_state(model, path: Path) -> None:
    import torch
    ckpt = torch.load(str(path), map_location="cpu", weights_only=False)
    if isinstance(ckpt, dict):
        for k in ("state", "state_dict", "model_state_dict"):
            if k in ckpt and isinstance(ckpt[k], dict):
                ckpt = ckpt[k]
                break
    missing, unexpected = model.load_state_dict(ckpt, strict=False)
    log(f"load {path.name}: missing={len(missing)} unexpected={len(unexpected)}")
    if missing:
        log("  missing sample: " + ", ".join(list(missing)[:8]))


class SpecInMaskOut:
    """Built after the inner MSST module exists; registered as nn.Module at runtime."""

    @staticmethod
    def wrap(inner, kind: str):
        import torch
        from torch import nn
        from einops import rearrange

        class _Net(nn.Module):
            def __init__(self):
                super().__init__()
                self.inner = inner
                self.kind = kind
                for m in inner.modules():
                    if type(m).__name__ == "Attend":
                        m.flash = False

            def forward(self, spec):
                # spec: [B, F*C, T, 2]
                net = self.inner
                if self.kind == "mel":
                    x = spec.index_select(1, net.freq_indices.long())
                else:
                    x = spec
                x = rearrange(x, "b f t c -> b t (f c)")
                x = net.band_split(x)
                store = [None] * len(net.layers)
                for i, block in enumerate(net.layers):
                    if len(block) == 3:
                        linear_t, time_t, freq_t = block
                        b, t, f, d = x.shape
                        x = linear_t(x.reshape(b, t * f, d)).reshape(b, t, f, d)
                    else:
                        time_t, freq_t = block
                    if getattr(net, "skip_connection", False):
                        for j in range(i):
                            x = x + store[j]
                    b, t, f, d = x.shape
                    x = time_t(x.permute(0, 2, 1, 3).contiguous().reshape(b * f, t, d))
                    x = x.reshape(b, f, t, d).permute(0, 2, 1, 3).contiguous()
                    x = freq_t(x.reshape(b * t, f, d)).reshape(b, t, f, d)
                    if getattr(net, "skip_connection", False):
                        store[i] = x
                if hasattr(net, "final_norm"):
                    x = net.final_norm(x)
                masks = torch.stack([fn(x) for fn in net.mask_estimators], 1)
                masks = rearrange(masks, "b n t (f c) -> b n f t c", c=2)
                if self.kind == "mel":
                    bsz, n, g, t, c = masks.shape
                    f_full = spec.shape[1]
                    idx = net.freq_indices.long().view(1, 1, g, 1, 1).expand(bsz, n, g, t, c)
                    summed = spec.new_zeros(bsz, n, f_full, t, c)
                    summed = summed.scatter_add(2, idx, masks)
                    denom = net.num_bands_per_freq.to(dtype=masks.dtype).clamp(min=1e-8)
                    denom = denom.repeat_interleave(net.audio_channels)
                    masks = summed / denom.view(1, 1, -1, 1, 1)
                return masks

        return _Net()


def build_torch(arm: Arm):
    import torch
    _msst_path()
    if arm.kind == "mel":
        from models.bs_roformer import MelBandRoformer
        cls = MelBandRoformer
    else:
        from models.bs_roformer import BSRoformer
        cls = BSRoformer
    kwargs = load_cfg_kwargs(arm, cls)
    model = cls(**kwargs)
    load_state(model, arm.ckpt)
    model.eval()
    for p in model.parameters():
        p.requires_grad_(False)
    wrapped = SpecInMaskOut.wrap(model, arm.kind)
    wrapped.eval()
    return model, wrapped


def torch_chunk_vocals(model, arm: Arm, chunk: np.ndarray) -> np.ndarray:
    """Full MSST forward (waveform in) on one native-length chunk. [2, T] -> [2, T]."""
    import torch
    x = torch.from_numpy(np.ascontiguousarray(chunk[None].astype(np.float32)))
    with torch.no_grad():
        y = model(x)
    y = y.detach().cpu().numpy()
    if y.ndim == 4:  # [B, N, C, T]
        y = y[0, 0]
    elif y.ndim == 3:
        y = y[0]
    return y.astype(np.float32)


# --- download / export -------------------------------------------------------

def cmd_download() -> dict:
    S.ensure_dir(QA_MODELS)
    rec = {"files": []}
    # Kimberley config from the MSST checkout if present, else curl
    if not SEPR3.cfg.exists():
        src = MSST / "configs/KimberleyJensen/config_vocals_mel_band_roformer_kj.yaml"
        if src.exists():
            SEPR3.cfg.write_bytes(src.read_bytes())
        else:
            curl(SEPR3.cfg_url, SEPR3.cfg)
    if not SEPR3.ckpt.exists():
        curl(SEPR3.url, SEPR3.ckpt)
    if not SEPR2_ANVUEW.cfg.exists():
        curl(SEPR2_ANVUEW.cfg_url, SEPR2_ANVUEW.cfg)
    if not SEPR2_ANVUEW.ckpt.exists():
        curl(SEPR2_ANVUEW.url, SEPR2_ANVUEW.ckpt)
    for p, url, lic in (
        (SEPR3.ckpt, SEPR3.url, SEPR3.licence),
        (SEPR3.cfg, SEPR3.cfg_url, SEPR3.licence),
        (SEPR2_ANVUEW.ckpt, SEPR2_ANVUEW.url, SEPR2_ANVUEW.licence),
        (SEPR2_ANVUEW.cfg, SEPR2_ANVUEW.cfg_url, SEPR2_ANVUEW.licence),
    ):
        rec["files"].append({
            "path": str(p), "bytes": p.stat().st_size, "sha256": sha256_file(p),
            "url": url, "licence": lic,
        })
        log(f"{p.name} {p.stat().st_size} sha256={rec['files'][-1]['sha256']}")
    rec["moises_light"] = {
        "skipped": True,
        "reason": "no public weights",
        "checked": [
            "https://arxiv.org/abs/2510.06785 (paper, no code/weights)",
            "https://github.com/crlandsc/moises-light (unofficial architecture only; no ckpt)",
            "https://github.com/ZFTurbo/Music-Source-Separation-Training/blob/main/configs/config_musdb18_moises_light.yaml (train config, paper_large preset; no checkpoint in docs/pretrained_models.md)",
            "https://huggingface.co/api/models?search=moises-light -> []",
            "https://huggingface.co/api/models?search=moises_light -> []",
            "https://github.com/ZFTurbo/Music-Source-Separation-Training/releases (no moises_light asset)",
        ],
    }
    (OUT / "roformer_download.json").parent.mkdir(parents=True, exist_ok=True)
    (OUT / "roformer_download.json").write_text(json.dumps(rec, indent=2))
    return rec


def _export_one(arm: Arm) -> dict:
    import torch
    import onnx
    arm = arm_from_cfg(arm)
    T = spec_frames(arm.chunk, arm.hop)
    F = spec_bins(arm.n_fft)
    shape = (1, F, T, 2)
    log(f"export {arm.name} kind={arm.kind} spec {shape} chunk={arm.chunk} hop={arm.hop}")
    model, wrapped = build_torch(arm)
    dummy = torch.randn(*shape, dtype=torch.float32)
    with torch.no_grad():
        _ = wrapped(dummy)  # warmup / catch eager errors
    S.ensure_dir(arm.onnx.parent)
    tmp = arm.onnx.with_suffix(".onnx.tmp")
    with torch.no_grad():
        torch.onnx.export(
            wrapped, dummy, str(tmp),
            opset_version=OPSET,
            do_constant_folding=True,
            input_names=["spec"],
            output_names=["mask"],
            dynamo=False,
        )
    onnx.checker.check_model(onnx.load(str(tmp)))
    tmp.replace(arm.onnx)
    del model, wrapped, dummy
    rec = {
        "arm": arm.name, "kind": arm.kind, "onnx": str(arm.onnx),
        "file_mb": arm.onnx.stat().st_size / 1e6,
        "sha256": sha256_file(arm.onnx),
        "opset": OPSET, "spec_shape": list(shape),
        "chunk": arm.chunk, "hop": arm.hop, "n_fft": arm.n_fft, "overlap": arm.overlap,
        "notes": arm.notes, "licence": arm.licence, "ckpt": str(arm.ckpt),
    }
    # must load in ORT 1.27.0 CPU EP
    sess = S.cpu_session(arm.onnx, HT_THREADS, arena=False)
    ins = sess.get_inputs()
    rec["ort_inputs"] = [{"name": i.name, "shape": i.shape, "type": i.type} for i in ins]
    rec["ort_outputs"] = [{"name": o.name, "shape": o.shape, "type": o.type} for o in sess.get_outputs()]
    if any(isinstance(d, str) for i in ins for d in i.shape):
        rec["warning"] = "dynamic dim in ORT shape"
    x = np.zeros(shape, np.float32)
    y = sess.run(None, {ins[0].name: x})[0]
    rec["ort_out_shape"] = list(y.shape)
    rec["ort_nan_inf"] = S.finite_check(y)
    del sess
    log(f"  wrote {arm.onnx} {rec['file_mb']:.1f} MB in={rec['ort_inputs']}")
    return rec


def resolve_sepr2() -> Arm:
    """Prefer anvuew (vocals). Fall back to viperx if native export is impossible."""
    flag = OUT / "sepr2_choice.json"
    if flag.exists():
        d = json.loads(flag.read_text())
        return SEPR2_VIPERX if d.get("choice") == "viperx" else SEPR2_ANVUEW
    return SEPR2_ANVUEW


def _merge_json(path: Path, key: str, rec: dict) -> dict:
    out = {}
    if path.exists():
        try:
            out = json.loads(path.read_text())
        except json.JSONDecodeError:
            out = {}
    out.setdefault(key, {})
    if isinstance(rec, dict) and key in rec and isinstance(rec[key], dict):
        out[key].update(rec[key])
    return out


def cmd_export(arms: list[str]) -> dict:
    out = _merge_json(OUT / "roformer_export.json", "arms", {})
    S.ensure_dir(OUT)
    mapping = {"sepr3": SEPR3, "sepr2": resolve_sepr2()}
    for name in arms:
        arm = mapping[name]
        if name == "sepr2" and arm is SEPR2_ANVUEW:
            try:
                out["arms"][name] = _export_one(arm)
            except Exception as e:
                log(f"anvuew export failed: {type(e).__name__}: {e}")
                out["arms"][name + "_anvuew_error"] = repr(e)
                # viperx fallback
                if not SEPR2_VIPERX.cfg.exists():
                    src = MSST / "configs/viperx/model_bs_roformer_ep_317_sdr_12.9755.yaml"
                    if src.exists():
                        SEPR2_VIPERX.cfg.write_bytes(src.read_bytes())
                    else:
                        curl(SEPR2_VIPERX.cfg_url, SEPR2_VIPERX.cfg)
                if not SEPR2_VIPERX.ckpt.exists():
                    curl(SEPR2_VIPERX.url, SEPR2_VIPERX.ckpt)
                try:
                    rec = _export_one(SEPR2_VIPERX)
                    rec["fallback_from"] = "anvuew"
                    rec["fallback_error"] = repr(e)
                    out["arms"][name] = rec
                    (OUT / "sepr2_choice.json").write_text(json.dumps({"choice": "viperx", "why": repr(e)}))
                except Exception as e2:
                    out["arms"][name] = {"error": repr(e2), "anvuew_error": repr(e)}
                    log(f"viperx export also failed: {e2}")
            else:
                (OUT / "sepr2_choice.json").write_text(json.dumps({"choice": "anvuew"}))
        else:
            try:
                out["arms"][name] = _export_one(arm)
            except Exception as e:
                out["arms"][name] = {"error": repr(e)}
                log(f"FAILED export {name}: {e}")
    (OUT / "roformer_export.json").write_text(json.dumps(out, indent=2))
    return out


def active_arm(name: str) -> Arm:
    if name == "sepr3":
        return SEPR3
    if name == "sepr2":
        return resolve_sepr2()
    raise ValueError(name)


# --- parity / song / quality / cost -----------------------------------------

def cmd_parity(arms: list[str]) -> dict:
    clip = SONG / "vlog_music30.wav"
    stereo = S.load_wav(clip)
    out = _merge_json(OUT / "roformer_parity.json", "arms", {})
    out["clip"] = str(clip)
    for name in arms:
        arm = arm_from_cfg(active_arm(name))
        if not arm.onnx.exists():
            out["arms"][name] = {"error": f"missing {arm.onnx}"}
            continue
        n = min(arm.chunk, stereo.shape[1])
        chunk = np.zeros((2, arm.chunk), np.float32)
        chunk[:, :n] = stereo[:, :n]
        log(f"== parity {name} chunk {arm.chunk} from {clip.name} ==")
        model, wrapped = build_torch(arm)
        t0 = time.perf_counter()
        ref = torch_chunk_vocals(model, arm, chunk)
        torch_s = time.perf_counter() - t0
        # spec-in torch vs ONNX mask, plus full vocals SNR
        import torch
        spec = stft_stereo(chunk, arm.n_fft, arm.hop)
        with torch.no_grad():
            mask_t = wrapped(torch.from_numpy(spec)).numpy()
        del model, wrapped
        ort_arm = RoformerOrt(arm)
        t1 = time.perf_counter()
        est = ort_arm.separate_chunk(chunk)
        ort_s = time.perf_counter() - t1
        mask_o = ort_arm.run_spec(spec)
        rec = {
            "chunk_s": arm.chunk / SR,
            "torch_s": torch_s, "ort_chunk_s": ort_s,
            "vocals_snr_db": S.parity_db(ref, est),
            "mask_snr_db": S.parity_db(mask_t, mask_o),
            "ref_nan": S.finite_check(ref), "est_nan": S.finite_check(est),
            "onnx_input": f"{ort_arm.iname} {list(ort_arm.in_shape)} float32",
        }
        log(f"  {name} vocals SNR {rec['vocals_snr_db']:.2f} dB  mask {rec['mask_snr_db']:.2f} dB")
        out["arms"][name] = rec
        del ort_arm
    (OUT / "roformer_parity.json").write_text(json.dumps(out, indent=2))
    return out


def cmd_song(arms: list[str]) -> dict:
    clips = {
        "vlog_music30": SONG / "vlog_music30.wav",
        "battle_hymn30": SONG / "battle_hymn30.wav",
    }
    out = _merge_json(OUT / "roformer_song.json", "arms", {})
    for name in arms:
        arm = arm_from_cfg(active_arm(name))
        if not arm.onnx.exists():
            out["arms"][name] = {"error": f"missing {arm.onnx}"}
            continue
        sep = RoformerOrt(arm)
        rec = {"files": {}}
        for cid, path in clips.items():
            stereo = S.load_wav(path)
            t0 = time.perf_counter()
            est = sep.separate(stereo)
            wall = time.perf_counter() - t0
            dest = SONG / f"{cid}_{name}.wav"
            sf.write(str(dest), est.T, SR, subtype="FLOAT")
            rec["files"][cid] = {
                "wav": str(dest), "wall_s": wall, "audio_s": stereo.shape[1] / SR,
                "nan_inf": S.finite_check(est),
            }
            log(f"  {name} {cid} {wall:.1f}s -> {dest.name}")
        rec["info"] = {
            "file": str(arm.onnx), "chunk": arm.chunk, "hop": arm.hop,
            "overlap": arm.overlap, "n_fft": arm.n_fft,
            "onnx_input": f"{sep.iname} {list(sep.in_shape)} float32",
            "ep": "CPUExecutionProvider", "threads": HT_THREADS, "xnnpack": False,
        }
        out["arms"][name] = rec
        del sep
    (OUT / "roformer_song.json").write_text(json.dumps(out, indent=2))
    return out


def sep0_bins() -> dict | None:
    p = OUT / "quality.json"
    if not p.exists():
        return None
    q = json.loads(p.read_text())
    return q.get("arms", {}).get("sep0", {}).get("bins")


def select_mixes(mixes: list, every: int, offset: int) -> list:
    """every=4, offset=0 is mixes[::4] (carefree only on this 4-music grid).
    offset=2 is the sung auld_lang_syne slice. rep24 is 3 carefree + 3 auld per SNR bin."""
    if every == 0:
        # rep24
        out = []
        for b in range(len(mixes) // 24):
            base = b * 24
            out.extend(mixes[base + i] for i in (0, 2, 4, 6, 8, 10))
        return out
    return mixes[offset::every]


def cmd_quality(arms: list[str], every: int | None, offset: int = 0) -> dict:
    man = S.load_manifest()
    mixes = man["mixes"]
    base = sep0_bins()
    for name in arms:
        arm = arm_from_cfg(active_arm(name))
        if not arm.onnx.exists():
            (OUT / f"{name}_quality.json").write_text(json.dumps({"error": f"missing {arm.onnx}"}))
            continue
        log(f"== quality {name} ==")
        sep = RoformerOrt(arm)
        use_every = every
        if use_every is None:
            mix0 = mixes[0]
            w = S.load_wav(Path(mix0["base"] + "_mix.wav"))
            t0 = time.perf_counter()
            _ = sep.separate(w)
            one = time.perf_counter() - t0
            pred = one * len(mixes)
            log(f"  first mix {one:.1f}s; 96-mix estimate {pred/60:.1f} min")
            use_every = 4 if pred > QUALITY_BUDGET_S else 1
        subset = select_mixes(mixes, use_every, offset)
        per_bin = {str(s): {"si_sdr": [], "sir": [], "sar": []} for s in SNR_BINS}
        sung_ret = []
        n_ok = 0
        for mix in subset:
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
                log(f"  {name} {n_ok}/{len(subset)} si_sdr={met['si_sdr']:.2f} {wall:.1f}s")
        bins = {str(s): {k: S.summarize(per_bin[str(s)][k]) for k in ("si_sdr", "sir", "sar")} for s in SNR_BINS}
        delta = None
        if base:
            delta = {}
            for s in SNR_BINS:
                b = str(s)
                delta[b] = {}
                for k in ("si_sdr", "sir", "sar"):
                    m = bins[b][k]["mean"]
                    m0 = base[b][k]["mean"]
                    delta[b][k] = None if m is None or m0 is None else m - m0
        rec = {
            "arm": name,
            "info": {
                "file": str(arm.onnx), "file_mb": arm.onnx.stat().st_size / 1e6,
                "chunk": arm.chunk, "hop": arm.hop, "n_fft": arm.n_fft,
                "overlap": arm.overlap, "kind": arm.kind, "notes": arm.notes,
                "licence": arm.licence, "ep": "CPUExecutionProvider",
                "threads": HT_THREADS, "xnnpack": False,
                "onnx_input": f"{sep.iname} {list(sep.in_shape)} float32",
            },
            "subset": "all" if use_every == 1 else (f"every_{use_every}_offset_{offset}" if offset else f"every_{use_every}"),
            "n_mixes": n_ok, "n_available": len(mixes),
            "mix_ids": [m["id"] for m in subset],
            "bins": bins,
            "delta_vs_sep0": delta,
            "singing_energy": {
                "energy_ratio": S.summarize([x["energy_ratio"] for x in sung_ret]),
                "proj_fraction": S.summarize([x["proj_fraction"] for x in sung_ret]),
                "n": len(sung_ret),
            },
        }
        dest = OUT / f"{name}_quality.json"
        if offset and dest.exists():
            prev = json.loads(dest.read_text())
            prev["singing_energy"] = rec["singing_energy"]
            prev["singing_subset"] = rec["subset"]
            prev["singing_n_mixes"] = rec["n_mixes"]
            prev["singing_mix_ids"] = rec.get("mix_ids")
            prev["singing_bins"] = rec["bins"]
            dest.write_text(json.dumps(prev, indent=2))
            log(f"  merged singing into {dest}")
        else:
            dest.write_text(json.dumps(rec, indent=2))
            log(f"  wrote {dest}")
        del sep
    return {}


def cost_worker(arm_name: str, wav: Path, runs: int, warmup: int) -> dict:
    arm = arm_from_cfg(active_arm(arm_name))
    stereo = S.load_wav(wav)
    audio_s = stereo.shape[1] / SR
    sep = RoformerOrt(arm)
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
        "arm": arm_name,
        "audio_s": audio_s,
        "warmup": warmup, "runs": runs,
        "ms_per_s": ms_s,
        "median_ms_per_s": float(np.median(ms_s)),
        "peak_rss_mb": float(rss_mb),
        "file_mb": arm.onnx.stat().st_size / 1e6,
        "nan_inf": S.finite_check(last) if last is not None else None,
        "threads": HT_THREADS,
        "ep": "CPUExecutionProvider",
        "xnnpack": False,
        "onnx_input": f"{sep.iname} {list(sep.in_shape)} float32",
        "chunk": arm.chunk, "hop": arm.hop, "overlap": arm.overlap,
    }
    print(json.dumps(rec), flush=True)
    return rec


def cmd_cost(arms: list[str]) -> dict:
    clip = OUT / "cost_60s.wav"
    S.ensure_dir(OUT)
    if not clip.exists():
        S.ffmpeg_wav(S.VLOG_44K, clip, SR, 2, 0.0, COST_S)
    out = {"clip": str(clip), "contended": True, "arms": {}}
    for name in arms:
        log(f"== cost {name} (subprocess) ==")
        cmd = [str(PY), str(Path(__file__).resolve()), "cost-worker",
               "--arm", name, "--wav", str(clip), "--runs", str(COST_RUNS), "--warmup", "1"]
        try:
            p = subprocess.run(cmd, check=True, capture_output=True, text=True)
            lines = [ln for ln in p.stdout.splitlines() if ln.startswith("{")]
            rec = json.loads(lines[-1])
            rec["command"] = " ".join(cmd)
        except subprocess.CalledProcessError as e:
            rec = {"error": (e.stderr or e.stdout or repr(e))[-2000:], "command": " ".join(cmd)}
            log(f"FAILED {name}: {str(rec['error'])[:400]}")
        out["arms"][name] = rec
        if "median_ms_per_s" in rec:
            log(f"  {name} median {rec['median_ms_per_s']:.1f} ms/s  RSS {rec['peak_rss_mb']:.0f} MB")
    (OUT / "roformer_cost.json").write_text(json.dumps(out, indent=2))
    return out


def cmd_device(arms: list[str]) -> None:
    for name in arms:
        arm = arm_from_cfg(active_arm(name))
        if not arm.onnx.exists():
            log(f"DEVICE {name} MISSING {arm.onnx}")
            continue
        sess = S.cpu_session(arm.onnx, HT_THREADS, arena=False)
        ins = sess.get_inputs()
        shape = ",".join(str(d) for d in ins[0].shape)
        chunk_s = arm.chunk / SR
        new_s = (arm.chunk // arm.overlap) / SR
        extra = ""
        if len(ins) > 1:
            extra = " inputs=" + ";".join(f"{i.name}:{i.shape}" for i in ins)
        log(f"DEVICE {name} file={arm.onnx} shape={shape} chunk_s={chunk_s} new_s={new_s}{extra}")


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)

    def add_arms(sp):
        sp.add_argument("--arms", nargs="+", default=ARMS_DEFAULT)

    add_arms(sub.add_parser("download"))
    add_arms(sub.add_parser("export"))
    add_arms(sub.add_parser("parity"))
    add_arms(sub.add_parser("song"))
    q = sub.add_parser("quality")
    add_arms(q)
    q.add_argument("--every", type=int, default=None, help="1=all 96; 4=every 4th mix; 0=rep24; default auto")
    q.add_argument("--offset", type=int, default=0, help="start index for every-N slice (2=sung auld on this grid)")
    add_arms(sub.add_parser("cost"))
    add_arms(sub.add_parser("device"))
    add_arms(sub.add_parser("all"))
    w = sub.add_parser("cost-worker")
    w.add_argument("--arm", required=True)
    w.add_argument("--wav", type=Path, required=True)
    w.add_argument("--runs", type=int, default=COST_RUNS)
    w.add_argument("--warmup", type=int, default=1)
    args = p.parse_args()
    S.ensure_dir(OUT)
    S.ensure_dir(SONG)

    if args.cmd == "download":
        cmd_download()
        return
    if args.cmd == "cost-worker":
        cost_worker(args.arm, args.wav, args.runs, args.warmup)
        return
    if args.cmd == "export":
        cmd_export(args.arms)
        return
    if args.cmd == "parity":
        cmd_parity(args.arms)
        return
    if args.cmd == "song":
        cmd_song(args.arms)
        return
    if args.cmd == "quality":
        cmd_quality(args.arms, args.every, args.offset)
        return
    if args.cmd == "cost":
        cmd_cost(args.arms)
        return
    if args.cmd == "device":
        cmd_device(args.arms)
        return
    if args.cmd == "all":
        cmd_download()
        cmd_export(args.arms)
        cmd_parity(args.arms)
        cmd_song(args.arms)
        cmd_quality(args.arms, None, 0)
        cmd_cost(args.arms)
        cmd_device(args.arms)
        return


if __name__ == "__main__":
    main()
