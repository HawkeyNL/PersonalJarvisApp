import SwiftUI

// Building blocks of the Jarvis design language (desktop components/jv/*).

// MARK: - Backdrop

/// Dark sky with a soft glow behind the orb, a few stars and the horizon arc.
struct JarvisBackdrop: View {
    private static let stars: [(x: CGFloat, y: CGFloat, size: CGFloat, opacity: Double)] = [
        (0.07, 0.14, 3, 0.8), (0.90, 0.11, 2, 0.6), (0.10, 0.42, 2, 0.5), (0.93, 0.38, 3, 0.7),
        (0.05, 0.69, 2, 0.5), (0.95, 0.63, 2, 0.5), (0.15, 0.97, 3, 0.6), (0.85, 0.89, 2, 0.5),
    ]

    var body: some View {
        GeometryReader { proxy in
            let size = proxy.size
            ZStack {
                JarvisTheme.background
                RadialGradient(colors: [Color(red: 20 / 255.0, green: 150 / 255.0, blue: 95 / 255.0).opacity(0.28), .clear],
                               center: UnitPoint(x: 0.5, y: 0.22), startRadius: 0, endRadius: 330)
                Canvas { context, canvas in
                    for star in Self.stars {
                        let rect = CGRect(x: star.x * canvas.width, y: star.y * canvas.height, width: star.size, height: star.size)
                        context.fill(Path(ellipseIn: rect), with: .color(Color(hex: 0x9BFFD6).opacity(star.opacity)))
                    }
                }
                Ellipse()
                    .fill(RadialGradient(colors: [Color(red: 20 / 255.0, green: 120 / 255.0, blue: 80 / 255.0).opacity(0.5),
                                                  Color(hex: 0x04160F), Color(hex: 0x010A07)],
                                         center: .top, startRadius: 0, endRadius: 320))
                    .overlay(Ellipse().stroke(Color(red: 110 / 255.0, green: 1, blue: 200 / 255.0).opacity(0.55), lineWidth: 2))
                    .shadow(color: JarvisTheme.accent.opacity(0.35), radius: 20)
                    .frame(width: size.width * 2.3, height: 500)
                    .position(x: size.width / 2, y: size.height + 190)
            }
        }
        .ignoresSafeArea()
        .accessibilityHidden(true)
    }
}

// MARK: - Orb

