import CoreTransferable
import ImageIO
import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

/// Chat (D-Home): your messages in accent bubbles, Jarvis answers as plain text
/// under a name line, a thinking line while a reply runs, and the composer.
struct ChatView: View {
    @ObservedObject var model: JarvisAppModel
    let navigate: (HubRoute) -> Void
    @State private var draft = ""
    @FocusState private var composerFocused: Bool
    @State private var picking = false
    @State private var picked: [PhotosPickerItem] = []
    @State private var viewing: ViewedPhoto?
    @State private var confirmingDelete = false
    @State private var deleteError: String?
    @State private var serverNotice = false
    /// Chip errors held while the mic listens (it would type them), said when it stops.
    @State private var heldAnnouncements: [String] = []
    /// A proposed job open in the editor.
    @State private var editingProposal: ProposalCard?
    /// A job added from a proposal, opened from its card.
    @State private var openJob: UUID?

    /// Core's own run after a reply, while its placeholder row is shown.
    private var searchingWeb: Bool {
        model.webSearchRun.map { run in model.messages.contains { $0.id == "run:\(run)" } } ?? false
    }

    /// Photos must all be uploaded first; a photo alone is a message too.
    private var canSend: Bool {
        (!draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || !model.chips.isEmpty)
            && ChatPhotos.allReady(model.chips) && !model.isSending
    }

