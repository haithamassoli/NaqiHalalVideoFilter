import Foundation

/// Locked model contracts. These mirror `ml/Models.kt` on Android and are
/// verified against the real graphs by `ModelContractTests`. Changing a number
/// here without re-exporting the matching `.onnx` silently corrupts output, so
/// the tests assert every one of them against the loaded session.
enum Models {

    // MARK: NSFW 5-class gate (MobileNetV2 1.4-224)

    enum Nsfw {
        static let file = "nsfw_mnv2_140_f32_static"
        static let input = "input"          // [1,3,224,224] f32 NCHW RGB, /255, no mean/std
        static let output = "prediction"    // [1,5] softmax
        static let side = 224

        /// Alphabetical — locked by the tf2onnx export, do not reorder.
        enum Class: Int, CaseIterable, Sendable {
            case drawings = 0, hentai = 1, neutral = 2, porn = 3, sexy = 4
        }
    }

    // MARK: genderage (InsightFace)

    enum GenderAge {
        static let file = "genderage_static"
        static let input = "data"           // [1,3,96,96] f32 NCHW
        static let output = "fc1"           // [1,3] = [femaleLogit, maleLogit, age/100]
        static let side = 96
    }

    // MARK: htdemucs 4-stem, 2.6 s segment

    enum Demucs {
        static let file = "htdemucs_s26_f32"

        /// Waveform branch input: [1, 2, 114660] f32.
        static let waveInput = "input"
        /// Spectrogram branch input: [1, 4, 2048, 112] f32
        /// (4 = 2 channels x {real, imag}).
        static let specInput = "x"
        /// [1, 4, 4, 2048, 112] — 4 stems x 4 (2ch x re/im).
        static let specOutput = "out_spec"
        /// [1, 4, 2, 114660] — 4 stems x 2 channels.
        static let waveOutput = "out_wave"

        static let sampleRate = 44_100
        static let channels = 2
        /// 2.6 s at 44.1 kHz. Baked into the export — see the Android segment
        /// sweep (7.8 s = 3.24 GB RSS, 2.6 s = 1.30 GB). Changing this requires
        /// re-exporting the graph.
        static let segmentFrames = 114_660
        static let nFFT = 4096
        static let hop = 1024
        static let specBins = 2048           // nFFT/2, Nyquist row dropped
        static let specFrames = 112

        /// Stem order from the demucs checkpoint. Locked.
        enum Stem: Int, CaseIterable, Sendable {
            case drums = 0, bass = 1, other = 2, vocals = 3
        }
    }

    // MARK: YAMNet music classifier

    enum YamNet {
        static let file = "yamnet"
        static let input = "waveform"       // [15600] f32 mono at 16 kHz
        static let output = "output_0"       // [1,521] AudioSet scores
        static let sampleRate = 16_000
        static let frameSamples = 15_600
        static let classes = 521
    }

    enum Person {
        static let file = "yolo11n_seg"
        static let side = 640
    }

    static var personURL: URL? {
        Bundle.main.url(forResource: Person.file, withExtension: "mlmodelc", subdirectory: "Models")
            ?? Bundle.main.url(forResource: Person.file, withExtension: "mlmodelc")
    }

    /// Every model the app ships, for the smoke test and the preflight check.
    static let bundled = [Nsfw.file, GenderAge.file, Demucs.file, YamNet.file]

    static func url(_ file: String) -> URL? {
        Bundle.main.url(forResource: file, withExtension: "onnx", subdirectory: "Models")
            ?? Bundle.main.url(forResource: file, withExtension: "onnx")
    }
}