/// The living Jarvis orb. Mood speeds the animation up (idle ×1, listening
/// ×0.7, thinking ×0.4); Reduce Motion shows a still frame.
struct JarvisOrb: View {
    var size: CGFloat
    var mood: Mood = .idle
    var showsLabel = true
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 30.0, paused: reduceMotion)) { context in
            OrbFrame(size: size, factor: moodFactor(mood),
                     time: reduceMotion ? 0 : context.date.timeIntervalSinceReferenceDate,
                     showsLabel: showsLabel)
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

private struct OrbFrame: View {
    let size: CGFloat
    let factor: Double
    let time: Double
    let showsLabel: Bool

    private static let mint = Color(red: 150 / 255.0, green: 1, blue: 210 / 255.0)
    private static let spark = Color(hex: 0xB8FFDF)
    // Sparkle positions as fractions of their ring box, plus size and phase.
    private static let innerSparks: [(CGFloat, CGFloat, CGFloat, Double)] = [
        (0.50, 0.00, 4, 0), (0.85, 0.15, 3, 1.1), (0.99, 0.58, 4, 2.3), (0.70, 0.96, 3, 0.6),
        (0.22, 0.91, 4, 1.8), (0.01, 0.44, 3, 2.7), (0.16, 0.12, 2, 0.3),
    ]
    private static let outerSparks: [(CGFloat, CGFloat, CGFloat, Double)] = [
        (0.30, 0.04, 2, 1.4), (0.96, 0.34, 3, 2.1), (0.80, 0.90, 2, 0.9), (0.04, 0.68, 3, 2.9),
    ]
    // Neural paths in a 100×100 box: end, control 1, control 2 (from the centre).
    private static let signals: [(CGPoint, CGPoint, CGPoint, Double, Double)] = [
        (CGPoint(x: 24, y: 30), CGPoint(x: 42, y: 40), CGPoint(x: 35, y: 38), 3.4, 0),
        (CGPoint(x: 77, y: 28), CGPoint(x: 58, y: 41), CGPoint(x: 66, y: 36), 4.1, 1.3),
        (CGPoint(x: 80, y: 70), CGPoint(x: 60, y: 55), CGPoint(x: 70, y: 62), 3.8, 2.2),
        (CGPoint(x: 22, y: 72), CGPoint(x: 41, y: 58), CGPoint(x: 32, y: 64), 4.6, 0.7),
        (CGPoint(x: 49, y: 88), CGPoint(x: 50, y: 62), CGPoint(x: 47, y: 74), 3.1, 1.9),
        (CGPoint(x: 50, y: 12), CGPoint(x: 51, y: 38), CGPoint(x: 54, y: 24), 4.3, 2.8),
    ]
    private static let nodes: [(CGFloat, CGFloat, CGFloat, Double)] = [
        (35, 38, 1.1, 0), (66, 36, 1.1, 0.4), (70, 62, 1.1, 0.9), (32, 64, 1.1, 1.3), (54, 24, 1, 1.7), (47, 74, 1, 2.1),
        (24, 30, 0.8, 0.6), (77, 28, 0.8, 1.1), (80, 70, 0.8, 1.5), (22, 72, 0.8, 1.9), (14, 52, 0.7, 2.4), (88, 48, 0.7, 0.2),
    ]

    /// 0…1 sine wave with the given period in seconds.
    private func wave(_ period: Double, _ offset: Double = 0) -> Double {
        (sin((time + offset) / period * 2 * .pi) + 1) / 2
    }

    /// 0…1 position within a repeating period.
    private func phase(_ period: Double, _ offset: Double = 0) -> Double {
        let value = (time + offset) / period
        return value - value.rounded(.down)
    }

    /// Heartbeat keyframes of the export (1 → 1.03 → .995 → 1.018 → 1).
    private func beat() -> Double {
        let p = phase(3.2 * factor)
        let keys: [(Double, Double)] = [(0, 1), (0.1, 1.03), (0.2, 0.995), (0.3, 1.018), (0.45, 1), (1, 1)]
        for index in 1..<keys.count where p <= keys[index].0 {
            let (p0, v0) = keys[index - 1], (p1, v1) = keys[index]
            return v0 + (v1 - v0) * (p - p0) / (p1 - p0)
        }
        return 1
    }

    var body: some View {
        let accent = JarvisTheme.accent
        let breathe = wave(6)
        let core = size * 0.87
        let morph = sin(time / 14 * 2 * .pi) * 0.015
        ZStack {
            Circle()
                .fill(RadialGradient(gradient: Gradient(stops: [
                    .init(color: accent.opacity(0.34), location: 0),
                    .init(color: accent.opacity(0.12), location: 0.36),
                    .init(color: .clear, location: 0.62),
                ]), center: .center, startRadius: 0, endRadius: size * 1.13))
                .frame(width: size * 1.6, height: size * 1.6)
                .scaleEffect(0.97 + 0.08 * breathe)
                .opacity(0.6 + 0.4 * breathe)
            sparkRing(Self.innerSparks, box: size * 1.22)
                .rotationEffect(.degrees(phase(60) * 360))
            sparkRing(Self.outerSparks, box: size * 1.44)
                .rotationEffect(.degrees(-phase(95) * 360))
                .opacity(0.7)
            Circle()
                .stroke(Self.mint.opacity(0.3), lineWidth: 1)
                .frame(width: size * 1.04, height: size * 1.04)
                .scaleEffect(x: 1 - morph, y: 1 + morph)
            Circle()
                .stroke(Self.mint.opacity(0.5), lineWidth: 1.5)
                .shadow(color: accent.opacity(0.4), radius: 11)
                .frame(width: size, height: size)
                .scaleEffect(x: 1 + morph, y: 1 - morph)
            Circle()
                .stroke(accent.opacity(0.38), lineWidth: 1)
                .frame(width: size * 0.94, height: size * 0.94)
                .scaleEffect(x: 1 - morph * 0.7, y: 1 + morph * 0.7)
            sphere(core: core)
                .frame(width: core, height: core)
                .scaleEffect(beat())
            if showsLabel {
                VStack(spacing: 7) {
                    Text("JARVIS")
                        .font(JarvisFont.display(size * 0.088))
                        .tracking(size * 0.035)
                        .foregroundStyle(JarvisTheme.text1)
                        .shadow(color: Color(red: 0, green: 30 / 255.0, blue: 20 / 255.0).opacity(0.85), radius: 6)
                    Rectangle()
                        .fill(JarvisTheme.text1.opacity(0.85))
                        .frame(width: size * 0.18, height: 2)
                }
            }
        }
        .frame(width: size * 1.6, height: size * 1.6)
        .drawingGroup()
        .frame(width: size, height: size)
    }

    private func sparkRing(_ sparks: [(CGFloat, CGFloat, CGFloat, Double)], box: CGFloat) -> some View {
        ZStack {
            ForEach(sparks.indices, id: \.self) { index in
                let spark = sparks[index]
                Circle()
                    .fill(Self.spark)
                    .frame(width: spark.2, height: spark.2)
                    .shadow(color: JarvisTheme.accent, radius: spark.2 > 2 ? 3 : 0)
                    .opacity(0.2 + 0.8 * wave(3.2, spark.3))
                    .position(x: spark.0 * box, y: spark.1 * box)
            }
        }
        .frame(width: box, height: box)
    }

    private func sphere(core: CGFloat) -> some View {
        let scale = core / 100
        let factor = self.factor
        // Blob offsets as fractions of the core (export keyframes jvoDrift A/B/C).
        let drift: [CGFloat] = [
            CGFloat(-0.16 + 0.3 * wave(9)), CGFloat(-0.12 + 0.3 * wave(9, 2)),
            CGFloat(0.19 - 0.34 * wave(12, 3)), CGFloat(0.21 - 0.38 * wave(12, 5)),
            CGFloat(-0.16 + 0.4 * wave(15, 1)), CGFloat(0.22 - 0.26 * wave(15, 4)),
        ]
        return ZStack {
            Circle()
                .fill(RadialGradient(gradient: Gradient(stops: [
                    .init(color: Color(hex: 0x3FF0A2), location: 0),
                    .init(color: Color(hex: 0x14A868), location: 0.28),
                    .init(color: Color(hex: 0x0A6744), location: 0.56),
                    .init(color: Color(hex: 0x05301F), location: 0.84),
                    .init(color: Color(hex: 0x03190F), location: 1),
                ]), center: UnitPoint(x: 0.42, y: 0.38), startRadius: 0, endRadius: core * 0.85))
            blob(color: Color(red: 170 / 255.0, green: 1, blue: 215 / 255.0).opacity(0.75), diameter: core * 0.56,
                 x: drift[0], y: drift[1], core: core)
            blob(color: Color(red: 90 / 255.0, green: 1, blue: 175 / 255.0).opacity(0.65), diameter: core * 0.5,
                 x: drift[2], y: drift[3], core: core)
            blob(color: Color(red: 205 / 255.0, green: 1, blue: 232 / 255.0).opacity(0.5), diameter: core * 0.4,
                 x: drift[4], y: drift[5], core: core)
            ForEach(Self.signals.indices, id: \.self) { index in
                let signal = Self.signals[index]
                let path = Self.neuralPath(signal, scale: scale)
                path.stroke(Color(red: 210 / 255.0, green: 1, blue: 235 / 255.0).opacity(0.26), lineWidth: max(0.5, 0.45 * scale))
                let head = CGFloat(phase(signal.3 * factor, signal.4) * 2.6)
                let tail: CGFloat = min(max(0, head - 0.18), 1)
                let tip: CGFloat = min(max(0, head), 1)
                path.trim(from: tail, to: tip)
                    .stroke(Color(hex: 0xEAFFF5), style: StrokeStyle(lineWidth: max(0.8, scale), lineCap: .round))
                    .opacity(head - 0.18 < 1 ? 1 : 0)
            }
            ForEach(Self.nodes.indices, id: \.self) { index in
                let node = Self.nodes[index]
                Circle()
                    .fill(Color(hex: 0xDFFFEE))
                    .frame(width: node.2 * 2 * scale, height: node.2 * 2 * scale)
                    .position(x: node.0 * scale, y: node.1 * scale)
                    .opacity(0.3 + 0.7 * wave(2.6, node.3))
            }
            let nucleus = wave(4 * factor)
            Circle()
                .fill(RadialGradient(colors: [Color(red: 200 / 255.0, green: 1, blue: 228 / 255.0).opacity(0.75),
                                              Color(red: 80 / 255.0, green: 240 / 255.0, blue: 165 / 255.0).opacity(0.35), .clear],
                                     center: .center, startRadius: 0, endRadius: core * 0.17))
                .frame(width: core * 0.34, height: core * 0.34)
                .scaleEffect(0.88 + 0.26 * nucleus)
                .opacity(0.5 + 0.45 * nucleus)
            Ellipse()
                .fill(RadialGradient(colors: [Color.white.opacity(0.32), .clear], center: .center, startRadius: 0, endRadius: core * 0.18))
                .frame(width: core * 0.36, height: core * 0.22)
                .rotationEffect(.degrees(-22))
                .offset(x: -core * 0.16, y: -core * 0.29)
        }
        .frame(width: core, height: core)
        .clipShape(Circle())
        .shadow(color: JarvisTheme.accent.opacity(0.55), radius: 30)
    }

    private func blob(color: Color, diameter: CGFloat, x: CGFloat, y: CGFloat, core: CGFloat) -> some View {
        Circle()
            .fill(RadialGradient(colors: [color, .clear], center: .center, startRadius: 0, endRadius: diameter * 0.45))
            .frame(width: diameter, height: diameter)
            .offset(x: core * x, y: core * y)
            .blendMode(.screen)
    }

    private static func neuralPath(_ signal: (CGPoint, CGPoint, CGPoint, Double, Double), scale: CGFloat) -> Path {
        func point(_ p: CGPoint) -> CGPoint { CGPoint(x: p.x * scale, y: p.y * scale) }
        var path = Path()
        path.move(to: point(CGPoint(x: 50, y: 50)))
        path.addCurve(to: point(signal.0), control1: point(signal.1), control2: point(signal.2))
        return path
    }
}

// MARK: - Status

struct StatusDot: View {
    let tone: Tone
    var size: CGFloat = 8

    var body: some View {
        Circle()
            .fill(tone.color)
            .frame(width: size, height: size)
            .shadow(color: tone == .idle ? .clear : tone.color.opacity(0.6), radius: 4)
            .accessibilityHidden(true)
    }
}

/// Status dot with its text label; the label carries the meaning.
struct StatusLabel: View {
    let tone: Tone
    let text: String

    var body: some View {
        HStack(spacing: 6) {
            StatusDot(tone: tone)
            Text(text).font(JarvisFont.body(12)).foregroundStyle(tone.labelColor)
        }
        .accessibilityElement(children: .combine)
    }
}

/// Uppercase section label ("YOUR SYSTEM").
struct Eyebrow: View {
    let text: String

    var body: some View {
        Text(text.uppercased())
            .font(JarvisFont.body(10))
            .tracking(3)
            .foregroundStyle(JarvisTheme.text6)
            .accessibilityAddTraits(.isHeader)
    }
}

/// Round icon badge with an accent ring.
struct IconBadge: View {
    let symbol: String
    var size: CGFloat = 40

    var body: some View {
        Image(systemName: symbol)
            .font(.system(size: size * 0.42, weight: .regular))
            .foregroundStyle(JarvisTheme.accent)
            .frame(width: size, height: size)
            .overlay(Circle().stroke(JarvisTheme.accent.opacity(0.6), lineWidth: 1.5))
            .accessibilityHidden(true)
    }
}

// MARK: - Module card

/// Hub card: icon, title, two short lines and a status dot.
struct ModuleCard: View {
    let title: String
    let symbol: String
    let summary: CardSummary

    var body: some View {
        HStack(spacing: 10) {
            IconBadge(symbol: symbol)
            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(JarvisFont.body(12.5, 600))
                    .tracking(0.75)
                    .foregroundStyle(JarvisTheme.text1)
                Text(summary.line1).font(JarvisFont.body(11)).foregroundStyle(JarvisTheme.text5)
                if !summary.line2.isEmpty {
                    Text(summary.line2).font(JarvisFont.body(11)).foregroundStyle(JarvisTheme.text6)
                }
            }
            .lineLimit(1)
            .minimumScaleFactor(0.85)
            Spacer(minLength: 0)
            StatusDot(tone: summary.tone, size: 7)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .frame(maxWidth: .infinity, minHeight: 72, alignment: .leading)
        .background(JarvisTheme.card, in: RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(JarvisTheme.accent.opacity(0.35), lineWidth: 1))
        .contentShape(RoundedRectangle(cornerRadius: 16))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel([title, summary.line1, summary.line2].filter { !$0.isEmpty }.joined(separator: ", "))
        .accessibilityAddTraits(.isButton)
    }
}

// MARK: - Panel, tiles, activity

/// Detail panel of a node page: header, description and optional actions.
struct NodePanel<Accessory: View>: View {
    let item: NodeItem
    let accessory: Accessory

    init(item: NodeItem, @ViewBuilder accessory: () -> Accessory) {
        self.item = item
        self.accessory = accessory()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 14) {
                IconBadge(symbol: item.icon, size: 52)
                VStack(alignment: .leading, spacing: 4) {
                    Text(item.title)
                        .font(JarvisFont.body(17, 500))
                        .foregroundStyle(JarvisTheme.text1)
                        .accessibilityAddTraits(.isHeader)
                    StatusLabel(tone: item.tone, text: item.status)
                }
            }
            Text(item.detail)
                .font(JarvisFont.body(13.5))
                .foregroundStyle(JarvisTheme.text3)
                .lineSpacing(3)
                .padding(.top, 12)
                .fixedSize(horizontal: false, vertical: true)
            accessory
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(JarvisTheme.panelFill, in: RoundedRectangle(cornerRadius: 20))
        .overlay(RoundedRectangle(cornerRadius: 20).stroke(JarvisTheme.accent.opacity(0.55), lineWidth: 1.5))
        .shadow(color: JarvisTheme.accent.opacity(0.16), radius: 12)
    }
}

