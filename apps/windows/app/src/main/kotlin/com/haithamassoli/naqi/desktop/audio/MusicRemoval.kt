package com.haithamassoli.naqi.desktop.audio

import com.haithamassoli.naqi.desktop.Models
import java.io.File

// Owner: the audio agent. Contract in apps/windows/SEAMS.md. Port from Android's AudioPipeline /
// HtdemucsSession / MusicGateSession onto core's DemucsSeparator and MusicGate, reading PCM with
// media.AudioDecoder and writing it with media.AacWriter.

object MusicRemoval {
    /**
     * Remove the music from [input]'s audio and write what is left to [outM4a] (AAC-LC, 44.1 kHz stereo,
     * same length in samples as the decoded source). [keepStems] is `FilterOps.keepStems`: "vocals" or
     * "vocals_other". [durationMs] (from `Probe`) is only a progress denominator. YAMNet gates which 2.6 s
     * chunks htdemucs separates; a missing YAMNet means "separate everything", a missing htdemucs throws.
     *
     * Returns false, writing nothing, when the source has no audio stream. [onProgress] gets 0..100.
     * Cancellable.
     */
    suspend fun run(
        input: File,
        keepStems: String,
        outM4a: File,
        durationMs: Long,
        modelsDir: File = Models.dir(),
        onProgress: (Int) -> Unit,
    ): Boolean = TODO("audio")
}
