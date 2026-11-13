import Foundation

// Roadmaps (/v1/roadmaps): Core's contract, response checks, input rules, GitHub link
// parsing and the display helpers, as in desktop roadmaps.ts. Every write names the
// revision it builds on (?expected_revision=N); Core answers 409 when the roadmap moved
// on, and the page then reloads. Owner edits never set an item's auto_status or a link's
// cached GitHub state; both are display only.

enum RoadmapStatus: String, Codable, CaseIterable {
    case live, ready
    case inProgress = "in_progress"
    case planned, blocked

    var label: String {
        switch self {
        case .live: "Live"
        case .ready: "Ready"
        case .inProgress: "In progress"
        case .planned: "Planned"
        case .blocked: "Blocked"
        }
    }

    /// A dependency is met once it is ready (merged) or live.
    var isDone: Bool { self == .live || self == .ready }
}

struct RoadmapLink: Codable, Equatable {
    enum Kind: String, Codable { case pr, issue, release, url }

    var kind: Kind
    var repo: String? = nil
    var number: Int? = nil
    var tag: String? = nil
    var url: String? = nil
    /// The state GitHub last reported, as Core cached it; display only, never sent.
    var state: String? = nil

    func sameTarget(_ other: RoadmapLink) -> Bool {
        kind == other.kind && repo == other.repo && number == other.number && tag == other.tag && url == other.url
    }
}

extension RoadmapLink {
    private enum Keys: String, CodingKey { case kind, repo, number, tag, url, state }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: Keys.self)
        kind = try container.decode(Kind.self, forKey: .kind)
        repo = try container.decodeIfPresent(String.self, forKey: .repo)
        number = try container.decodeIfPresent(Int.self, forKey: .number)
        tag = try container.decodeIfPresent(String.self, forKey: .tag)
        url = try container.decodeIfPresent(String.self, forKey: .url)
        state = (try? container.decodeIfPresent(LenientText.self, forKey: .state))?.value.flatMap { $0.count <= 40 ? $0 : nil }
    }

    /// A link as Core accepts it: the cached `state` is Core's, never sent.
    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: Keys.self)
        try container.encode(kind, forKey: .kind)
        try container.encodeIfPresent(repo, forKey: .repo)
        try container.encodeIfPresent(number, forKey: .number)
        try container.encodeIfPresent(tag, forKey: .tag)
        try container.encodeIfPresent(url, forKey: .url)
    }
}

struct Roadmap: Decodable, Identifiable, Equatable {
    let id: UUID
    let title: String
    /// Display only: anything but a string reads as none.
    let description: String?
    let phases: [String]
    let revision: Int
    let archived_at: String?

    private enum Keys: String, CodingKey { case id, title, description, phases, revision, archived_at }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: Keys.self)
        id = try container.decode(UUID.self, forKey: .id)
        title = try container.decode(String.self, forKey: .title)
        description = (try? container.decodeIfPresent(LenientText.self, forKey: .description))?.value
        phases = try container.decode([String].self, forKey: .phases)
        revision = try container.decode(Int.self, forKey: .revision)
        archived_at = try container.decodeIfPresent(String.self, forKey: .archived_at)
    }
}

struct RoadmapItem: Decodable, Identifiable, Equatable {
    let id: String
    let title: String
    let status: RoadmapStatus
    /// What GitHub last derived; display only, an unknown value reads as none.
    var auto_status: RoadmapStatus? = nil
    var phase: String? = nil
    var position = 0
    var notes: String? = nil
    var depends_on: [String] = []
    var links: [RoadmapLink] = []
}

extension RoadmapItem {
    private enum Keys: String, CodingKey { case id, title, status, auto_status, phase, position, notes, depends_on, links }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: Keys.self)
        id = try container.decode(String.self, forKey: .id)
        title = try container.decode(String.self, forKey: .title)
        status = try container.decode(RoadmapStatus.self, forKey: .status)
        auto_status = (try? container.decodeIfPresent(RoadmapStatus.self, forKey: .auto_status)) ?? nil
        phase = try container.decodeIfPresent(String.self, forKey: .phase)
        position = try container.decode(Int.self, forKey: .position)
        notes = try container.decodeIfPresent(String.self, forKey: .notes)
        depends_on = try container.decode([String].self, forKey: .depends_on)
        links = try container.decode([RoadmapLink].self, forKey: .links)
    }
}