extension NodePanel where Accessory == EmptyView {
    init(item: NodeItem) { self.init(item: item) { EmptyView() } }
}

/// Full-width call to action inside a panel.
struct PanelButton: View {
    let title: String
    var symbol = "arrow.right"
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 10) {
                Text(title).font(JarvisFont.body(13.5, 500))
                Image(systemName: symbol).font(.system(size: 13, weight: .semibold))
            }
            .foregroundStyle(JarvisTheme.text1)
            .frame(maxWidth: .infinity, minHeight: 44)
            .background(JarvisTheme.accent.opacity(0.1), in: RoundedRectangle(cornerRadius: 12))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(JarvisTheme.accent.opacity(0.55), lineWidth: 1))
            .contentShape(RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(.plain)
        .padding(.top, 14)
    }
}

struct TileData: Identifiable {
    let label: String
    let value: String
    let symbol: String
    var id: String { label }
}

/// Two-column grid of labelled values.
struct TileGrid: View {
    let tiles: [TileData]

    var body: some View {
        LazyVGrid(columns: [GridItem(.flexible(), spacing: 10), GridItem(.flexible(), spacing: 10)], spacing: 10) {
            ForEach(tiles) { tile in
                VStack(alignment: .leading, spacing: 6) {
                    Image(systemName: tile.symbol)
                        .font(.system(size: 15))
                        .foregroundStyle(JarvisTheme.accent)
                        .accessibilityHidden(true)
                    Text(tile.value)
                        .font(JarvisFont.body(15, 500))
                        .foregroundStyle(JarvisTheme.text1)
                        .lineLimit(2)
                        .minimumScaleFactor(0.8)
                    Text(tile.label)
                        .font(JarvisFont.body(11))
                        .foregroundStyle(JarvisTheme.text6)
                }
                .padding(12)
                .frame(maxWidth: .infinity, minHeight: 92, alignment: .topLeading)
                .background(JarvisTheme.tile, in: RoundedRectangle(cornerRadius: 14))
                .overlay(RoundedRectangle(cornerRadius: 14).stroke(JarvisTheme.accent.opacity(0.3), lineWidth: 1))
                .accessibilityElement(children: .combine)
            }
        }
    }
}

