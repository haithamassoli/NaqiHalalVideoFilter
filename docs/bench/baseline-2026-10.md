# S23 baseline — 2026-10 (plan §3.2, §3.4, §9)

Device T1 micro + T2 end-to-end on the 4:29 vlog. Fast lane only.

## Caveats

- **USB charging, single device, 2 runs per shape.** Plan §3.2 says charging adds heat. Battery protection / airplane mode were **not** changed. After ~95 % Samsung reports battery status 4 (not-charging) while USB powered stays true.
- **Start gate is recorded-only after T1 htdemucs.** Wait until `dumpsys battery` temperature ≤ 32.5 °C **and** `dumpsys thermalservice` `Thermal Status: 0`. Poll 5 s. T1 htdemucs used `GATE_MAX=600` and passed (battC=32.4 °C). Every later run used `GATE_MAX=90` because the battery never cooled below 32.5 °C on the charger; those gates timed out and the job started hot. `getThermalHeadroom` is not exposed over adb; idle `scaling_cur_freq` is logged at each poll but not gated on.
- Host Mac was running other CPU jobs at the same time. Those jobs do not share the phone CPU; they are irrelevant to device timings. Numbers below are from this device run.
- Screen: `KEYCODE_SLEEP` after each `am start` (launch wakes the display).
- pm clear between T2 jobs (plan §3.2 / rig.md), then `pm grant` READ_MEDIA_VIDEO + POST_NOTIFICATIONS.

## Rig

| | |
|---|---|
| device | Galaxy S23 SM-S911U1, adb serial `R3CW5070LGM` |
| APK | `com.haithamassoli.naqi.benchmark` (`DEBUG_HOOKS`, non-debuggable) |
| clip | `/sdcard/Movies/naqi-bench/vlog.webm` — 269.233 s, 1080p30 AV1 + Opus 48 k stereo |
| T1 | `--ei bench_seconds 300`, production session options |
| T1 SEP | htdemucs CPU EP × 6, spinning off, arena off, mem-pattern off |
| T1 GATE/NSFW/GEN | XNNPACK, ORT intra-op 1, spinning off, XNNPACK threads = 4 (`XNNPACK_THREADS`) |
| T2 order | ABC then CBA (censor, music, both × 2) |
| charging | USB powered on every run. status=2 (charging) until ~95 %, then status=4 (not-charging) |

## Commands

```
scripts/bench/device_run.sh
```

Recorded `am start` lines from this run:

```
adb -s R3CW5070LGM shell am start -W -n com.haithamassoli.naqi.benchmark/com.haithamassoli.naqi.MainActivity -e bench_model /sdcard/Android/data/com.haithamassoli.naqi.benchmark/files/bench/htdemucs_s26_f16.onnx --es bench_ep cpu --ei bench_threads 6 --ei bench_seconds 300 --es bench_tag t1-htdemucs
adb -s R3CW5070LGM shell am start -W -n com.haithamassoli.naqi.benchmark/com.haithamassoli.naqi.MainActivity -e bench_model /sdcard/Android/data/com.haithamassoli.naqi.benchmark/files/bench/yamnet.onnx --es bench_ep xnnpack --ei bench_threads 4 --ei bench_seconds 300 --es bench_tag t1-yamnet
adb -s R3CW5070LGM shell am start -W -n com.haithamassoli.naqi.benchmark/com.haithamassoli.naqi.MainActivity -e bench_model /sdcard/Android/data/com.haithamassoli.naqi.benchmark/files/bench/nsfw_mnv2_140_int8.onnx --es bench_ep xnnpack --ei bench_threads 4 --ei bench_seconds 300 --es bench_tag t1-nsfw --es bench_shape 1,3,224,224
adb -s R3CW5070LGM shell am start -W -n com.haithamassoli.naqi.benchmark/com.haithamassoli.naqi.MainActivity -e bench_model /sdcard/Android/data/com.haithamassoli.naqi.benchmark/files/bench/genderage.onnx --es bench_ep xnnpack --ei bench_threads 4 --ei bench_seconds 300 --es bench_tag t1-genderage --es bench_shape 1,3,96,96
adb -s R3CW5070LGM shell am start -W -n com.haithamassoli.naqi.benchmark/com.haithamassoli.naqi.MainActivity -e autorun_path /sdcard/Movies/naqi-bench/vlog.webm --es censor_who women
adb -s R3CW5070LGM shell am start -W -n com.haithamassoli.naqi.benchmark/com.haithamassoli.naqi.MainActivity -e autorun_path /sdcard/Movies/naqi-bench/vlog.webm --ez remove_music true --es censor_who none
adb -s R3CW5070LGM shell am start -W -n com.haithamassoli.naqi.benchmark/com.haithamassoli.naqi.MainActivity -e autorun_path /sdcard/Movies/naqi-bench/vlog.webm --ez remove_music true --es censor_who women
adb -s R3CW5070LGM shell am start -W -n com.haithamassoli.naqi.benchmark/com.haithamassoli.naqi.MainActivity -e autorun_path /sdcard/Movies/naqi-bench/vlog.webm --ez remove_music true --es censor_who women
adb -s R3CW5070LGM shell am start -W -n com.haithamassoli.naqi.benchmark/com.haithamassoli.naqi.MainActivity -e autorun_path /sdcard/Movies/naqi-bench/vlog.webm --ez remove_music true --es censor_who none
adb -s R3CW5070LGM shell am start -W -n com.haithamassoli.naqi.benchmark/com.haithamassoli.naqi.MainActivity -e autorun_path /sdcard/Movies/naqi-bench/vlog.webm --es censor_who women
```

