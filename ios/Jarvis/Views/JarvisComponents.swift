import SwiftUI

// Building blocks of the Jarvis design language (desktop components/jv/*),
// drawn with the Instrument tokens (Instrument.swift).

// MARK: - Orb

/// The Jarvis orb in Instrument accent colours, light and dark. It breathes
/// slowly; mood speeds it up (idle ×1, listening ×0.7, thinking ×0.4).
/// Reduce Motion shows a still frame.
struct JarvisOrb: View {
    var size: CGFloat
    var mood: Mood = .idle
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 30.0, paused: reduceMotion)) { context in
            let time = reduceMotion ? 0 : context.date.timeIntervalSinceReferenceDate
            let breathe = (sin(time / (4 * moodFactor(mood)) * 2 * .pi) + 1) / 2
            ZStack {
                Circle()
                    .fill(JV.accentSubtle)
                    .overlay(Circle().strokeBorder(JV.accentBorder, lineWidth: 1))
                    .scaleEffect(0.94 + 0.06 * breathe)
                Circle()
                    .fill(RadialGradient(colors: [JV.focusRing, JV.accentSolid], center: UnitPoint(x: 0.4, y: 0.35),
                                         startRadius: 0, endRadius: size * 0.36))
                    .frame(width: size * 0.6, height: size * 0.6)
                    .scaleEffect(0.97 + 0.04 * breathe)
            }
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
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
            Text(text).font(JV.font(13)).foregroundStyle(tone.color)
        }
        .accessibilityElement(children: .combine)
    }
}

/// Sentence-case section label ("Your system").
struct Eyebrow: View {
    let text: String

    var body: some View {
        Text(text)
            .font(JV.font(13, .medium))
            .foregroundStyle(JV.textSecondary)
            .accessibilityAddTraits(.isHeader)
    }
}

/// Accent icon tile.
struct IconBadge: View {
    let symbol: String
    var size: CGFloat = 40

    var body: some View {
        Image(systemName: symbol)
            .font(.system(size: size * 0.42, weight: .regular))
            .foregroundStyle(JV.accentText)
            .frame(width: size, height: size)
            .background(JV.accentSubtle, in: RoundedRectangle(cornerRadius: JV.Radius.md, style: .continuous))
            .accessibilityHidden(true)
    }
}

// MARK: - Lists

/// Instrument list row: icon tile, title, optional detail and value, chevron.
struct ListRow: View {
    let title: String
    let symbol: String
    var detail: String? = nil
    var value: String? = nil

    var body: some View {
        HStack(spacing: 12) {
            IconBadge(symbol: symbol, size: 32)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(JV.font(15, .medium)).foregroundStyle(JV.textPrimary)
                if let detail, !detail.isEmpty {
                    Text(verbatim: detail).font(JV.font(13)).foregroundStyle(JV.textSecondary)
                }
            }
            Spacer(minLength: 8)
            if let value {
                Text(verbatim: value).font(JV.font(13)).foregroundStyle(JV.textSecondary).multilineTextAlignment(.trailing)
            }
            Image(systemName: "chevron.right")
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(JV.textSecondary)
                .accessibilityHidden(true)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .frame(minHeight: 52)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }
}

/// Bordered list of pushed pages, one row per route.
struct RouteList: View {
    let routes: [HubRoute]
    var detail: (HubRoute) -> String? = { _ in nil }

    var body: some View {
        VStack(spacing: 0) {
            ForEach(Array(routes.enumerated()), id: \.element) { index, route in
                if index > 0 { ListDivider() }
                NavigationLink(value: route) { ListRow(title: route.title, symbol: route.symbol, detail: detail(route)) }
                    .buttonStyle(.plain)
            }
        }
        .jvCard(padding: 0)
    }
}

/// Hairline between list rows, inset past the icon tile.
struct ListDivider: View {
    var body: some View { Rectangle().fill(JV.separator).frame(height: 1).padding(.leading, 56) }
}