struct ActivityRow: Identifiable {
    let id: String
    let symbol: String
    let title: String
    var detail: String? = nil
    var time: String? = nil
    let tone: Tone
    let toneLabel: String
}

struct ActivityRowView: View {
    let row: ActivityRow

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: row.symbol)
                .font(.system(size: 15))
                .foregroundStyle(JarvisTheme.text2)
                .frame(width: 36, height: 36)
                .background(Color(red: 6 / 255.0, green: 30 / 255.0, blue: 24 / 255.0).opacity(0.9), in: RoundedRectangle(cornerRadius: 10))
                .overlay(RoundedRectangle(cornerRadius: 10).stroke(JarvisTheme.accent.opacity(0.2), lineWidth: 1))
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 3) {
                Text(row.title)
                    .font(JarvisFont.body(12.5, 500))
                    .foregroundStyle(JarvisTheme.text1)
                    .lineLimit(1)
                if let detail = row.detail, !detail.isEmpty {
                    Text(detail)
                        .font(JarvisFont.body(11))
                        .foregroundStyle(JarvisTheme.text6)
                        .lineLimit(2)
                }
            }
            Spacer(minLength: 4)
            if let time = row.time, !time.isEmpty {
                Text(time).font(JarvisFont.body(11)).foregroundStyle(JarvisTheme.text6)
            }
            StatusDot(tone: row.tone)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .frame(minHeight: 58)
        .accessibilityElement(children: .combine)
        .accessibilityValue(row.toneLabel)
    }
}

