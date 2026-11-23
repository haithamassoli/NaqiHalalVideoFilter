import Foundation

/// Mutable state confined to one serial queue. The compiler cannot prove the
/// confinement, so the unchecked conformance carries it — every use must stay
/// on a single queue (AVFoundation's `requestMediaDataWhenReady` guarantees
/// exactly that for its callback).
final class Confined<T>: @unchecked Sendable {
    var v: T
    init(_ v: T) { self.v = v }
}