    private var hasPhotos: Bool { model.messages.contains { !($0.attachments ?? []).isEmpty } }

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
                        if model.currentConversationId != nil {
                            Button("Delete conversation", systemImage: "trash", role: .destructive) { confirmingDelete = true }
                        }
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
        .confirmationDialog("Delete this conversation?", isPresented: $confirmingDelete, titleVisibility: .visible) {
            Button("Delete for good", role: .destructive) {
                Task {
                    do { try await model.deleteConversation() }
                    catch { deleteError = "Could not delete: \(error.localizedDescription)" }
                }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            if hasPhotos { Text("Its photos go too, kept ones included.") }
        }
        .alert("Not deleted", isPresented: Binding(get: { deleteError != nil }, set: { if !$0 { deleteError = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(verbatim: deleteError ?? "")
        }
        .fullScreenCover(item: $viewing) { photo in PhotoViewer(model: model, id: photo.id) }
        // The editor of a proposed job; saving adds the edited job and resolves its card.
        .sheet(item: $editingProposal) { card in
            NewJobSheet(model: model, kinds: [card.proposal.body.kind], title: JobProposals.editorTitle,
                        initial: card.proposal.draft, submit: JobProposals.add,
                        write: { await model.addJobProposal(card.id, edited: $0) })
        }
        .navigationDestination(item: $openJob) { id in JobPage(model: model, id: id, navigate: navigate) }
        .photosPicker(isPresented: $picking, selection: $picked,
                      maxSelectionCount: max(1, ChatPhotos.maxPhotos - model.chips.count), matching: .images)
        .onChange(of: picked) { _, items in
            guard !items.isEmpty else { return }
            picked = []
            model.attachPhotos(items.map { item -> () async throws -> Data in
                {
                    guard let photo = try await item.loadTransferable(type: PickedPhoto.self) else {
                        throw ChatPhotos.Failure(message: ChatPhotos.unreadable)
                    }
                    return photo.data
                }
            })
        }
        // Each chip's error is said once, also when two chips fail the same way.
        .onChange(of: model.chips.compactMap { chip in chip.error.map { [chip.id.uuidString, $0] } }) { old, new in
            for failure in new where !old.contains(failure) {
                // Not while listening: the mic would type the announcement.
                if model.voiceInput.listening {
                    if heldAnnouncements.count < 8 { heldAnnouncements.append(failure[1]) }
                } else {
                    AccessibilityNotification.Announcement(failure[1]).post()
                }
            }
        }
        .onChange(of: model.voiceInput.listening) { _, listening in
            guard !listening else { return }
            guard !heldAnnouncements.isEmpty else { return }
            // One announcement, queued behind "Stopped listening".
            var held = AttributedString(heldAnnouncements.joined(separator: " "))
            held.accessibilitySpeechAnnouncementPriority = .low
            AccessibilityNotification.Announcement(held).post()
            heldAnnouncements = []
        }
        .alert("Speech goes to Apple", isPresented: $serverNotice) {
            Button("Continue") {
                model.voiceInput.acknowledgeServerNotice()
                listen()
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text(VoiceInputRules.serverNotice)
        }
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
                            MessageView(message: message, removed: model.removedPhotos,
                                        photo: { await model.photo(id: $0) }, open: { viewing = ViewedPhoto(id: $0) }).id(message.id)
                            ForEach(model.jobProposals.filter { $0.messageId == message.id }) { card in
                                ProposalCardView(card: card, locked: model.lockState != .unlocked,
                                                 add: { Task { await model.addJobProposal(card.id) } },
                                                 edit: { if model.lockState == .unlocked { editingProposal = card } },
                                                 dismiss: { model.dismissJobProposal(card.id) },
                                                 viewJob: { openJob = $0 }, checkJobs: { navigate(.jobs) })
                            }
                        }
                        if let offer = model.researchOffer, offer.conversationId == model.currentConversationId,
                           !model.isSending, model.chips.isEmpty {
                            // Only this tap sends a web search; the shown question is all that leaves.
                            VStack(alignment: .leading, spacing: JV.Space.xs) {
                                Button("Search the web", systemImage: "globe") { Task { await model.searchWeb() } }
                                    .buttonStyle(JVButtonStyle(kind: .secondary, size: .small))
                                Text(verbatim: "Sends only “\(offer.question)” to your subscription provider, which searches the web.")
                                    .font(JV.font(12, relativeTo: .caption)).foregroundStyle(JV.textSecondary)
                            }
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
        VStack(alignment: .leading, spacing: JV.Space.xs) {
            if !model.chips.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: JV.Space.xs) {
                        ForEach(model.chips) { chip in PhotoChipView(chip: chip) { model.removeChip(chip.id) } }
                    }
                }
                .accessibilityElement(children: .contain)
                .accessibilityLabel("Photos to send")
                ForEach(model.chips.filter { $0.error != nil }) { chip in
                    Text(verbatim: chip.error ?? "").font(JV.font(12, relativeTo: .caption)).foregroundStyle(JV.dangerText)
                }
                if !model.photosDisclosed {
                    Text("Photos go to your Claude/Codex subscription to be read; never to web search.")
                        .font(JV.font(12, relativeTo: .caption)).foregroundStyle(JV.textSecondary)
                }
            }
            if let notice = model.chipNotice {
                Text(verbatim: notice).font(JV.font(12, relativeTo: .caption)).foregroundStyle(JV.textSecondary)
            }
            if let problem = model.voiceInput.problem {
                Text(verbatim: problem).font(JV.font(12, relativeTo: .caption)).foregroundStyle(JV.dangerText)
            }
            input
        }
        .padding(.horizontal, JV.Space.md)
        .padding(.vertical, JV.Space.xs)
        .background(JV.bgPage)
        .overlay(alignment: .top) { Rectangle().fill(JV.separator).frame(height: 1) }
    }

    private var input: some View {
        let shape = RoundedRectangle(cornerRadius: JV.Radius.lg, style: .continuous)
        return HStack(alignment: .bottom, spacing: JV.Space.xs) {
            Menu {
                Button("Photo library", systemImage: "photo.on.rectangle") { picking = true }
                Button("Paste photo", systemImage: "doc.on.clipboard") { model.pastePhotos(Self.pastedPhotos()) }
            } label: {
                Image(systemName: "photo.badge.plus")
                    .font(.system(size: 17))
                    .foregroundStyle(model.chips.count < ChatPhotos.maxPhotos ? JV.textSecondary : JV.textDisabled)
                    .frame(width: 44, height: 44)
            }
            .disabled(model.chips.count >= ChatPhotos.maxPhotos)
            .accessibilityLabel("Attach photos")
            .accessibilityHint("Up to \(ChatPhotos.maxPhotos) photos per message")
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
            if model.voiceInput.available || model.voiceInput.listening || model.voiceInput.finishing {
                MicButton(voice: model.voiceInput, busy: model.isSending, toggle: listen)
            }
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
            .accessibilityHint(ChatPhotos.allReady(model.chips) ? "" : "Waiting for photos")
        }
        .padding(.leading, JV.Space.xxs)
        .padding(.trailing, JV.Space.xxs)
        .padding(.vertical, JV.Space.xxs)
        .background(JV.bgSurface, in: shape)
        .overlay(shape.strokeBorder(JV.borderStrong, lineWidth: 1))
    }

    private func send() {
        model.expireStaleChips()
        // A reply in progress keeps the draft.
        guard canSend else { return }
        let message = draft
        draft = ""
        // Nothing was sent (refused here or by Core): the draft comes back.
        Task { if await model.send(message), draft.isEmpty { draft = message } }
    }

    /// Like desktop, the words heard are sent at once; here they join the draft first.
    private func listen() {
        let voice = model.voiceInput
        if voice.listening { voice.stop(); return }
        // Said once, before the first listening whose audio would go to Apple.
        if voice.needsServerNotice { serverNotice = true; return }
        // The mic would hear Jarvis read its reply aloud.
        if model.voiceEnabled { model.stopSpeaking() }
        Task {
            await voice.start(while: { [model] in
                UIApplication.shared.applicationState != .background && model.lockState == .unlocked && model.isAuthenticated
                    && !model.isSending
            }) { said in
                draft = VoiceInputRules.appending(said, to: draft)
                send()
            }
        }
    }

    /// Images on the clipboard (a screenshot), read only on the owner's tap.
    private static func pastedPhotos() -> [() async throws -> Data] {
        UIPasteboard.general.itemProviders.compactMap { provider -> (() async throws -> Data)? in
            guard let type = provider.registeredTypeIdentifiers.first(where: { UTType($0)?.conforms(to: .image) == true }) else { return nil }
            return {
                try await withCheckedThrowingContinuation { (done: CheckedContinuation<Data, Error>) in
                    _ = provider.loadFileRepresentation(forTypeIdentifier: type) { file, _ in
                        // The file exists only during this callback.
                        done.resume(with: Result { try PickedPhoto.read(file) })
                    }
                }
            }
        }
    }
}

/// A picked photo, read from its file only once the size is known to be within bounds.
private struct PickedPhoto: Transferable {
    let data: Data

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(importedContentType: .image) { received in try PickedPhoto(data: PickedPhoto.read(received.file)) }
    }