struct RoadmapSnapshot: Decodable, Equatable {
    let roadmap: Roadmap
    let items: [RoadmapItem]
}

struct RoadmapList: Decodable { let roadmaps: [Roadmap] }

struct NewRoadmap: Encodable, Equatable {
    let title: String
    let description: String?
    let phases: [String]
}

/// The roadmap's own fields; absent ones are left as they are, a nil description clears it.
struct RoadmapPatch: Encodable, Equatable {
    var title: String?
    var description: String??
    var phases: [String]?

    private enum Keys: String, CodingKey { case title, description, phases }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: Keys.self)
        try container.encodeIfPresent(title, forKey: .title)
        if let description {
            if let description { try container.encode(description, forKey: .description) } else { try container.encodeNil(forKey: .description) }
        }
        try container.encodeIfPresent(phases, forKey: .phases)
    }
}

/// The new roadmap's id from Core's answer to a create; nil when it cannot be read.
struct CreatedRoadmap: Decodable {
    let id: UUID?

    private enum Keys: String, CodingKey { case roadmap }
    private struct Inner: Decodable { let id: UUID }

    init(from decoder: Decoder) throws {
        id = (try? decoder.container(keyedBy: Keys.self).decode(Inner.self, forKey: .roadmap))?.id
    }
}

/// Item fields in a Jarvis proposal, with which of them it sets (null included).
struct RoadmapFields: Decodable {
    enum Field: String, CaseIterable { case title, status, phase, position, notes, depends_on, links }

    let present: Set<Field>
    let title: String?
    let status: RoadmapStatus?
    let phase: String?
    let position: Int?
    let notes: String?
    let depends_on: [String]?
    let links: [RoadmapLink]?

    private struct Keys: CodingKey {
        let stringValue: String
        var intValue: Int? { nil }
        init(_ field: Field) { stringValue = field.rawValue }
        init?(stringValue: String) { self.stringValue = stringValue }
        init?(intValue: Int) { nil }
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: Keys.self)
        present = Set(Field.allCases.filter { container.contains(Keys($0)) })
        title = try container.decodeIfPresent(String.self, forKey: Keys(.title))
        status = try container.decodeIfPresent(RoadmapStatus.self, forKey: Keys(.status))
        phase = try container.decodeIfPresent(String.self, forKey: Keys(.phase))
        position = try container.decodeIfPresent(Int.self, forKey: Keys(.position))
        notes = try container.decodeIfPresent(String.self, forKey: Keys(.notes))
        depends_on = try container.decodeIfPresent([String].self, forKey: Keys(.depends_on))
        links = try container.decodeIfPresent([RoadmapLink].self, forKey: Keys(.links))
    }
}

/// Changes Jarvis proposes; the owner accepts or rejects them as a whole.
struct RoadmapProposal: Decodable, Identifiable {
    enum Op: Decodable {
        case create(RoadmapFields)
        case update(itemId: String, patch: RoadmapFields)

        private enum Keys: String, CodingKey { case op, item, item_id, patch }

        init(from decoder: Decoder) throws {
            let container = try decoder.container(keyedBy: Keys.self)
            switch try container.decode(String.self, forKey: .op) {
            case "create_item": self = .create(try container.decode(RoadmapFields.self, forKey: .item))
            case "update_item":
                self = .update(itemId: try container.decode(String.self, forKey: .item_id), patch: try container.decode(RoadmapFields.self, forKey: .patch))
            default: throw DecodingError.dataCorruptedError(forKey: .op, in: container, debugDescription: "unknown op")
            }
        }
    }

    let id: String
    let base_revision: Int
    let ops: [Op]
    let rationale: String?
    let created_at: String
    let expires_at: String
}

struct RoadmapProposals: Decodable { let proposals: [RoadmapProposal] }

