import SwiftUI

// Instrument mobile patterns (canvas I-* boards; tokens.css .list/.li, .seg,
// .badge, .tile, .card, .empty, .skel), drawn with the tokens in Instrument.swift.
// Spacing follows the canvas values; tokens.css has no spacing scale.

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

/// Status badge (.badge): a dot and its label on a tinted pill. The label always
/// carries the meaning; Increase Contrast adds an outline.
struct Badge: View {
    enum Style: Equatable {
        case neutral, accent, positive, negative, danger

        var foreground: Color {
            switch self {
            case .neutral: JV.textPrimary
            case .accent: JV.accentText
            case .positive: JV.positiveText
            case .negative: JV.negativeText
            case .danger: JV.dangerText
            }
        }

        var background: Color {
            switch self {
            case .neutral: JV.bgSunken
            case .accent: JV.accentSubtle
            case .positive: JV.positiveSubtle
            case .negative: JV.negativeSubtle
            case .danger: JV.dangerSubtle
            }
        }
    }

    let text: String
    var style: Style = .neutral
    var dot = true
    @Environment(\.colorSchemeContrast) private var contrast

    var body: some View {
        HStack(spacing: 6) {
            if dot { Circle().fill(style.foreground).frame(width: 6, height: 6).accessibilityHidden(true) }
            Text(verbatim: text)
                .font(JV.font(12, .medium, relativeTo: .caption))
                .fixedSize(horizontal: false, vertical: true)
        }
        .foregroundStyle(style.foreground)
        .padding(.horizontal, 9)
        .padding(.vertical, 3)
        .frame(minHeight: 24)
        .background(style.background, in: Capsule())
        .overlay { if contrast == .increased { Capsule().strokeBorder(style.foreground, lineWidth: 1) } }
        .accessibilityElement(children: .combine)
    }
}