    /// The file's bytes, refused before reading when it is over the size limit.
    static func read(_ file: URL?) throws -> Data {
        guard let file else { throw ChatPhotos.Failure(message: ChatPhotos.unreadable) }
        let size = try file.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
        guard size <= ChatPhotos.maxInputBytes else { throw ChatPhotos.Failure(message: ChatPhotos.tooLarge) }
        return try Data(contentsOf: file)
    }
}

private struct ViewedPhoto: Identifiable { let id: String }

/// The composer mic (desktop useMic): a ring that follows your voice while listening;
/// it stops after 5 s of silence. Leaving the chat or the app drops what it heard.
private struct MicButton: View {
    let voice: VoiceInput
    /// A reply is running: the mic waits, so Jarvis is never spoken into a recording.
    let busy: Bool
    let toggle: () -> Void
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        Button(action: toggle) {
            Image(systemName: voice.listening ? "mic.fill" : "mic")
                .font(.system(size: 17))
                .foregroundStyle(voice.listening ? JV.accentText : JV.textSecondary)
                .frame(width: 44, height: 44)
                .overlay {
                    if voice.listening {
                        Circle().strokeBorder(JV.accentSubtle, lineWidth: 2 + voice.level * 4).frame(width: 40, height: 40)
                    }
                }
        }
        .buttonStyle(.plain)
        .disabled(voice.finishing || (busy && !voice.listening))
        .accessibilityLabel("Speak")
        .accessibilityValue(voice.listening ? "Listening" : "")
        .accessibilityHint(voice.listening ? "Stops after 5 s of silence" : "")
        .accessibilityAddTraits(voice.listening ? .isSelected : [])
        .onChange(of: voice.listening) { was, now in
            if was && !now { AccessibilityNotification.Announcement("Stopped listening").post() }
        }
        .onChange(of: voice.problem) { _, problem in
            if let problem { AccessibilityNotification.Announcement(problem).post() }
        }
        // The background lock stops it in the app model; this also covers the app switcher
        // and Control Center. Permission prompts (inactive too) come before listening.
        .onChange(of: scenePhase) { _, phase in
            if phase != .active, voice.listening || voice.finishing { voice.stop(discard: true) }
        }
        .onDisappear { voice.stop(discard: true) }
    }
}

