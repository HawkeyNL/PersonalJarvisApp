import SwiftUI

/// Chat (D-Home): your messages in accent bubbles, Jarvis answers as plain text
/// under a name line, a thinking line while a reply runs, and the composer.
struct ChatView: View {
    @ObservedObject var model: JarvisAppModel
    @State private var draft = ""
    @FocusState private var composerFocused: Bool

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
                            MessageView(message: message).id(message.id)
                        }
                        if model.isSending {
                            HStack(spacing: 8) {
                                ProgressView().controlSize(.small).tint(JV.textSecondary)
                                Text("Jarvis is thinking…").font(JV.font(13)).foregroundStyle(JV.textSecondary)
                            }
                            .accessibilityElement(children: .combine)
                        }
                    }
                    .padding(16)
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
        return HStack(alignment: .bottom, spacing: 8) {
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
        .padding(.trailing, 4)
        .padding(.vertical, 4)
        .background(JV.bgSurface, in: shape)
        .overlay(shape.strokeBorder(JV.borderStrong, lineWidth: 1))
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .background(JV.bgPage)
        .overlay(alignment: .top) { Rectangle().fill(JV.separator).frame(height: 1) }
    }

    private func send() {
        let message = draft
        draft = ""
        Task { await model.send(message) }
    }
}

private struct MessageView: View {
    let message: ConversationMessage

    private var time: String? {
        parseCoreDate(message.at).map { $0.formatted(date: .omitted, time: .shortened) }
    }

    var body: some View {
        if message.isAssistant { assistant } else { mine }
    }

    /// Your message: an accent bubble on the right, its time under it.
    private var mine: some View {
        VStack(alignment: .trailing, spacing: 4) {
            Text(verbatim: message.content)
                .font(JV.font(16))
                .foregroundStyle(JV.textOnAccent)
                .textSelection(.enabled)
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .background(JV.accentSolid, in: UnevenRoundedRectangle(
                    topLeadingRadius: JV.Radius.lg, bottomLeadingRadius: JV.Radius.lg,
                    bottomTrailingRadius: JV.Radius.sm, topTrailingRadius: JV.Radius.lg, style: .continuous))
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

    /// Jarvis: a name line with the tile, then the answer as plain text.
    private var assistant: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                IconBadge(symbol: "sparkles", size: 24)
                Text("Jarvis").font(JV.font(12, .medium, relativeTo: .caption)).foregroundStyle(JV.textSecondary)
                if let time {
                    Text(verbatim: time).font(JV.number(12, relativeTo: .caption)).foregroundStyle(JV.textSecondary)
                }
            }
            Text(verbatim: message.content)
                .font(JV.font(16))
                .foregroundStyle(JV.textPrimary)
                .lineSpacing(4)
                .textSelection(.enabled)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.trailing, 32)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(Text(verbatim: "Jarvis: " + message.content))
        .accessibilityValue(Text(verbatim: time ?? ""))
    }
}
