# Plan — make bundled ffmpeg link on 16 KB-page devices

> **Status: moot as of 1.3.** `youtubedl-android:ffmpeg` was removed with the link-download feature —
> this defect is the main reason it was removed rather than fixed. Keep this document: it is the
> measured evidence, and it is what to re-read if downloads are ever revived from
> `origin/feat/download-and-share`.

**Goal:** `ffmpeg`/`ffprobe` from `youtubedl-android:ffmpeg:0.18.1` currently cannot link on a 16 KB-page
device, which kills every DASH merge, `--extract-audio`, and remux. Fix that without giving up quality
and without forking the AAR.

Measured 2026-07-29. Devices: AVD `Pixel_9a` (`google_apis_playstore_ps16k`, Android 15, arm64,
`getconf PAGESIZE` → 16384) and Galaxy S23 (4 KB) as control.

---

## 1. The defect

Inside `libffmpeg.zip.so`, 5 of 110 ELFs are `p_align 0x1000`:

| Library | Align | Needed by |
|---|---|---|
| `libwebp.so` | `0x1000` | `libavcodec.so.61` (**fatal**) |
| `libwebpmux.so` | `0x1000` | `libavcodec.so.61` (**fatal**) |
| `libsharpyuv.so` | `0x1000` | `libwebp.so` (**fatal**, transitive) |
| `libwebpdecoder.so` | `0x1000` | nothing — dead weight |
| `libwebpdemux.so` | `0x1000` | nothing — dead weight |

Everything else clears the bar: all 8 ELFs in `lib/arm64-v8a/`, all 140 in `libpython.zip.so`, the other
105 in `libffmpeg.zip.so`. The app installs, launches and runs on 16 KB; `libpython.so` links and
`Python 3.12.11` starts. Only ffmpeg is dead.

```
16 KB emu : CANNOT LINK EXECUTABLE "./ffmpeg": empty/missing DT_HASH/DT_GNU_HASH
            in ".../libwebpmux.so" (new hash type from the future?)
4 KB S23  : ffmpeg version 7.1.1        ← identical binary, identical libs, same command
```

**Why no scanner caught it.** The misaligned libs live *inside a zip* (`libffmpeg.zip.so` is a zip
wearing a `.so` extension). A `lib/**/*.so` alignment sweep — Play's included — sees only the 8 clean
top-level ELFs and passes. `docs/m4-packaging-spike.md` §1 says the `.zip.so` payloads "are never
`dlopen`'d, so alignment does not apply to them"; true of the zips, false of the ~250 ELFs they unpack.
**That line needs correcting as part of this work.**

## 2. Two dead ends, already tried — do not retry

**Swap in Termux's current libwebp.** Downloaded `libwebp_1.6.0-rc1-0_aarch64.deb` from
`packages.termux.dev`: all four `LOAD` segments are `0x1000`. Termux ships a 4 KB libwebp today, so
there is nothing to copy. (Worth an upstream `termux-packages` issue — Termux's own 16 KB support has
the same hole.)

**Patch `p_align` in the existing ELFs.** Requires `p_offset ≡ p_vaddr (mod 16384)`. It isn't:
`LOAD` 2 of `libwebp.so` has `p_offset 0x0132c4`, `p_vaddr 0x0142c4` → `0x32c4` vs `0x02c4`. Editing
the field alone produces a lib the loader maps at the wrong offset.

So: **build libwebp ourselves.**

## 3. Approach

Build the 5 libs from upstream source with the NDK (16 KB-aligned), ship them in `assets/`, and copy
them over what `FFmpeg.init()` unpacked.

**Why runtime overwrite and not a Gradle/AAR rewrite.** Splicing the zip at build time means a task that
reads the resolved AAR, rewrites a 34 MB payload, and feeds it back as a generated `jniLibs` dir — plus
a same-name collision with the AAR's own `libffmpeg.zip.so` that AGP resolves by rules we'd have to pin
down. The overwrite is ~12 lines at the one place both inits already happen
(`Downloader.ensureInit`, `Downloader.kt:62`), and the target directory is app-private and writable:

```
noBackupFilesDir/youtubedl-android/packages/ffmpeg/usr/lib/   ← verified on device
```

**No page-size branch.** A 16 KB-aligned lib loads fine on a 4 KB device, so the copy runs
unconditionally and there is one code path, exercised on every device we test.

Ship all 5, not just the 3 that matter: the build emits them anyway, and a uniformly clean `usr/lib`
stops the next person re-finding `libwebpdecoder.so` and wondering.

## 4. Steps

### 4.1 Build libwebp v1.6.0 for arm64

NDK r27.1 (`27.1.12297006`, already installed) defaults to 16 KB max-page-size; pass the flag anyway so
an NDK downgrade can't silently regress it.

The five `set_version(...)` calls in `CMakeLists.txt` (lines 339, 449, 450, 451, 556) give the libs
versioned sonames (`libwebp.so.7`). `libavcodec`'s `DT_NEEDED` says `libwebp.so`, so comment them out
and let CMake default the SONAME to the plain filename.