extension Tone {
    var badge: Badge.Style {
        switch self {
        case .ok: .positive
        case .idle: .neutral
        case .warn: .negative
        case .error: .danger
        }
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

/// Icon tile (.tile), accent by default.
struct IconBadge: View {
    let symbol: String
    var size: CGFloat = 40
    var style: Badge.Style = .accent

    var body: some View {
        Image(systemName: symbol)
            .font(.system(size: size * 0.42, weight: .regular))
            .foregroundStyle(style == .neutral ? JV.textSecondary : style.foreground)
            .frame(width: size, height: size)
            .background(style.background, in: RoundedRectangle(cornerRadius: JV.Radius.md, style: .continuous))
            .accessibilityHidden(true)
    }
}

// MARK: - Lists

/// Instrument list row (.li): icon tile, title, optional detail and value, chevron.
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
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
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

/// Hairline between list rows, by default inset past the icon tile.
struct ListDivider: View {
    var inset: CGFloat = 60
    @Environment(\.colorSchemeContrast) private var contrast

    var body: some View {
        Rectangle().fill(contrast == .increased ? JV.border : JV.separator).frame(height: 1).padding(.leading, inset)
    }
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

/// Segmented views (.seg): one choice of a few, 44 pt segments; the selected
/// one is raised with a 3:1 outline. Scrolls sideways when the labels do not
/// fit (narrow width, large text).
struct SegmentedControl<ID: Hashable>: View {
    struct Option: Identifiable {
        let id: ID
        let label: String
        var accessibilityValue = ""
    }

    let label: String
    let options: [Option]
    @Binding var selection: ID

    var body: some View {
        ViewThatFits(in: .horizontal) {
            segments(fill: true)
            ScrollView(.horizontal) { segments(fill: false) }.scrollIndicators(.hidden)
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel(label)
    }

    private func segments(fill: Bool) -> some View {
        HStack(spacing: 2) {
            ForEach(options) { option in
                let selected = option.id == selection
                let shape = RoundedRectangle(cornerRadius: JV.Radius.md - 2, style: .continuous)
                Button { selection = option.id } label: {
                    Text(verbatim: option.label)
                        .font(JV.font(14, selected ? .semibold : .medium))
                        .foregroundStyle(selected ? JV.textPrimary : JV.textSecondary)
                        .lineLimit(1)
                        .fixedSize()
                        .padding(.horizontal, 14)
                        .frame(maxWidth: fill ? .infinity : nil, minHeight: 44)
                        .background(selected ? JV.bgRaised : Color.clear, in: shape)
                        .overlay { if selected { shape.strokeBorder(JV.borderStrong, lineWidth: 1) } }
                        .contentShape(shape)
                }
                .buttonStyle(.plain)
                .accessibilityValue(option.accessibilityValue)
                .accessibilityAddTraits(selected ? .isSelected : [])
            }
        }
        .padding(3)
        .background(JV.bgSunken, in: RoundedRectangle(cornerRadius: JV.Radius.md, style: .continuous))
    }
}

extension SegmentedControl where ID == String {
    /// The views of a page; each segment's status is its accessibility value.
    init(label: String, items: [NodeItem], selection: Binding<String>) {
        self.init(label: label, options: items.map { Option(id: $0.id, label: $0.label, accessibilityValue: $0.status) },
                  selection: selection)
    }

    /// Plain views without a status, as (id, label) pairs.
    init(label: String, pairs: [(String, String)], selection: Binding<String>) {
        self.init(label: label, options: pairs.map { Option(id: $0.0, label: $0.1) }, selection: selection)
    }
}

// MARK: - Panel, tiles, activity

/// Title and status badge of a card; the badge moves under the title when
/// both do not fit on one line.
struct TitleBadgeRow: View {
    let title: String
    let badge: Badge

    var body: some View {
        ViewThatFits(in: .horizontal) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                heading
                Spacer(minLength: 8)
                badge
            }
            VStack(alignment: .leading, spacing: 6) {
                heading
                badge
            }
        }
    }

    private var heading: some View {
        Text(verbatim: title)
            .font(JV.font(18, .semibold, relativeTo: .title3))
            .foregroundStyle(JV.textPrimary)
            .fixedSize(horizontal: false, vertical: true)
            .accessibilityAddTraits(.isHeader)
    }
}

/// Summary card of a page view (D-Memory persona card): title, status badge,
/// description and optional actions.
struct NodePanel<Accessory: View>: View {
    let item: NodeItem
    let accessory: Accessory

    init(item: NodeItem, @ViewBuilder accessory: () -> Accessory) {
        self.item = item
        self.accessory = accessory()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            TitleBadgeRow(title: item.title, badge: Badge(text: item.status, style: item.tone.badge))
            if !item.detail.isEmpty {
                Text(verbatim: item.detail)
                    .font(JV.font(13))
                    .foregroundStyle(JV.textSecondary)
                    .lineSpacing(2)
                    .fixedSize(horizontal: false, vertical: true)
            }
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
                Image(systemName: symbol).font(.system(size: 13, weight: .semibold)).accessibilityHidden(true)
            }
            .frame(maxWidth: .infinity)
        }
        .buttonStyle(JVButtonStyle(kind: .quiet, size: .large))
        .padding(.top, 6)
    }
}

struct TileData: Identifiable {
    let label: String
    let value: String
    var id: String { label }
}