Start-gate log: `1` pass, `9` timeout. Full poll log: `qa-assets/bench-out/device/gate.log`.

## T1 — B-micro, sustained 300 s

p50cool = first 60 s after the start gate; p50sustained = last 60 s of the 300 s loop. Rank on sustained. Times in **ms / inference**.

| model | ep | threads | n | p50cool | p50sustained | p90 | max | hwm MB | battC start→end | freq start→end (kHz, policies 0,3,7) |
|---|---|---:|---:|---:|---:|---:|---:|---:|---|---|
| htdemucs_s26_f16 | cpu | 6 | 116 | 8690.457 | 2293.097 | 2364.861 | 8826.639 | 1085.2 | 32.4→37.1 | `1017600,1785600,2841600` → `1228800,1536000,1843200` |
| yamnet | xnnpack | 4 | 92596 | 2.727 | 3.646 | 3.648 | 89.567 | 220.6 | 35.5→37.2 | `1555200,2457600,2841600` → `1017600,844800,1593600` |
| nsfw_mnv2_140_int8 | xnnpack | 4 | 51477 | 3.891 | 6.044 | 6.070 | 185.632 | 203.5 | 35.6→37.2 | `1900800,2457600,2841600` → `1017600,1286400,1593600` |
| genderage | xnnpack | 4 | 686031 | 0.287 | 0.429 | 0.442 | 99.468 | 238.8 | 36.2→38.4 | `1555200,2457600,2841600` → `1017600,1286400,1593600` |

htdemucs **ms per audio-second** = p50sustained / 2.34 = 2293.097 / 2.34 = **980.0 ms/s**.
Plan §1 figure is 2297 ms/chunk. This run's p50sustained is **2293.097 ms/chunk** (0.998× the plan figure).

Raw BENCH lines:

```
09-30 14:14:43.747 23009 23029 I BenchModel: BENCH tag=t1-htdemucs model=/sdcard/Android/data/com.haithamassoli.naqi.benchmark/files/bench/htdemucs_s26_f16.onnx ep=cpu threads=6 n=116 createMs=579 p50=2299.240 p90=2364.861 max=8826.639 mean=2601.487 hwmKb=1111292 freqStart=1017600,1785600,2841600 freqEnd=1228800,1536000,1843200 battCStart=32.4 battCEnd=37.1 headroomStart=0.5867 headroomEnd=0.7633 seconds=300 p50cool=8690.457 p50sustained=2293.097
09-30 14:29:24.378 27936 27956 I BenchModel: BENCH tag=t1-yamnet model=/sdcard/Android/data/com.haithamassoli.naqi.benchmark/files/bench/yamnet.onnx ep=xnnpack threads=4 n=92596 createMs=66 p50=3.173 p90=3.648 max=89.567 mean=3.238 hwmKb=225856 freqStart=1555200,2457600,2841600 freqEnd=1017600,844800,1593600 battCStart=35.5 battCEnd=37.2 headroomStart=0.6867 headroomEnd=0.7733 seconds=300 p50cool=2.727 p50sustained=3.646
09-30 14:36:02.705 29522 29542 I BenchModel: BENCH tag=t1-nsfw model=/sdcard/Android/data/com.haithamassoli.naqi.benchmark/files/bench/nsfw_mnv2_140_int8.onnx ep=xnnpack threads=4 n=51477 createMs=76 p50=5.251 p90=6.070 max=185.632 mean=5.826 hwmKb=208344 freqStart=1900800,2457600,2841600 freqEnd=1017600,1286400,1593600 battCStart=35.6 battCEnd=37.2 headroomStart=0.6900 headroomEnd=0.7733 seconds=300 p50cool=3.891 p50sustained=6.044
09-30 14:42:42.597 31596 31615 I BenchModel: BENCH tag=t1-genderage model=/sdcard/Android/data/com.haithamassoli.naqi.benchmark/files/bench/genderage.onnx ep=xnnpack threads=4 n=686031 createMs=22 p50=0.424 p90=0.442 max=99.468 mean=0.437 hwmKb=244504 freqStart=1555200,2457600,2841600 freqEnd=1017600,1286400,1593600 battCStart=36.2 battCEnd=38.4 headroomStart=0.7033 headroomEnd=0.7800 seconds=300 p50cool=0.287 p50sustained=0.429
```

