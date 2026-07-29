# M4.0 — Packaging spike (gate)

**Verdict: GO.** Both youtubedl-android AARs coexist with ONNX Runtime, `useLegacyPackaging = true`
does not cost 16 KB-page support, and htdemucs inference is **bit-identical** after the flip.

Measured 2026-07-29. Devices: Galaxy S23 (SM-S911U1, Android 16, 4 KB pages) for functional work;
Android 15 `google_apis_playstore_ps16k` arm64 emulator (`PAGESIZE=16384`) for the page-size question.

---

## What the gate was actually about

The PRD framed this as "flipping `useLegacyPackaging` to `true` **should** satisfy ORT — unverified."
That framing had the risk in the wrong place. `useLegacyPackaging` controls whether `.so` files are
stored compressed in the APK and extracted at install time, or stored uncompressed and mapped straight
out of the APK. 16 KB-page support is a property of **the `.so`'s own ELF `LOAD` segment alignment** —
which is unchanged either way. In-APK zip alignment only matters when libs are *not* extracted, so
`useLegacyPackaging = true` removes a 16 KB constraint rather than adding one.

The real risk was never the flag. It was whether **youtubedl-android's own native libraries** are built
16 KB-aligned. If they were not, they would fail to load on every 16 KB device regardless of packaging,
and no Gradle setting could fix it.

They are.

## 1. 16 KB alignment — every library in the APK

`llvm-readelf -l` over the arm64 libs, taking the maximum `LOAD` segment alignment:

| Library | Origin | Max `LOAD` align | 16 KB OK |
|---|---|---|---|
| `libonnxruntime.so` | ONNX Runtime | `0x4000` | yes |
| `libonnxruntime4j_jni.so` | ONNX Runtime | `0x4000` | yes |
| `libface_detector_v2_jni.so` | ML Kit | `0x4000` | yes |
| `libandroidx.graphics.path.so` | AndroidX | `0x4000` | yes |
| **`libpython.so`** | youtubedl-android | `0x4000` | yes |
| **`libqjs.so`** | youtubedl-android | `0x4000` | yes |
| **`libffmpeg.so`** | youtubedl-android :ffmpeg | `0x4000` | yes |
| **`libffprobe.so`** | youtubedl-android :ffmpeg | `0x4000` | yes |
| `libpython.zip.so` | youtubedl-android | n/a | not an ELF |
| `libffmpeg.zip.so` | youtubedl-android :ffmpeg | n/a | not an ELF |

`0x4000` = 16384. Every real ELF clears the bar.

The two `.zip.so` files are **zip archives wearing a `.so` extension** — the trick that gets the Python
stdlib and the ffmpeg binaries past the APK packager and into `nativeLibraryDir`, where the library
unzips them at `init()`. They are never `dlopen`'d, so alignment does not apply to them. They are also
the exact reason `useLegacyPackaging = true` is mandatory: with `false` they stay inside the APK, and
`applicationInfo.nativeLibraryDir` contains nothing for the library to read.

Reproduce:

```sh
unzip -q app-debug.apk 'lib/*' -d /tmp/apkx
readelf=~/Library/Android/sdk/ndk/27.1.12297006/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-readelf
for f in /tmp/apkx/lib/arm64-v8a/*.so; do
  [ "$(head -c 4 "$f" | xxd -p)" = "7f454c46" ] || { echo "$f: not ELF"; continue; }
  echo "$f: $($readelf -l "$f" | awk '/^  LOAD/ {print $NF}' | sort -u | tail -1)"
done
```

## 2. 16 KB device — installs and runs

Android 15 `ps16k` arm64 emulator, `getconf PAGESIZE` → `16384`. The APK installs, `MainActivity`
launches, and the process stays alive with **no `dlopen` failure, no `UnsatisfiedLinkError`, and nothing
in the crash buffer.**

**Limit, stated plainly:** an emulator confirms *loading* on 16 KB pages, which is the page-size-sensitive
part. Full inference and the timing numbers below were run on the S23 (4 KB) because that is where the
QA assets and the real hardware behaviour are. A physical 16 KB device would still be worth an hour
before release.

## 3. ORT correctness — bit-identical output

`ModelSmoke` is load-only; load success does not prove uncorrupted inference. So this was an A/B of a
**real separation job** against a build of the previous commit (`8257f63`, `useLegacyPackaging = false`),
built in a throwaway git worktree with the same ONNX assets copied in, on the same device, same source:

```
source: files/qa30_44k1.mp4 (30 s, 11.9 MB), removeMusic=true, censorWomen=false
both runs: AudioPipeline stats frames=1323008 mean=0.025478143 std=0.14424586 — identical
both runs: 16 htdemucs chunks, ~1.75–1.89 s per chunk — no regression
```

| | audio stream MD5 |
|---|---|
| `useLegacyPackaging = false` (HEAD) | `123d5055a536e3ca04563f97b0ac30ba` |
| `useLegacyPackaging = true` (M4) | `123d5055a536e3ca04563f97b0ac30ba` |

**Identical.** (Whole-file MD5s differ — container metadata carries a creation timestamp. The audio
stream is what htdemucs produced, and it matches byte for byte.)

That is the strongest form this check can take: the packaging change is provably numerically inert.

## 4. yt-dlp — works, but only after a self-update

`YoutubeDL.init()` + `FFmpeg.init()` both succeed on device (~1.5 s cold). A real end-to-end download
lands correctly:

```
downloaded "Big Buck Bunny 60fps 4K - Official Blender Foundation Short Film.mp4"
  (102,240,370 bytes) in ~20 s, published to Movies/Naqi, quarantine emptied
```

**Finding that matters more than the gate: the bundled yt-dlp is dead on arrival for YouTube.**
youtubedl-android 0.18.1 ships yt-dlp `2025.11.12`. On 2026-07-29 that version fails every YouTube link:

```
WARNING: Your yt-dlp version (2025.11.12) is older than 90 days!
WARNING: [youtube] [jsc] Error solving n challenge ... found 0 n function possibilities
ERROR: [youtube] ...: Requested format is not available
```

`YoutubeDL.updateYoutubeDL(context, STABLE)` fixes it in ~2 s (`2025.11.12` → `2026.07.04`), after which
the same URL downloads first try. **The runtime updater is therefore not an M4.4 nicety — it is a
functional prerequisite**, and it was pulled forward into `Downloader.update()` during M4.1. A first-run
update-before-first-download is required, not optional. This is the PRD's "extractors break weekly" risk
arriving before the feature even shipped.

`YoutubeDL.version()` returns `null` until an update has run once — it reads a value the updater writes,
not the bundled zipapp's own version. Do not use it as an "is initialized" signal.

## 5. Sizes

| | Before | After | Δ |
|---|---|---|---|
| Debug APK | 164 MB | 196 MB | +32 MB |
| Installed app dir (`/data/app/…`) | — | 271 MB | — |
| `filesDir/models` (ONNX copies) | 120 MB | 120 MB | — |
| **Installed total** | ~250 MB | **~391 MB** | **+141 MB** |

The APK grew less than the PRD's predicted +54.6 MB (+32 MB observed) because the AAR blobs compress in
the debug APK. Installed footprint grew *more* than the APK, which is `useLegacyPackaging = true` doing
exactly what it says: 84 MB of native libs now exist both compressed in the APK and extracted on disk.

PRD predicted ~186 MB APK / 350–400 MB installed. Actual: 196 MB / ~391 MB. **Prediction held.**

The mitigation is unchanged and still unblocked-by-nothing-but-a-host: activating `ModelDownloader`
removes ~105 MB of assets from the APK and stops the double-store, taking installed to ~145 MB before
downloads are counted. It still needs a host for htdemucs and the NSFW gate, which does not exist.

## 6. Collisions

None surfaced. One rule was added pre-emptively rather than reactively, because youtubedl-android pulls
in commons-compress and Jackson and all three ship the same metadata paths:

```kotlin
resources {
    excludes += setOf("META-INF/{AL2.0,LGPL2.1,LICENSE*,NOTICE*,DEPENDENCIES}")
}
```

Nothing in the app reads those entries. **Note for M4.4:** excluding them from the APK does not excuse
the attribution obligation — that is what the `NOTICE` file and the in-app licenses screen are for.

`minSdk` unaffected (library needs 24, app is 26). ABI filter still `arm64-v8a` only, which is what keeps
the +32 MB from being +128 MB.

---

## Checklist

- [x] Both AARs added, aria2c excluded
- [x] `useLegacyPackaging = true`, collisions resolved
- [x] Builds and installs on a 16 KB-page device
- [x] ORT verified by real separation job, output **bit-identical** to pre-change build
- [x] `YoutubeDL.init()` extracts Python/ffmpeg, version prints, real download round-trips
- [x] APK + installed size measured on device
- [x] Findings written up

**Carried forward:** the yt-dlp self-update is a functional prerequisite, not an M4.4 polish item.
