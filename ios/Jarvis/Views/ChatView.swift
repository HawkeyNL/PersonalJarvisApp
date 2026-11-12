import SwiftUI

/// Chat (D-Home): your messages in accent bubbles, Jarvis answers as plain text
/// under a name line, a thinking line while a reply runs, and the composer.
struct ChatView: View {
    @ObservedObject var model: JarvisAppModel
    @State private var draft = ""
    @FocusState private var composerFocused: Bool

    /// Core's own run after a reply, while its placeholder row is shown.
    private var searchingWeb: Bool {
        model.webSearchRun.map { run in model.messages.contains { $0.id == "run:\(run)" } } ?? false
    }

    private var canSend: Bool { !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !model.isSending }

    var body: some View {
        Group {
            if model.connectionState != .reachable || !model.isAuthenticated {
                EnrollmentView(model: model)
            } else {
                conversation
            }
        }
        .jarvisNavigationBar(title: model.currentConversationTitle)
        .toolbar {
            ToolbarItemGroup(placement: .keyboard) {
                Spacer()
                Button("Done", systemImage: "keyboard.chevron.compact.down") {
                    composerFocused = false
                }
                .accessibilityLabel("Dismiss keyboard")
            }
            if model.isAuthenticated {
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        if model.voiceEnabled {
                            Button("Stop speaking", systemImage: "stop.circle") { model.stopSpeaking() }
                        }
                        Button("New conversation", systemImage: "square.and.pencil") { model.newConversation() }
                        ForEach(model.conversations) { item in
                            Button(item.title) { Task { await model.openConversation(item.id) } }
                        }
                    } label: {
                        Image(systemName: "ellipsis.circle").frame(minWidth: 44, minHeight: 44)
                    }
                    .accessibilityLabel("Conversation options")
                }
            }
        }
        .onChange(of: model.isSending) { _, sending in
            if sending { AccessibilityNotification.Announcement("Jarvis is thinking").post() }
        }
        .onDisappear { composerFocused = false }
    }

    private var conversation: some View {
        VStack(spacing: 0) {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 18) {
                        if model.messages.isEmpty && !model.isSending {
                            UnavailableState(title: "Ask Jarvis anything", kind: .empty,
                                             detail: "Replies appear here. Conversations are stored on your Home Node.",
                                             symbol: "bubble.left")
                        }
                        ForEach(model.messages) { message in
                            MessageView(message: message, photo: { await model.photo(id: $0) }).id(message.id)
                        }
                        if model.isSending {
                            HStack(spacing: JV.Space.xs) {
                                ProgressView().controlSize(.small).tint(JV.textSecondary)
                                Text(searchingWeb ? "Searching the web…" : "Jarvis is thinking…").font(JV.font(13)).foregroundStyle(JV.textSecondary)
                            }
                            .accessibilityElement(children: .combine)
                        }
                    }
                    .padding(JV.Space.md)
                }
                .background(JV.bgPage)
                .scrollDismissesKeyboard(.interactively)
                .onChange(of: model.messages.count) { _, _ in
                    if let last = model.messages.last { proxy.scrollTo(last.id, anchor: .bottom) }
                }
            }
            composer
        }
    }

    private var composer: some View {
        let shape = RoundedRectangle(cornerRadius: JV.Radius.lg, style: .continuous)
        return HStack(alignment: .bottom, spacing: JV.Space.xs) {
            TextField("Ask Jarvis anything…", text: $draft, axis: .vertical)
                .textFieldStyle(.plain)
                .font(JV.font(16))
                .foregroundStyle(JV.textPrimary)
                .lineLimit(1...3)
                .focused($composerFocused)
                .padding(.vertical, 11)
                .submitLabel(.send)
                .onSubmit { send() }
                .accessibilityLabel("Message to Jarvis")
            Button(action: send) {
                Image(systemName: "arrow.up")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(canSend ? JV.textOnAccent : JV.textDisabled)
                    .frame(width: 44, height: 44)
                    .background(canSend ? JV.accentSolid : JV.bgSunken,
                                in: RoundedRectangle(cornerRadius: JV.Radius.md, style: .continuous))
            }
            .buttonStyle(.plain)
            .disabled(!canSend)
            .accessibilityLabel("Send message")
        }
        .padding(.leading, 14)
        .padding(.trailing, JV.Space.xxs)
        .padding(.vertical, JV.Space.xxs)
        .background(JV.bgSurface, in: shape)
        .overlay(shape.strokeBorder(JV.borderStrong, lineWidth: 1))
        .padding(.horizontal, JV.Space.md)
        .padding(.vertical, JV.Space.xs)
        .background(JV.bgPage)
        .overlay(alignment: .top) { Rectangle().fill(JV.separator).frame(height: 1) }
    }

    private func send() {
        let message = draft
        draft = ""
        Task { await model.send(message) }
    }
}

/// A chat photo for display: decoded, gone (deleted or expired), or not loadable now.
enum ChatPhoto {
    case image(UIImage)
    case removed
    case unavailable
}

/// One photo of a message, read-only (sending comes later).
private struct PhotoThumb: View {
    let id: String
    let load: (String) async -> ChatPhoto
    @State private var photo: ChatPhoto?