## T2 — end-to-end on the vlog

Source duration **269.233 s** (269.2 s). Wall per video-minute = wall_s / (269.233/60). Realtime factor = 269.233 / wall_s (>1 means faster than realtime). Charging column is the dumpsys snapshot at job start.

| shape | run | total wall | analyze | render | separate | mux/publish | wall / video-min | realtime | peak RSS | maxThermal | battC start→end | charging |
|---|---:|---|---|---|---|---|---:|---:|---:|---:|---|---|
| censor | 1 | 68965 ms (1.15 min) | 30581 ms (0.51 min) | 37820 ms (0.63 min) | — | pub 256 ms (0.00 min) | 15.37 | 3.904 | 325 MB (332848 kB) | 0 | 36.9→37.8 | USB=true status=2 (charging) |
| censor | 2 | 92522 ms (1.54 min) | 51080 ms (0.85 min) | 40839 ms (0.68 min) | — | pub 331 ms (0.01 min) | 20.62 | 2.910 | 345 MB (353504 kB) | 0 | 33.0→34.1 | USB=true status=4 (not-charging) |
| music | 1 | 355106 ms (5.92 min) | — | — | 342508 ms (5.71 min) | mux 12298 ms (0.20 min) | 79.14 | 0.758 | 1172 MB (1200316 kB) | 0 | 36.9→37.4 | USB=true status=2 (charging) |
| music | 2 | 935448 ms (15.59 min) | — | — | 926035 ms (15.43 min) | mux 9053 ms (0.15 min) | 208.47 | 0.288 | 1264 MB (1294620 kB) | 0 | 37.0→34.1 | USB=true status=4 (not-charging) |
| both | 1 | 367911 ms (6.13 min) | 54804 ms (0.91 min) | 38632 ms (0.64 min) | 265875 ms (4.43 min) | mux 8287 ms (0.14 min) | 81.99 | 0.732 | 1238 MB (1267600 kB) | 0 | 35.2→38.7 | USB=true status=2 (charging) |
| both | 2 | 471464 ms (7.86 min) | 88923 ms (1.48 min) | 70995 ms (1.18 min) | 301669 ms (5.03 min) | mux 9550 ms (0.16 min) | 105.07 | 0.571 | 1327 MB (1359132 kB) | 1 | 39.2→40.1 | USB=true status=4 (not-charging) |

### Median per shape and run-to-run spread

Exit criterion (plan §3.4): the same build measured twice under the start gate agrees within 5 % on every counter. Spread = |r1−r2| / median(r1,r2).

| shape | n | median wall | r1 | r2 | spread wall | within 5 %? | median analyze | analyze spread | median separate | separate spread |
|---|---:|---|---|---|---:|---|---|---:|---|---:|
| censor | 2 | 80744 ms (1.35 min) | 68965 ms (1.15 min) | 92522 ms (1.54 min) | 29.2 % | NO | 40830 ms (0.68 min) | 50.2 % | — | — |
| music | 2 | 645277 ms (10.75 min) | 355106 ms (5.92 min) | 935448 ms (15.59 min) | 89.9 % | NO | — | — | 634272 ms (10.57 min) | 92.0 % |
| both | 2 | 419688 ms (6.99 min) | 367911 ms (6.13 min) | 471464 ms (7.86 min) | 24.7 % | NO | 71864 ms (1.20 min) | 47.5 % | 283772 ms (4.73 min) | 12.6 % |

**Exit criterion did NOT hold on every counter.**

- censor wall: 29.2 % — outside 5 %
- censor analyze: 50.2 % — outside 5 %
- music wall: 89.9 % — outside 5 %
- music separate: 92.0 % — outside 5 %
- both wall: 24.7 % — outside 5 %
- both analyze: 47.5 % — outside 5 %
- both separate: 12.6 % — outside 5 %

Plan §3.4 exit did not hold: music wall 355 s vs 935 s.

