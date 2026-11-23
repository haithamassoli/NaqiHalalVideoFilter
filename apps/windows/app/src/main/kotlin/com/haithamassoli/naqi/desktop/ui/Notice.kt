package com.haithamassoli.naqi.desktop.ui

/**
 * The open-source notices the About screen shows. The entries that apply to this app are copied from the
 * repository's root `NOTICE`, with the Android-only components (yt-dlp, youtubedl-android, CPython, QuickJS,
 * ML Kit, AndroidX, YOLO11n-seg, which is not bundled here) left out and the desktop ones added. Keep the
 * shared entries in step with that file.
 *
 * Not translated, as on Android: a licence notice is a legal text, and a translation would be a second,
 * unreviewed version of it.
 */
internal const val NOTICE = """Naqi — Halal Video Filter (desktop)
Copyright (C) 2026 Haitham Assoli

This program is free software: you can redistribute it and/or modify it under
the terms of the GNU General Public License as published by the Free Software
Foundation, either version 3 of the License, or (at your option) any later
version. See the LICENSE file for the full text.

======================================================
UNRESOLVED LICENCE ISSUE — READ BEFORE ANY PUBLIC RELEASE
======================================================

  The NSFW gate (nsfw_mnv2_140_int8.onnx, quantized from nsfw_mnv2_140_f32.onnx,
  from GantMan/nsfw_model 1.2.0) is marked NOASSERTION upstream and still
  needs review before distribution.

======================================================
THIRD-PARTY COMPONENTS
======================================================

--- Video

FFmpeg — ffmpeg.org
  Bundled through the JavaCPP FFmpeg preset's GPL build (configured with
  --enable-gpl), so GPL-2.0-or-later applies. Decodes and encodes every video.

x264 — videolan.org/developers/x264.html
  GPL-2.0-or-later. Part of that FFmpeg build; encodes the filtered video.

JavaCPP and JavaCPP Presets — github.com/bytedeco/javacpp-presets
  Apache License 2.0, or GPL-2.0 with the Classpath Exception. The Java
  bindings to FFmpeg.

--- Machine learning

ONNX Runtime — github.com/microsoft/onnxruntime
  MIT. Runs every model below, on the CPU.

Demucs (htdemucs) — github.com/facebookresearch/demucs
  MIT. Weights re-exported to ONNX at a 2.6 s segment via
  github.com/sevagh/demucs.onnx. Used for music removal.

YAMNet — github.com/tensorflow/models/tree/master/research/audioset/yamnet
  Apache License 2.0. Google's TF2 SavedModel, converted to ONNX by
  models/yamnet_export.py. Answers "is there music in this chunk?" so silent
  and speech-only stretches skip stem separation.

nsfw_model (MobileNetV2 1.4-224) — github.com/GantMan/nsfw_model
  NOASSERTION upstream. See the unresolved-licence section above. The shipped
  artifact is the INT8 quantization of it (models/nsfw_int8_quantize.py).

InsightFace (buffalo_l — genderage.onnx) — github.com/deepinsight/insightface
  Code MIT; the buffalo_l weight pack is published for non-commercial research
  use, and that restriction was explicitly waived by the owner on 2026-08-03
  (recorded at the GENDERAGE entry in the Android app's Models.kt). Shipped
  verbatim; powers the per-face gender vote behind the Women/Men picker.

YuNet (face_detection_yunet_2023mar.onnx) — github.com/opencv/opencv_zoo
  MIT. Face detection, in place of the Android app's ML Kit.

--- Desktop / Kotlin

Apache License 2.0:
  Compose Multiplatform (UI, Material 3) and Skiko — JetBrains
  Kotlin standard library and kotlinx.coroutines — JetBrains

Thmanyah Sans — Thmanyah
  The interface font, the same files the Android app bundles.

--- Test-only

JUnit 4 — Eclipse Public License 1.0

======================================================

The full text of each licence is available from the project pages listed above.
Naqi's own terms are in LICENSE at the root of this repository."""