/// Bordered list of activity rows; rows are tappable when `onSelect` is set.
struct ActivityList: View {
    let rows: [ActivityRow]
    var emptyText = "Nothing to show yet."
    var onSelect: ((ActivityRow) -> Void)? = nil

    var body: some View {
        VStack(spacing: 0) {
            if rows.isEmpty {
                Text(emptyText)
                    .font(JarvisFont.body(12.5))
                    .foregroundStyle(JarvisTheme.text5)
                    .frame(maxWidth: .infinity, minHeight: 58)
            }
            ForEach(Array(rows.enumerated()), id: \.element.id) { index, row in
                if index > 0 { Divider().overlay(JarvisTheme.accent.opacity(0.16)) }
                if let onSelect {
                    Button { onSelect(row) } label: { ActivityRowView(row: row).contentShape(Rectangle()) }
                        .buttonStyle(.plain)
                } else {
                    ActivityRowView(row: row)
                }
            }
        }
        .background(JarvisTheme.tile.opacity(0.75), in: RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(JarvisTheme.accent.opacity(0.3), lineWidth: 1))
    }
}

// MARK: - Unavailable

/// Honest empty state for features Core does not offer (yet). Never sample data.
struct UnavailableState: View {
    enum Kind { case planned, coreUpdate, error }