/// A photo waiting in the composer: its preview, a spinner while it is shrunk and
/// uploaded, and ✕ to remove it.
private struct PhotoChipView: View {
    let chip: PhotoChip
    let remove: () -> Void

    private var status: String {
        switch chip.state {
        case .preparing: "Photo, preparing"
        case .uploading: "Photo, uploading"
        case .ready: "Photo, ready to send"
        case .failed: "Photo, not uploaded"
        }
    }

    var body: some View {
        ZStack(alignment: .topTrailing) {
            ZStack {
                if let preview = chip.preview {
                    Image(uiImage: preview).resizable().scaledToFill().opacity(chip.state == .failed ? 0.4 : 1)
                }
                switch chip.state {
                case .preparing, .uploading: ProgressView().controlSize(.small)
                case .failed: Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(JV.dangerText)
                case .ready: EmptyView()
                }
            }
            .frame(width: 56, height: 56)
            .background(JV.bgSunken)
            .clipShape(RoundedRectangle(cornerRadius: JV.Radius.md, style: .continuous))
            .padding(.top, 6).padding(.trailing, 6)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(status)
            Button(action: remove) {
                Image(systemName: "xmark")
                    .font(.system(size: 10, weight: .bold))
                    .foregroundStyle(JV.textInverse)
                    .frame(width: 22, height: 22)
                    .background(JV.textPrimary, in: Circle())
                    .frame(width: 44, height: 44, alignment: .topTrailing)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Remove photo")
        }
    }
}

/// One photo full screen, to keep past its 90 days or delete.
private struct PhotoViewer: View {
    @ObservedObject var model: JarvisAppModel
    let id: String
    @Environment(\.dismiss) private var dismiss
    @State private var photo: ChatPhoto?
    @State private var busy = false
    @State private var confirming = false
    @State private var error: String?

