# Fast-lane results — 2026-09-30

Plan: [`model-benchmark-plan.md`](../model-benchmark-plan.md) §11 fast lane:
P0 → GATE-0b + GATE-1 → SEP-1 at T0/T1 → GEN-1 against the §3.3 bar.
Test clip: *a week in my life vlog* (4:29, 1080p30 AV1 + Opus, English). Device: Galaxy S23 (SM-S911U1).

The scope was a single clip, a single device, and no listening panel. Every verdict below is a **smoke-level signal**. None of it is the corpus-backed decision that §10 asks for. Details and raw commands are in the per-slot docs:
[rig](rig.md) · [baseline](baseline-2026-10.md) · [gate](gate-t0.md) · [separator](sep-t0.md) · [gender](gen-t0.md).

## 1. Headline: on the S23 the job loses its big cores, and the rig can't see it

This is the most important result, and it was not a planned item.

About a minute after the screen goes off, the S23 parks the mid (A715) and prime (X3) clusters at their
floor clocks (614 / 864 MHz) for our process. The ORT threads then run on CPUs 0–2 (A510) only.
Thermal status stays 0, headroom sits at 0.6–0.7, and the battery cools. **`maxThermal` can't see any of it**,
which is the blind spot that §3.1 predicted.

A controlled check used `bench_model`, alternating SEP-1 and htdemucs over 150 s each, with the screen turned off 2 s after start:

| arm | p50 first 60 s | p50 last 60 s | mid/prime clocks at end |
|---|---:|---:|---|
| SEP-1 run 1 | 1 864 ms | 17 393 ms | 614 / 864 MHz |
| htdemucs run 1 | 2 394 ms | 16 620 ms | 614 / 864 MHz |
| SEP-1 run 2 | 2 089 ms | 17 865 ms | 614 / 864 MHz |
| htdemucs run 2 | 2 448 ms | 15 392 ms | 614 / 864 MHz |

The same effect shows in the real jobs. The same 269 s of audio took 266–926 s in the `separate` stage
across four runs, against ≈140 s predicted from the microbench (60 separated chunks × 2.29 s). The run-to-run spread was
29 % (censor), 90 % (music) and 25 % (both), so **the §3.4 exit criterion (≤ 5 %) failed**. A live probe
showed the process in cpuset `/moderate` with its busy threads on CPUs 0–2, with the screen on or off.

Consequences:
- **Any device number in this round is only good as a relative number inside one run.** Before Phase 2+ device work, fix the rig: keep
  the screen on (`svc power stayon usb`) or find the policy that parks the clusters.
- **This is also a product issue.** Users lock their phone while a film processes. If the parking is what
  it looks like, the fix could be worth more than any model swap (up to 7× on the separator).
  Next step: a Perfetto `sched` + `cpu_frequency` trace. Then try an ADPF `PerformanceHintManager` session for the
  ORT threads, and check the FilterWorker's foreground-service type. Each fix goes in its own PR with a T2 ABBA.
- The music-2 run also logged `c2.android.av1-dav1d.decoder` (software AV1), while other runs used `c2.qti.av1.decoder`.
  That is a second possible confound to rule out in the same trace.

### Follow-up (same day): battery exemption on, then screen on

The owner reported that "Faster background processing" (the battery-optimization exemption) was **off** for the runs above.
I re-ran the same ABAB with the benchmark app exempt (`deviceidle whitelist` confirms it), and added one control with the screen on
(`qa-assets/bench-out/device/t1-sep1-unrestricted/`):

| arm | p50 first 60 s | p50 last 60 s | mid/prime clocks at end |
|---|---:|---:|---|
| SEP-1 ×2 (exempt, screen off) | 9 909 / 10 170 ms | 9 789 / 9 918 ms (overall p50) | 614 / 864 MHz |
| htdemucs ×2 (exempt, screen off) | 8 679 / 8 646 ms | 8 603 / 8 613 ms (overall p50) | 614 / 864 MHz |
| htdemucs (exempt, **screen on**) | 8 692 ms | 8 865 ms | 1 286 / 1 248 MHz |

