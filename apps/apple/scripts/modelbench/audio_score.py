#!/usr/bin/env python3
"""Score an existing native/Python output against aligned reference stems."""
import argparse
import json
from pathlib import Path

import numpy as np

from audio_compare import quality, read_audio, sha256


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--candidate", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--reference", type=Path, required=True)
    parser.add_argument("--instrumental", type=Path)
    parser.add_argument("--results", type=Path, required=True)
    args = parser.parse_args()
    source_paths = {p.resolve() for p in (args.output, args.reference, args.instrumental) if p is not None}
    if args.results.resolve() in source_paths:
        parser.error("Results must not overwrite output/reference/instrumental files")
    output, reference = read_audio(args.output), read_audio(args.reference)
    music = read_audio(args.instrumental) if args.instrumental else None
    if output.shape != reference.shape or (music is not None and music.shape != reference.shape):
        raise ValueError("Output and reference samples/channels must be aligned")
    record = {"candidate": args.candidate, "output": str(args.output), "output_sha256": sha256(args.output), "reference": str(args.reference), "reference_sha256": sha256(args.reference), "frames": output.shape[-1], "finite": bool(np.isfinite(output).all()), "quality": quality(reference, output, music)}
    if music is not None:
        record["instrumental_sha256"] = sha256(args.instrumental)
    args.results.parent.mkdir(parents=True, exist_ok=True)
    with args.results.open("a") as stream:
        stream.write(json.dumps(record) + "\n")
    print(json.dumps(record))


if __name__ == "__main__":
    main()
