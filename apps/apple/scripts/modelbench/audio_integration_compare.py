#!/usr/bin/env python3
"""Compare old/new exported audio; signal differences are not removal accuracy."""
import argparse
import json
import subprocess
from pathlib import Path

import numpy as np

from audio_compare import quality, sha256

RATE = 44100


def decode(path):
    # ponytail: these evaluation clips fit in RAM; stream metrics for hour-long media.
    data = subprocess.check_output(["ffmpeg", "-v", "error", "-i", str(path), "-map", "0:a:0",
                                    "-vn", "-ar", str(RATE), "-ac", "2", "-c:a", "pcm_f32le", "-f", "f32le", "pipe:1"])
    if not data or len(data) % 8:
        raise ValueError("Empty or incomplete stereo float32 decode")
    samples = np.frombuffer(data, dtype="<f4").reshape(-1, 2).T
    if not np.isfinite(samples).all():
        raise ValueError("Non-finite decoded audio")
    probe = json.loads(subprocess.check_output(["ffprobe", "-v", "error", "-select_streams", "a:0",
                                              "-show_entries", "stream=start_time,duration,codec_name,sample_rate,channels",
                                              "-of", "json", str(path)]))["streams"][0]
    return samples, probe


def difference(old, new):
    if old.shape != new.shape:
        raise ValueError("Comparative signal metrics require identical sample counts")
    error = new.astype(np.float64) - old
    return {"max_absolute_difference": float(np.abs(error).max()),
            "difference_rms": float(np.square(error).mean() ** .5),
            "samples_differing_by_more_than_1e_5": int(np.count_nonzero(np.abs(error) > 1e-5))}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-check", action="store_true")
    parser.add_argument("--source", type=Path)
    parser.add_argument("--old", type=Path)
    parser.add_argument("--new", type=Path)
    parser.add_argument("--reference", type=Path)
    parser.add_argument("--instrumental", type=Path)
    parser.add_argument("--results", type=Path)
    args = parser.parse_args()
    if args.self_check:
        x = np.array([[0, .25, -.5], [.5, -.25, 0]], dtype=np.float32)
        assert difference(x, x)["difference_rms"] == 0
        assert abs(difference(x, x + .125)["difference_rms"] - .125) < 1e-8
        try:
            difference(x, x[:, :-1])
        except ValueError:
            print("integration audio self-check passed: equality, measured change, unequal-count rejection")
            return
        raise AssertionError("Unequal sample counts must be rejected")
    if not all((args.source, args.old, args.new, args.results)):
        parser.error("source, old, new and results are required")
    sources = {p.resolve() for p in (args.source, args.old, args.new, args.reference, args.instrumental) if p is not None}
    if args.results.resolve() in sources:
        parser.error("Results must not overwrite media/reference files")
    old, old_probe = decode(args.old)
    new, new_probe = decode(args.new)
    start_delta = float(new_probe.get("start_time", 0)) - float(old_probe.get("start_time", 0))
    row = {"source": str(args.source), "source_sha256": sha256(args.source),
           "old": str(args.old), "old_sha256": sha256(args.old), "new": str(args.new), "new_sha256": sha256(args.new),
           "old_audio_probe": old_probe, "new_audio_probe": new_probe,
           "decoded_sample_rate": RATE, "decoded_channels": 2,
           "old_frames": old.shape[-1], "new_frames": new.shape[-1], "finite": True,
           "sample_count_equal": old.shape == new.shape, "audio_start_delta_s": start_delta,
           "meaning": "Old/new signal difference and export integrity; not instrument-removal accuracy or listening quality"}
    if old.shape == new.shape and abs(start_delta) <= 1 / RATE:
        row["signal_difference"] = difference(old, new)
    else:
        row["signal_difference"] = None
        row["alignment_note"] = "No silent trimming/time-shifting; waveform comparison withheld until aligned"
    if args.reference:
        reference, reference_probe = decode(args.reference)
        music = decode(args.instrumental)[0] if args.instrumental else None
        if old.shape != reference.shape or new.shape != reference.shape or (music is not None and music.shape != reference.shape):
            raise ValueError("Known references must have the same decoded sample count as both outputs")
        reference_start = float(reference_probe.get("start_time", 0))
        if abs(float(old_probe.get("start_time", 0)) - reference_start) > 1 / RATE or abs(float(new_probe.get("start_time", 0)) - reference_start) > 1 / RATE:
            raise ValueError("Known-reference timeline anchors must match both outputs")
        row["reference_sha256"] = sha256(args.reference)
        row["old_quality"] = quality(reference, old, music)
        row["new_quality"] = quality(reference, new, music)
    args.results.parent.mkdir(parents=True, exist_ok=True)
    with args.results.open("a") as stream:
        stream.write(json.dumps(row) + "\n")
    print(json.dumps(row), flush=True)


if __name__ == "__main__":
    main()
