#!/usr/bin/env python3
"""Create tiny known-reference controls from original, independently sourced stems.

The PyTorch tutorial supplies the isolated singing/drums/bass/other segments;
EveryAyah supplies Arabic recitation. Downloaded media remain outside Git.
"""
import argparse
import hashlib
import json
from pathlib import Path

import numpy as np
import requests
import soundfile as sf
from scipy.signal import resample_poly

RATE = 44100
BASE = "https://download.pytorch.org/torchaudio/tutorial-assets/"
FILES = {
    "english.wav": BASE + "Lab41-SRI-VOiCES-src-sp0307-ch127535-sg0042.wav",
    "recitation.mp3": "https://everyayah.com/data/Alafasy_128kbps/001001.mp3",
    **{f"{stem}.wav": BASE + f"hdemucs_{stem}_segment.wav" for stem in ("vocals", "drums", "bass", "other")},
}


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read(path):
    x, rate = sf.read(path, dtype="float32", always_2d=True)
    if x.shape[1] == 1:
        x = np.repeat(x, 2, axis=1)
    return resample_poly(x, RATE, rate, axis=0).astype(np.float32) if rate != RATE else x


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--assets", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    args = parser.parse_args()
    original, controls = args.assets / "reference-originals", args.assets / "controls"
    original.mkdir(parents=True, exist_ok=True)
    controls.mkdir(parents=True, exist_ok=True)
    provenance = []
    for name, url in FILES.items():
        path = original / name
        if not path.exists():
            response = requests.get(url, timeout=60)
            response.raise_for_status()
            path.write_bytes(response.content)
        provenance.append({"file": str(path), "url": url, "sha256": digest(path)})
    stems = {name: read(original / f"{name}.wav") for name in ("vocals", "drums", "bass", "other")}
    assert len({x.shape for x in stems.values()}) == 1, "Misaligned source stems"
    music = stems["drums"] + stems["bass"] + stems["other"]
    records = []

    def write(name, x, **labels):
        assert np.isfinite(x).all()
        path = controls / f"{name}.wav"
        sf.write(path, x, RATE, subtype="FLOAT")
        records.append({"name": name, "file": str(path), "sha256": digest(path), "frames": len(x), "seconds": len(x) / RATE, "sample_rate": RATE, "channels": 2, **labels})
        return str(path)

    for name, source in (("english", read(original / "english.wav")),
                         ("recitation", read(original / "recitation.mp3")),
                         ("singing", stems["vocals"])):
        voice = source * (.1 / np.sqrt(np.square(source.astype(np.float64)).mean()))
        bed = np.tile(music, (int(np.ceil(len(voice) / len(music))), 1))[:len(voice)]
        bed *= .1 / np.sqrt(np.square(bed.astype(np.float64)).mean())
        # Joint scaling leaves a known 0 dB vocal/instrument RMS ratio.
        scale = min(1, .9 / np.max(np.abs(voice + bed)))
        voice, bed = (voice * scale).astype(np.float32), (bed * scale).astype(np.float32)
        reference = write(name + "-clean", voice, kind=name, instruments=False)
        instrumental = write(name + "-instrumental", bed, kind="instrumental", instruments=True)
        write(name + "-mix0db", voice + bed, kind=name, instruments=True,
              reference=reference, instrumental=instrumental, vocal_to_music_rms_db=0)
    write("silence", np.zeros((RATE, 2), dtype=np.float32), kind="silence", instruments=False)
    write("partial", np.zeros((137, 2), dtype=np.float32), kind="short-silence", instruments=False)
    args.manifest.parent.mkdir(parents=True, exist_ok=True)
    args.manifest.write_text(json.dumps({"rate": RATE, "purpose": "Small screening controls, not representative corpus or listening-study claim", "source_stems": "PyTorch hybrid_demucs tutorial original 150–155 s isolated reference segments; treated as supplied reference stems, not as a new annotated corpus", "provenance": provenance, "items": records}, indent=2) + "\n")
    print(json.dumps({"manifest": str(args.manifest), "items": len(records)}))


if __name__ == "__main__":
    main()
