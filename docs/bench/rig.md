# Phase 0 rig — `bench_model` (plan §3.3) and start-gate (plan §3.2)

T1 harness lives in the `benchmark` APK (`applicationId` `com.haithamassoli.naqi.benchmark`, `DEBUG_HOOKS=true`). Release behaviour is unchanged.

## `bench_model`

Build, install, and let the app create its external files dir **before** pushing models. A dir created by `adb mkdir`/`adb push` is owned by `shell` and the non-debuggable app cannot see the files.

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleBenchmark
adb -s R3CW5070LGM install -r app/build/outputs/apk/benchmark/app-benchmark.apk

PKG=com.haithamassoli.naqi.benchmark
BENCH=/sdcard/Android/data/$PKG/files/bench
# one start so getExternalFilesDir("bench") exists and is app-owned
adb -s R3CW5070LGM shell am start -n $PKG/com.haithamassoli.naqi.MainActivity \
  -e bench_model $BENCH/yamnet.onnx --ei bench_iters 1

adb -s R3CW5070LGM push app/src/main/assets/models/yamnet.onnx $BENCH/yamnet.onnx
adb -s R3CW5070LGM push app/src/main/assets/models/htdemucs_s26_f16.onnx $BENCH/htdemucs_s26_f16.onnx
```

Any readable path works. `/data/local/tmp` is not readable on this non-debuggable build; use `$BENCH`.

### Extras

| Extra | Default | Meaning |
|---|---|---|
| `-e bench_model <abs.onnx>` | (required) | Model path |
| `--es bench_ep cpu\|xnnpack` | `cpu` | `cpu` = HtdemucsSession options (arena off, no mem-pattern, spinning off, intra-op = threads). `xnnpack` = imageSessionOptions with XNNPACK `intra_op_num_threads` = threads |
| `--ei bench_threads N` | cpu: `min(6, cores)`; xnnpack: `4` | |
| `--ei bench_iters N` | `50` | Measured runs after one warmup |
| `--ei bench_seconds S` | `0` (off) | Loop until S seconds instead of iters. Reports `p50cool` (first 60 s) and `p50sustained` (last 60 s) |
| `--ez bench_profile true` | false | `SessionOptions.enableProfiling` → `filesDir/bench/profile*` (mirrored to external). Session log level VERBOSE |
| `--es bench_shape 1,3,96,96` | (none) | Required when any input dim is dynamic (`genderage`, `nsfw_mnv2_140_int8`). Single-input static graphs may also override |
| `--es bench_tag name` | `""` | Copied into the log/JSON |

Input tensors are `Random(42)` in `[-1,1]`, FLOAT or FLOAT16 per the graph. Multiple inputs are all filled.

Incumbent IO (from the ONNX graphs, 2026-09-30):

- `yamnet.onnx` — `waveform` FLOAT `[15600]`
- `htdemucs_s26_f16.onnx` — `input` FLOAT `[1,2,114660]`, `x` FLOAT `[1,4,2048,112]` (f16 **weights**, f32 inputs)
- `genderage.onnx` — `data` FLOAT `[None,3,96,96]` → needs `--es bench_shape 1,3,96,96`
- `nsfw_mnv2_140_int8.onnx` — `input` FLOAT `[unk,3,224,224]` → needs `--es bench_shape 1,3,224,224`

### Output

One logcat line, tag `BenchModel`, starting `BENCH ` (key=value, `Locale.ROOT`), then `BENCH done`. Errors: `BENCH error=...`.

JSON-lines, one object per run, appended to both:

- `filesDir/bench/bench_model.jsonl` (internal; `run-as` does not work on this APK)
- `getExternalFilesDir("bench")/bench_model.jsonl` → pull with:

```bash
adb pull /sdcard/Android/data/com.haithamassoli.naqi.benchmark/files/bench/
```

JSON fields: `tag`, `model`, `ep`, `threads`, `n`, `createMs`, `p50`, `p90`, `max`, `mean`, `hwmKb`, `freqStart`/`freqEnd` (kHz per cpufreq policy, sorted), `battCStart`/`battCEnd` (°C), `headroomStart`/`headroomEnd` (`getThermalHeadroom(0)`; `null` if API &lt; 30 or NaN — NaN when polled &lt;500 ms apart, so short runs often have `headroomEnd=null`). Sustained mode adds `seconds`, `p50cool`, `p50sustained`.

Job telemetry (same `DEBUG_HOOKS` builds): `JobStats.tick()` writes at most one JSONL line per 1000 ms to `filesDir/bench/job-<epoch ms>.jsonl` (also mirrored). Fields: `t`, `stage`, `freq`, `battC`, `headroom`, `thermal`, `rssKb`, `hwmKb`. `finish()` appends `{"final":true,"t":<total ms>,"stages":{...},...}`. logcat `SOAK` lines are unchanged.

### Verification (S23 `R3CW5070LGM`, contended, provisional)

```bash
adb -s R3CW5070LGM shell am start -n com.haithamassoli.naqi.benchmark/com.haithamassoli.naqi.MainActivity \
  -e bench_model /sdcard/Android/data/com.haithamassoli.naqi.benchmark/files/bench/yamnet.onnx \
  --ei bench_iters 30 --es bench_ep xnnpack --ei bench_threads 4 --es bench_tag yamnet-verify