- The exemption removed the fast-then-slow cliff. The job is now **uniformly** about 3.7× slower than this morning's 2.3 s, even with the screen on.
  So the remaining limit is neither screen-off nor Doze.
- The device showed `sem_low_heat_mode=1` (*Performance profile → Light*), with `scaling_max_freq` capped below `cpuinfo_max_freq`.
  The owner switched the profile to **Standard**, and htdemucs `bench_model` recovered to **1 710 ms** (screen on) and **1 921 ms** (screen off).
  So the Light profile alone cost about 5×.

### The real cause for real jobs: Samsung confines a foreground service to the little cores

Setup: a T2 `music` job on the Standard profile, with the battery exemption on (`qa-assets/bench-out/device/t2-standard/`).

| run | `separate` stage | process state during separate |
|---|---:|---|
| screen off (FGS, `mediaProcessing`) | **734.9 s** (the last ~70 s ran after I brought the app to the front) | procState 4 (FGS), `mCurSchedGroup=6`, cpuset **`/moderate`**, `Cpus_allowed_list 0-2` |
| app on screen for the whole job | **256.0 s** | procState 2 (TOP), cpuset **`/top-app`**, CPUs 0–7, prime at 3.36 GHz |

- The worker *is* a proper foreground service (`isForeground=true types=0x2000`). One UI still places FGS processes in its
  `/moderate` cpuset, which allows only the three A510 cores. `bench_model` doesn't reproduce this reliably, because it runs from the activity and not from the FGS.
  Only T2 shows it.
- Neither the exemption nor the performance profile fixes this. **The only lever measured so far is keeping Naqi on screen: 2.9× faster.**
  ADPF hints can't widen a cpuset, so they are unlikely to help here.
- Product options, cheapest first:
  1. A "keep the screen on" mode on the job screen (`FLAG_KEEP_SCREEN_ON`), with a hint that processing is about 3× faster while Naqi stays open.
  2. Check whether any One UI allow-list moves an FGS out of `/moderate`. Unknown, and needs a Samsung source.

## 2. Per-slot results

### SEP: separator (T0 host + T1 device)

| | speech SI-SDR vs SEP-0 (−5/0/5/10 dB) | vlog residual-music s | host ms/audio-s (idle) | **S23 ms/audio-s (cool)** | verdict |
|---|---|---:|---:|---:|---|
| SEP-0 htdemucs | — | **5.85** | 214.9 | 1 023–1 046 ¹ | control 🔴 |
| SEP-1 UVR MDX 3_9662 | +0.59 / +0.49 / −0.29 / **−0.59** | 32.2 | 564.3 | **420–470** ² | **fails §5.2** |
| SEP-2 UVR MDX 9482 | +0.72 / −0.06 / −0.87 / −1.29 | 32.2 | 649.9 | — | fails |
| SEP-7 GTCRN | −2.2 / −2.9 / −3.9 / −5.2 | 29.3 | 43.6 | — | fails fast-mode (residual music) |

¹ 2 394–2 448 ms per chunk ÷ 2.34 s stride. ² 1 864–2 089 ms per 5.92 s window ÷ 4.44 s of new audio (0.25 overlap).

- **Host ratios lie here as well.** On the Mac, SEP-1 costs 2.6× htdemucs. On the S23 (cool) it costs **~0.43×**, which
  matches the plan's "about half the cost". §5.2's "host cost ≤ 0.7×" clause is the wrong screen for this graph.
  Re-state it on T1.
- SEP-1 still fails §5.2 on quality. It misses speech SI-SDR at +10 dB by 0.09 dB and SIR at +5 dB by 0.6 dB. On the vlog it
  also leaves **5.5× more residual music** than htdemucs, because it keeps more of the singing (singing projected fraction 0.49 vs 0.20).
  The §5.4 listening test decides, but singing retention looks like the real problem for the default
  `vocals` mode.