    let title: String
    var kind: Kind = .planned
    var detail: String? = nil
    var symbol = "sparkles"

    var body: some View {
        HStack(alignment: .top, spacing: 16) {
            Image(systemName: symbol)
                .font(.system(size: 17))
                .foregroundStyle(JarvisTheme.text5)
                .frame(width: 40, height: 40)
                .overlay(Circle().stroke(JarvisTheme.accent.opacity(0.3), lineWidth: 1.5))
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 4) {
                Text(title).font(JarvisFont.body(13, 500)).foregroundStyle(JarvisTheme.text1)
                Text(kindLabel.uppercased())
                    .font(JarvisFont.body(11))
                    .tracking(2.2)
                    .foregroundStyle(JarvisTheme.text5)
                if let detail {
                    Text(detail)
                        .font(JarvisFont.body(12))
                        .foregroundStyle(JarvisTheme.text4)
                        .lineSpacing(3)
                        .padding(.top, 4)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 16)
        .background(JarvisTheme.tile.opacity(0.5), in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(JarvisTheme.accent.opacity(0.3), style: StrokeStyle(lineWidth: 1, dash: [4, 3])))
        .accessibilityElement(children: .combine)
    }

    private var kindLabel: String {
        switch kind {
        case .planned: "Not yet available"
        case .coreUpdate: "Requires newer Core"
        case .error: "Could not load"
        }
    }
}

extension UnavailableState {
    /// Empty state for a read that did not succeed; `nil` while it loads or after success.
    static func forSource<T>(_ source: Availability<T>, title: String, symbol: String = "sparkles",
                             forbidden: String = "Turned off in Core.") -> UnavailableState? {
        switch source {
        case .unsupported:
            return UnavailableState(title: title, kind: .coreUpdate, detail: "Update Core to see this here.", symbol: symbol)
        case let .error(reason):
            return UnavailableState(title: title, kind: .error, detail: failureText(reason, forbidden: forbidden), symbol: symbol)
        case .loading, .ok:
            return nil
        }
    }
}

// MARK: - Node page scaffold

struct NodeItem: Identifiable {
    let id: String
    let label: String
    let title: String
    let icon: String
    let detail: String
    let tone: Tone
    let status: String

