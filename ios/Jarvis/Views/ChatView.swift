import SwiftUI

struct ChatView: View {
    @ObservedObject var model: JarvisAppModel
    @State private var draft = ""
    @FocusState private var composerFocused: Bool

    var body: some View {
        Group {
            if model.connectionState != .reachable || !model.isAuthenticated {
                EnrollmentView(model: model)
            } else {
                conversation
            }
        }
        .navigationTitle(model.currentConversationTitle)
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(JarvisTheme.background, for: .navigationBar)
        .toolbarBackground(.visible, for: .navigationBar)
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
                    } label: { Image(systemName: "ellipsis.circle") }
                }
            }
        }
        .onDisappear { composerFocused = false }
    }

    private var conversation: some View {
        VStack(spacing: 0) {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 12) {
                        ForEach(model.messages) { message in
                            MessageBubble(message: message).id(message.id)
                        }
                        if model.isSending { ProgressView().padding() }
                    }
                    .padding()
                }
                .background(JarvisTheme.background)
                .scrollDismissesKeyboard(.interactively)
                .onChange(of: model.messages.count) { _, _ in
                    if let last = model.messages.last { proxy.scrollTo(last.id, anchor: .bottom) }
                }
            }
            Divider().overlay(JarvisTheme.accent.opacity(0.2))
            HStack(alignment: .bottom, spacing: 10) {
                TextField("Message Jarvis", text: $draft, axis: .vertical)
                    .textFieldStyle(.plain)
                    .font(JarvisFont.body(15))
                    .lineLimit(1...3)
                    .focused($composerFocused)
                    .padding(14)
                    .background(JarvisTheme.panel, in: RoundedRectangle(cornerRadius: 18))
                    .overlay(RoundedRectangle(cornerRadius: 18).stroke(JarvisTheme.accent.opacity(0.25)))
                    .submitLabel(.send)
                    .onSubmit { send() }
                Button(action: send) { Image(systemName: "arrow.up.circle.fill").font(.largeTitle).frame(width: 44, height: 44) }
                    .disabled(draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || model.isSending)
                    .accessibilityLabel("Send message")
            }
            .padding()
            .background(JarvisTheme.background)
        }
    }

    private func send() {
        let message = draft
        draft = ""
        Task { await model.send(message) }
    }
}

private struct MessageBubble: View {
    let message: ConversationMessage

    var body: some View {
        HStack {
            if !message.isAssistant { Spacer(minLength: 48) }
            Text(message.content)
                .font(JarvisFont.body(15))
                .foregroundStyle(JarvisTheme.text2)
                .textSelection(.enabled)
                .padding(12)
                .background(message.isAssistant ? JarvisTheme.tile : JarvisTheme.accent.opacity(0.18))
                .clipShape(RoundedRectangle(cornerRadius: 16))
                .overlay(RoundedRectangle(cornerRadius: 16).stroke(JarvisTheme.accent.opacity(message.isAssistant ? 0.3 : 0.45), lineWidth: 1))
            if message.isAssistant { Spacer(minLength: 48) }
        }
    }
}