    private var meta: PhotoMeta? { model.photoMeta[id] }

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()
                switch photo {
                case .image(let image)?:
                    Image(uiImage: image).resizable().scaledToFit().accessibilityLabel("Photo")
                case .removed?:
                    Text("Photo removed").foregroundStyle(.white)
                case .unavailable?:
                    Text("This photo could not be loaded.").foregroundStyle(.white)
                case nil:
                    ProgressView().tint(.white)
                }
            }
            .safeAreaInset(edge: .bottom) {
                VStack(alignment: .leading, spacing: JV.Space.xs) {
                    if let error {
                        Text(verbatim: error).font(JV.font(13)).foregroundStyle(JV.dangerText)
                    }
                    if case .image? = photo {
                        Text(verbatim: ChatPhotos.expiryHint(meta)).font(JV.font(13)).foregroundStyle(JV.textSecondary)
                        actions
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(JV.Space.md)
                .background(JV.bgSurface)
            }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }
                }
            }
        }
        // The full-size photo never shows in the app switcher.
        .privacyCover()
        .task { photo = await model.photo(id: id, full: true) }
        .onChange(of: error) { _, error in
            if let error { AccessibilityNotification.Announcement(error).post() }
        }
        .confirmationDialog("Delete this photo?", isPresented: $confirming, titleVisibility: .visible) {
            Button("Delete for good", role: .destructive) { Task { await delete() } }
            Button("Cancel", role: .cancel) {}
        }
    }

    private var actions: some View {
        HStack(spacing: JV.Space.xs) {
            Button(meta?.saved == true ? "Kept" : "Keep", systemImage: meta?.saved == true ? "star.fill" : "star") {
                Task { await toggleKeep() }
            }
            .buttonStyle(JVButtonStyle(kind: .secondary))
            .accessibilityHint(meta?.saved == true ? "Stops keeping it past 90 days" : "Keeps it past 90 days")
            .accessibilityAddTraits(meta?.saved == true ? .isSelected : [])
            Button("Delete", systemImage: "trash", role: .destructive) { confirming = true }
                .buttonStyle(JVButtonStyle(kind: .secondary))
                .accessibilityLabel("Delete photo")
        }
        .disabled(busy)
    }

    private func toggleKeep() async {
        guard !busy else { return }
        busy = true; error = nil
        defer { busy = false }
        do {
            try await model.keepPhoto(id, saved: meta?.saved != true)
            // Gone on the Home Node: its message now shows "Photo removed".
            if model.removedPhotos.contains(id) { dismiss() }
        } catch { self.error = "Could not change this photo. Try again." }
    }

    private func delete() async {
        guard !busy else { return }
        busy = true; error = nil
        defer { busy = false }
        do {
            try await model.deletePhoto(id)
            dismiss()
        } catch { self.error = "Could not delete this photo. Try again." }
    }
}

/// A chat photo for display: decoded, gone (deleted or expired), or not loadable now.
enum ChatPhoto {
    case image(UIImage)
    case removed
    case unavailable

    /// The photo as a thumbnail of at most `maxPixels` on its long side, upright, decoded
    /// straight from the JPEG so its full-size bitmap is never held. Core sends up to 1600 px.
    static func thumbnail(_ data: Data, maxPixels: Int = 512) -> UIImage? {
        guard let source = CGImageSourceCreateWithData(data as CFData, [kCGImageSourceShouldCache: false] as CFDictionary),
              let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                  kCGImageSourceCreateThumbnailFromImageAlways: true,
                  kCGImageSourceCreateThumbnailWithTransform: true,
                  kCGImageSourceShouldCacheImmediately: true,
                  kCGImageSourceThumbnailMaxPixelSize: maxPixels,
              ] as CFDictionary)
        else { return nil }
        return UIImage(cgImage: image)
    }
}

/// One photo of a message; tapping it opens it full screen.
private struct PhotoThumb: View {
    let id: String
    let removed: Bool
    let load: (String) async -> ChatPhoto
    let open: (String) -> Void
    @State private var photo: ChatPhoto?