    static func planned(_ id: String, _ label: String, _ title: String, icon: String, detail: String) -> NodeItem {
        NodeItem(id: id, label: label, title: title, icon: icon, detail: detail, tone: .idle, status: "Not yet available")
    }
}

/// Horizontal pill selector (satellites on the node pages, sub-tabs).
struct ChipRow: View {
    struct Chip: Identifiable {
        let id: String
        let label: String
        let symbol: String
        var accessibilityValue = ""
    }

    let chips: [Chip]
    @Binding var selection: String
    var compact = false

    var body: some View {
        ScrollView(.horizontal) {
            HStack(spacing: 8) {
                ForEach(chips) { chip in
                    let selected = chip.id == selection
                    Button { selection = chip.id } label: {
                        HStack(spacing: 7) {
                            Image(systemName: chip.symbol).font(.system(size: compact ? 12 : 14))
                            Text(chip.label).font(JarvisFont.body(compact ? 12 : 13, selected ? 500 : 400))
                        }
                        .foregroundStyle(selected ? JarvisTheme.text1 : JarvisTheme.text4)
                        .padding(.horizontal, compact ? 12 : 14)
                        .frame(minHeight: compact ? 36 : 40)
                        .background(selected ? JarvisTheme.accent.opacity(compact ? 0.1 : 0.14) : (compact ? Color.clear : JarvisTheme.control),
                                    in: RoundedRectangle(cornerRadius: compact ? 10 : 20))
                        .overlay(RoundedRectangle(cornerRadius: compact ? 10 : 20)
                            .stroke(selected ? JarvisTheme.accent : JarvisTheme.accent.opacity(compact ? 0.2 : 0.3), lineWidth: selected ? 1.5 : 1))
                        .contentShape(RoundedRectangle(cornerRadius: compact ? 10 : 20))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(chip.label)
                    .accessibilityValue(chip.accessibilityValue)
                    .accessibilityAddTraits(selected ? .isSelected : [])
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 2)
        }
        .scrollIndicators(.hidden)
        .padding(.horizontal, -16)
    }
}

/// Shared layout of every node page: small orb, satellite selector and the
/// page's own content for the selected satellite.
struct NodeScaffold<Content: View>: View {
    let title: String
    let subtitle: String
    let items: [NodeItem]
    @Binding var selection: String
    let mood: Mood
    let content: Content

    init(title: String, subtitle: String, items: [NodeItem], selection: Binding<String>, mood: Mood,
         @ViewBuilder content: () -> Content) {
        self.title = title
        self.subtitle = subtitle
        self.items = items
        self._selection = selection
        self.mood = mood
        self.content = content()
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                JarvisOrb(size: 120, mood: mood, showsLabel: false)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 40)
                ChipRow(chips: items.map { ChipRow.Chip(id: $0.id, label: $0.label, symbol: $0.icon, accessibilityValue: $0.status) },
                        selection: $selection)
                content
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 32)
        }
        .background { JarvisBackdrop() }
        .jarvisNavigationBar(title: title, subtitle: subtitle)
    }
}

extension View {
    /// Dark navigation bar with the page title in the display face and the
    /// profile/settings button.
    func jarvisNavigationBar(title: String, subtitle: String) -> some View {
        navigationTitle(title.capitalized)
            .navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(JarvisTheme.background, for: .navigationBar)
            .toolbarBackground(.visible, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .principal) {
                    VStack(spacing: 3) {
                        Text(title.uppercased())
                            .font(JarvisFont.display(19))
                            .tracking(6)
                            .foregroundStyle(JarvisTheme.text1)
                        Text(subtitle.uppercased())
                            .font(JarvisFont.body(9))
                            .tracking(2.4)
                            .foregroundStyle(JarvisTheme.text5)
                    }
                    .lineLimit(1)
                    .accessibilityElement(children: .combine)
                    .accessibilityAddTraits(.isHeader)
                }
                ToolbarItem(placement: .topBarTrailing) { ProfileButton() }
            }
    }
}

/// Opens Settings (connection, voice, security, models).
struct ProfileButton: View {
    var body: some View {
        NavigationLink(value: HubRoute.settings) {
            Image(systemName: "person.crop.circle")
                .font(.system(size: 20))
                .foregroundStyle(JarvisTheme.text1)
                .frame(width: 44, height: 44)
                .overlay(Circle().stroke(JarvisTheme.accent.opacity(0.7), lineWidth: 1.5).padding(2))
        }
        .accessibilityLabel("Profile and settings")
    }
}