/// Labelled values as small cards (D-Runs figures), two per row; one per row
/// at accessibility text sizes.
struct TileGrid: View {
    let tiles: [TileData]
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        let columns = typeSize.isAccessibilitySize ? 1 : 2
        Grid(horizontalSpacing: 12, verticalSpacing: 12) {
            ForEach(Array(stride(from: 0, to: tiles.count, by: columns)), id: \.self) { start in
                GridRow {
                    ForEach(tiles[start..<min(start + columns, tiles.count)]) { tile in cell(tile) }
                    if start + columns > tiles.count { Color.clear.gridCellUnsizedAxes([.horizontal, .vertical]) }
                }
            }
        }
    }

    private func cell(_ tile: TileData) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(verbatim: tile.label)
                .font(JV.font(13))
                .foregroundStyle(JV.textSecondary)
            Text(verbatim: tile.value)
                .font(JV.number(20, .semibold, relativeTo: .title3))
                .foregroundStyle(JV.textPrimary)
                .lineLimit(3)
                .minimumScaleFactor(0.8)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .jvCard(padding: 16)
        .accessibilityElement(children: .combine)
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
    /// Shows `toneLabel` as a status badge; off where it would repeat on every row.
    var badge = true
    var selected = false
}

/// A row of a list card (.li): tile, title and detail, status badge and time.
/// At accessibility text sizes the badge and time move under the title.
struct ActivityRowView: View {
    let row: ActivityRow
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        let stacked = typeSize.isAccessibilitySize
        HStack(spacing: 12) {
            if !stacked {
                IconBadge(symbol: row.symbol, size: 36, style: .neutral)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: row.title)
                    .font(JV.font(15, .medium))
                    .foregroundStyle(JV.textPrimary)
                    .lineLimit(stacked ? nil : 2)
                if let detail = row.detail, !detail.isEmpty {
                    Text(verbatim: detail)
                        .font(JV.font(12, relativeTo: .caption))
                        .foregroundStyle(JV.textSecondary)
                        .lineLimit(stacked ? nil : 2)
                }
                if stacked { trailing(alignment: .leading).padding(.top, 4) }
            }
            if !stacked {
                Spacer(minLength: 4)
                trailing(alignment: .trailing)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .frame(maxWidth: .infinity, minHeight: 58, alignment: .leading)
        .background(row.selected ? JV.bgSelected : Color.clear)
        .accessibilityElement(children: .combine)
        // A shown badge is already read as part of the row.
        .accessibilityValue(Text(verbatim: row.badge ? "" : row.toneLabel))
        .accessibilityAddTraits(row.selected ? .isSelected : [])
    }

    private func trailing(alignment: HorizontalAlignment) -> some View {
        VStack(alignment: alignment, spacing: 4) {
            if row.badge { Badge(text: row.toneLabel, style: row.tone.badge) }
            if let time = row.time, !time.isEmpty {
                Text(verbatim: time).font(JV.number(12, relativeTo: .caption)).foregroundStyle(JV.textSecondary)
            }
        }
    }
}

/// List card of activity rows; rows are tappable when `onSelect` is set.
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
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 20)
                    .frame(maxWidth: .infinity, minHeight: 58)
            }
            ForEach(Array(rows.enumerated()), id: \.element.id) { index, row in
                if index > 0 { ListDivider(inset: 0) }
                if let onSelect {
                    Button { onSelect(row) } label: { ActivityRowView(row: row).contentShape(Rectangle()) }
                        .buttonStyle(.plain)
                } else {
                    ActivityRowView(row: row)
                }
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: JV.Radius.lg, style: .continuous))
        .jvCard(padding: 0)
    }
}

/// What Core does not offer yet, as plain rows. Never sample data.
struct LaterList: View {
    let items: [NodeItem]