/// One line of the roadmap's history.
struct RoadmapChange: Decodable, Identifiable {
    enum Author: String, Decodable {
        case owner, jarvis, github
        var label: String {
            switch self {
            case .owner: "You"
            case .jarvis: "Jarvis"
            case .github: "GitHub"
            }
        }
    }

    let id: String
    let revision: Int
    let at: String
    let author: Author
    let device_id: String?
    let proposal_id: String?
    let action: String
    let item_id: String?
    let title: String
    let fields: [String]
    let from_status: RoadmapStatus?
    let to_status: RoadmapStatus?
}

struct RoadmapHistory: Decodable { let changes: [RoadmapChange] }

struct NewRoadmapItem: Encodable, Equatable {
    let title: String
    let phase: String?
}

/// Only what changed; absent fields are left as they are. `.some(nil)` clears a phase
/// or the notes.
struct RoadmapItemPatch: Encodable, Equatable {
    var title: String?
    var status: RoadmapStatus?
    var phase: String??
    var notes: String??
    var depends_on: [String]?
    var links: [RoadmapLink]?

    private enum Keys: String, CodingKey { case title, status, phase, notes, depends_on, links }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: Keys.self)
        try container.encodeIfPresent(title, forKey: .title)
        try container.encodeIfPresent(status, forKey: .status)
        try Self.encode(phase, .phase, &container)
        try Self.encode(notes, .notes, &container)
        try container.encodeIfPresent(depends_on, forKey: .depends_on)
        try container.encodeIfPresent(links, forKey: .links)
    }

    /// Absent: left out; `.some(nil)`: an explicit null.
    private static func encode(_ value: String??, _ key: Keys, _ container: inout KeyedEncodingContainer<Keys>) throws {
        guard let value else { return }
        if let value { try container.encode(value, forKey: key) } else { try container.encodeNil(forKey: key) }
    }
}

/// The item sheet's editable fields.
struct RoadmapItemDraft: Equatable {
    var title: String
    var status: RoadmapStatus
    var phase: String?
    var notes: String
    var depends_on: [String]
    var links: [RoadmapLink]

    init(_ item: RoadmapItem) {
        title = item.title
        status = item.status
        phase = item.phase
        notes = item.notes ?? ""
        depends_on = item.depends_on
        links = item.links
    }
}

enum ParsedLink: Equatable {
    case link(RoadmapLink)
    case invalid(String)
}

enum Roadmaps {
    static let maxRoadmaps = 20
    static let maxItems = 300
    static let maxLinks = 10
    static let maxDependsOn = 20
    static let defaultPhases = ["Now", "Next", "Later"]
    static let changedElsewhere = "Changed elsewhere, reloaded."
    static let notConfirmed = "Not confirmed; reloaded to check."

    static func path(_ id: UUID) -> String { "/v1/roadmaps/\(id.uuidString.lowercased())" }
    static func historyPath(_ id: UUID) -> String { "\(path(id))/history?limit=50" }
    static func proposalsPath(_ id: UUID) -> String { "\(path(id))/proposals" }


    // MARK: Response checks: anything unexpected reads as failed (fail closed).

    private static func fits(_ text: String?, _ max: Int) -> Bool { (text?.count ?? 0) <= max }

    static func valid(_ link: RoadmapLink) -> Bool {
        fits(link.repo, 200) && fits(link.tag, 200) && fits(link.url, 500) && (link.number ?? 0) >= 0
    }

    static func valid(_ roadmap: Roadmap) -> Bool {
        roadmap.title.count <= 1000 && fits(roadmap.description, 10_000) && roadmap.phases.count <= 20
            && roadmap.phases.allSatisfy { $0.count <= 200 } && roadmap.revision >= 0 && fits(roadmap.archived_at, 64)
    }

    static func valid(_ item: RoadmapItem) -> Bool {
        isSafePathSegment(item.id) && item.id.count <= 64 && item.title.count <= 1000 && fits(item.phase, 200) && item.position >= 0 && fits(item.notes, 20_000)
            && item.depends_on.count <= maxDependsOn && item.depends_on.allSatisfy { $0.count <= 200 }
            && item.links.count <= maxLinks && item.links.allSatisfy(valid)
    }