### Job telemetry (`job-*.jsonl`)

`JobStats.tick()` writes at most one JSONL line per 1000 ms to `filesDir/bench/job-<epoch>.jsonl` and mirrors it to the external files dir. `pm clear` between T2 jobs wipes that tree; the driver then dummy-starts the app to recreate an empty external dir **before** the job writes. Ticks that only landed in the internal dir are not in the pull. Handle missing files as empty (parser does not crash).

| shape | run | jsonl in pull | ticks (non-final) | tick t range (ms) | median Δt (ms) |
|---|---:|---|---:|---|---:|
| censor | 1 | `bench/bench/job-1790768666097.jsonl` | 59 | 729–67738 | 1008 |
| censor | 2 | `bench/bench/job-1790771391919.jsonl` | 72 | 661–91193 | 1026 |
| music | 1 | `bench/bench/job-1790768838186.jsonl` | 108 | 5052–354115 | 2773 |
| music | 2 | `bench/bench/job-1790770353343.jsonl` | 105 | 7879–934677 | 9390 |
| both | 1 | `bench/bench/job-1790769297249.jsonl` | 137 | 595–366879 | 2819 |
| both | 2 | `bench/bench/job-1790769771613.jsonl` | 175 | 627–470443 | 2840 |

**Which runs lack 1 Hz telemetry, and why.** The Finding's 1 Hz / ~870 s freq series is the orchestrator live readout on **t2/music-2**. Every T2 dir in this checkout has a `job-*.jsonl` whose `final.t` matches SOAK, but the 1 Hz stream is missing from the pull for the long jobs (music-1, music-2, both-1, both-2 — median Δt in the table is several seconds, not 1000 ms). Censor-1/2 are short enough that the pull is still ~1 Hz. Cause: `pm clear` recreates the empty external dir before the job writes; ticks that only landed in `filesDir` never made `adb pull`. A missing file is treated as empty (parser does not crash).

## Finding: the in-job separator runs on little cores

Evidence gathered on this S23 during the T2 music/both jobs (copied from the orchestrator readout; not re-derived):

