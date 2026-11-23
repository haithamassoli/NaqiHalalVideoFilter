import CoreVideo
import Foundation
import os

/// InsightFace `genderage` over up to `AnalyzeConstants.voteCap` crops per face
/// track. Which crops get spent, and what an unread face means, is the whole
/// Women/Men feature (`spec-analyze.md` §5).
struct GenderVote {
    let model: OrtModel
    /// Errors are logged once per pass, not once per crop: a broken graph would
    /// otherwise write one line per face per frame.
    private let loggedFailure = Confined(false)

    init(model: OrtModel) { self.model = model }

    /// `+1` male, `−1` female, `0` abstain. Abstain votes for nobody, and no
    /// vote cast already means censor — so every failure path here fails safe.
    ///
    /// `rect` is the **raw, unpadded** detector box in upright-normalised space,
    /// not the EDL's padded rect.
    func vote(in frame: SampledFrame, rect: NRect) -> Int {
        do {
            let tensor = Self.crop(frame, rect)
            let out = try model.run([Models.GenderAge.input:
                                        .float(tensor, shape: [1, 3, Models.GenderAge.side, Models.GenderAge.side])])
            guard let y = out[Models.GenderAge.output] else { throw OrtError.outputMissing(Models.GenderAge.output) }
            let logits = try y.floats()
            guard logits.count >= 2 else { return 0 }
            // Softmax over two logits is the sigmoid of their difference.
            let p = 1 / (1 + exp(-(logits[1] - logits[0])))
            if max(p, 1 - p) < AnalyzeConstants.genderConfidenceFloor { return 0 }
            return p >= 0.5 ? 1 : -1
        } catch {
            if !loggedFailure.v {
                loggedFailure.v = true
                Log.analyze.error("gender vote failed, abstaining: \(error.localizedDescription, privacy: .public)")
            }
            return 0
        }
    }

    /// InsightFace's **square** crop — `max(boxW, boxH) * 1.5` centred on the box
    /// centre, nearest-resampled to 96². Same 1.5 factor as the EDL's 25 %
    /// keyframe pad, different shape: the EDL rect grows each axis
    /// independently, and feeding that in stretches every non-square face
    /// against what the model saw in training (§10.13).
    ///
    /// Values are raw **0..255 floats**. The gate wants /255 on the identical
    /// layout, so a copy-pasted fill silently feeds this graph 1/255 of its
    /// trained range (§10.14) — that is why this walk is separate.
    ///
    /// Static and internal so the equivalence test can pin it against a scalar
    /// transcription of §5.2 without loading a graph. It never touched `self`.
    static func crop(_ frame: SampledFrame, _ rect: NRect) -> [Float] {
        let side = Models.GenderAge.side
        let plane = side * side
        let uw = Float(frame.transform.uprightSize.width)
        let uh = Float(frame.transform.uprightSize.height)
        let uprightW = Int(uw), uprightH = Int(uh)

        let half = max(rect.width * uw, rect.height * uh) * 1.5 / 2
        let x0 = (rect.left + rect.right) / 2 * uw - half
        let y0 = (rect.top + rect.bottom) / 2 * uh - half
        let step = half * 2 / Float(side)
        // Out-of-frame samples clamp to the edge pixel — edge replication, where
        // the Python reference pads with black. Measured on Android: the median
        // crop had 23.7 % of its 1.5x square outside the frame, so this is a
        // common path, not a corner (§5.2).
        let ux = (0..<side).map { min(max(Int(x0 + Float($0) * step), 0), uprightW - 1) }
        let uy = (0..<side).map { min(max(Int(y0 + Float($0) * step), 0), uprightH - 1) }

        // Read the detector buffer, not the source planes: the pixels are
        // already in memory and a 9 216-px gather is noise next to the gate's
        // 50 176 (§5.2).
        let px = frame.detect
        CVPixelBufferLockBaseAddress(px, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(px, .readOnly) }
        let yBase = CVPixelBufferGetBaseAddressOfPlane(px, 0)!.assumingMemoryBound(to: UInt8.self)
        let yRow = CVPixelBufferGetBytesPerRowOfPlane(px, 0)
        let cBase = CVPixelBufferGetBaseAddressOfPlane(px, 1)!.assumingMemoryBound(to: UInt8.self)
        let cRow = CVPixelBufferGetBytesPerRowOfPlane(px, 1)
        let rot = frame.transform.rotationDegrees

        return [Float](unsafeUninitializedCapacity: 3 * plane) { out, n in
            n = 3 * plane
            for j in 0..<side {
                for i in 0..<side {
                    // Upright -> unrotated display, §5.2's table.
                    let dx: Int, dy: Int
                    switch rot {
                    case 90: dx = uy[j]; dy = uprightW - 1 - ux[i]
                    case 180: dx = uprightW - 1 - ux[i]; dy = uprightH - 1 - uy[j]
                    case 270: dx = uprightH - 1 - uy[j]; dy = ux[i]
                    default: dx = ux[i]; dy = uy[j]
                    }
                    let ci = (dy >> 1) * cRow + (dx >> 1) * 2
                    let y = Int32(yBase[dy * yRow + dx])
                    let u = Int32(cBase[ci]) - 128
                    let v = Int32(cBase[ci + 1]) - 128
                    let idx = j * side + i
                    // Same integer BT.601 full-range math as the gate, but the
                    // result stays 0..255 — no /255 here.
                    out[idx] = Float(clamp255(y + ((1436 * v) >> 10)))
                    out[plane + idx] = Float(clamp255(y - (((352 * u) + (731 * v)) >> 10)))
                    out[2 * plane + idx] = Float(clamp255(y + ((1815 * u) >> 10)))
                }
            }
        }
    }

    private static func clamp255(_ v: Int32) -> Int32 { min(max(v, 0), 255) }

    /// Read at eviction only, never earlier, so a track is judged once it is
    /// over and all its votes are in.
    ///
    /// **Ties and 0/0 censor.** "No vote cast" covers a crop under 80 px, a
    /// one-frame track, a missing model, an ORT throw, and a `voteCap` spent
    /// entirely on abstentions — all of them censor. This is the one behaviour
    /// that must never flip (§10.12).
    static func shouldCensor(female: Int, male: Int, who: FilterOps.Who) -> Bool {
        switch who {
        case .women: !(male > female)
        case .men: !(female > male)
        // No vote is ever taken for these — the caller skips the crop entirely
        // via `Who.skipsGenderVote` — but the verdict is still defined here so
        // the answer does not depend on that optimisation being applied.
        case .everyone: true
        case .none: false
        }
    }
}
