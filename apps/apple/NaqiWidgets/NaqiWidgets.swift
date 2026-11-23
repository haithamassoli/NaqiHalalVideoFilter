import ActivityKit
import SwiftUI
import WidgetKit

/// Live Activity for a running filter job — Android's foreground-service
/// notification, in the shape iOS gives it.
///
/// The extension renders state the app posts through `LiveActivity`; it never
/// computes anything, never loads a model and never localizes: every word it
/// draws arrives already translated in `NaqiJobAttributes`, which is one shared
/// file so the two sides cannot drift.
@main
struct NaqiWidgets: WidgetBundle {
    var body: some Widget { NaqiJobActivity() }
}

struct NaqiJobActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: NaqiJobAttributes.self) { context in
            let look = Look(context)
            LockScreenView(look: look)
                .environment(\.layoutDirection, look.direction)
                .activityBackgroundTint(Brand.deep)
                .activitySystemActionForegroundColor(Brand.jadeBright)
        } dynamicIsland: { context in
            let look = Look(context)
            return DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    Ring(look: look, size: 44)
                        .padding(.leading, 4)
                        .environment(\.layoutDirection, look.direction)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    Text(look.percent)
                        .font(.custom("thmanyahsans-Bold", size: 22, relativeTo: .title2))
                        .foregroundStyle(look.tint)
                        .contentTransition(.numericText(value: look.fraction))
                        .frame(maxHeight: .infinity)
                        .padding(.trailing, 4)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(look.title)
                            .font(.custom("thmanyahsans-Bold", size: 17, relativeTo: .headline))
                            .lineLimit(1)
                        Captions(look: look)
                        Bar(look: look).padding(.top, 2)
                    }
                    .foregroundStyle(Brand.paper)
                    .environment(\.layoutDirection, look.direction)
                }
            } compactLeading: {
                Ring(look: look, size: 22)
            } compactTrailing: {
                Text(look.percent)
                    .font(.custom("thmanyahsans-Bold", size: 12, relativeTo: .caption))
                    .foregroundStyle(look.tint)
                    .contentTransition(.numericText(value: look.fraction))
            } minimal: {
                Ring(look: look, size: 22)
            }
            .keylineTint(look.tint)
        }
    }
}

/// Everything the views draw, resolved once from the context. The one piece
/// of judgement in the extension lives here: a running card that has gone
/// stale is a suspended app, and says so.
struct Look {
    var title: String
    var caption: String
    var detail: String
    var symbol: String
    var fraction: Double
    var phase: NaqiJobAttributes.Phase
    var direction: LayoutDirection

    init(_ context: ActivityViewContext<NaqiJobAttributes>) {
        self.init(context.attributes, context.state, stale: context.isStale)
    }

    init(_ attributes: NaqiJobAttributes, _ state: NaqiJobAttributes.ContentState, stale: Bool) {
        title = attributes.title
        caption = state.caption
        detail = state.detail
        symbol = state.symbol
        fraction = max(0, min(1, state.fraction))
        phase = state.phase
        direction = attributes.rightToLeft ? .rightToLeft : .leftToRight
        if stale, phase == .running {
            phase = .paused
            caption = attributes.pausedCaption
            detail = attributes.pausedDetail
            symbol = "pause.fill"
        }
    }

    var tint: Color {
        switch phase {
        case .running, .done: Brand.jadeBright
        case .paused: Brand.amber
        case .failed: Brand.coral
        }
    }

    var percent: String {
        let n = Int((fraction * 100).rounded())
        return direction == .rightToLeft ? "\(n)٪" : "\(n)%"
    }
}

private struct LockScreenView: View {
    let look: Look

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                Ring(look: look, size: 44)
                VStack(alignment: .leading, spacing: 2) {
                    Text(look.title)
                        .font(.custom("thmanyahsans-Bold", size: 17, relativeTo: .headline))
                        .lineLimit(1)
                    Captions(look: look, stacked: true)
                }
                Spacer(minLength: 8)
                if look.phase != .done {
                    Text(look.percent)
                        .font(.custom("thmanyahsans-Bold", size: 22, relativeTo: .title2))
                        .foregroundStyle(look.tint)
                        .contentTransition(.numericText(value: look.fraction))
                }
            }
            if look.phase != .done { Bar(look: look) }
        }
        .padding(16)
        .foregroundStyle(Brand.paper)
    }
}

/// Stage on the left, time left / queue / next step on the right. On a finished
/// card the detail is the file name or the failure sentence, so it gets its own
/// line instead of fighting the caption for width — and so does the lock
/// screen, where the percent already takes the right-hand side.
private struct Captions: View {
    let look: Look
    var stacked = false