    var body: some View {
        ZStack {
            switch photo {
            case .image(let image)?:
                Image(uiImage: image).resizable().scaledToFill().accessibilityLabel("Photo")
            case .removed?:
                Text("Photo removed").font(JV.font(12, relativeTo: .caption)).foregroundStyle(JV.textSecondary).padding(JV.Space.xs)
            case .unavailable?:
                Text("Photo unavailable").font(JV.font(12, relativeTo: .caption)).foregroundStyle(JV.textSecondary).padding(JV.Space.xs)
            case nil:
                ProgressView().controlSize(.small)
            }
        }
        .frame(width: 140, height: 140)
        .background(JV.bgSunken)
        .clipShape(RoundedRectangle(cornerRadius: JV.Radius.md, style: .continuous))
        .task(id: id) { photo = await load(id) }
    }
}

private struct MessageView: View {
    let message: ConversationMessage
    let photo: (String) async -> ChatPhoto

    @ViewBuilder private var photos: some View {
        if let ids = message.attachments, !ids.isEmpty {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: JV.Space.xs) { ForEach(ids, id: \.self) { PhotoThumb(id: $0, load: photo) } }
            }
            .frame(maxWidth: 4 * 148)
        }
    }

    private var time: String? {
        parseCoreDate(message.at).map { $0.formatted(date: .omitted, time: .shortened) }
    }

    var body: some View {
        if message.isAssistant { assistant } else { mine }
    }

    /// Your message: an accent bubble on the right, its time under it.
    private var mine: some View {
        VStack(alignment: .trailing, spacing: JV.Space.xxs) {
            photos
            // A photo alone is a message too: then there is no text bubble.
            if !message.content.isEmpty || (message.attachments ?? []).isEmpty {
                Text(verbatim: message.content)
                    .font(JV.font(16))
                    .foregroundStyle(JV.textOnAccent)
                    .textSelection(.enabled)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)
                    .background(JV.accentSolid, in: UnevenRoundedRectangle(
                        topLeadingRadius: JV.Radius.lg, bottomLeadingRadius: JV.Radius.lg,
                        bottomTrailingRadius: JV.Radius.sm, topTrailingRadius: JV.Radius.lg, style: .continuous))
            }
            if let time {
                Text(verbatim: time).font(JV.number(12, relativeTo: .caption)).foregroundStyle(JV.textSecondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .trailing)
        .padding(.leading, 48)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(Text(verbatim: "You: " + message.content))
        .accessibilityValue(Text(verbatim: time ?? ""))
    }

    /// A web answer shows its trailing Sources as links instead of in the text.
    private var answer: (body: String, sources: [WebAnswer.Source]) {
        message.isWeb ? WebAnswer.split(message.content) : (message.content, [])
    }

    /// Jarvis: a name line with the tile, then the answer as plain text. A web answer
    /// gets a "Web" chip and its Sources as links; nothing is loaded by itself.
    private var assistant: some View {
        let answer = self.answer
        return VStack(alignment: .leading, spacing: JV.Space.xs) {
            VStack(alignment: .leading, spacing: JV.Space.xs) {
                HStack(spacing: JV.Space.xs) {
                    IconBadge(symbol: "sparkles", size: 24)
                    Text("Jarvis").font(JV.font(12, .medium, relativeTo: .caption)).foregroundStyle(JV.textSecondary)
                    if let time {
                        Text(verbatim: time).font(JV.number(12, relativeTo: .caption)).foregroundStyle(JV.textSecondary)
                    }
                    if message.isWeb {
                        Text("Web").font(JV.font(12, .medium, relativeTo: .caption)).foregroundStyle(JV.accentText)
                            .padding(.horizontal, JV.Space.xs).padding(.vertical, 2)
                            .background(JV.accentSubtle, in: Capsule())
                    }
                }
                Text(verbatim: answer.body)
                    .font(JV.font(16))
                    .foregroundStyle(JV.textPrimary)
                    .lineSpacing(4)
                    .textSelection(.enabled)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .accessibilityElement(children: .combine)
            .accessibilityLabel(Text(verbatim: (message.isWeb ? "Jarvis, from the web: " : "Jarvis: ") + answer.body))
            .accessibilityValue(Text(verbatim: time ?? ""))
            photos
            // Outside the combined element so each source stays its own link.
            if !answer.sources.isEmpty {
                VStack(alignment: .leading, spacing: JV.Space.xxs) {
                    Text("Sources").font(JV.font(12, .medium, relativeTo: .caption)).foregroundStyle(JV.textSecondary)
                    // Validated https URLs only (WebAnswer); Link opens them in the browser.
                    // A card: the plain title (verbatim, never Markdown), then host and short path.
                    ForEach(answer.sources, id: \.self) { source in
                        Link(destination: source.url) {
                            VStack(alignment: .leading, spacing: 2) {
                                if let title = source.title {
                                    Text(verbatim: title).font(JV.font(14, .medium)).foregroundStyle(JV.accentText).lineLimit(2)
                                }
                                Text(verbatim: source.host + source.shortPath)
                                    .font(JV.font(12, relativeTo: .caption)).foregroundStyle(JV.textSecondary).lineLimit(1).truncationMode(.middle)
                            }
                            .multilineTextAlignment(.leading)
                            .padding(.horizontal, JV.Space.sm).padding(.vertical, JV.Space.xs)
                            .frame(maxWidth: 320, alignment: .leading)
                            .background(JV.bgSunken, in: RoundedRectangle(cornerRadius: JV.Radius.md, style: .continuous))
                        }
                        .accessibilityLabel(Text(verbatim: (source.title.map { $0 + ", " } ?? "") + source.host))
                        .accessibilityHint("Opens in the browser")
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.trailing, JV.Space.xxl)
    }
}