```

```
BENCH tag=yamnet-verify model=/sdcard/Android/data/com.haithamassoli.naqi.benchmark/files/bench/yamnet.onnx ep=xnnpack threads=4 n=30 createMs=48 p50=2.012 p90=2.149 max=2.435 mean=2.050 hwmKb=251680 freqStart=1900800,2457600,2841600 freqEnd=1900800,2457600,2841600 battCStart=32.4 battCEnd=32.4 headroomStart=0.5967 headroomEnd=null
BENCH done
```

```bash
adb -s R3CW5070LGM shell am force-stop com.haithamassoli.naqi.benchmark
adb -s R3CW5070LGM shell am start -n com.haithamassoli.naqi.benchmark/com.haithamassoli.naqi.MainActivity \
  -e bench_model /sdcard/Android/data/com.haithamassoli.naqi.benchmark/files/bench/htdemucs_s26_f16.onnx \
  --es bench_ep cpu --ei bench_threads 6 --ei bench_iters 10 --es bench_tag htdemucs-f16-verify
```

```
BENCH tag=htdemucs-f16-verify model=/sdcard/Android/data/com.haithamassoli.naqi.benchmark/files/bench/htdemucs_s26_f16.onnx ep=cpu threads=6 n=10 createMs=519 p50=1541.273 p90=1599.985 max=1633.420 mean=1549.050 hwmKb=1079108 freqStart=1017600,2457600,2841600 freqEnd=1555200,1401600,2227200 battCStart=32.7 battCEnd=32.7 headroomStart=0.5933 headroomEnd=0.6433
BENCH done
```

These timings were taken while other CPU-heavy jobs shared the Mac/USB host and the phone was not start-gated. Treat them as a harness smoke test, not a baseline.

## §3.2 start-gate checklist (before a scored T1/T2 run)

1. Room 20–25 °C, logged. Phone on a stand, air gap, no case. Airplane mode (Wi-Fi only if using wireless adb). Screen off. `pm clear` between job runs.
2. Samsung Battery protection **Maximum** (80–85 %) so the phone runs off the charger at the limit — or run on battery over wireless adb. Do not rely on `cmd battery unplug`.
3. Wait ≥ 5 min idle. Start only when **all three** hold (poll headroom every 1–5 s; calls &lt;500 ms apart return NaN; the server drops samples after 10 s idle):
   - battery temperature ≤ 32.5 °C (`battC` in the JSONL);
   - `getThermalHeadroom(0)` ≤ this rig's recorded idle baseline + 0.02;
   - idle `scaling_cur_freq` maxima look normal (the `freq` array).
4. Interleave candidates ABBA or randomized blocks. ≥ 5 blocks for T2, ≥ 3 for T1. Discard the first run after install or reboot.
5. Report two numbers per candidate: **cool-start** (first 60 s after the gate) and **sustained** (after ≥ 5 min under load). Rank on sustained. `bench_seconds` fills both.
6. Median + IQR per arm, and a bootstrap 95 % CI of the paired A/B ratio. A CI that crosses 1.0 is “no measured difference”.
7. Optional: `adb shell cmd power set-fixed-performance-mode-enabled true` and `Window.isSustainedPerformanceModeSupported()`. Record which mode was active. Unsupported is fine.