/// Titled group of an Instrument page (I-Settings sections).
struct ListSection<Content: View>: View {
    let title: String
    @ViewBuilder let content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Eyebrow(text: title).padding(.horizontal, 4)
            content
        }
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
                        .font(JV.font(18, .semibold, relativeTo: .title3))
                        .foregroundStyle(JV.textPrimary)
                        .accessibilityAddTraits(.isHeader)
                    StatusLabel(tone: item.tone, text: item.status)
                }
            }
            Text(item.detail)
                .font(JV.font(15))
                .foregroundStyle(JV.textSecondary)
                .lineSpacing(3)
                .padding(.top, 12)
                .fixedSize(horizontal: false, vertical: true)
            accessory
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .jvCard(padding: 16)
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
                Text(title)
                Image(systemName: symbol).font(.system(size: 13, weight: .semibold))
            }
            .frame(maxWidth: .infinity)
        }
        .buttonStyle(JVButtonStyle(kind: .quiet, size: .large))
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
                        .foregroundStyle(JV.accentText)
                        .accessibilityHidden(true)
                    Text(tile.value)
                        .font(JV.number(17, .semibold))
                        .foregroundStyle(JV.textPrimary)
                        .lineLimit(2)
                        .minimumScaleFactor(0.8)
                    Text(tile.label)
                        .font(JV.font(13))
                        .foregroundStyle(JV.textSecondary)
                }
                .padding(12)
                .frame(maxWidth: .infinity, minHeight: 92, alignment: .topLeading)
                .background(JV.bgSunken, in: RoundedRectangle(cornerRadius: JV.Radius.md, style: .continuous))
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
                .foregroundStyle(JV.textSecondary)
                .frame(width: 36, height: 36)
                .background(JV.bgSunken, in: RoundedRectangle(cornerRadius: JV.Radius.md, style: .continuous))
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 3) {
                Text(row.title)
                    .font(JV.font(15, .medium))
                    .foregroundStyle(JV.textPrimary)
                    .lineLimit(1)
                if let detail = row.detail, !detail.isEmpty {
                    Text(detail)
                        .font(JV.font(13))
                        .foregroundStyle(JV.textSecondary)
                        .lineLimit(2)
                }
            }
            Spacer(minLength: 4)
            if let time = row.time, !time.isEmpty {
                Text(time).font(JV.number(13)).foregroundStyle(JV.textSecondary)
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
                    .font(JV.font(15))
                    .foregroundStyle(JV.textSecondary)
                    .frame(maxWidth: .infinity, minHeight: 58)
            }
            ForEach(Array(rows.enumerated()), id: \.element.id) { index, row in
                if index > 0 { Divider().overlay(JV.separator) }
                if let onSelect {
                    Button { onSelect(row) } label: { ActivityRowView(row: row).contentShape(Rectangle()) }
                        .buttonStyle(.plain)
                } else {
                    ActivityRowView(row: row)
                }
            }
        }
        .jvCard(padding: 0)
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
                .foregroundStyle(JV.textSecondary)
                .frame(width: 40, height: 40)
                .background(JV.bgSunken, in: Circle())
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 4) {
                Text(title).font(JV.font(15, .semibold)).foregroundStyle(JV.textPrimary)
                Text(kindLabel)
                    .font(JV.font(13, .medium))
                    .foregroundStyle(JV.textSecondary)
                if let detail {
                    Text(detail)
                        .font(JV.font(13))
                        .foregroundStyle(JV.textSecondary)
                        .lineSpacing(3)
                        .padding(.top, 4)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 16)
        .background(JV.bgSurface, in: RoundedRectangle(cornerRadius: JV.Radius.lg, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: JV.Radius.lg, style: .continuous)
            .strokeBorder(JV.border, style: StrokeStyle(lineWidth: 1, dash: [4, 3])))
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
                            Text(chip.label).font(JV.font(compact ? 13 : 14, .medium))
                        }
                        .foregroundStyle(selected ? JV.accentText : JV.textSecondary)
                        .padding(.horizontal, compact ? 12 : 14)
                        .frame(minHeight: compact ? 36 : 40)
                        .background(selected ? JV.bgSelected : (compact ? Color.clear : JV.bgSunken),
                                    in: RoundedRectangle(cornerRadius: compact ? JV.Radius.md : JV.Radius.xl, style: .continuous))
                        .overlay(RoundedRectangle(cornerRadius: compact ? JV.Radius.md : JV.Radius.xl, style: .continuous)
                            .strokeBorder(selected ? JV.accentBorder : (compact ? JV.border : Color.clear), lineWidth: 1))
                        .contentShape(RoundedRectangle(cornerRadius: compact ? JV.Radius.md : JV.Radius.xl, style: .continuous))
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

/// Shared layout of every node page: satellite selector and the page's own
/// content for the selected satellite.
struct NodeScaffold<Content: View>: View {
    let title: String
    let subtitle: String
    let items: [NodeItem]
    @Binding var selection: String
    let content: Content

    init(title: String, subtitle: String, items: [NodeItem], selection: Binding<String>,
         @ViewBuilder content: () -> Content) {
        self.title = title
        self.subtitle = subtitle
        self.items = items
        self._selection = selection
        self.content = content()
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                ChipRow(chips: items.map { ChipRow.Chip(id: $0.id, label: $0.label, symbol: $0.icon, accessibilityValue: $0.status) },
                        selection: $selection)
                content
            }
            .padding(.horizontal, 16)
            .padding(.top, 12)
            .padding(.bottom, 32)
        }
        .background(JV.bgPage)
        .jarvisNavigationBar(title: title, subtitle: subtitle)
    }
}

extension View {
    /// Navigation bar with a sentence-case title and subtitle. Settings is a
    /// tab, so pages carry no profile button.
    func jarvisNavigationBar(title: String, subtitle: String) -> some View {
        navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(JV.bgPage, for: .navigationBar)
            .toolbarBackground(.visible, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .principal) {
                    VStack(spacing: 1) {
                        Text(title)
                            .font(JV.font(17, .semibold, relativeTo: .headline))
                            .foregroundStyle(JV.textPrimary)
                        Text(subtitle)
                            .font(JV.font(12, relativeTo: .caption))
                            .foregroundStyle(JV.textSecondary)
                    }
                    .lineLimit(1)
                    .accessibilityElement(children: .combine)
                    .accessibilityAddTraits(.isHeader)
                }
            }
    }
}

/// Opens Settings from the enrollment screen (connection, voice, security).
struct ProfileButton: View {
    var body: some View {
        NavigationLink(value: HubRoute.settings) {
            Image(systemName: "person.crop.circle")
                .font(.system(size: 22))
                .foregroundStyle(JV.accentText)
                .frame(width: 44, height: 44)
        }
        .accessibilityLabel("Profile and settings")
    }
}
