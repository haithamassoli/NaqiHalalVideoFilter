# iOS model comparison: screening protocol

Date: 2026-10-02. Baseline: `e8af471`. Scope agreed before measured runs.

The requested audio output keeps human speech, singing, chanting, humming and
recitation while suppressing musical instruments. Compare vocals only with the
music gate disabled. Do not add a singing-removal classifier or a scene filter.

Vision compares face and whole-person coverage for the existing
women/men/everyone policies. A body detector or mask is not a gender classifier
or a persistent identity. Record uncertain face-to-person associations instead
of transferring a decision to a nearby person.

## Inputs and hardware

- The two supplied YouTube Shorts, identified and hashed in
  `scripts/modelbench/manifest.json`. Keep originals outside Git.
- Lossless decoded 44.1 kHz stereo float WAVs for audio comparison.
- H.264/AAC derivatives for Apple media APIs; record those hashes separately.
- Apple M3 MacBook Air, 8 CPU cores, 10 GPU cores, 24 GB unified memory.
- Physical iPhones are unavailable, confirmed by the user. Simulator checks
  establish build/correctness only; M3 timings cannot select a phone device tier.

## Admission checks

1. Every measured model has an exact artifact/checkpoint, hash, runtime and
   preprocessing configuration. Record unsuccessful downloads or conversions.
2. Audio must be finite, correctly aligned, and retain the original sample count
   and channel layout. Silence and partial windows must not crash or create NaNs.
3. Compare accelerated output against the CPU reference, separately from speed.
   Report raw numerical differences and their limitations rather than assuming
   that an enabled provider proves correct output or Neural Engine placement.
4. For reference mixtures, compare SI-SDR and vocal distortion with the current
   separator. Prefer a replacement only when preservation is no worse and
   instrument suppression improves, or equivalent quality has a measured cost
   advantage. A speech-only model cannot win without a singing control.
5. These Shorts have no isolated reference stems. Listening outputs and spectral
   diagnostics do not establish SDR, complete instrument removal or Arabic WER.
   Never use generic music probability to penalize wanted singing.
6. Vision counts do not establish recall. Compare original frames and overlays;
   quantify coverage only for explicitly annotated frames/regions. Report missing
   people, merged masks, face association ambiguity and mask boundary leakage.
7. Native all-person segmentation cannot implement selective women/men coverage
   without an independently validated instance association.

## Measurement and decision

Run heavy benchmarks one at a time on the shared M3. Record load separately from
inference, warm-up count, timed sample count, precision, resolution/window size,
requested compute units and actual fallback when observable. Label Python
screening timings separately from native Swift timings. State memory metric
units and whether it measures RSS or physical footprint.

RTF is processing seconds / input seconds. Report the complete separator stage
separately from model-only latency; do not call either a full exported-job RTF.
Preserve raw JSONL records and local audio/visual artifacts for review.

Choose a practical iOS candidate from admitted results, prioritizing vocal
preservation and censor coverage, followed by speed, peak memory and model size.
Keep at most two candidates per slot in the recommendation. If the available
evidence cannot justify replacing a production model, retain its baseline and
name the strongest next candidate. Physical-phone thermals, sustained memory,
energy and full-job tests remain required before a phone-performance claim.
