import SwiftUI

/// The single most distinctive moving element in the app: M3 Expressive's
/// `LinearWavyProgressIndicator`. The filled portion is a travelling sine wave,
/// the remaining track a flat rule. SwiftUI has no equivalent and a plain
/// `ProgressView` loses the signature, so it is drawn by hand at 10 pt.
///
/// The wave travels on a `TimelineView` clock rather than a repeating
/// animation: a determinate bar that also animates its own phase would fight
/// the value animation, and the phase must keep moving while the value sits
/// still for minutes at a time.
struct WavyProgress: View {
    /// 0…1.
    let value: Double
    var animating = true

    /// `Canvas` draws in absolute coordinates, so a bar that grows from x = 0
    /// grows from the *left* in Arabic — filling towards where the job started
    /// instead of away from it.
    @Environment(\.layoutDirection) private var layoutDirection
    /// Ninety minutes of travelling sine is exactly what Reduce Motion is for.
    /// It drops the `TimelineView` rather than pausing it: a paused schedule is
    /// still a schedule, and the static bar has nothing left to tick for.
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// Peak-to-trough 5 pt inside a 10 pt box, leaving room for the 4 pt stroke.
    private let amplitude: CGFloat = 2.5
    private let wavelength: CGFloat = 22
    private let stroke: CGFloat = 4
    /// Points per second the crest travels. Slow enough to read as "working",
    /// not as a spinner.
    private let speed: CGFloat = 26

    var body: some View {
        Group {
            if animating && !reduceMotion {
                TimelineView(.animation(minimumInterval: 1.0 / 30.0)) { ctx in
                    bar(phase: CGFloat(ctx.date.timeIntervalSinceReferenceDate) * speed)
                }
            } else {
                bar(phase: 0)
            }
        }
        .frame(height: 10)
        // The mirror is a transform on the drawing only. The accessibility
        // element is attached outside it and over the same bounding box, so a
        // flipped bar still reports one element at the row's own position.
        .scaleEffect(x: layoutDirection == .rightToLeft ? -1 : 1, y: 1)
        .animation(Naqi.spring, value: value)
        .accessibilityElement()
        .accessibilityIdentifier("progress.wavy")
        // Without a label VoiceOver reads a bare "37 %" — a number with no noun.
        .accessibilityLabel(Text(.progressBarLabel))
        .accessibilityValue(Text(value, format: .percent.precision(.fractionLength(0))))
        // A determinate bar that never re-announces is a bar read once.
        .accessibilityAddTraits(.updatesFrequently)
    }

    /// Takes the phase rather than reading the clock, so the Reduce Motion path
    /// can draw the identical bar with the wave frozen at 0.
    private func bar(phase: CGFloat) -> some View {
        Canvas { gc, size in
            let clamped = min(1, max(0, value))
            let filled = size.width * clamped
            let mid = size.height / 2

            // Remaining track: a flat rule, with a gap after the crest so
            // the two halves never touch.
            let gap: CGFloat = filled > 0 && filled < size.width ? 6 : 0
            if filled + gap < size.width {
                var track = Path()
                track.move(to: CGPoint(x: filled + gap, y: mid))
                track.addLine(to: CGPoint(x: size.width - stroke / 2, y: mid))
                gc.stroke(track, with: .color(Naqi.C.surfaceContainerHighest),
                          style: StrokeStyle(lineWidth: stroke, lineCap: .round))
            }

            guard filled > 0 else { return }
            var wave = Path()
            var x: CGFloat = 0
            while x <= filled {
                let y = mid + amplitude * sin((x + phase) * 2 * .pi / wavelength)
                if x == 0 { wave.move(to: CGPoint(x: x, y: y)) }
                else { wave.addLine(to: CGPoint(x: x, y: y)) }
                x += 1
            }
            gc.stroke(wave, with: .color(Naqi.C.primary),
                      style: StrokeStyle(lineWidth: stroke, lineCap: .round))
        }
    }
}

#Preview {
    VStack(spacing: 24) {
        WavyProgress(value: 0.0)
        WavyProgress(value: 0.37)
        WavyProgress(value: 1.0)
    }
    .padding(40)
    .background(Naqi.C.background)
}
