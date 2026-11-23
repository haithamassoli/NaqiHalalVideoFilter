# iOS person censoring integration review

Date: 2026-10-02. This review concerns the production integration on `feat/ios-person-censoring`, following the model comparison. Physical iPhones remain unavailable; the iOS Simulator establishes correctness/UI behavior and native M3 runs establish Apple Silicon feasibility.

## Detector and artifact

The production `PersonDetector` runs the screened YOLO11n-seg executable graph and weights through native Core ML. It requests CPU+GPU on hardware and CPU on the simulator. The actor owns its reusable RGB buffer/model; missing models, incompatible shapes and invalid person predictions throw instead of silently selecting face-only detection.

The input matches the reference: 640×640 electrical RGB, centered letterbox with 114 padding, /255 inside the exported graph, person as best of 80 classes, confidence strictly greater than 0.25, IoU NMS 0.7 and max 300 detections. Boxes are decoded before clipping, then transformed to upright source pixels. The output contract is `[1,116,8400]` float32. Masks are not consumed by the application: rendering uses conservatively expanded body rectangles.

Four source frames (frontal presenter, foreground hands, rear view and three-panel collage) produced exactly the same boxes as the previous Python-host Core ML CPU+GPU reference. Two of those images were also stored at 0/90/180/270-degree rotations and restored through the production input path, with matched box IoU=1.0 in all eight comparisons. These controls use decoded PNG/RGB pixels; the complete production NV12 decode/render paths are evaluated separately below. [Parity records](results/vision-person-detector-parity.json).

The model was also independently re-exported from the pinned official checkpoint. Canonical model protobuf and weight-bin hashes matched the screened package; only descriptive timestamps/package UUIDs are excluded from the graph hash. `scripts/fetch-person-model.py` stages the compiled iOS18-compatible `yolo11n_seg.mlmodelc` into the existing ignored Models folder. Export tools and media/model binaries are not committed or shipped as Python dependencies.

## Tracking and policy review

- The source is analyzed on every decoded frame in person mode. Frame orientation stays explicit; YCbCr/transfer attachments are propagated when detector frames are scaled.
- Production face association requires a single containing body with ≥80% face-area overlap. This is broader than the comparison's upper-third heuristic; the earlier 8/8 association count is a benchmark diagnostic, not a measured production association score.
- Near-tied geometric matches close old identities and create unknown tracks. Several faces inside one body box also split the track, preserving earlier evidence rather than letting a later male/female vote rewrite its past geometry.
- A previously classified person retains evidence while its face is hidden within a continuous body track. Unknown tracks are censored under both women and men policies; everyone bypasses gender classification.
- At least two votes are required before a track becomes male/female. One occluded observation cannot spare a body as the opposite category; insufficient evidence remains unknown. Raw votes remain in the EDL. The final policy renders apply this guard.
- A selected face that cannot be assigned safe body bounds promotes that interval to full-frame censoring. Person-mode errors fail the job; a missing body result is not automatically an error.
- Short detection gaps hold expanded geometry for at most 300 ms. IDs have shot scope; the lightweight 16×16 luma cut detector resets evidence on detected cuts. It cannot guarantee detecting cuts with similar pictures or preserving identity through all crossings.
- Person evidence is stored independently of which policy currently censors it. New EDL fields round-trip, legacy options without the target field retain face mode, and checkpoints distinguish face/person processing.

No policy can recover a person missed by both face and body detectors. The comparison's tiny human on a camera display is a known case: absence supplies neither an unknown track nor a full-frame fallback trigger. This remains a coverage limit rather than an inference exception.

## Complete exported-video review

Complete exported Shorts were reviewed with the scene gate both enabled and disabled. The scene gate can conceal a body-detection miss by covering the whole picture; the separate women/men/everyone exports therefore disable it. Four selected frames of the third Arabic lyric video were reviewed as **audio-only video passthrough**, with its static male portrait unchanged; they do not establish person-policy coverage on that video.

Actual final policy frames reviewed after the two-vote guard: foreground hands at 36.5 s, rear view at 17.5 s, farther presenter at 18.5 s, partial body at 28.5 s, the three-panel collage at 52.5 s, camera display at 8.5 s and occluded faces at 5.3/5.75 s. Source/output review sheets remain outside Git under `qa-assets/modelbench/integration/vision-review`. The earlier `*_compare.jpg` sheets are diagnostic pre-guard/default-scene runs; `*_final_policies.jpg` sheets show the final scene-off women/men/everyone videos.

