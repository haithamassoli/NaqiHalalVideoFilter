import CoreGraphics
import CoreImage
import CoreML
import CoreVideo
import Foundation

enum PersonDetectionError: Error, LocalizedError {
    case modelMissing
    case modelContract
    case invalidOutput
    case invalidFrame
    case pixelBuffer(CVReturn)

    var errorDescription: String? {
        switch self {
        case .modelMissing: "The person detection model is missing. Run scripts/fetch-person-model.py before building."
        case .modelContract: "The person detection model does not match the validated YOLO11 contract."
        case .invalidOutput: "Person detection returned invalid values."
        case .invalidFrame: "The video frame has invalid person detection coordinates."
        case .pixelBuffer(let code): "The person detection image buffer could not be allocated (\(code))."
        }
    }
}

/// The actor owns the reusable RGB input and model; calls finish while the
/// sampler still owns its YUV slot. Results use FaceDetector's upright pixels.
actor PersonDetector {
    static let side = 640
    static let confidence: Float = 0.25
    static let nmsIoU: CGFloat = 0.7
    /// Long side of a stored `BodyMask`, in cells.
    static let maskSide = 32
    private let model: MLModel
    private let outputName: String
    /// Set only in precise body mode; the prototype tensor is ignored otherwise.
    private let protoName: String?
    private let input: CVPixelBuffer
    private let context: CIContext
    /// The previous frame's crop retry found nobody either.
    private var retryWasEmpty = false

    struct Person: Sendable {
        var rect: CGRect
        var mask: BodyMask?
    }

    init(modelURL: URL? = Models.personURL, masks: Bool = false) throws {
        guard let modelURL else { throw PersonDetectionError.modelMissing }
        let configuration = MLModelConfiguration()
        #if targetEnvironment(simulator)
        configuration.computeUnits = .cpuOnly
        #else
        configuration.computeUnits = .all
        #endif
        model = try MLModel(contentsOf: modelURL, configuration: configuration)
        guard let image = model.modelDescription.inputDescriptionsByName["image"]?.imageConstraint,
              image.pixelsWide == Self.side, image.pixelsHigh == Self.side,
              let output = model.modelDescription.outputDescriptionsByName.first(where: {
                  $0.value.multiArrayConstraint?.shape.map(\.intValue) == [1, 116, 8400]
              }), output.value.multiArrayConstraint?.dataType == .float32 else {
            throw PersonDetectionError.modelContract
        }
        outputName = output.key
        if masks {
            guard let proto = model.modelDescription.outputDescriptionsByName.first(where: {
                $0.value.multiArrayConstraint?.shape.map(\.intValue) == [1, 32, 160, 160]
            }), proto.value.multiArrayConstraint?.dataType == .float32 else {
                throw PersonDetectionError.modelContract
            }
            protoName = proto.key
        } else {
            protoName = nil
        }
        var buffer: CVPixelBuffer?
        let status = CVPixelBufferCreate(nil, Self.side, Self.side, kCVPixelFormatType_32BGRA,
            [kCVPixelBufferIOSurfacePropertiesKey: [:], kCVPixelBufferMetalCompatibilityKey: true] as CFDictionary, &buffer)
        guard status == kCVReturnSuccess, let buffer else { throw PersonDetectionError.pixelBuffer(status) }
        input = buffer
        // Ultralytics resizes electrical RGB values rather than linear light.
        context = CIContext(options: [.workingColorSpace: NSNull(), .outputColorSpace: NSNull(),
                                      .cacheIntermediates: false, .highQualityDownsample: false])
    }

    func detect(_ frame: SampledFrame) throws -> [Person] {
        try Task.checkCancellation()
        let size = frame.transform.uprightSize
        // Core ML/CI create autoreleased tensors and images. Bound their life
        // to one frame rather than the long-lived analysis task's outer pool.
        return try autoreleasepool {
            func infer(crop: CGRect? = nil) throws -> [Person] {
                let layout = try Self.prepare(frame, into: input, context: context, crop: crop)
                let provider = try MLDictionaryFeatureProvider(dictionary: ["image": MLFeatureValue(pixelBuffer: input)])
                let prediction = try model.prediction(from: provider)
                guard let output = prediction.featureValue(for: outputName)?.multiArrayValue else {
                    throw PersonDetectionError.modelContract
                }
                var proto: MLMultiArray?
                if let protoName {
                    guard let p = prediction.featureValue(for: protoName)?.multiArrayValue else {
                        throw PersonDetectionError.modelContract
                    }
                    proto = p
                }
                try Task.checkCancellation()
                return try Self.decode(output, proto: proto, layout: layout).map { found in
                    let rect = found.rect.offsetBy(dx: crop?.minX ?? 0, dy: crop?.minY ?? 0)
                    var mask = found.mask
                    mask?.rect = NRect(left: Float(rect.minX / size.width), top: Float(rect.minY / size.height),
                                       right: Float(rect.maxX / size.width), bottom: Float(rect.maxY / size.height))
                    return Person(rect: rect, mask: mask)
                }
            }
            let people = try infer()
            guard people.isEmpty else { retryWasEmpty = false; return people }
            // ponytail: an empty scene retries every other sample. A person found
            // one sample late is still covered by PersonTracker's 300 ms lead-in.
            if retryWasEmpty { retryWasEmpty = false; return [] }
            // Letterboxing a portrait frame shrinks people shown on small
            // screens. Retry overlapping square crops without another model.
            var recovered: [Person] = []
            for crop in Self.retryCrops(size: size) {
                for person in try infer(crop: crop)
                where recovered.allSatisfy({ Self.iou($0.rect, person.rect) <= Self.nmsIoU }) {
                    recovered.append(person)
                }
            }
            retryWasEmpty = recovered.isEmpty
            return recovered
        }
    }

    static func retryCrops(size: CGSize) -> [CGRect] {
        let side = min(size.width, size.height) * 0.6
        guard side > 0 else { return [] }
        let columns = max(1, Int(ceil((size.width - side) / (side * 0.75))))
        let rows = max(1, Int(ceil((size.height - side) / (side * 0.75))))
        return (0...rows).flatMap { row in
            (0...columns).map { column in
                CGRect(x: (size.width - side) * CGFloat(column) / CGFloat(columns),
                       y: (size.height - side) * CGFloat(row) / CGFloat(rows), width: side, height: side)
            }
        }
    }

    struct Letterbox: Sendable {
        let source: CGSize
        let scale: CGFloat
        let width: Int, height: Int, left: Int, top: Int

        init(source: CGSize) throws {
            guard source.width.isFinite, source.height.isFinite, source.width > 0, source.height > 0 else {
                throw PersonDetectionError.invalidFrame
            }
            self.source = source
            scale = min(CGFloat(PersonDetector.side) / source.width, CGFloat(PersonDetector.side) / source.height)
            width = Int((source.width * scale).rounded())
            height = Int((source.height * scale).rounded())
            // Matches LetterBox(center=True,auto=False), including odd padding.
            left = Int((CGFloat(PersonDetector.side - width) / 2 - 0.1).rounded())
            top = Int((CGFloat(PersonDetector.side - height) / 2 - 0.1).rounded())
        }

        func upright(_ rect: CGRect) -> CGRect {
            CGRect(x: (rect.minX - CGFloat(left)) / scale,
                   y: (rect.minY - CGFloat(top)) / scale,
                   width: rect.width / scale, height: rect.height / scale)
                .intersection(CGRect(origin: .zero, size: source))
        }
    }

    static func prepare(_ frame: SampledFrame, into input: CVPixelBuffer, context: CIContext,
                        crop: CGRect? = nil) throws -> Letterbox {
        let oriented = CIImage(cvPixelBuffer: frame.detect).oriented(frame.orientation)
        let size = frame.transform.uprightSize
        guard abs(oriented.extent.width - size.width) < 0.5,
              abs(oriented.extent.height - size.height) < 0.5 else { throw PersonDetectionError.invalidFrame }
        var upright = oriented.transformed(by: CGAffineTransform(translationX: -oriented.extent.minX, y: -oriented.extent.minY))
        if let crop {
            guard CGRect(origin: .zero, size: size).contains(crop), !crop.isEmpty else {
                throw PersonDetectionError.invalidFrame
            }
            let ciCrop = CGRect(x: crop.minX, y: size.height - crop.maxY, width: crop.width, height: crop.height)
            upright = upright.cropped(to: ciCrop).transformed(by: CGAffineTransform(translationX: -ciCrop.minX, y: -ciCrop.minY))
        }
        let layout = try Letterbox(source: crop?.size ?? size)
        let resized = upright.transformed(by: CGAffineTransform(scaleX: CGFloat(layout.width) / layout.source.width,
                                                               y: CGFloat(layout.height) / layout.source.height))
        // Core Image uses bottom-left coordinates; model boxes use top-left.
        let placed = resized.transformed(by: CGAffineTransform(translationX: CGFloat(layout.left),
            y: CGFloat(Self.side - layout.height - layout.top)))
        let bounds = CGRect(x: 0, y: 0, width: Self.side, height: Self.side)
        let background = CIImage(color: CIColor(red: 114.0 / 255, green: 114.0 / 255, blue: 114.0 / 255, alpha: 1)).cropped(to: bounds)
        context.render(placed.composited(over: background), to: input, bounds: bounds, colorSpace: nil)
        return layout
    }

    static func decode(_ output: MLMultiArray, layout: Letterbox) throws -> [CGRect] {
        try decode(output, proto: nil, layout: layout).map(\.rect)
    }

    /// With `proto`, each person also gets a mask whose `rect` is left for
    /// the caller, which alone knows the crop offset and full frame size.
    static func decode(_ output: MLMultiArray, proto: MLMultiArray?, layout: Letterbox) throws -> [Person] {
        guard output.shape.map(\.intValue) == [1, 116, 8400], output.dataType == .float32 else {
            throw PersonDetectionError.modelContract
        }
        let strides = output.strides.map(\.intValue)
        let values = output.dataPointer.assumingMemoryBound(to: Float.self)
        var candidates: [(rect: CGRect, score: Float, index: Int)] = []
        for i in 0..<8400 {
            let person = values[4 * strides[1] + i * strides[2]]
            guard person.isFinite else { throw PersonDetectionError.invalidOutput }
            guard person > Self.confidence else { continue }
            // classes=[0],multi_label=False keeps only anchors whose best class
            // is person; accepting its score alone differs from the benchmark.
            var personIsBest = true
            for c in 1..<80 {
                let score = values[(4 + c) * strides[1] + i * strides[2]]
                guard score.isFinite else { throw PersonDetectionError.invalidOutput }
                if score > person { personIsBest = false }
            }
            guard personIsBest else { continue }
            let x = values[i * strides[2]], y = values[strides[1] + i * strides[2]]
            let width = values[2 * strides[1] + i * strides[2]], height = values[3 * strides[1] + i * strides[2]]
            guard x.isFinite, y.isFinite, width.isFinite, height.isFinite, width > 0, height > 0 else {
                throw PersonDetectionError.invalidOutput
            }
            candidates.append((CGRect(x: CGFloat(x - width / 2), y: CGFloat(y - height / 2),
                                      width: CGFloat(width), height: CGFloat(height)), person, i))
        }
        candidates.sort { $0.score == $1.score ? $0.index < $1.index : $0.score > $1.score }
        var kept: [(rect: CGRect, score: Float, index: Int)] = []
        for candidate in candidates {
            guard kept.allSatisfy({ Self.iou($0.rect, candidate.rect) <= Self.nmsIoU }) else { continue }
            kept.append(candidate)
            if kept.count == 300 { break } // Same max_det as the validated reference.
        }
        return try kept.compactMap { candidate in
            let rect = layout.upright(candidate.rect)
            guard !rect.isNull, !rect.isEmpty else { return nil }
            let mask = try proto.map { try Self.mask(output, proto: $0, anchor: candidate.index, upright: rect, layout: layout) }
            return Person(rect: rect, mask: mask)
        }
    }

    /// Samples `sigmoid(coefficients · prototypes) > 0.5` at each cell centre
    /// of the upright box; the logit sign is the same test without `exp`.
    static func mask(_ output: MLMultiArray, proto: MLMultiArray, anchor: Int,
                     upright rect: CGRect, layout: Letterbox) throws -> BodyMask {
        guard proto.shape.map(\.intValue) == [1, 32, 160, 160], proto.dataType == .float32 else {
            throw PersonDetectionError.modelContract
        }
        let os = output.strides.map(\.intValue), ps = proto.strides.map(\.intValue)
        let values = output.dataPointer.assumingMemoryBound(to: Float.self)
        let prototypes = proto.dataPointer.assumingMemoryBound(to: Float.self)
        let coefficients = (0..<32).map { values[(84 + $0) * os[1] + anchor * os[2]] }
        let long = max(rect.width, rect.height)
        let width = max(1, Int((CGFloat(maskSide) * rect.width / long).rounded()))
        let height = max(1, Int((CGFloat(maskSide) * rect.height / long).rounded()))
        var bits = Data(count: (width * height + 7) / 8)
        // Prototypes cover the 640 letterbox at a quarter of its resolution.
        let cell = CGFloat(PersonDetector.side) / 160
        for y in 0..<height {
            let ly = (rect.minY + (CGFloat(y) + 0.5) * rect.height / CGFloat(height)) * layout.scale + CGFloat(layout.top)
            let py = min(159, max(0, Int(ly / cell)))
            for x in 0..<width {
                let lx = (rect.minX + (CGFloat(x) + 0.5) * rect.width / CGFloat(width)) * layout.scale + CGFloat(layout.left)
                let px = min(159, max(0, Int(lx / cell)))
                var logit: Float = 0
                for c in 0..<32 { logit += coefficients[c] * prototypes[c * ps[1] + py * ps[2] + px * ps[3]] }
                guard logit.isFinite else { throw PersonDetectionError.invalidOutput }
                if logit > 0 { bits[(y * width + x) / 8] |= 1 << UInt8((y * width + x) % 8) }
            }
        }
        return BodyMask(rect: NRect(left: 0, top: 0, right: 1, bottom: 1), width: width, height: height, bits: bits)
    }

    private static func iou(_ a: CGRect, _ b: CGRect) -> CGFloat {
        let intersection = a.intersection(b)
        guard !intersection.isNull, !intersection.isEmpty else { return 0 }
        let area = intersection.width * intersection.height
        return area / (a.width * a.height + b.width * b.height - area)
    }
}