- separate-stage wall for the same 269 s of audio: music-1 342.5 s, music-2 926.0 s, both-1 265.9 s, both-2 301.7 s. T1 predicts roughly 60 separated chunks (host gate replica, `docs/bench/gate-t0.md`) × 2.29 s ≈ 140 s, so the in-job separator is 1.9–6.6× slower than the isolated microbench.
- t2/music-2 telemetry (1 Hz): for ~870 s policy3 (A715 mid cluster) sat at 614 400 kHz and policy7 (X3) at 864 000 kHz — their floors — while policy0 (A510 little) sat at its 1 900 800 max; thermal status 0 throughout; headroom 0.63–0.74; battery cooling 37.7→34.1 °C. So NOT thermal throttling — invisible to maxThermal, exactly the blind spot plan §3.1 describes.
- Live probe during a music job (screen on and screen off, same result): process cpuset `/moderate`, `mCurSchedGroup=6`; the six busiest threads (named `DefaultDispatch` — ORT pool threads inherit the creating coroutine thread's name) last ran on CPUs 0–2 only (the little cluster on S23 is CPUs 0–2).
- T1 `bench_model` htdemucs (thread started from `MainActivity`) got 2293 ms/chunk sustained, matching plan §1's 2297 — but its first 60 s ran at ~8.7 s/chunk (p50cool 8690 ms), consistent with the same placement effect early on.
- Charging state: all runs on USB power; Samsung stopped charging at ~95 % during music-2 (battery status 4), so charging heat is not the explanation either.

**Recommendation (hypothesis, next step):** confirm with a Perfetto `sched` + `power/cpu_frequency` trace (plan §3.3), then test (a) ADPF `PerformanceHintManager` hint session for the ORT threads, (b) the FilterWorker's foreground-service type / process importance, (c) thread affinity or `Process.setThreadPriority` for the separator thread. Any fix is its own PR with a T2 ABBA.

### Decoder (AV1 source)

Component names from logcat this run:

- `c2.qti.av1.decoder` (first seen on censor run 1)
- `c2.android.opus.decoder` (first seen on music run 1)
- `c2.android.av1-dav1d.decoder` (first seen on music run 2)

### Output A/V duration (ffprobe on pulled `Movies/Naqi/vlog-naqi-*.mp4`)

| shape | run | video s | audio s | source s |
|---|---:|---:|---:|---:|
| censor | 1 | 269.233 | 269.221 | 269.233 |
| censor | 2 | 269.233 | 269.221 | 269.233 |
| music | 1 | 269.233 | 269.282 | 269.233 |
| music | 2 | 269.233 | 269.282 | 269.233 |
| both | 1 | 269.233 | 269.282 | 269.233 |
| both | 2 | 269.233 | 269.282 | 269.233 |

### NaqiOps / SOAK extras

- **censor run 1** result=`ok`
  - `09-30 14:44:26.084  2045  2045 I NaqiOps : autorun censorWho=women censorFaces=true censorNsfw=true removeMusic=false wholeFrame=false`
  - `09-30 14:44:26.084  2045  2045 I NaqiOps : autorun censorWho=women censorFaces=true censorNsfw=true removeMusic=false wholeFrame=false`
  - SOAK extra: `shape=censor tracks=96 faces=2330 untracked=0 liveTracks=0 spared=1`
  - `09-30 14:45:35.062  2045  2092 I JobStats: SOAK total=68965ms(1.1min) peakRssKb=332848 maxThermal=0 shape=censor tracks=96 faces=2330 untracked=0 liveTracks=0 spared=1`
- **censor run 2** result=`ok`
  - `09-30 15:29:51.902 19869 19869 I NaqiOps : autorun censorWho=women censorFaces=true censorNsfw=true removeMusic=false wholeFrame=false`
  - `09-30 15:29:51.902 19869 19869 I NaqiOps : autorun censorWho=women censorFaces=true censorNsfw=true removeMusic=false wholeFrame=false`
  - SOAK extra: `shape=censor tracks=105 faces=2362 untracked=0 liveTracks=0 spared=5`
  - `09-30 15:31:24.442 19869 19891 I JobStats: SOAK total=92522ms(1.5min) peakRssKb=353504 maxThermal=0 shape=censor tracks=105 faces=2362 untracked=0 liveTracks=0 spared=5`
- **music run 1** result=`ok`
  - `09-30 14:47:18.172  4872  4872 I NaqiOps : autorun censorWho=none censorFaces=false censorNsfw=true removeMusic=true wholeFrame=false`
  - `09-30 14:47:18.172  4872  4872 I NaqiOps : autorun censorWho=none censorFaces=false censorNsfw=true removeMusic=true wholeFrame=false`
  - SOAK extra: `shape=music resumable=false`
  - `09-30 14:53:13.292  4872  4906 I JobStats: SOAK total=355106ms(5.9min) peakRssKb=1200316 maxThermal=0 shape=music resumable=false`
- **music run 2** result=`ok`
  - `09-30 15:12:33.327 16447 16447 I NaqiOps : autorun censorWho=none censorFaces=false censorNsfw=true removeMusic=true wholeFrame=false`
  - `09-30 15:12:33.327 16447 16447 I NaqiOps : autorun censorWho=none censorFaces=false censorNsfw=true removeMusic=true wholeFrame=false`
  - SOAK extra: `shape=music resumable=false`
  - `09-30 15:28:08.791 16447 16472 I JobStats: SOAK total=935448ms(15.6min) peakRssKb=1294620 maxThermal=0 shape=music resumable=false`
- **both run 1** result=`ok`
  - `09-30 14:54:57.235  7520  7520 I NaqiOps : autorun censorWho=women censorFaces=true censorNsfw=true removeMusic=true wholeFrame=false`
  - SOAK extra: `shape=combined tracks=98 faces=2368 untracked=0 liveTracks=0 spared=5`
  - `09-30 15:01:05.161  7520  7547 I JobStats: SOAK total=367911ms(6.1min) peakRssKb=1267600 maxThermal=0 shape=combined tracks=98 faces=2368 untracked=0 liveTracks=0 spared=5`
- **both run 2** result=`ok`
  - `09-30 15:02:51.597 11383 11383 I NaqiOps : autorun censorWho=women censorFaces=true censorNsfw=true removeMusic=true wholeFrame=false`
  - SOAK extra: `shape=combined tracks=102 faces=2231 untracked=0 liveTracks=0 spared=6`
  - `09-30 15:10:43.078 11383 11407 I JobStats: SOAK total=471464ms(7.9min) peakRssKb=1359132 maxThermal=1 shape=combined tracks=102 faces=2231 untracked=0 liveTracks=0 spared=6`

## How to rerun

```
scripts/bench/device_run.sh                 # full T1+T2+write-up
scripts/bench/device_run.sh --t1            # micro only
scripts/bench/device_run.sh --t2            # e2e only
scripts/bench/device_run.sh --parse         # rebuild this file from qa-assets/bench-out/device/
```

Raw logs: `qa-assets/bench-out/device/{t1,t2,gate.log,driver.log}`.
