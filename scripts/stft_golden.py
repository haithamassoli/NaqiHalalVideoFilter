#!/usr/bin/env python3
"""Golden generator for the out-of-graph htdemucs STFT / iSTFT (M2, Owner A).

Numpy-only, no torch / no demucs. Mirrors the demucs `_spec` / `_ispec` math
(dsp-spec.md §1-§2, cross-checked against plan.md's locked constants) so that
app/src/test/java/.../audio/DspTest.kt can validate the Kotlin `Fft`/`Stft`
port against an independent reference.

The SAME code path runs at full size; here it runs a small, odd-tail config
(nfft=64, hop=16, T=4123) so the JSON stays tiny AND `T % hop != 0` exercises
the odd-tail overlap-add branch the full config (347136 % 1024 == 0) never hits.

Reference is float64 (numpy `rfft`/`irfft`, which already fold in 1/N on the
inverse); the Kotlin port is float32 data with float64 window/envelope/OLA, so
DspTest compares at atol 1e-4 (measured f32-vs-f64 gap here is ~2e-7).

Fields written to app/src/test/resources/dsp/stft_small.json:
  nfft, hop, T, bins (= nfft/2), le (= ceil(T/hop))         ints
  cac_shape = [4, bins, le]                                 the shape `cac` flattens (C-order)
  input     = [ch0[T], ch1[T]]                              f32 stereo test signal (planar)
  cac       = forward(input) flattened [4][bins][le]        channel-major [ch0.re, ch0.im, ch1.re, ch1.im]
  roundtrip = inverse(cac)   = [ch0[T], ch1[T]]             iSTFT of the untouched cac

Run from the repo root:  python3 scripts/stft_golden.py
"""
import json
import os

import numpy as np


def hann_periodic(n_fft):
    # torch.hann_window(n_fft, periodic=True): 0.5*(1 - cos(2*pi*n/N)), denominator N (NOT N-1).
    n = np.arange(n_fft)
    return 0.5 * (1.0 - np.cos(2.0 * np.pi * n / n_fft))


def reflect_pad(x, left, right):
    # torch F.pad(mode="reflect"): mirror EXCLUDING the edge sample (no edge-sample repeat).
    return np.concatenate([x[left:0:-1], x, x[-2:-2 - right:-1]])


def forward_cac(ch0, ch1, nfft, hop, T):
    """Planar stereo (each length T) -> model CaC spec [4, bins, le], bins = nfft/2.

    Level-A reflect re-pad (padL/padR aligns freq & time branches), then torch.stft(center=True):
    center reflect-pad nfft/2 each side, periodic-Hann window, rfft, normalized=True (x 1/sqrt(nfft)).
    Drop the Nyquist bin (keep 0..nfft/2-1), keep frames [2:2+le], pack channel-major real-before-imag.
    """
    le = -(-T // hop)                      # ceil(T/hop)
    pad_l = hop // 2 * 3
    pad_r = pad_l + le * hop - T
    bins = nfft // 2
    scale = 1.0 / np.sqrt(nfft)
    win = hann_periodic(nfft)
    center = nfft // 2
    cac = np.zeros((4, bins, le), dtype=np.float64)
    for ci, ch in enumerate((ch0, ch1)):
        sig_a = reflect_pad(ch.astype(np.float64), pad_l, pad_r)     # length T + padL + padR
        sig_b = reflect_pad(sig_a, center, center)                  # center pad, length + nfft
        for f in range(2, 2 + le):                                  # frame slice [2 : 2+le]
            start = f * hop
            spec = np.fft.rfft(sig_b[start:start + nfft] * win)     # nfft/2 + 1 complex bins
            t = f - 2
            cac[2 * ci,     :, t] = spec[:bins].real * scale        # drop Nyquist (bin nfft/2)
            cac[2 * ci + 1, :, t] = spec[:bins].imag * scale
    return cac


def inverse_cac(cac, nfft, hop, T):
    """Summed CaC spec [4, bins, le] -> planar stereo waveform [2, T] (iSTFT, cac untouched)."""
    le = -(-T // hop)
    pad_l = hop // 2 * 3
    pad_r = pad_l + le * hop - T
    bins = nfft // 2
    padded = T + pad_l + pad_r
    win = hann_periodic(nfft)
    nframes = padded // hop + 1                                     # = le + 4
    unscale = np.sqrt(nfft)
    env = np.zeros(padded + nfft, dtype=np.float64)                 # window sum-of-squares (WSS)
    for f in range(nframes):                                        # env over ALL frames (dsp-spec §2c)
        env[f * hop:f * hop + nfft] += win * win
    offset = nfft // 2 + padded_front_trim(pad_l)                   # center pad + level-A front trim
    out = np.zeros((2, T), dtype=np.float64)
    for ci in range(2):
        ola = np.zeros(padded + nfft, dtype=np.float64)             # DOUBLE accumulator
        for f in range(2, 2 + le):                                  # zeroed boundary frames add nothing
            t = f - 2
            spec = np.zeros(bins + 1, dtype=np.complex128)          # rebuild the nfft/2+1 half-spectrum
            spec[:bins] = (cac[2 * ci, :, t] + 1j * cac[2 * ci + 1, :, t]) * unscale
            # Nyquist bin (index `bins`) stays zero (dsp-spec §2b); irfft folds in 1/N exactly once.
            ola[f * hop:f * hop + nfft] += np.fft.irfft(spec, n=nfft) * win
        rec = ola / (env + 1e-8)
        out[ci] = rec[offset:offset + T]
    return out


def padded_front_trim(pad_l):
    return pad_l                                                    # named for parity with dsp-spec §2d


def main():
    nfft, hop, T = 64, 16, 4123
    bins, le = nfft // 2, -(-T // hop)
    rng = np.random.default_rng(7)
    inp = rng.standard_normal((2, T)).astype(np.float32)
    cac = forward_cac(inp[0], inp[1], nfft, hop, T)
    roundtrip = inverse_cac(cac, nfft, hop, T)

    payload = {
        "nfft": nfft, "hop": hop, "T": T, "bins": bins, "le": le,
        "cac_shape": [4, bins, le],
        "input": [inp[0].tolist(), inp[1].tolist()],
        "cac": cac.ravel(order="C").tolist(),
        "roundtrip": [roundtrip[0].tolist(), roundtrip[1].tolist()],
    }
    out_path = os.path.abspath(os.path.join(
        os.path.dirname(__file__), "..", "app", "src", "test", "resources", "dsp", "stft_small.json"))
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    with open(out_path, "w") as fh:
        json.dump(payload, fh)

    size = os.path.getsize(out_path)
    assert len(payload["cac"]) == 4 * bins * le, "cac length mismatch"
    print(f"wrote {out_path}")
    print(f"  {size} bytes ({size / 1e6:.2f} MB); nfft={nfft} hop={hop} T={T} bins={bins} le={le} "
          f"nframes={le + 4}; cac len={len(payload['cac'])}")


if __name__ == "__main__":
    main()