- Next: a listening test on SEP-1, and in parallel a MDX-class architecture trained to *drop* singing (§5.5 training track).

### GATE: music gate (T0 host)

| arm | vlog chunks separated / 116 | missed-music s | recitation + adhan pass-through | verdict |
|---|---:|---:|---:|---|
| GATE-0 YAMNet | 60 | 2 | **0 %** (97 / 97 separated) | control |
| GATE-0b (drop Chant, Mantra) | 60 | 2 | 37.1 % | same vlog cost; J3 win |
| GATE-1 PretrainedSED mn06 | 66 | 2 | 73.2 % | +10 % SEP on the vlog |

- **The plan's hypothesis is confirmed. Today's gate sends every Qur'an recitation and adhan chunk to the separator**, and in
  20 of 22 files the top class is Chant or Mantra. GATE-0b is a one-line fix for most recitation, but not for adhan, where plain `Music` scores 0.45.
- Neither arm meets the −10 % projected-SEP bar on this English vlog.
- Owner decision (§12.3): is chanted singing music? GATE-0b stops treating it as music.

### GEN: face gender (T0 host, 82 hand-labelled tracks)

| | female track acc | women exposed (Women mode) | host ms / crop (idle) | S23 ms / crop |
|---|---:|---:|---:|---:|
| GEN-0 InsightFace 🔴 | 94.8 % | 4.5 % | 0.69 | 0.43 |
| GEN-1 OMZ 0013, tight crop | 62.0 % | 28.4 % | 1.97 | — |

- GEN-1 fails the §7.1 bar. The conversion is not the cause: the ONNX matches OpenVINO, with argmax agreement on 200 / 200 inputs. The model is weak on selfie angles.
- The next step is **GEN-T** (distil into a small clean-licence model) or shipping Everyone-only. The set was 67 female tracks of essentially
  one woman plus 2 male tracks, so men-visible could not be measured.

## 3. Updated decision table (provisional)

| slot | today | fast-lane signal | next |
|---|---|---|---|
| SEP | htdemucs 🔴 (owner: licence not a constraint) | SEP-1 is ~0.43× cost on device. It keeps singing, which the **owner wants** (2026-09-30: "remove instruments, keep voice and singing"), so its vlog "residual music" is mostly a desired outcome. It misses the speech bar only marginally (−0.09 dB SI-SDR @ +10, −0.6 dB SIR @ +5) | owner listens to `sep0_vlog.wav` vs `sep1_vlog.wav`; if it passes → T2 ABBA and ship. `vocals_other` dropped |
| GATE | YAMNet | GATE-0b fixes most recitation at zero cost | **closed (owner, 2026-09-30):** recitation/adhan with music is very rare, so no recitation protection is needed. Keep YAMNet. |
| GEN | genderage 🔴 (owner: keep) | GEN-1 not viable | none needed while licence is not a constraint |
| rig / runtime | — | One UI puts the FGS in `/moderate` (little cores only); Light profile costs about 5× more | keep-screen-on mode deferred by owner; S23 only for now |

## 4. Idle host re-cost (supersedes the "contended" numbers in the slot docs)

All runs on the Mac, idle, one arm at a time:
- SEP: htdemucs 214.9, SEP-1 564.3, SEP-2 649.9, SEP-7 43.6 ms per audio-second.
- GATE: GATE-0 15.3, GATE-0b 13.5, GATE-1 8.8 ms per chunk.
- GEN: GEN-0 0.69, GEN-1 1.97, GEN-1 INT8 3.92 ms per crop.

## 5. Not done in this round

Everything outside the fast lane, including:
- Phase 1 corpora and the labelling policy;
- the §5.4 listening test;
- NSFW and FACE bake-offs;
- LiteRT GPU and the QNN ceiling;
- the D2–D7 device matrix.

SEP-1 quantization was also skipped, because it failed T0. Rig limits this round: the phone was on USB power and charging, the start gate was record-only (the battery never cooled
below 32.5 °C on the charger), and there were only 2 runs per shape.