    static func valid(_ list: RoadmapList) -> Bool { list.roadmaps.count <= 200 && list.roadmaps.allSatisfy(valid) }

    static func valid(_ snapshot: RoadmapSnapshot) -> Bool {
        valid(snapshot.roadmap) && snapshot.items.count <= maxItems && snapshot.items.allSatisfy(valid)
    }

    // MARK: Input rules (Core enforces the same; these only explain them early)

    /// Core's no_hidden_chars denylist: control, invisible and bidi code points.
    private static func isHidden(_ scalar: Unicode.Scalar) -> Bool {
        if scalar.properties.generalCategory == .control { return true }
        switch scalar.value {
        case 0xAD, 0x34F, 0x61C, 0x115F, 0x1160, 0x180E, 0x200B...0x200F, 0x2028...0x202E, 0x2060...0x2069, 0x3164,
             0xFE00...0xFE0F, 0xFEFF, 0xFFF9...0xFFFB, 0xE0000...0xE007F: return true
        default: return false
        }
    }

    /// Non-blank, at most `max` characters, no hidden characters; multi-line text may also
    /// hold line breaks and tabs.
    static func validText(_ value: String, max: Int, multiline: Bool = false) -> Bool {
        guard !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, value.unicodeScalars.count <= max else { return false }
        return value.unicodeScalars.allSatisfy { (multiline && ($0 == "\n" || $0 == "\t")) || !isHidden($0) }
    }