    var body: some View {
        ZStack {
            switch removed ? ChatPhoto.removed : photo {
            case .image(let image)?:
                Button { open(id) } label: { Image(uiImage: image).resizable().scaledToFill() }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Photo")
                    .accessibilityHint("Opens it full screen")
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
        .task(id: id) { if !removed { photo = await load(id) } }
    }
}

/// One job a chat answer proposed, under that answer (desktop JobProposalCard.vue). Nothing
/// is made until Add (the proposal as is) or Add in the editor (the edited job). Core's text
/// is shown verbatim.
private struct ProposalCardView: View {
    let card: ProposalCard
    let locked: Bool
    let add: () -> Void
    let edit: () -> Void
    let dismiss: () -> Void
    let viewJob: (UUID) -> Void
    let checkJobs: () -> Void

    private var busy: Bool { card.state == .adding }

    var body: some View {
        let proposal = card.proposal
        VStack(alignment: .leading, spacing: JV.Space.xxs) {
            HStack(spacing: JV.Space.xs) {
                Badge(text: proposal.body.kind.label, style: .accent, dot: false)
                Text(verbatim: JobProposals.title(proposal)).font(JV.font(15, .medium)).foregroundStyle(JV.textPrimary)
            }
            caption(JobProposals.schedule(proposal))
            if let detail = JobProposals.detail(proposal) { caption(detail) }
            switch card.state {
            case let .added(job):
                status(JobProposals.added, link: JobProposals.viewJob) { viewJob(job) }
            case .unconfirmed:
                status(card.error ?? JobProposals.notConfirmed, link: JobProposals.checkJobs, action: checkJobs)
            case .open, .adding:
                if let error = card.error {
                    Text(verbatim: error).font(JV.font(13)).foregroundStyle(JV.dangerText).fixedSize(horizontal: false, vertical: true)
                }
            }
            HStack(spacing: JV.Space.xs) {
                if card.state == .open || busy {
                    Button(JobProposals.add, action: add)
                        .buttonStyle(JVButtonStyle(kind: .secondary, size: .small)).disabled(locked || busy)
                    Button(JobProposals.edit, action: edit)
                        .buttonStyle(JVButtonStyle(kind: .quiet, size: .small)).disabled(locked || busy)
                }
                Button(JobProposals.dismiss, action: dismiss)
                    .buttonStyle(JVButtonStyle(kind: .quiet, size: .small)).disabled(busy)
            }
            .padding(.top, JV.Space.xxs)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, JV.Space.md)
        .padding(.vertical, JV.Space.sm)
        .background(JV.bgSunken, in: RoundedRectangle(cornerRadius: JV.Radius.md, style: .continuous))
        .accessibilityElement(children: .contain)
        .accessibilityLabel(Text(verbatim: "Proposed job: \(JobProposals.title(proposal))"))
        .onChange(of: card.state) { _, state in
            // Added, not confirmed or refused: said once it is known.
            guard state != .adding, let text = card.error ?? (state == .open ? nil : JobProposals.added) else { return }
            AccessibilityNotification.Announcement(text).post()
        }
    }

    private func caption(_ text: String) -> some View {
        Text(verbatim: text).font(JV.font(12, relativeTo: .caption)).foregroundStyle(JV.textSecondary)
            .fixedSize(horizontal: false, vertical: true)
    }

    private func status(_ text: String, link: String, action: @escaping () -> Void) -> some View {
        HStack(spacing: JV.Space.xs) {
            Text(verbatim: text).font(JV.font(13)).foregroundStyle(JV.textPrimary).fixedSize(horizontal: false, vertical: true)
            Button(link, action: action).font(JV.font(13, .medium)).foregroundStyle(JV.accentText).buttonStyle(.plain)
                .frame(minHeight: 44)
        }
    }
}

private struct MessageView: View {
    let message: ConversationMessage
    let removed: Set<String>
    let photo: (String) async -> ChatPhoto
    let open: (String) -> Void

    @ViewBuilder private var photos: some View {
        if let ids = message.attachments, !ids.isEmpty {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: JV.Space.xs) {
                    ForEach(ids, id: \.self) { PhotoThumb(id: $0, removed: removed.contains($0), load: photo, open: open) }
                }
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
            // Outside the combined element so each photo stays its own button.
            photos
            VStack(alignment: .trailing, spacing: JV.Space.xxs) {
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
            .accessibilityElement(children: .combine)
            .accessibilityLabel(Text(verbatim: "You: " + message.content))
            .accessibilityValue(Text(verbatim: time ?? ""))
        }
        .frame(maxWidth: .infinity, alignment: .trailing)
        .padding(.leading, 48)
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