    var body: some View {
        ListSection(title: "Later") {
            VStack(spacing: 0) {
                ForEach(Array(items.enumerated()), id: \.element.id) { index, item in
                    if index > 0 { ListDivider(inset: 0) }
                    VStack(alignment: .leading, spacing: 2) {
                        Text(verbatim: item.title).font(JV.font(15, .medium)).foregroundStyle(JV.textPrimary)
                        Text(verbatim: item.detail)
                            .font(JV.font(13))
                            .foregroundStyle(JV.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .frame(maxWidth: .infinity, minHeight: 52, alignment: .leading)
                    .accessibilityElement(children: .combine)
                    .accessibilityValue("Not yet available")
                }
            }
            .jvCard(padding: 0)
        }
    }
}

// MARK: - Empty, loading and error

/// Empty, unavailable and error states (F-Components). Empty and unavailable
/// are a centred card; an error names the read, says why and offers Try again
/// where the page can repeat its own read. Never sample data.
struct UnavailableState: View {
    enum Kind { case empty, planned, coreUpdate, error }

    struct Action {
        let label: String
        let run: () async -> Void
    }

    let title: String
    var kind: Kind = .planned
    var detail: String? = nil
    var symbol = "sparkles"
    var action: Action? = nil
    @State private var running = false

    var body: some View {
        if kind == .error { errorCard } else { emptyCard }
    }

    private var emptyCard: some View {
        VStack(spacing: 10) {
            VStack(spacing: 10) {
                IconBadge(symbol: symbol, size: 36)
                Text(verbatim: title)
                    .font(JV.font(15, .semibold))
                    .foregroundStyle(JV.textPrimary)
                    .accessibilityAddTraits(.isHeader)
                if let label = kindLabel { Badge(text: label, dot: false) }
                if let detail {
                    Text(verbatim: detail)
                        .font(JV.font(13))
                        .foregroundStyle(JV.textSecondary)
                        .lineSpacing(2)
                }
            }
            .multilineTextAlignment(.center)
            .fixedSize(horizontal: false, vertical: true)
            .accessibilityElement(children: .combine)
            actionButton
        }
        .padding(.horizontal, 24)
        .padding(.vertical, 32)
        .frame(maxWidth: .infinity)
        .jvCard(padding: 0)
    }

    private var errorCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            VStack(alignment: .leading, spacing: 8) {
                HStack(spacing: 10) {
                    IconBadge(symbol: "exclamationmark.triangle", size: 36, style: .negative)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(verbatim: title)
                            .font(JV.font(15, .semibold))
                            .foregroundStyle(JV.textPrimary)
                            .accessibilityAddTraits(.isHeader)
                        Text("Could not load").font(JV.font(13, .medium)).foregroundStyle(JV.dangerText)
                    }
                }
                if let detail {
                    Text(verbatim: detail)
                        .font(JV.font(13))
                        .foregroundStyle(JV.textSecondary)
                        .lineSpacing(2)
                }
            }
            .fixedSize(horizontal: false, vertical: true)
            .accessibilityElement(children: .combine)
            actionButton
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .jvCard(padding: 16)
    }

    @ViewBuilder private var actionButton: some View {
        if let action {
            Button(action.label) {
                Task {
                    running = true
                    await action.run()
                    running = false
                }
            }
            .buttonStyle(JVButtonStyle(kind: .secondary, size: .large))
            .disabled(running)
        }
    }

    private var kindLabel: String? {
        switch kind {
        case .empty, .error: nil
        case .planned: "Not yet available"
        case .coreUpdate: "Requires newer Core"
        }
    }
}

extension UnavailableState {
    /// State for a read that did not succeed; `nil` while it loads or after
    /// success. `retry` repeats the page's own read.
    static func forSource<T>(_ source: Availability<T>, title: String, symbol: String = "sparkles",
                             forbidden: String = "Turned off in Core.",
                             retry: (() async -> Void)? = nil) -> UnavailableState? {
        switch source {
        case .unsupported:
            return UnavailableState(title: title, kind: .coreUpdate, detail: "Update Core to see this here.", symbol: symbol)
        case let .error(reason):
            return UnavailableState(title: title, kind: .error, detail: failureText(reason, forbidden: forbidden), symbol: symbol,
                                    action: retry.map { Action(label: "Try again", run: $0) })
        case .loading, .ok:
            return nil
        }
    }
}