    var body: some View {
        let layout = look.phase == .running && !stacked
            ? AnyLayout(HStackLayout(spacing: 6))
            : AnyLayout(VStackLayout(alignment: .leading, spacing: 1))
        layout {
            if !look.caption.isEmpty {
                Text(look.caption)
                    .foregroundStyle(look.phase == .running ? Brand.paper.opacity(0.7) : look.tint)
                    .font(.custom(look.phase == .running ? "thmanyahsans-Regular" : "thmanyahsans-Bold",
                                  size: 13, relativeTo: .footnote))
            }
            if look.phase == .running && !stacked { Spacer(minLength: 0) }
            if !look.detail.isEmpty {
                Text(look.detail).foregroundStyle(Brand.paper.opacity(0.7))
            }
        }
        .font(.custom("thmanyahsans-Regular", size: 13, relativeTo: .footnote))
        .lineLimit(look.phase == .failed ? 2 : 1)
    }
}

/// Progress ring with the stage glyph inside. The glyph is what tells the
/// compact and minimal presentations apart from any other app's ring.
private struct Ring: View {
    let look: Look
    let size: CGFloat

    var body: some View {
        let line = max(2.5, size / 11)
        ZStack {
            Circle().stroke(look.tint.opacity(0.22), lineWidth: line)
            Circle()
                .trim(from: 0, to: look.phase == .done ? 1 : look.fraction)
                .stroke(look.tint, style: StrokeStyle(lineWidth: line, lineCap: .round))
                .rotationEffect(.degrees(-90))
                // Fills the way the bar fills: clockwise in English,
                // counter-clockwise in Arabic.
                .scaleEffect(x: look.direction == .rightToLeft ? -1 : 1)
            Image(systemName: look.symbol)
                .font(.system(size: size * 0.4, weight: .bold))
                .foregroundStyle(look.tint)
                .contentTransition(.symbolEffect(.replace))
        }
        .frame(width: size, height: size)
        .padding(line / 2)
        .accessibilityElement()
        .accessibilityLabel(look.caption.isEmpty ? look.title : look.caption)
        .accessibilityValue(look.percent)
    }
}

private struct Bar: View {
    let look: Look

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                Capsule().fill(Brand.paper.opacity(0.15))
                Capsule().fill(look.tint)
                    .frame(width: max(6, look.fraction * geo.size.width))
            }
        }
        .frame(height: 6)
    }
}

/// The brand colours the activity needs. Duplicated from `Naqi.C` rather than
/// sharing the whole theme: pulling `Theme.swift` in would drag the app's
/// entire design system into an extension that draws one ring and one bar.
/// Amber and coral are the card's own: paused and failed need to read at a
/// glance on a dark lock screen.
enum Brand {
    static let deep = Color(red: 0x0C / 255, green: 0x15 / 255, blue: 0x12 / 255)
    static let jadeBright = Color(red: 0x55 / 255, green: 0xC3 / 255, blue: 0xA1 / 255)
    static let paper = Color(red: 0xF5 / 255, green: 0xF7 / 255, blue: 0xF3 / 255)
    static let amber = Color(red: 0xF2 / 255, green: 0xB8 / 255, blue: 0x4B / 255)
    static let coral = Color(red: 0xFF / 255, green: 0x8A / 255, blue: 0x80 / 255)
}

#if DEBUG
private let previewAttributes = NaqiJobAttributes(
    title: "Family trip 2026", pausedCaption: "Paused", pausedDetail: "Open Naqi to resume")

#Preview("Lock screen", as: .content, using: previewAttributes) {
    NaqiJobActivity()
} contentStates: {
    NaqiJobAttributes.ContentState(caption: "Analyzing", detail: "~12 min remaining · 2 more in the queue",
                                   symbol: "eye", fraction: 0.42)
    NaqiJobAttributes.ContentState(phase: .paused, caption: "Paused", detail: "Open Naqi to resume",
                                   symbol: "pause.fill", fraction: 0.42)
    NaqiJobAttributes.ContentState(phase: .done, caption: "Filtering done", detail: "Family trip 2026 (Naqi).mp4",
                                   symbol: "checkmark", fraction: 1)
}

#Preview("Island", as: .dynamicIsland(.expanded), using: previewAttributes) {
    NaqiJobActivity()
} contentStates: {
    NaqiJobAttributes.ContentState(caption: "Rendering", detail: "~4 min remaining", symbol: "film", fraction: 0.71)
}
#endif
