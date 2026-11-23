# P7 overlap spike, simulator result

Date: 2026-08-23

Configuration:

- Release build on iPhone 17 Simulator, iOS 26.5.
- Source: `tv1-h264.mp4`, 643.01 seconds.
- `removeMusic = false`, `who = everyone`.
- Real `AnalyzePass.run` overlapped with a forced full-frame `RenderPass.run`.
- The spike lived only in `BenchTests` and did not change production behavior.

Decision rule fixed before the run:

- SHIP only if `analyzeDone <= 115000 ms` and `wall <= 150000 ms`.
- Record DEAD if `wall >= 175000 ms`.

Result: **DEAD on Simulator.**

The isolated run exceeded 175 seconds without either task returning and was
stopped at about 305 seconds. The xcresult records one canceled benchmark after
304.9 seconds. This is already past the fixed rejection boundary, so waiting for
the remaining render could not change the decision.

Result bundle:

`/tmp/naqi-p7-release.9ekLdR/p7-overlap-iphone17.xcresult`

The spike was removed after the measurement, as the plan required. Production
keeps the existing two-pass schedule. A physical-device rerun may be useful for
profiling, but it does not authorize the horizon implementation unless it also
passes the same fixed limits.