/// One placeholder row (.skel): sunken bars the size of the row that replaces it.
struct SkeletonRow: View {
    var body: some View {
        HStack(spacing: 12) {
            bar(width: 36, height: 36, radius: JV.Radius.md)
            VStack(alignment: .leading, spacing: 8) {
                bar(width: 150, height: 14)
                bar(width: 96, height: 12)
            }
            Spacer(minLength: 0)
        }
    }

    private func bar(width: CGFloat, height: CGFloat, radius: CGFloat = JV.Radius.sm) -> some View {
        RoundedRectangle(cornerRadius: radius, style: .continuous).fill(JV.bgSunken).frame(width: width, height: height)
    }
}

/// Loading state: a list card of placeholder rows that pulses gently and holds
/// still with Reduce Motion. VoiceOver reads `label`.
struct LoadingState: View {
    let label: String
    var rows = 3
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var dim = false

    var body: some View {
        VStack(spacing: 0) {
            ForEach(0..<rows, id: \.self) { index in
                if index > 0 { ListDivider(inset: 0) }
                SkeletonRow()
                    .padding(.horizontal, 16)
                    .padding(.vertical, 11)
                    .frame(minHeight: 58)
            }
        }
        .opacity(dim ? 0.55 : 1)
        .jvCard(padding: 0)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(label)
        .onAppear {
            guard !reduceMotion else { return }
            withAnimation(.easeInOut(duration: 0.7).repeatForever(autoreverses: true)) { dim = true }
        }
    }
}

// MARK: - Pages

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

/// A pushed page: title and line in the bar, then the page's cards and lists.
/// The page scrolls back to the top when `top` changes.
struct PageScaffold<Content: View>: View {
    let title: String
    let subtitle: String
    let top: AnyHashable?
    let content: Content
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    init(title: String, subtitle: String, top: AnyHashable? = nil, @ViewBuilder content: () -> Content) {
        self.title = title
        self.subtitle = subtitle
        self.top = top
        self.content = content()
    }

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    content
                }
                .id(Self.topID)
                .padding(.horizontal, 16)
                .padding(.top, 12)
                .padding(.bottom, 32)
            }
            .onChange(of: top) { _, _ in
                withAnimation(reduceMotion ? nil : JV.Motion.easeOut(JV.Motion.enter)) { proxy.scrollTo(Self.topID, anchor: .top) }
            }
        }
        .background(JV.bgPage)
        .jarvisNavigationBar(title: title, subtitle: subtitle)
    }

    private static var topID: String { "page-top" }
}

extension View {
    /// Navigation bar with a sentence-case title and optional line. Settings is
    /// a tab, so pages carry no profile button.
    func jarvisNavigationBar(title: String, subtitle: String? = nil) -> some View {
        navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(JV.bgPage, for: .navigationBar)
            .toolbarBackground(.visible, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .principal) {
                    VStack(spacing: 1) {
                        Text(verbatim: title)
                            .font(JV.font(17, .semibold, relativeTo: .headline))
                            .foregroundStyle(JV.textPrimary)
                        if let subtitle {
                            Text(verbatim: subtitle)
                                .font(JV.font(12, relativeTo: .caption))
                                .foregroundStyle(JV.textSecondary)
                        }
                    }
                    .lineLimit(1)
                    .accessibilityElement(children: .combine)
                    .accessibilityAddTraits(.isHeader)
                }
            }
    }

    /// Instrument page behind a system List or Form: page background, surface
    /// rows (set per section with `listRowBackground`), sentence-case headers.
    func instrumentList() -> some View {
        scrollContentBackground(.hidden)
            .background(JV.bgPage)
            .textCase(nil)
            .toolbarBackground(JV.bgPage, for: .navigationBar)
            .toolbarBackground(.visible, for: .navigationBar)
    }
}

/// Section header of a system List in Plex.
@MainActor func listHeader(_ text: String) -> some View {
    Text(verbatim: text).font(JV.font(13, .medium, relativeTo: .footnote)).foregroundStyle(JV.textSecondary)
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
