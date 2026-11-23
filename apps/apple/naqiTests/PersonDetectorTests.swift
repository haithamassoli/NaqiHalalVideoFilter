import CoreGraphics
import CoreImage
import CoreML
import CoreVideo
import Foundation
import Testing
@testable import naqi

struct PersonDetectorTests {
    @Test("bundled native Core ML person model runs on an actual image buffer")
    func bundledModel() async throws {
        let url = try #require(Models.personURL, "Run scripts/fetch-person-model.py before building")
        // Precise mode also checks the bundled prototype output's contract.
        let detector = try PersonDetector(modelURL: url, masks: true)
        var allocated: CVPixelBuffer?
        let status = CVPixelBufferCreate(nil, 64, 64, kCVPixelFormatType_32BGRA,
            [kCVPixelBufferIOSurfacePropertiesKey: [:]] as CFDictionary, &allocated)
        #expect(status == kCVReturnSuccess)
        let buffer = try #require(allocated)
        CVPixelBufferLockBaseAddress(buffer, [])
        let bytes = try #require(CVPixelBufferGetBaseAddress(buffer)).assumingMemoryBound(to: UInt8.self)
        let stride = CVPixelBufferGetBytesPerRow(buffer)
        for y in 0..<64 { for x in 0..<64 {
            for c in 0..<4 { bytes[y * stride + x * 4 + c] = c == 3 ? 255 : 114 }
        } }
        CVPixelBufferUnlockBaseAddress(buffer, [])
        let frame = SampledFrame(ptsMs: 0, detect: buffer, transform: .identity(size: CGSize(width: 64, height: 64)),
                                 orientation: .up, gate: nil)
        let people = try await detector.detect(frame)
        #expect(people.allSatisfy { $0.mask?.isValid == true })
        #expect(people.map(\.rect).allSatisfy { r in
            [r.minX, r.minY, r.maxX, r.maxY].allSatisfy(\.isFinite)
                && !r.isEmpty && CGRect(x: 0, y: 0, width: 64, height: 64).contains(r)
        })
    }

    @Test("YOLO11 person-only decoding, NMS, padding and clamping")
    func boxes() throws {
        let output = try MLMultiArray(shape: [1, 116, 8400], dataType: .float32)
        let p = output.dataPointer.assumingMemoryBound(to: Float.self)
        p.initialize(repeating: 0, count: output.count)
        func anchor(_ index: Int, _ box: [Float], score: Float) {
            for attribute in 0..<4 { p[attribute * 8400 + index] = box[attribute] }
            p[4 * 8400 + index] = score
        }
        anchor(0, [200, 300, 100, 200], score: 0.9)
        anchor(1, [204, 303, 100, 200], score: 0.8) // duplicate
        anchor(2, [300, 200, 100, 100], score: 0.8)
        p[5 * 8400 + 2] = 0.9 // bicycle wins; not a person
        anchor(3, [560, 320, 160, 600], score: 0.7) // clips at right edge
        anchor(4, [50, 50, 10, 10], score: 0.6) // entirely in letterbox
        anchor(5, [200, 300, 100, 200], score: 0.25) // threshold is strict
        let layout = try PersonDetector.Letterbox(source: CGSize(width: 360, height: 640))
        let boxes = try PersonDetector.decode(output, layout: layout)
        #expect(boxes == [CGRect(x: 10, y: 200, width: 100, height: 200),
                          CGRect(x: 340, y: 20, width: 20, height: 600)])
        p[4 * 8400] = .nan
        #expect(throws: PersonDetectionError.self) { try PersonDetector.decode(output, layout: layout) }
        let wrong = try MLMultiArray(shape: [1, 6, 10], dataType: .float32)
        #expect(throws: PersonDetectionError.self) { try PersonDetector.decode(wrong, layout: layout) }
    }

    @Test("precise mode samples the prototype mask inside the upright box")
    func masks() throws {
        let output = try MLMultiArray(shape: [1, 116, 8400], dataType: .float32)
        let proto = try MLMultiArray(shape: [1, 32, 160, 160], dataType: .float32)
        let p = output.dataPointer.assumingMemoryBound(to: Float.self)
        p.initialize(repeating: 0, count: output.count)
        for (attribute, value) in [200, 300, 100, 200, 0.9].enumerated() { p[attribute * 8400] = Float(value) }
        p[84 * 8400] = 1 // first coefficient selects prototype 0
        let q = proto.dataPointer.assumingMemoryBound(to: Float.self)
        q.initialize(repeating: 0, count: proto.count)
        // Prototype 0 is positive left of letterbox x 200 (prototype column 50).
        for y in 0..<160 { for x in 0..<160 { q[y * 160 + x] = x < 50 ? 1 : -1 } }
        let layout = try PersonDetector.Letterbox(source: CGSize(width: 360, height: 640))
        let person = try #require(PersonDetector.decode(output, proto: proto, layout: layout).first)
        #expect(person.rect == CGRect(x: 10, y: 200, width: 100, height: 200))
        let mask = try #require(person.mask)
        #expect(mask.width == 16 && mask.height == 32 && mask.isValid)
        // Upright x 10..110 is letterbox x 150..250, so its left half is set.
        for y in [0, 31] {
            #expect((0..<16).map { mask[$0, y] } == Array(repeating: true, count: 8) + Array(repeating: false, count: 8))
        }
        #expect(try PersonDetector.decode(output, proto: nil, layout: layout).first?.mask == nil)
    }

    @Test("letterbox dimensions reject invalid geometry and preserve odd padding")
    func geometry() throws {
        let layout = try PersonDetector.Letterbox(source: CGSize(width: 640, height: 359))
        #expect(layout.top == 140)
        #expect(640 - layout.height - layout.top == 141)
        #expect(throws: PersonDetectionError.self) {
            try PersonDetector.Letterbox(source: CGSize(width: 0, height: 10))
        }
    }

    @Test("retry crops cover the image with overlap and stay inside upright bounds",
          arguments: [CGSize(width: 360, height: 640), CGSize(width: 640, height: 360), CGSize(width: 640, height: 640)])
    func retryCoverage(_ size: CGSize) {
        let crops = PersonDetector.retryCrops(size: size)
        let bounds = CGRect(origin: .zero, size: size)
        #expect(crops.isEmpty == false)
        #expect(crops.allSatisfy { bounds.contains($0) && $0.width == $0.height })
        for y in stride(from: 0.0, through: size.height, by: 10) {
            for x in stride(from: 0.0, through: size.width, by: 10) {
                // A small person crossing a tile edge still fits another tile.
                let person = CGRect(x: max(0, min(x, size.width - 30)),
                                    y: max(0, min(y, size.height - 60)), width: 30, height: 60)
                #expect(crops.contains { $0.contains(person) })
            }
        }
        #expect(PersonDetector.retryCrops(size: .zero).isEmpty)
    }

    @Test("RGB input rotates stored pixels upright and pads with 114", arguments: [0, 90, 180, 270])
    func orientation(_ rotation: Int) throws {
        func buffer(_ width: Int, _ height: Int) throws -> CVPixelBuffer {
            var result: CVPixelBuffer?
            let status = CVPixelBufferCreate(nil, width, height, kCVPixelFormatType_32BGRA,
                [kCVPixelBufferIOSurfacePropertiesKey: [:]] as CFDictionary, &result)
            #expect(status == kCVReturnSuccess)
            return try #require(result)
        }
        let source = try buffer(4, 2)
        CVPixelBufferLockBaseAddress(source, [])
        let bytes = try #require(CVPixelBufferGetBaseAddress(source)).assumingMemoryBound(to: UInt8.self)
        let stride = CVPixelBufferGetBytesPerRow(source)
        // RGBA corner quadrants: red, green / blue, white.
        let colors: [[UInt8]] = [[0,0,255,255],[0,255,0,255],[255,0,0,255],[255,255,255,255]]
        for y in 0..<2 { for x in 0..<4 {
            let color = colors[y * 2 + x / 2]
            for c in 0..<4 { bytes[y * stride + x * 4 + c] = color[c] }
        } }
        CVPixelBufferUnlockBaseAddress(source, [])
        let orientation = FrameSampler.orientation(rotation)
        let size = rotation == 90 || rotation == 270 ? CGSize(width: 2, height: 4) : CGSize(width: 4, height: 2)
        let frame = SampledFrame(ptsMs: 0, detect: source, transform: .identity(size: size), orientation: orientation, gate: nil)
        let input = try buffer(640, 640)
        let context = CIContext(options: [.useSoftwareRenderer: true, .workingColorSpace: NSNull(), .outputColorSpace: NSNull()])
        let layout = try PersonDetector.prepare(frame, into: input, context: context)
        CVPixelBufferLockBaseAddress(input, .readOnly)
        let output = try #require(CVPixelBufferGetBaseAddress(input)).assumingMemoryBound(to: UInt8.self)
        let row = CVPixelBufferGetBytesPerRow(input)
        let expected = rotation == 0 ? [0,1,2,3] : rotation == 90 ? [2,0,3,1] : rotation == 180 ? [3,2,1,0] : [1,3,0,2]
        for corner in 0..<4 {
            let x = layout.left + Int(Double(layout.width) * (corner % 2 == 0 ? 0.25 : 0.75))
            let y = layout.top + Int(Double(layout.height) * (corner < 2 ? 0.25 : 0.75))
            for c in 0..<3 { #expect(abs(Int(output[y * row + x * 4 + c]) - Int(colors[expected[corner]][c])) <= 2) }
        }
        for c in 0..<3 { #expect(output[c] == 114) }
        CVPixelBufferUnlockBaseAddress(input, .readOnly)

        // Crop in upright top-left space, including rotations and bottom tiles.
        for corner in 0..<4 {
            let crop = CGRect(x: corner % 2 == 0 ? 0 : size.width / 2,
                              y: corner < 2 ? 0 : size.height / 2,
                              width: size.width / 2, height: size.height / 2)
            let cropped = try PersonDetector.prepare(frame, into: input, context: context, crop: crop)
            #expect(cropped.source == crop.size)
            CVPixelBufferLockBaseAddress(input, .readOnly)
            let center = (cropped.top + cropped.height / 2) * row + (cropped.left + cropped.width / 2) * 4
            for c in 0..<3 {
                #expect(abs(Int(output[center + c]) - Int(colors[expected[corner]][c])) <= 2)
            }
            CVPixelBufferUnlockBaseAddress(input, .readOnly)
        }
        #expect(throws: PersonDetectionError.self) {
            try PersonDetector.prepare(frame, into: input, context: context,
                                       crop: CGRect(x: -1, y: 0, width: 1, height: 1))
        }
    }
}