    /// Phases typed one per line: trimmed, blank lines dropped. Nil when they break Core's
    /// rule (at most 20 distinct names of 1 to 60 characters).
    static func parsePhases(_ text: String) -> [String]? {
        let phases = text.components(separatedBy: "\n").map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty }
        guard phases.count <= 20, Set(phases).count == phases.count, phases.allSatisfy({ validText($0, max: 60) }) else { return nil }
        return phases
    }

    /// Why a new roadmap cannot be created yet; nil when Core should accept it.
    static func newRoadmapIssue(title: String, description: String, phases: String) -> String? {
        if !validText(title.trimmingCharacters(in: .whitespacesAndNewlines), max: 120) { return "The title must be 1 to 120 characters on one line." }
        if !description.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !validText(description, max: 2000, multiline: true) {
            return "The description must be at most 2000 characters."
        }
        if parsePhases(phases) == nil { return "Phases: at most 20 different names of 1 to 60 characters, one per line." }
        return nil
    }

    static func validRepo(_ repo: String) -> Bool {
        repo.wholeMatch(of: #/[A-Za-z0-9][A-Za-z0-9\-]{0,38}\/[A-Za-z0-9._\-]{1,100}/#) != nil
            && !repo.hasSuffix("/.") && !repo.hasSuffix("/..")
    }

    private static func validTag(_ tag: String) -> Bool { tag.wholeMatch(of: #/[A-Za-z0-9._+\/\-]{1,100}/#) != nil }
    private static func validNumber(_ number: Int?) -> Bool { number.map { (1...1_000_000_000).contains($0) } ?? false }

    /// An absolute https URL with a host, no credentials, printable ASCII.
    static func validHttpsUrl(_ value: String) -> Bool {
        guard value.utf16.count <= 500, value.hasPrefix("https://"), value.count > 8,
              value.unicodeScalars.allSatisfy({ (0x21...0x7E).contains($0.value) }),
              let url = URLComponents(string: value), url.scheme == "https", let host = url.host, !host.isEmpty,
              url.user == nil, url.password == nil else { return false }
        return true
    }

    /// One link from what the owner typed or pasted. A GitHub URL names its own kind (pull →
    /// pr, issues → issue, releases/tag → release); `owner/name#12` becomes the `hint` kind
    /// (pr or issue) and `owner/name@v1.2.0` a release. Anything else must be an https URL.
    static func parseLink(_ input: String, hint: RoadmapLink.Kind = .pr) -> ParsedLink {
        let text = input.trimmingCharacters(in: .whitespacesAndNewlines)
        if let match = text.wholeMatch(of: #/https:\/\/github\.com\/([^\/?#]+\/[^\/?#]+)\/(pull|issues|releases\/tag)\/([^?#]+?)\/?(?:[?#].*)?/#) {
            let repo = String(match.output.1)
            let rest = String(match.output.3)
            if match.output.2 == "releases/tag" {
                guard let tag = rest.removingPercentEncoding, validRepo(repo), validTag(tag) else { return .invalid("That release link is not valid.") }
                return .link(RoadmapLink(kind: .release, repo: repo, tag: tag))
            }
            let number = rest.wholeMatch(of: #/([0-9]+)(?:\/.*)?/#).flatMap { Int($0.output.1) }
            guard validRepo(repo), validNumber(number) else { return .invalid("That GitHub link is not valid.") }
            return .link(RoadmapLink(kind: match.output.2 == "pull" ? .pr : .issue, repo: repo, number: number))
        }
        if let short = text.wholeMatch(of: #/([^#@\s]+)#([0-9]+)/#), validRepo(String(short.output.1)), validNumber(Int(short.output.2)) {
            return .link(RoadmapLink(kind: hint, repo: String(short.output.1), number: Int(short.output.2)))
        }
        if let release = text.wholeMatch(of: #/([^#@\s]+)@(\S+)/#), validRepo(String(release.output.1)), validTag(String(release.output.2)) {
            return .link(RoadmapLink(kind: .release, repo: String(release.output.1), tag: String(release.output.2)))
        }
        if text.lowercased().hasPrefix("http://") { return .invalid("Only https links are allowed.") }
        if validHttpsUrl(text) { return .link(RoadmapLink(kind: .url, url: text)) }
        return .invalid("Paste a GitHub link, owner/name#12, owner/name@v1.2.0 or an https URL.")
    }

    static func linkLabel(_ link: RoadmapLink) -> String {
        switch link.kind {
        case .url: link.url ?? ""
        case .release: "\(link.repo ?? "")@\(link.tag ?? "")"
        case .pr, .issue: "\(link.repo ?? "")#\(link.number.map(String.init) ?? "")"
        }
    }

    /// encodeURIComponent's unreserved characters.
    private static let uriComponent = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'()")

    /// Where a link opens; nil when it does not hold an https target.
    static func linkHref(_ link: RoadmapLink) -> String? {
        let base = "https://github.com/\(link.repo ?? "")"
        switch link.kind {
        case .pr: return link.number.map { "\(base)/pull/\($0)" }
        case .issue: return link.number.map { "\(base)/issues/\($0)" }
        case .release:
            let tag = (link.tag ?? "").components(separatedBy: "/")
                .map { $0.addingPercentEncoding(withAllowedCharacters: uriComponent) ?? "" }.joined(separator: "/")
            return "\(base)/releases/tag/\(tag)"
        case .url: return link.url.flatMap { validHttpsUrl($0) ? $0 : nil }
        }
    }

    static func addLink(_ links: [RoadmapLink], _ link: RoadmapLink) -> [RoadmapLink]? {
        guard links.count < maxLinks, !links.contains(where: { $0.sameTarget(link) }) else { return nil }
        return links + [link]
    }

    // MARK: Display

    struct StatusCount: Equatable { let status: RoadmapStatus; let count: Int }

    /// Items per status, in status order, statuses without items left out.
    static func tally(_ items: [RoadmapItem]) -> [StatusCount] {
        RoadmapStatus.allCases.map { status in StatusCount(status: status, count: items.filter { $0.status == status }.count) }
            .filter { $0.count > 0 }
    }

    private static func byPosition(_ a: RoadmapItem, _ b: RoadmapItem) -> Bool {
        a.position != b.position ? a.position < b.position : a.title.localizedCompare(b.title) == .orderedAscending
    }

    struct PhaseGroup { let phase: String?; let items: [RoadmapItem] }

    /// The rail: one group per roadmap phase in its order, then items without a (known)
    /// phase. Empty phases stay, so the rail shows the whole plan.
    static func groupByPhase(_ phases: [String], _ items: [RoadmapItem]) -> [PhaseGroup] {
        let groups = phases.map { phase in PhaseGroup(phase: phase, items: items.filter { $0.phase == phase }.sorted(by: byPosition)) }
        let rest = items.filter { $0.phase.map { !phases.contains($0) } ?? true }.sorted(by: byPosition)
        return rest.isEmpty ? groups : groups + [PhaseGroup(phase: nil, items: rest)]
    }

    struct Waiting { let item: RoadmapItem; let unmet: [RoadmapItem] }

    /// What holds the plan up: blocked items, and open items with an unmet dependency.
    /// Done items wait on nothing.
    static func waitingOn(_ items: [RoadmapItem]) -> [Waiting] {
        let byId = Dictionary(items.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        return items.filter { !$0.status.isDone }
            .map { item in Waiting(item: item, unmet: item.depends_on.compactMap { byId[$0] }.filter { !$0.status.isDone }) }
            .filter { $0.item.status == .blocked || !$0.unmet.isEmpty }
            .sorted { byPosition($0.item, $1.item) }
    }

    /// Items `id` may depend on: not itself and none that already depends on it, directly
    /// or through others (Core refuses cycles).
    static func dependencyOptions(_ items: [RoadmapItem], _ id: String?) -> [RoadmapItem] {
        guard let id else { return items.sorted(by: byPosition) }
        var dependents: Set<String> = [id]
        var grew = true
        while grew {
            grew = false
            for item in items where !dependents.contains(item.id) && item.depends_on.contains(where: { dependents.contains($0) }) {
                dependents.insert(item.id)
                grew = true
            }
        }
        return items.filter { !dependents.contains($0.id) }.sorted(by: byPosition)
    }

    /// The first non-blank line of an item's notes.
    static func firstLine(_ text: String?) -> String {
        text?.components(separatedBy: "\n").first { !$0.trimmingCharacters(in: .whitespaces).isEmpty } ?? ""
    }

    /// Only what changed, so an unchanged sheet sends nothing (nil). Empty notes clear them.
    static func patch(_ item: RoadmapItem, _ draft: RoadmapItemDraft) -> RoadmapItemPatch? {
        var patch = RoadmapItemPatch()
        let title = draft.title.trimmingCharacters(in: .whitespacesAndNewlines)
        if title != item.title { patch.title = title }
        if draft.status != item.status { patch.status = draft.status }
        if draft.phase != item.phase { patch.phase = .some(draft.phase) }
        let notes = draft.notes.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : draft.notes
        if notes != item.notes { patch.notes = .some(notes) }
        if draft.depends_on != item.depends_on { patch.depends_on = draft.depends_on }
        let sameLinks = draft.links.count == item.links.count && zip(draft.links, item.links).allSatisfy { $0.sameTarget($1) }
        if !sameLinks { patch.links = draft.links }
        return patch == RoadmapItemPatch() ? nil : patch
    }

    /// Why a draft cannot be saved yet; nil when Core should accept it.
    static func draftIssue(_ draft: RoadmapItemDraft) -> String? {
        if !validText(draft.title.trimmingCharacters(in: .whitespacesAndNewlines), max: 200) {
            return "The title must be 1 to 200 characters on one line."
        }
        if !draft.notes.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !validText(draft.notes, max: 4000, multiline: true) {
            return "Notes must be at most 4000 characters, without hidden characters."
        }
        return nil
    }

    // MARK: Proposals and history

    private static func valid(_ fields: RoadmapFields) -> Bool {
        fits(fields.title, 1000) && fits(fields.phase, 200) && (fields.position ?? 0) >= 0 && fits(fields.notes, 20_000)
            && (fields.depends_on.map { $0.count <= maxDependsOn && $0.allSatisfy { $0.count <= 200 } } ?? true)
            && (fields.links.map { $0.count <= maxLinks && $0.allSatisfy(valid) } ?? true)
    }

    static func valid(_ list: RoadmapProposals) -> Bool {
        list.proposals.count <= 100 && list.proposals.allSatisfy { p in
            isSafePathSegment(p.id) && p.id.count <= 64 && p.base_revision >= 0 && p.ops.count <= 20 && fits(p.rationale, 4000)
                && p.created_at.count <= 64 && p.expires_at.count <= 64 && p.ops.allSatisfy { op in
                    switch op {
                    case let .create(item): return item.title != nil && valid(item)
                    case let .update(itemId, patch): return itemId.count <= 64 && valid(patch)
                    }
                }
        }
    }

    static func valid(_ history: RoadmapHistory) -> Bool {
        history.changes.count <= 100 && history.changes.allSatisfy { c in
            c.id.count <= 64 && c.revision >= 0 && c.at.count <= 64 && fits(c.device_id, 64) && fits(c.proposal_id, 64)
                && c.action.count <= 40 && fits(c.item_id, 64) && c.title.count <= 1000 && c.fields.count <= 20 && c.fields.allSatisfy { $0.count <= 200 }
        }
    }

    private static func fieldLabel(_ field: RoadmapFields.Field) -> String {
        switch field {
        case .title: "Title"
        case .status: "Status"
        case .phase: "Phase"
        case .position: "Position"
        case .notes: "Notes"
        case .depends_on: "Depends on"
        case .links: "Links"
        }
    }

    struct DiffRow: Equatable { let field: String; let before: String; let after: String }
    struct OpView: Equatable { let create: Bool; let title: String; let rows: [DiffRow] }

    /// One proposal operation as before → after rows, in the roadmap's words.
    static func describe(_ op: RoadmapProposal.Op, items: [RoadmapItem]) -> OpView {
        let none = "—"
        let byId = Dictionary(items.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        func text(_ value: String?) -> String { value.flatMap { $0.isEmpty ? nil : $0 } ?? none }
        func deps(_ ids: [String]?) -> String { ids.flatMap { $0.isEmpty ? nil : $0.map { byId[$0]?.title ?? "Unknown item" }.joined(separator: ", ") } ?? none }
        func links(_ list: [RoadmapLink]?) -> String { list.flatMap { $0.isEmpty ? nil : $0.map(linkLabel).joined(separator: ", ") } ?? none }
        func fieldValue(_ field: RoadmapFields.Field, _ f: RoadmapFields) -> String {
            switch field {
            case .title: text(f.title)
            case .status: f.status?.label ?? none
            case .phase: text(f.phase)
            case .position: f.position.map(String.init) ?? none
            case .notes: text(f.notes)
            case .depends_on: deps(f.depends_on)
            case .links: links(f.links)
            }
        }
        func itemValue(_ field: RoadmapFields.Field, _ item: RoadmapItem) -> String {
            switch field {
            case .title: text(item.title)
            case .status: item.status.label
            case .phase: text(item.phase)
            case .position: String(item.position)
            case .notes: text(item.notes)
            case .depends_on: deps(item.depends_on)
            case .links: links(item.links)
            }
        }
        switch op {
        case let .create(item):
            let rows = RoadmapFields.Field.allCases.filter { $0 != .title && $0 != .position && ($0 == .status || item.present.contains($0)) }
                .map { field in
                    DiffRow(field: fieldLabel(field), before: none, after: field == .status ? (item.status ?? .planned).label : fieldValue(field, item))
                }
            return OpView(create: true, title: item.title ?? "", rows: rows)
        case let .update(itemId, patch):
            let current = byId[itemId]
            let rows = RoadmapFields.Field.allCases.filter { patch.present.contains($0) }
                .map { field in DiffRow(field: fieldLabel(field), before: current.map { itemValue(field, $0) } ?? none, after: fieldValue(field, patch)) }
            return OpView(create: false, title: current?.title ?? "An item that no longer exists", rows: rows)
        }
    }

    private static let actionLabels = [
        "roadmap_created": "Created the roadmap", "roadmap_updated": "Changed the roadmap", "roadmap_archived": "Archived the roadmap",
        "item_created": "Added", "item_updated": "Changed", "item_deleted": "Deleted",
    ]

    /// One history line, e.g. `Changed “Sync”: status Planned → Ready`.
    static func describe(_ change: RoadmapChange) -> String {
        let action = actionLabels[change.action] ?? "Changed"
        let subject = change.item_id != nil ? "\(action) “\(change.title)”" : action
        let status = change.from_status.flatMap { from in change.to_status.map { "status \(from.label) → \($0.label)" } }
        let others = change.fields.filter { !(status != nil && $0 == "status") }.map { $0.replacingOccurrences(of: "_", with: " ") }
        let detail = ([status].compactMap { $0 } + others).joined(separator: ", ")
        return !detail.isEmpty && change.action.hasSuffix("updated") ? "\(subject): \(detail)" : subject
    }

    /// Why a roadmap edit cannot be saved yet, or the patch (nil: nothing changed).
    static func roadmapPatch(_ roadmap: Roadmap, title: String, description: String, phases: String) -> Result<RoadmapPatch?, PatchIssue> {
        let newTitle = title.trimmingCharacters(in: .whitespacesAndNewlines)
        let newDescription: String? = description.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : description
        if !validText(newTitle, max: 120) { return .failure(PatchIssue("The title must be 1 to 120 characters on one line.")) }
        if let newDescription, !validText(newDescription, max: 2000, multiline: true) {
            return .failure(PatchIssue("The description must be at most 2000 characters."))
        }
        guard let parsed = parsePhases(phases) else {
            return .failure(PatchIssue("Phases: at most 20 different names of 1 to 60 characters, one per line."))
        }
        var patch = RoadmapPatch()
        if newTitle != roadmap.title { patch.title = newTitle }
        if newDescription != roadmap.description { patch.description = .some(newDescription) }
        if parsed != roadmap.phases { patch.phases = parsed }
        return .success(patch == RoadmapPatch() ? nil : patch)
    }

    struct PatchIssue: Error, Equatable {
        let text: String
        init(_ text: String) { self.text = text }
    }

    /// Phases being removed that still hold items; their items move to No phase.
    static func removedPhases(_ roadmap: Roadmap, items: [RoadmapItem], phases: String) -> [String] {
        let next = parsePhases(phases) ?? []
        return roadmap.phases.filter { phase in !next.contains(phase) && items.contains { $0.phase == phase } }
    }

    private static let timeFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_GB")
        formatter.dateFormat = "d MMM, HH:mm"
        return formatter
    }()

    /// "5 Nov, 10:42" in this device's time zone.
    static func timeText(_ iso: String) -> String { parseCoreDate(iso).map(timeFormatter.string(from:)) ?? "an unknown time" }

    /// The notice after a refused accept or reject.
    static func decisionFailureText(_ error: Error) -> String {
        if Jobs.status(error) == 409 { return "Not applied: the roadmap changed since Jarvis proposed this, or a limit is reached. Reloaded." }
        if let known = error as? JarvisAPIError, known == .invalidResponse || known == .responseTooLarge { return notConfirmed }
        return "Not done. \(failureText(error))"
    }

    // MARK: Failures, in desktop's words

    static func failureText(_ error: Error) -> String {
        if Jobs.isNetwork(error) { return "No answer from your Home Node. Check the connection." }
        switch Jobs.status(error) {
        case 401: return "Your session is not valid. Sign in again from Settings."
        case 404: return "This roadmap does not exist on your Home Node (or this Core has no roadmaps yet)."
        case 405, 501: return "This Core has no roadmaps yet. Update Core on your Home Node."
        case 503: return "Core could not read the roadmaps just now. Try again in a minute."
        default: return "Core returned an answer this app does not understand, or an error. Try again later."
        }
    }

    /// The notice after a failed write. A 409 is a conflict when the reload shows a newer
    /// revision; otherwise Core refused for a limit or an archived roadmap. A 400 means a
    /// rule this app did not catch.
    static func writeFailureText(_ error: Error, revisionMoved: Bool) -> String {
        // A 2xx answer this app could not read: Core may have made the change.
        if let known = error as? JarvisAPIError, known == .invalidResponse || known == .responseTooLarge { return notConfirmed }
        if Jobs.status(error) == 409 {
            return revisionMoved ? changedElsewhere : "Core refused this change: the roadmap is archived or a limit is reached."
        }
        if Jobs.status(error) == 400 { return "Core refused this change as invalid. Check the fields and try again." }
        if Jobs.isNetwork(error) { return "No answer from your Home Node. It is unknown whether the change arrived; reloaded to check." }
        return "Not saved. \(failureText(error))"
    }
}
