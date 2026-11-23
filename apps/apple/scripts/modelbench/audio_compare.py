#!/usr/bin/env python3
"""One candidate, one input, one process; audio stays outside Git.

Run --self-check before using the benchmark. Python times are Mac screening
times, never native iOS performance. Model imports are lazy to keep checks cheap.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import platform
import resource
import subprocess
import sys
import tempfile
import time
from pathlib import Path

import numpy as np
import soundfile as sf
import torch
import yaml
from torch.nn import functional as F

RATE = 44100
MPS_DRIVER_SAMPLES = []


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def read_audio(path):
    # ponytail: short benchmark clips live in RAM; use the production streaming decoder for long media.
    x, rate = sf.read(path, dtype="float32", always_2d=True)
    if rate != RATE or x.shape[1] not in (1, 2) or not len(x):
        raise ValueError("Expected nonempty mono/stereo 44.1 kHz WAV")
    if not np.isfinite(x).all():
        raise ValueError("Non-finite input")
    if x.shape[1] == 1:
        x = np.repeat(x, 2, axis=1)
    return x.T.copy()


def stft(x, nfft, bins):
    z = torch.stft(torch.from_numpy(x), nfft, 1024,
                   window=torch.hann_window(nfft), center=True,
                   normalized=False, return_complex=True)
    return torch.view_as_real(z).permute(0, 3, 1, 2).reshape(1, 4, nfft // 2 + 1, -1)[:, :, :bins].numpy()


def istft(spec, nfft, length):
    spec = torch.from_numpy(np.asarray(spec, dtype=np.float32))
    spec = F.pad(spec, (0, 0, 0, nfft // 2 + 1 - spec.shape[-2]))
    z = spec.reshape(2, 2, nfft // 2 + 1, -1).permute(0, 2, 3, 1).contiguous()
    return torch.istft(torch.view_as_complex(z), nfft, 1024,
                       window=torch.hann_window(nfft), center=True,
                       normalized=False, length=length).numpy()


def mdx_audio(x, predict, compensation, profile, export=None):
    # UVR official model metadata: BOTH Kim2 and Voc_FT use 7680, not 6144.
    nfft, bins, chunk = 7680, 3072, 1024 * 255
    trim = nfft // 2
    gen = chunk - 2 * trim
    pad = gen + trim - x.shape[-1] % gen
    mix = np.pad(x, ((0, 0), (trim, pad)))
    result = np.zeros_like(mix)
    divider = np.zeros(mix.shape[-1], dtype=np.float32)
    for offset in range(0, mix.shape[-1], int(chunk * .75)):
        length = min(chunk, mix.shape[-1] - offset)
        part = np.pad(mix[:, offset:offset + length], ((0, 0), (0, chunk - length)))
        spec = stft(part, nfft, bins)
        spec[:, :, :3] = 0
        before = time.perf_counter()
        prediction = predict(spec)
        profile.append(time.perf_counter() - before)
        if not np.isfinite(prediction).all():
            raise ValueError("Non-finite model output")
        if export and offset == 0:
            quantized = spec.astype(np.float16).astype(np.float32)
            quantized.astype("<f2").tofile(str(export) + ".input.f16.bin")
            quantized.astype("<f4").tofile(str(export) + ".input.f32.bin")
            predict(quantized).astype("<f4").tofile(str(export) + ".reference.f32.bin")
            Path(str(export) + ".json").write_text(json.dumps({"shape": list(spec.shape), "nfft": nfft, "hop": 1024, "zero_low_bins": 3, "input_dtype": "little-endian float32 prequantized to float16", "reference_dtype": "little-endian float32", "benchmark_note": "This run includes an extra untimed-reference graph call; exclude separator RTF from comparisons"}, indent=2) + "\n")
        y = istft(prediction, nfft, chunk)
        window = np.hanning(length).astype(np.float32)
        result[:, offset:offset + length] += y[:, :length] * window
        divider[offset:offset + length] += window
    usable = slice(trim, trim + x.shape[-1])
    if (divider[usable] <= 0).any():
        raise ValueError("Uncovered overlap-add samples")
    return result[:, usable] / divider[usable] * compensation


def scnet_audio(x, model, device, profile):
    # Official apply.py's 20 s windows and inference.py's 50% overlap.
    # Disable random shift averaging to make repeated/CPU-provider checks exact.
    mono = x.mean(axis=0)
    mean = float(mono.astype(np.float64).mean())
    std = max(float(mono.astype(np.float64).std(ddof=1)), 1e-8)
    mix = (x - mean) / std
    chunk, stride = 20 * RATE, 10 * RATE
    weight = np.minimum(np.arange(1, chunk + 1), np.arange(chunk, 0, -1)).astype(np.float32)
    weight /= weight.max()
    result, divider = np.zeros_like(x), np.zeros(x.shape[-1], dtype=np.float32)
    for offset in range(0, x.shape[-1], stride):
        length = min(chunk, x.shape[-1] - offset)
        delta = chunk - length
        start = offset - delta // 2
        stop = start + chunk
        part = np.pad(mix[:, max(0, start):min(stop, mix.shape[-1])],
                      ((0, 0), (max(0, -start), max(0, stop - mix.shape[-1]))))
        tensor = torch.from_numpy(part).unsqueeze(0).to(device)
        if device == "mps":
            torch.mps.synchronize()
        before = time.perf_counter()
        with torch.inference_mode():
            y = model(tensor)[0, model.sources.index("vocals")].cpu().numpy()
        if device == "mps":
            torch.mps.synchronize()
        profile.append(time.perf_counter() - before)
        if device == "mps":
            MPS_DRIVER_SAMPLES.append(torch.mps.driver_allocated_memory())
        if not np.isfinite(y).all():
            raise ValueError("Non-finite SCNet output")
        y = y[:, delta // 2:delta // 2 + length]
        result[:, offset:offset + length] += y * weight[:length]
        divider[offset:offset + length] += weight[:length]
    return result / divider * std + mean


def roformer_audio(x, model, device, profile):
    # Checkpoint author's 8 s windows, two overlaps, 10% fades, reflect borders.
    chunk, stride = 352800, 176400
    fade, border = chunk // 10, chunk - stride
    padded = x.shape[-1] > 2 * border
    mix = np.pad(x, ((0, 0), (border, border)), mode="reflect") if padded else x
    result, divider = np.zeros_like(mix), np.zeros(mix.shape[-1], dtype=np.float32)
    weight = np.ones(chunk, dtype=np.float32)
    weight[:fade] = np.linspace(0, 1, fade)
    weight[-fade:] = np.linspace(1, 0, fade)
    for offset in range(0, mix.shape[-1], stride):
        length = min(chunk, mix.shape[-1] - offset)
        mode = "reflect" if length > chunk // 2 + 1 else "constant"
        part = np.pad(mix[:, offset:offset + length], ((0, 0), (0, chunk - length)), mode=mode)
        tensor = torch.from_numpy(part).unsqueeze(0).to(device)
        if device == "mps":
            torch.mps.synchronize()
        before = time.perf_counter()
        with torch.inference_mode():
            y = model(tensor)[0].cpu().numpy()
        if device == "mps":
            torch.mps.synchronize()
        profile.append(time.perf_counter() - before)
        if device == "mps":
            MPS_DRIVER_SAMPLES.append(torch.mps.driver_allocated_memory())
        if not np.isfinite(y).all():
            raise ValueError("Non-finite RoFormer output")
        w = weight.copy()
        if offset == 0:
            w[:fade] = 1
        elif offset + chunk >= mix.shape[-1]:
            w[-fade:] = 1
        result[:, offset:offset + length] += y[:, :length] * w[:length]
        divider[offset:offset + length] += w[:length]
    result /= divider
    return result[:, border:-border] if padded else result


def quality(reference, output, instrumental=None):
    ref, out = reference.astype(np.float64).ravel(), output.astype(np.float64).ravel()
    ref -= ref.mean()
    out -= out.mean()
    energy = ref @ ref
    if energy < 1e-12:
        return {"si_sdr_db": None, "reason": "silent vocal reference"}
    gain = (out @ ref) / energy
    target = gain * ref
    error = out - target
    metrics = {"si_sdr_db": float(10 * np.log10((target @ target + 1e-12) / (error @ error + 1e-12))),
               "vocal_gain_db": float(20 * np.log10(abs(gain) + 1e-12)),
               "scale_dependent_sdr_db": float(10 * np.log10((energy + 1e-12) / (np.square(out - ref).sum() + 1e-12)))}
    if instrumental is not None:
        music = instrumental.astype(np.float64).ravel()
        music -= music.mean()
        coeff = np.linalg.lstsq(np.stack((ref, music), axis=1), out, rcond=None)[0]
        metrics["two_source_music_gain_db"] = float(20 * np.log10(abs(coeff[1]) + 1e-12))
        metrics["two_source_vocal_gain_db"] = float(20 * np.log10(abs(coeff[0]) + 1e-12))
    return metrics


def self_check():
    torch.set_num_threads(1)
    t = np.arange(261120) / RATE
    x = np.stack((.1 * np.sin(2 * np.pi * 440 * t), .1 * np.sin(2 * np.pi * 880 * t))).astype(np.float32)
    reconstructed = istft(stft(x, 7680, 3072), 7680, x.shape[-1])
    assert np.max(np.abs(x[:, 7680:-7680] - reconstructed[:, 7680:-7680])) < 1e-5
    for n in (1, 8000, 300001):
        source = np.zeros((2, n), dtype=np.float32)
        output = mdx_audio(source, lambda spec: spec, 1, [])
        assert output.shape == source.shape and np.isfinite(output).all() and not output.any()
    identity = quality(x, x)
    assert identity["si_sdr_db"] > 100 and abs(identity["vocal_gain_db"]) < 1e-6
    attenuated = quality(x, x * .1)
    assert abs(attenuated["vocal_gain_db"] + 20) < 1e-5
    with tempfile.TemporaryDirectory() as temporary:
        source = Path(temporary) / "source.wav"
        source.write_bytes(b"source safety sentinel")
        result = subprocess.run([sys.executable, __file__, "--candidate", "kim2", "--assets", temporary,
                                 "--input", str(source), "--output", str(source), "--results", temporary + "/results.jsonl"],
                                capture_output=True)
        assert result.returncode == 2 and source.read_bytes() == b"source safety sentinel"
    print("audio self-check passed: STFT packing, inverse, silence/partial OLA, gain-aware metrics, source overwrite guard")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-check", action="store_true")
    parser.add_argument("--candidate", choices=("kim2", "vocft", "scnet", "roformer"))
    parser.add_argument("--provider", choices=("cpu", "mps", "coreml-all", "coreml-gpu", "coreml-cpu"), default="cpu")
    parser.add_argument("--assets", type=Path)
    parser.add_argument("--sources", type=Path, help="Folder containing pinned SCNet and Mel-Band-Roformer checkouts")
    parser.add_argument("--input", type=Path)
    parser.add_argument("--reference", type=Path)
    parser.add_argument("--instrumental", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--results", type=Path)
    parser.add_argument("--export-spectrum", type=Path)
    parser.add_argument("--threads", type=int, default=4)
    args = parser.parse_args()
    if args.self_check:
        self_check()
        return
    if not all((args.candidate, args.assets, args.input, args.output, args.results)):
        parser.error("candidate, assets, input, output and results are required")
    source_paths = {p.resolve() for p in (args.input, args.reference, args.instrumental) if p is not None}
    if args.output.resolve() in source_paths or args.results.resolve() in source_paths:
        parser.error("Output/results must not overwrite input/reference/instrumental files")
    if args.output.resolve() == args.results.resolve():
        parser.error("Audio output and results must use different files")
    torch.set_num_threads(args.threads)
    input_audio = read_audio(args.input)
    record = {"candidate": args.candidate, "provider": args.provider, "device": "Apple M3 MacBook Air 24 GB", "scope": "Python screening, not iPhone/full exported job", "os": platform.platform(), "python": platform.python_version(), "torch": torch.__version__, "threads": args.threads, "input": str(args.input), "input_sha256": sha256(args.input), "duration_s": input_audio.shape[-1] / RATE, "gate": False, "stem": "vocals", "warmup": 0, "memory_definition": "fresh-process resource.getrusage ru_maxrss bytes on macOS; includes Python/runtime/load/input and all inference", "status": "failed"}
    profile = []
    record["run_utc"] = datetime.now(timezone.utc).isoformat()
    record["separator_includes_parity_export"] = bool(args.export_spectrum)
    try:
        load_start = time.perf_counter()
        if args.candidate in ("kim2", "vocft"):
            import onnxruntime as ort
            filename = "Kim_Vocal_2.onnx" if args.candidate == "kim2" else "UVR-MDX-NET-Voc_FT.onnx"
            model_path = args.assets / "UVR/models/MDXNet" / filename
            data = json.loads((args.assets / "mdx_model_data.json").read_text())
            model_md5 = hashlib.md5(model_path.read_bytes()[-10000 * 1024:]).hexdigest()
            config = data[model_md5]
            assert config["mdx_n_fft_scale_set"] == 7680 and config["mdx_dim_f_set"] == 3072
            record.update(model_sha256=sha256(model_path), config=config, config_sha256=sha256(args.assets / "mdx_model_data.json"), runtime_version=ort.__version__)
            if args.provider == "cpu":
                options = ort.SessionOptions()
                options.intra_op_num_threads = args.threads
                options.inter_op_num_threads = 1
                session = ort.InferenceSession(str(model_path), sess_options=options, providers=["CPUExecutionProvider"])
                predict = lambda spec: session.run(None, {session.get_inputs()[0].name: spec})[0]
                record["precision"] = "fp32"
            elif args.provider.startswith("coreml") and args.candidate == "vocft":
                import coremltools as ct
                units = {"coreml-all": ct.ComputeUnit.ALL, "coreml-gpu": ct.ComputeUnit.CPU_AND_GPU, "coreml-cpu": ct.ComputeUnit.CPU_ONLY}[args.provider]
                package = args.assets / "UVR-MDX-CoreML/UVR-MDX-NET-Voc_FT.mlpackage"
                session = ct.models.MLModel(str(package), compute_units=units)
                predict = lambda spec: session.predict({"input": spec.astype(np.float16)})["output"].astype(np.float32)
                record.update(precision="fp16", runtime_version=ct.__version__, package_model_sha256=sha256(package / "Data/com.apple.CoreML/model.mlmodel"), package_weights_sha256=sha256(package / "Data/com.apple.CoreML/weights/weight.bin"), actual_compute_placement="not observed; requested units only")
            else:
                raise ValueError("MDX supports ONNX CPU or Voc_FT native CoreML")
            adapter = lambda: mdx_audio(input_audio, predict, config["compensate"], profile, args.export_spectrum)
        elif args.candidate == "scnet":
            sys.path.insert(0, str(args.sources / "SCNet"))
            from scnet.SCNet import SCNet
            config_path = args.sources / "SCNet/conf/config.yaml"
            config = yaml.safe_load(config_path.read_text())["model"]
            model_path = args.assets / "scnet-small.th"
            checkpoint = torch.load(model_path, map_location="cpu", weights_only=False)
            model = SCNet(**config)
            model.load_state_dict({k.removeprefix("module."): v for k, v in checkpoint["best_state"].items()})
            model.eval().to(args.provider)
            record.update(model_sha256=sha256(model_path), config_sha256=sha256(config_path), precision="fp32", config=config, segment_s=20, overlap=.5, shifts=0)
            adapter = lambda: scnet_audio(input_audio, model, args.provider, profile)
        else:
            sys.path.insert(0, str(args.sources / "Mel-Band-Roformer"))
            from models.mel_band_roformer import MelBandRoformer
            config_path = args.sources / "Mel-Band-Roformer/configs/config_vocals_mel_band_roformer.yaml"
            config = yaml.load(config_path.read_text(), Loader=yaml.FullLoader)["model"]
            model_path = args.assets / "Roformer/MelBandRoformer.ckpt"
            model = MelBandRoformer(**config)
            model.load_state_dict(torch.load(model_path, map_location="cpu", weights_only=True))
            model.eval().to(args.provider)
            record.update(model_sha256=sha256(model_path), config_sha256=sha256(config_path), precision="fp32", config=config, segment_s=8, overlap=.5, shifts=0)
            adapter = lambda: roformer_audio(input_audio, model, args.provider, profile)
        record["load_s"] = time.perf_counter() - load_start
        before = time.perf_counter()
        output = adapter()
        record["separator_s"] = time.perf_counter() - before
        if output.shape != input_audio.shape or not np.isfinite(output).all():
            raise ValueError("Output shape/finite integrity failed")
        args.output.parent.mkdir(parents=True, exist_ok=True)
        sf.write(args.output, output.T, RATE, subtype="FLOAT")
        record.update(status="ok", rtf=record["separator_s"] / record["duration_s"], output=str(args.output), output_sha256=sha256(args.output), output_frames=output.shape[-1], output_peak=float(np.abs(output).max()), output_rms=float(np.square(output.astype(np.float64)).mean() ** .5), model_calls=len(profile), model_call_s=profile, model_first_call_s=profile[0] if profile else None, model_call_p50_s=float(np.median(profile)) if profile else None, finite=True)
        if args.reference:
            reference = read_audio(args.reference)
            if reference.shape != output.shape:
                raise ValueError("Reference not aligned")
            music = read_audio(args.instrumental) if args.instrumental else None
            record["quality"] = quality(reference, output, music)
            record["mixture_quality"] = quality(reference, input_audio, music)
    except Exception as error:
        record["status"] = "failed"
        record["error"] = f"{type(error).__name__}: {error}"
        import traceback
        traceback.print_exc()
    record["peak_rss_bytes"] = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    if MPS_DRIVER_SAMPLES:
        record["mps_driver_allocated_bytes_sampled_max"] = max(MPS_DRIVER_SAMPLES)
        record["mps_memory_note"] = "After-inference driver allocation samples, not a GPU peak; do not add to RSS as unified-memory pages may overlap"
    args.results.parent.mkdir(parents=True, exist_ok=True)
    with args.results.open("a") as stream:
        stream.write(json.dumps(record, default=str) + "\n")
    print(json.dumps(record, default=str), flush=True)
    if record["status"] != "ok":
        raise SystemExit(1)


if __name__ == "__main__":
    main()