| Diagnostic body interiors | Women | Men | Everyone |
|---|---:|---:|---:|
| Short1 hands/shirt at 36.5 s | 14/14 covered | 0/14 covered, correctly spared known female | 14/14 covered |
| Short2 partial body at 28.5 s | 5/5 covered | 5/5 covered, unknown | 5/5 covered |
| Short2 collage at 52.5 s | 9/9 covered | 9/9 covered, unknown | 9/9 covered |

These are rendering-region checks of sparse manually inspected body interiors, not dense segmentation recall, recognition accuracy or a universal coverage guarantee. The actual scene-off output shows blurred hands/clothes at 36.5 s and a blurred rear body at 17.5 s in women mode; men mode spares both known-female examples. Partial-body/collage observations have insufficient face evidence and are also blurred in men mode, an observed conservative over-censoring cost. [Probe records](results/vision-integration-probes.json).

Important failures and limitations:

- **The person displayed on the small camera screen is still visible at 8.5 s in the complete exported video.** Neither detector creates a usable observation there. Policy uncertainty/full-frame fallback cannot cover an object that was never detected.
- Two initial “male” tracks in Short1 were actually the same female presenter obscured by the jewelry bag at 5.3/5.75 s. They were unmatched-face tracks with only one male vote each; the legacy face EDL covered a face at both timestamps. The two-vote guard now treats those records as unknown. It does not make that uncertain woman disappear from men-mode censoring: both policies conservatively censor unknowns.
- Several overlapping large person boxes at Short2 11.15 s correspond to one presenter hiding behind a product box. They are body-part/duplicate detections, not evidence of several real people. Increasing or suppressing their identities without reliable ownership evidence could transfer a category to another person; the detector's validated NMS settings remain fixed.
- The 433 tracks in Short2 are mostly small/background cases: one crowd shot accounts for 256 small tracks. Principal foreground tracks are longer and stable (for example 0–5.35 s, 15.87–18.28 s and 22.45–24.85 s). The farther presenter after a cut and collage faces remain unknown at the 80 px voting floor. The 101 ms global median track duration should not be reported as the lifetime of the main presenter.
- **A motion-blurred foreground hand remains visible in Short1 at 6.0 s**, within the 14 uncensored frames at 5.867–6.300 s. The source and actual scene-off output show the same visible hand over the jewelry table. A native Vision hand-pose probe returned zero hand observations at this timestamp and at the tiny-camera timestamp; it supplied no validated fallback. Other uncensored Short2 intervals include legitimate product/scenery imagery; count totals alone are not missed-person duration. [Hand-pose diagnostic](results/vision-integration-hand-probe.json).

The initial person run used roughly 4.84 GiB process physical footprint. Bounding synchronous Core ML/Core Image temporaries with an autorelease pool per detection preserved the complete Short1 EDL exactly and reduced subsequent combined-run peaks to about 2691/2625 MiB for the two Shorts. These include the existing audio pipeline and remain native M3 measurements, not phone memory ceilings.

Final classifications are 13 female/63 unknown for Short1 and 5 female/428 unknown for Short2; no track meets the final male classification rule. The final scene-off whole-frame counts for women/men/everyone are respectively 90/45/90 of 1626 frames and 219/180/219 of 1598 frames. This does not classify every uncensored frame as safe or every blurred frame as a correct category match.

Policy comparison images include `-dQJ3djthDc_036500_final_policies.jpg`, `rX6wXhLqOIQ_017500_final_policies.jpg`, `rX6wXhLqOIQ_052500_final_policies.jpg`; the camera miss is visible in `rX6wXhLqOIQ_008500_final_policies.jpg`. Final outputs/region logs are under `qa-assets/modelbench/integration/short{1,2}-final-person-sceneoff-{women,men,everyone}.mp4`. Numerical review: [selected frames](results/vision-integration-reviewed-frames.json), [final policy/frame counts](results/vision-integration-policy-summary.json). The final probe/policy records include analysis/render-log hashes; local review images and final export hashes are identified by [review provenance](results/vision-integration-review-provenance.json).