```sh
git clone --depth 1 -b v1.6.0 https://github.com/webmproject/libwebp
cd libwebp
sed -i '' -E 's/^( *)set_version\(/\1# set_version(/' CMakeLists.txt

NDK=~/Library/Android/sdk/ndk/27.1.12297006
cmake -B build -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-24 \
  -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=ON \
  -DCMAKE_SHARED_LINKER_FLAGS="-Wl,-z,max-page-size=16384" \
  -DWEBP_BUILD_CWEBP=OFF -DWEBP_BUILD_DWEBP=OFF -DWEBP_BUILD_GIF2WEBP=OFF \
  -DWEBP_BUILD_IMG2WEBP=OFF -DWEBP_BUILD_VWEBP=OFF -DWEBP_BUILD_WEBPINFO=OFF \
  -DWEBP_BUILD_WEBPMUX=OFF -DWEBP_BUILD_ANIM_UTILS=OFF -DWEBP_BUILD_EXTRAS=OFF
cmake --build build
```

`android-24` matches the rest of the Termux payload, not the app's `minSdk 26` — these libs are loaded
by the ffmpeg subprocess, not by the app.

**The mux target is named `libwebpmux`, so check the emitted filename** — if CMake produced
`liblibwebpmux.so`, rename it and fix its SONAME before shipping.

### 4.2 Gate the build output

All four must pass before anything is checked in:

1. `llvm-readelf -lW` → every `LOAD` is `0x4000` on all 5.
2. `llvm-readelf -dW` → `SONAME` is exactly `libsharpyuv.so`, `libwebp.so`, `libwebpdecoder.so`,
   `libwebpdemux.so`, `libwebpmux.so`.
3. `libwebp.so` `NEEDED` = `libsharpyuv.so, libm.so, libc.so`; `libwebpmux.so` `NEEDED` =
   `libwebp.so, libc.so`. Anything extra (giflib, libpng, libtiff) means a build option leaked in and
   will fail to resolve inside the ffmpeg payload.
4. **Symbol coverage.** `libavcodec.so.61` imports exactly 15 symbols from these libs — all stable
   public encoder API, so a version mismatch against the bundled 1.5.x-vintage build is not a real
   risk, but assert it rather than assume it:

   ```
   WebPAnimEncoderAdd WebPAnimEncoderAssemble WebPAnimEncoderDelete WebPAnimEncoderNewInternal
   WebPAnimEncoderOptionsInitInternal WebPCleanupTransparentArea WebPConfigInitInternal WebPEncode
   WebPFree WebPMemoryWrite WebPMemoryWriterClear WebPMemoryWriterInit WebPPictureFree
   WebPPictureInitInternal WebPValidateConfig
   ```

   Every one must appear as a defined (`FUNC`/`GLOBAL`, not `UND`) symbol across the new
   `libwebp.so` + `libwebpmux.so`.

### 4.3 Ship them

`app/src/main/assets/webp16k/arm64-v8a/*.so` — ~790 KB raw, well under 400 KB in the APK. Record the
libwebp tag and the exact cmake line in a sibling `README` so the blobs are reproducible.

### 4.4 Wire it in

One function in `Downloader.kt`, called from `ensureInit` immediately after
`FFmpeg.getInstance().init(app)` — `init` is version-gated and re-extracts on library upgrade, so the
overwrite must follow every call, not run once.

```kotlin
// ponytail: overwrite what FFmpeg.init() unpacked instead of repacking the AAR. The 5 libwebp libs
// inside libffmpeg.zip.so are p_align 0x1000 and libavcodec DT_NEEDs two of them, so ffmpeg cannot
// link on a 16 KB-page device. See docs/plan-16kb-webp.md.
// Ceiling: re-check on every youtubedl-android bump — upstream may fix it and make this dead code.
```

Failure handling: if the copy throws, log and let `ensureInit` fail loudly. A silent catch would put us
straight back to an unexplained "Requested format is not available" on 16 KB devices.

### 4.5 Verify

| Gate | Where | Pass |
|---|---|---|
| Alignment of shipped assets | JVM unit test | new `Webp16kAlignmentTest` parses `e_phoff`/`p_align` out of each asset and asserts `0x4000` — this is the check that fails if someone drops a 4 KB lib back in |
| `ffmpeg -version` | 16 KB emu | prints `7.1.1`, no `CANNOT LINK` |
| Real DASH merge | 16 KB emu | a `bv*+ba` download produces a playable file |
| `--extract-audio` | 16 KB emu | produces an `.m4a` |
| Regression | S23 (4 KB) | same URL, same quality → byte-identical output to a pre-change build |
| Build gate | `compileDebugKotlin` + `testDebugUnitTest` | green (66 tests + the new one) |

The S23 regression is the one that matters most: it proves the swapped libwebp did not change ffmpeg's
behaviour on the platform where all the QA assets live.

### 4.6 Paperwork

- Correct `docs/m4-packaging-spike.md` §1 — the "never `dlopen`'d, so alignment does not apply" claim
  and the "every real ELF clears the bar" table, which only covered the top level.
- Note the finding in `docs/tasks-download-share.md`.
- File the Termux issue for their 4 KB libwebp.

## 5. Ceiling and rollback

**Ceiling.** This carries a hand-built copy of a third-party lib. It stays correct only as long as the
bundled ffmpeg's ABI expectations don't move. Re-run §4.2's symbol gate on every `youtubedl-android`
bump; if upstream ships a 16 KB-clean payload, delete the assets, the copy function and the test.

**Rollback** is deleting one function call — the app reverts to today's behaviour (fine on 4 KB, ffmpeg
dead on 16 KB) with no migration.

**Not doing:** rebuilding the whole ffmpeg payload, forking the AAR, or a Gradle zip-splice. Add those
only if a second misaligned lib shows up that isn't as isolated as libwebp is.
