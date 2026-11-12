import Combine
import Foundation
import UIKit

@MainActor
final class JarvisAppModel: ObservableObject {
    @Published private(set) var connectionState: ConnectionState = .unconfigured
    @Published private(set) var enrollmentState: EnrollmentState = .notStarted
    @Published private(set) var lockState: AppLockState = .unlocked
    @Published private(set) var isUnlocking = false
    private var needsForegroundUnlock = false
    /// Informational "update available" notice; nil when none, dismissed or invalid.
    @Published private(set) var iosUpdate: IOSUpdateNotice?
    @Published private(set) var conversations: [ConversationSummary] = []
    @Published private(set) var messages: [ConversationMessage] = []
    @Published private(set) var currentConversationId: UUID?
    @Published private(set) var currentConversationTitle = "New conversation"
    @Published private(set) var isSending = false
    /// A run Core started itself right after a reply (its automatic web search);
    /// only meaningful while its `run:` placeholder row is shown.
    @Published private(set) var webSearchRun: UUID?
    /// A web search Core advised after the last reply; only the owner's tap sends it.
    @Published private(set) var researchOffer: ResearchOffer?
    /// Photos in the composer, in order (ChatPhotos).
    @Published private(set) var chips: [PhotoChip] = []
    /// Why a photo was not added (the limit); cleared by the next attach.
    @Published private(set) var chipNotice: String?
    /// The open conversation's photos that still exist (from its last read), by id.
    @Published private(set) var photoMeta: [String: PhotoMeta] = [:]
    /// Photos known to be gone: shown as "Photo removed" without a download.
    @Published private(set) var removedPhotos: Set<String> = []
    /// Where photos go is said once, until the first message with photos is sent.
    @Published private(set) var photosDisclosed = UserDefaults.standard.bool(forKey: "jarvis.photos.disclosed") {
        didSet { UserDefaults.standard.set(photosDisclosed, forKey: "jarvis.photos.disclosed") }
    }
    @Published var endpointText: String
    @Published var notice: String?
    /// A signed password reset waiting for root confirmation on the Home Node.
    @Published var pendingPasswordReset: UUID?
    /// Why Core did not revoke this device; set while the user decides on erasing locally only.
    @Published var localEraseOffer: String?
    @Published private(set) var availableVoices: [LocalSpeechVoice] = []
    @Published var selectedVoice = UserDefaults.standard.string(forKey: "jarvis.voice.identifier") ?? "" {
        didSet {
            speech.setVoice(selectedVoice)
            UserDefaults.standard.set(selectedVoice, forKey: "jarvis.voice.identifier")
        }
    }
    func refreshLocalVoices() { availableVoices = speech.voices() }
    @Published var voiceRate = SpeechRate.normalize(UserDefaults.standard.object(forKey: "jarvis.voice.rate") as? Double ?? 1) {
        didSet {
            let rate = SpeechRate.normalize(voiceRate)
            speech.setRate(rate)
            UserDefaults.standard.set(rate, forKey: "jarvis.voice.rate")
        }
    }
    @Published var voiceEnabled = UserDefaults.standard.bool(forKey: "jarvis.voice.enabled") {
        didSet {
            speech.enabled = voiceEnabled
            UserDefaults.standard.set(voiceEnabled, forKey: "jarvis.voice.enabled")
            if !voiceEnabled { stopSpeaking() }
        }
    }
    private let realtime = RealtimeService()
    private let speech = RealtimeSpeech()
    private var realtimeAvailable = false
    private var pendingRequests = PendingChatRequests()
    private var voiceReleaseTask: Task<Void, Never>?
    private var presentation = ChatPresentationLifetime()
    /// The composer photos' lifetime: like `presentation`, but the background lock keeps it.
    private var photoSession = ChatPresentationLifetime()
    private func invalidatePresentation(clearIdentity: Bool) {
        presentation.invalidate()
        messages = []; conversations = []; isSending = false; realtimeAvailable = false
        photos.removeAll()
        photoMeta = [:]; removedPhotos = []; researchOffer = nil
        if clearIdentity {
            dropChips()
            pendingRequests.clear(); currentConversationId = nil; currentConversationTitle = "New conversation"
            pendingPasswordReset = nil
        }
    }

    /// Composer photos outlive the background lock (pasting means leaving the app); a new
    /// identity or Home Node deletes the uploaded ones and drops the rest. One still
    /// uploading stays pending and Core removes it after 24 hours.
    private func dropChips() {
        chips.forEach { forget($0) }
        chips = []; chipNotice = nil; photoSession.invalidate()
    }

    func stopSpeaking() {
        let run = speech.ownedRun
        speech.stop()
        voiceReleaseTask?.cancel()
        guard let run, let origin = endpointStore.endpoint, isAuthenticated, lockState == .unlocked else { return }
        voiceReleaseTask = Task { [weak self] in
            guard let self else { return }
            do { try await chat.releaseVoice(run: run, origin: origin) }
            catch { /* Local stop already succeeded; stale/offline leases expire. */ }
        }
    }

    private let endpointStore: EndpointStore
    private let api: JarvisAPIClient
    private let auth: AuthService
    private let chat: ChatService
    private let biometricLock: BiometricLock
    /// The "Approval notifications" setting and APNs token registration.
    let approvalPush = ApprovalPush()

    convenience init() {
        let endpointStore = EndpointStore()
        let api = JarvisAPIClient(baseURL: endpointStore.endpoint)
        let auth = AuthService(api: api)
        self.init(endpointStore: endpointStore, api: api, auth: auth, chat: ChatService(api: api, auth: auth))
    }

    #if DEBUG
    /// Native test seam: a signed-in, unlocked chat over `chat`; nothing in the app uses it.
    convenience init(signedInChat chat: ChatService, api: JarvisAPIClient, realtime: Bool) {
        self.init(endpointStore: EndpointStore(), api: api, auth: AuthService(api: api), chat: chat)
        enrollmentState = .authenticated
        realtimeAvailable = realtime
    }
    #endif

    private init(endpointStore: EndpointStore, api: JarvisAPIClient, auth: AuthService, chat: ChatService) {
        self.endpointStore = endpointStore
        self.api = api
        self.auth = auth
        self.chat = chat
        self.biometricLock = BiometricLock()
        self.endpointText = endpointStore.endpoint?.absoluteString ?? ""
        speech.setRate(voiceRate)
        speech.setVoice(selectedVoice)
        approvalPush.owner = self
    }

    var isAuthenticated: Bool { enrollmentState == .authenticated }

    func loadHomeNodeRegistry() async throws -> HomeNodeRegistry {
        guard isAuthenticated, lockState == .unlocked else { throw JarvisAPIError.unauthorized }
        let generation = presentation.id
        let result = try await auth.homeNodeRegistry()
        guard presentation.accepts(generation), lockState == .unlocked else { throw JarvisAPIError.unauthorized }
        return result
    }

    func loadModelPolicy() async throws -> ModelPolicySnapshot {
        guard isAuthenticated, lockState == .unlocked else { throw JarvisAPIError.unauthorized }
        let generation = presentation.id
        let result = try await auth.modelPolicy()
        guard presentation.accepts(generation), lockState == .unlocked else { throw JarvisAPIError.unauthorized }
        return result
    }

    func setModelEnabled(_ entry: ModelAccessEntry, policyHash: String) async throws {
        guard isAuthenticated, lockState == .unlocked else { throw JarvisAPIError.unauthorized }
        try await auth.setModelEnabled(entry, policyHash: policyHash)
    }

    func setModelRouting(_ routing: RoutingDocument, routingHash: String) async throws {
        guard isAuthenticated, lockState == .unlocked else { throw JarvisAPIError.unauthorized }
        try await auth.setModelRouting(routing, routingHash: routingHash)
    }

    /// Orb mood from what the app knows: a reply in progress means thinking.
    /// iOS has no microphone input yet, so it never listens.
    var mood: Mood { isSending ? .thinking : .idle }

    /// Optional owner read for the hub and node pages. An older Core (404/405)
    /// becomes `.unsupported`; a 401 signs out like every other session read;
    /// results from before a lock, logout or origin change are dropped.
    func coreRead<Response: Decodable>(_ path: String, as type: Response.Type = Response.self) async -> Availability<Response> {
        guard isAuthenticated, lockState == .unlocked else { return .error(.signin) }
        let generation = presentation.id
        do {
            let value: Response = try await auth.ownerGet(path)
            guard presentation.accepts(generation), lockState == .unlocked else { return .error(.signin) }
            return .ok(value)
        } catch {
            if (error as? JarvisAPIError) == .unauthorized, presentation.accepts(generation) { handle(error) }
            return Availability(error: error)
        }
    }

    /// At most one check per 6 hours; any failure, 204 or older Core means no banner.
    func checkForAppUpdate(now: Date = Date()) async {
        let defaults = UserDefaults.standard
        let last = defaults.object(forKey: "jarvis.update.checkedAt") as? Date ?? .distantPast
        guard now.timeIntervalSince(last) >= 6 * 3600,
              let running = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String,
              IOSUpdateNotice.parseVersion(running) != nil,
              isAuthenticated, lockState == .unlocked else { return }
        defaults.set(now, forKey: "jarvis.update.checkedAt")
        guard case let .ok(response) = await coreRead("/v1/app-updates/ios/\(running)", as: IOSUpdateResponse.self),
              let notice = IOSUpdateNotice.validated(response, running: running),
              defaults.string(forKey: "jarvis.update.dismissedVersion") != notice.version
        else { iosUpdate = nil; return }
        iosUpdate = notice
    }

    func dismissAppUpdate() {
        if let version = iosUpdate?.version { UserDefaults.standard.set(version, forKey: "jarvis.update.dismissedVersion") }
        iosUpdate = nil
    }

    /// Public liveness/readiness probe; `nil` when the Home Node did not answer OK.
    func probe(_ path: String) async -> HealthProbe? {
        try? await api.get(path, response: HealthProbe.self)
    }

    /// Approve an agent action exactly as shown (its hash), after Face ID / Touch ID.
    func approveAgentAction(_ id: String, shownSha256: String) async throws {
        guard isAuthenticated, lockState == .unlocked else { throw JarvisAPIError.unauthorized }
        guard let id = UUID(uuidString: id) else { throw JarvisAPIError.invalidResponse }
        try await auth.approveAgentAction(id, shownSha256: shownSha256, unlocked: stillUnlocked)
    }

    /// Deny (cancel) an agent action after Face ID / Touch ID.
    func denyAgentAction(_ id: String) async throws {
        guard isAuthenticated, lockState == .unlocked else { throw JarvisAPIError.unauthorized }
        guard let id = UUID(uuidString: id) else { throw JarvisAPIError.invalidResponse }
        try await auth.denyAgentAction(id, unlocked: stillUnlocked)
    }

    /// Pairing decisions sign or deny only after Face ID / Touch ID on this device.
    func approvePairing(_ item: PairingApprovalItem) async throws {
        guard isAuthenticated, lockState == .unlocked else { throw JarvisAPIError.unauthorized }
        try await auth.approvePairing(item)
    }

    func denyPairing(_ item: PairingApprovalItem) async throws {
        guard isAuthenticated, lockState == .unlocked else { throw JarvisAPIError.unauthorized }
        try await auth.denyPairing(item)
    }

    /// This device's own key fingerprint, grouped, for the pairing wait screen.
    func ownKeyFingerprint() async -> String? {
        (try? await auth.ownKeyFingerprint()).map(PairingFingerprint.grouped)
    }

    /// Register (token, environment) or remove (nil) this device's approval push target.
    func setPushTarget(_ target: (token: String, environment: String)?) async throws {
        guard isAuthenticated, lockState == .unlocked else { throw JarvisAPIError.unauthorized }
        try await auth.setPushTarget(target)
    }

    func registeredDeviceID() async -> UUID? {
        guard isAuthenticated, lockState == .unlocked else { return nil }
        return await auth.registeredDeviceID()
    }

    /// Refresh only the thread list; unlike loadConversations it never changes
    /// the open conversation.
    func refreshConversationList() async {
        guard isAuthenticated, lockState == .unlocked else { return }
        let generation = presentation.id
        do {
            let snapshot = try await chat.conversations()
            guard presentation.accepts(generation) else { return }
            conversations = snapshot
        } catch {
            guard presentation.accepts(generation) else { return }
            if (error as? JarvisAPIError) == .unauthorized { handle(error) } else { notice = safeMessage(error) }
        }
    }

    func start() async {
        // Retried by every identity use; signing fails closed until it succeeds.
        do { try await auth.migrateIdentity() } catch { notice = safeMessage(error) }
        guard let endpoint = endpointStore.endpoint else {
            connectionState = .unconfigured
            return
        }
        await api.configure(baseURL: endpoint)
        guard await checkConnection() else { return }
        do {
            if try await auth.requiresLocalUnlock() {
                lockState = .locked
                await unlock()
            } else {
                await restoreAuthentication()
            }
        } catch {
            enrollmentState = .failed(safeMessage(error))
        }
    }

    @discardableResult
    func saveEndpoint() async -> Bool {
        do {
            let endpoint = try EndpointNormalizer.normalize(endpointText)
            let changed = endpoint != endpointStore.endpoint
            if changed {
                invalidatePresentation(clearIdentity: true)
                realtime.stop(); speech.stop(); voiceReleaseTask?.cancel(); pendingRequests.clear()
                try await auth.clearLocalBinding()
                messages = []; conversations = []; currentConversationId = nil
            }
            endpointStore.save(endpoint)
            endpointText = endpoint.absoluteString
            // A new binding only for a new Home Node: requests (and photo deletes) bound to
            // the current one stay valid when the same address is saved again.
            if changed { await api.configure(baseURL: endpoint) }
            notice = nil
            guard await checkConnection() else { return false }
            if try await auth.requiresLocalUnlock() {
                lockState = .locked
                await unlock()
            } else {
                await restoreAuthentication()
            }
            return true
        } catch {
            connectionState = .unreachable(safeMessage(error))
            return false
        }
    }

    @discardableResult
    func checkConnection() async -> Bool {
        connectionState = .checking
        do {
            try await api.checkReadiness()
            connectionState = .reachable
            return true
        } catch {
            connectionState = .unreachable(safeMessage(error))
            return false
        }
    }

    func requestEnrollment(password: String? = nil, activationCode: String? = nil) async {
        let generation = presentation.id
        enrollmentState = .requesting
        do {
            let result: AuthServiceOutcome
            if let activationCode, let password {
                result = try await auth.activateFirstDevice(deviceName: UIDevice.current.name, code: activationCode, password: password)
            } else {
                result = try await auth.requestEnrollment(deviceName: UIDevice.current.name, password: password)
            }
            guard presentation.accepts(generation) else { return }
            apply(awaitResult: result)
            if result == .authenticated { await restoreAuthentication() }
        }
        catch {
            guard presentation.accepts(generation) else { return }
            if activationCode != nil {
                // Bootstrap may have succeeded before login failed. Restore a
                // saved device binding instead of attempting bootstrap twice.
                let recovery = try? await auth.restore(renew: false)
                guard presentation.accepts(generation) else { return }
                applyActivationFailure(error, recovery: recovery)
                if recovery == .authenticated { await restoreAuthentication() }
            } else {
                handle(error)
            }
        }
    }

    func applyActivationFailure(_ error: Error, recovery: AuthServiceOutcome?) {
        apply(awaitResult: recovery ?? .needsActivation)
        notice = safeMessage(error)
    }

    func refreshEnrollment() async {
        let generation = presentation.id
        enrollmentState = .authenticating
        do {
            let result = try await auth.refreshEnrollment()
            guard presentation.accepts(generation) else { return }
            apply(awaitResult: result)
            if result == .authenticated {
                lockState = .locked
                await unlock()
            }
        } catch { if presentation.accepts(generation) { handle(error) } }
    }

    func restoreAuthentication() async {
        let generation = presentation.id
        enrollmentState = .authenticating
        do {
            let result = try await auth.restore()
            guard presentation.accepts(generation) else { return }
            apply(awaitResult: result)
            if result == .authenticated {
                approvalPush.signedIn()
                await loadConversations()
                guard presentation.accepts(generation) else { return }
                let available = await chat.realtimeAvailable()
                guard presentation.accepts(generation) else { return }
                realtimeAvailable = available
                if realtimeAvailable, let origin = endpointStore.endpoint, lockState == .unlocked {
                    speech.enabled = voiceEnabled
                    realtime.start(origin: origin, auth: auth, speech: speech) { [weak self] event in try await self?.receiveRealtime(event) }
                }
            }
        } catch { if presentation.accepts(generation) { handle(error) } }
    }

    func unlock() async {
        guard !isUnlocking else { return }
        isUnlocking = true
        defer { isUnlocking = false }
        let generation = presentation.id
        let result = await biometricLock.unlock(reason: "Unlock your Jarvis conversations")
        guard presentation.accepts(generation) else { return }
        switch result {
        case .unlocked:
            lockState = .unlocked
            await restoreAuthentication()
        case .cancelled:
            lockState = .locked
        case .denied:
            lockState = .denied
        case let .unavailable(message):
            lockState = .unavailable(message)
        }
    }

    func unlockOnForeground() async {
        guard needsForegroundUnlock, !isUnlocking else { return }
        needsForegroundUnlock = false
        guard lockState != .unlocked else { return }
        await unlock()
    }

    func beginSignIn() async {
        do {
            if try await auth.requiresLocalUnlock() {
                lockState = .locked
                await unlock()
            } else {
                await restoreAuthentication()
            }
        } catch { handle(error) }
    }

    func lockWhenBackgrounded() {
        needsForegroundUnlock = true
        invalidatePresentation(clearIdentity: false)
        voiceReleaseTask?.cancel()
        realtime.stop(); speech.stop()
        if isAuthenticated { lockState = .locked }
    }

    /// Decoded chat photos; bounded, cleared with every presentation reset (sign-out, erase,
    /// expired session, Home Node change).
    private var photos: [String: ChatPhoto] = [:]

    /// One chat photo for display: a thumbnail, or `full` size for the full-screen view (not
    /// cached). A passing failure is not remembered; the next showing retries.
    func photo(id: String, full: Bool = false) async -> ChatPhoto {
        if removedPhotos.contains(id) { return .removed }
        if !full, let cached = photos[id] { return cached }
        guard let uuid = UUID(uuidString: id) else { return .unavailable }
        let generation = presentation.id
        let result: ChatPhoto
        do {
            let data = try await chat.photo(id: uuid)
            // Decoded off the main actor, already shrunk: 24 cached thumbnails stay small.
            result = await Task.detached(priority: .userInitiated) { ChatPhoto.thumbnail(data, maxPixels: full ? 1600 : 512) }.value
                .map(ChatPhoto.image) ?? .unavailable
        } catch let JarvisAPIError.rejected(status, _) where status == 404 {
            if presentation.accepts(generation) { markRemoved(id) }
            return .removed
        } catch {
            // An expired session goes through the usual sign-in-again flow (once: it resets the generation).
            if (error as? JarvisAPIError) == .unauthorized, presentation.accepts(generation) { handle(error) }
            return .unavailable
        }
        // A late answer from before a reset is dropped.
        guard presentation.accepts(generation) else { return .unavailable }
        if !full { remember(id, result) }
        return result
    }

    private func remember(_ id: String, _ photo: ChatPhoto) {
        // ponytail: drop-all when full; an LRU if conversations show many photos.
        if photos.count >= 24 { photos.removeAll() }
        if case .unavailable = photo {} else { photos[id] = photo }
    }

    /// Adds picked or pasted photos, up to four in the composer. Each is shrunk and
    /// uploaded at once.
    func attachPhotos(_ loads: [() async throws -> Data]) {
        chipNotice = nil
        for load in loads {
            guard chips.count < ChatPhotos.maxPhotos else { chipNotice = ChatPhotos.limit; break }
            let chip = PhotoChip()
            chips.append(chip)
            Task { await prepare(chip.id, load) }
        }
    }

    /// Clipboard images, or a word that there are none.
    func pastePhotos(_ loads: [() async throws -> Data]) {
        if loads.isEmpty { chipNotice = ChatPhotos.nothingToPaste } else { attachPhotos(loads) }
    }

    private func prepare(_ key: UUID, _ load: () async throws -> Data) async {
        let generation = photoSession.id
        // Changes the chip while it is still in the composer.
        func update(_ change: (inout PhotoChip) -> Void) -> Bool {
            guard photoSession.accepts(generation), let index = chips.firstIndex(where: { $0.id == key }) else { return false }
            change(&chips[index])
            return true
        }
        let data: Data
        do { data = try await load() } catch {
            _ = update { $0.state = .failed; $0.error = (error as? ChatPhotos.Failure)?.message ?? ChatPhotos.unreadable }
            return
        }
        // Decoded and re-encoded off the main actor.
        let prepared = await Task.detached(priority: .userInitiated) {
            Result<(jpeg: Data, preview: UIImage?), Error> {
                let jpeg = try ChatPhotos.downscale(data)
                return (jpeg, ChatPhoto.thumbnail(jpeg))
            }
        }.value
        let jpeg: Data
        switch prepared {
        case let .success(photo):
            jpeg = photo.jpeg
            guard update({ $0.preview = photo.preview; $0.state = .uploading }) else { return }
        case let .failure(error):
            _ = update { $0.state = .failed; $0.error = (error as? ChatPhotos.Failure)?.message ?? ChatPhotos.unsupported }
            return
        }
        var uploaded: (id: UUID, binding: UUID)?
        var failure: String?
        do { uploaded = try await chat.uploadPhoto(jpeg) }
        catch let JarvisAPIError.rejected(status, _) { failure = ChatPhotos.uploadFailure(status) }
        catch JarvisAPIError.unreachable, JarvisAPIError.timedOut { failure = ChatPhotos.unreachable }
        catch {
            if (error as? JarvisAPIError) == .unauthorized, photoSession.accepts(generation) { handle(error); return }
            failure = ChatPhotos.uploadFailure(0)
        }
        let kept = update { chip in
            if let uploaded {
                chip.photoId = uploaded.id; chip.binding = uploaded.binding; chip.state = .ready; chip.uploadedAt = Date()
            }
            else { chip.state = .failed; chip.error = failure }
        }
        // Removed (✕) while uploading: delete what Core kept. After a sign-out the photo
        // stays pending and Core removes it after 24 hours.
        if !kept, photoSession.accepts(generation), let uploaded { forget(id: uploaded.id, binding: uploaded.binding) }
    }

    /// ✕ on a chip: drop it, and delete it on the Home Node once uploaded.
    func removeChip(_ key: UUID) {
        guard let chip = chips.first(where: { $0.id == key }) else { return }
        chips.removeAll { $0.id == key }
        forget(chip)
        chipNotice = nil
    }

    /// Photos Core would no longer attach fail in place.
    func expireStaleChips(now: Date = Date()) { chips = ChatPhotos.expired(chips, now: now) }

    /// Best-effort delete of an uploaded photo that will not be sent, on the Home Node it
    /// was uploaded to only.
    private func forget(_ chip: PhotoChip) {
        if let id = chip.photoId, let binding = chip.binding { forget(id: id, binding: binding) }
    }

    private func forget(id: UUID, binding: UUID) {
        Task { try? await chat.deletePhoto(id: id, binding: binding) }
    }

    /// Hands the uploaded photos to a sent message; their previews go into the cache so the
    /// message shows them without a download. Returns them, for restoreChips.
    private func takeChips() -> [PhotoChip] {
        let taken = chips.filter { $0.state == .ready && $0.photoId != nil }
        for chip in taken {
            if let id = chip.photoId, let preview = chip.preview { remember(id.uuidString.lowercased(), .image(preview)) }
        }
        chips = []; chipNotice = nil
        if !taken.isEmpty { photosDisclosed = true }
        return taken
    }

    /// Puts the photos of a refused message back in the composer; any without room are deleted.
    private func restoreChips(_ taken: [PhotoChip]) {
        let restored = ChatPhotos.restore(taken, into: chips)
        chips = restored.chips
        restored.dropped.forEach { forget($0) }
        if !restored.dropped.isEmpty { chipNotice = ChatPhotos.limit }
    }

    /// Keep (or stop keeping) a photo past its 90 days. A photo Core no longer has (deleted
    /// elsewhere or expired) is marked removed instead.
    func keepPhoto(_ id: String, saved: Bool) async throws {
        guard let uuid = UUID(uuidString: id) else { return }
        let generation = presentation.id
        do {
            let meta = try await chat.setPhotoSaved(id: uuid, saved: saved)
            if presentation.accepts(generation), meta.key == id { photoMeta[id] = meta }
        } catch JarvisAPIError.rejected(status: 404, message: _) {
            if presentation.accepts(generation) { markRemoved(id) }
        } catch { throw failed(error, generation) }
    }

    /// Delete a photo for good; its messages show "Photo removed". Already gone counts as deleted.
    func deletePhoto(_ id: String) async throws {
        guard let uuid = UUID(uuidString: id) else { return }
        let generation = presentation.id
        do { try await chat.deletePhoto(id: uuid) }
        catch JarvisAPIError.rejected(status: 404, message: _) {}
        catch { throw failed(error, generation) }
        if presentation.accepts(generation) { markRemoved(id) }
    }

    private func markRemoved(_ id: String) {
        photoMeta[id] = nil
        removedPhotos.insert(id)
        remember(id, .removed)
    }

    /// An expired session goes through the usual sign-in-again flow; the error is passed on.
    private func failed(_ error: Error, _ generation: UUID) -> Error {
        if (error as? JarvisAPIError) == .unauthorized, presentation.accepts(generation) { handle(error) }
        return error
    }

    func logout() async {
        await approvalPush.signingOut(reset: false)
        invalidatePresentation(clearIdentity: true)
        let generation = presentation.id
        enrollmentState = .signedOut
        voiceReleaseTask?.cancel()
        pendingRequests.clear()
        realtime.stop(); speech.stop()
        do {
            let result = try await auth.logout()
            guard presentation.accepts(generation) else { return }
            apply(awaitResult: result)
            messages = []
            conversations = []
        } catch { if presentation.accepts(generation) { handle(error) } }
    }

    /// Revokes this device on Core with a signed approval, then erases it here. If Core
    /// does not confirm, nothing is erased: `localEraseOffer` asks the user instead.
    func resetDevice() async {
        var failure: Error?
        do { try await auth.unlinkDevice() } catch { failure = error }
        switch Self.afterUnlink(failure) {
        case .erase: await eraseLocally()
        case .nothing: break
        case let .notice(message): notice = message
        case let .offerLocalErase(reason): localEraseOffer = reason
        }
    }

    enum UnlinkOutcome: Equatable { case erase, nothing, notice(String), offerLocalErase(String) }

    /// Only a confirmed revoke erases. A refusal or unconfirmed revoke offers erasing locally;
    /// a cancelled or busy prompt offers nothing.
    static func afterUnlink(_ failure: Error?) -> UnlinkOutcome {
        guard let failure else { return .erase }
        switch failure {
        // The user stopped, or another prompt is open: nothing to offer.
        case let interrupted as ApprovalInterrupted: return .notice(safeMessage(interrupted))
        case is CancellationError: return .nothing
        // Server text is shown sanitised and bounded.
        default: return .offerLocalErase(PairingFingerprint.displayName(safeMessage(failure), max: 160))
        }
    }

    /// Unlock requests for this device; nil when signed out, locked or on any error.
    func pendingUnlocks() async -> [UnlockRequestItem]? {
        guard isAuthenticated, lockState == .unlocked else { return nil }
        let generation = presentation.id
        // Errors only pause polling; any other owner read still handles a real 401.
        let requests = try? await auth.pendingUnlocks(wait: 10)
        guard presentation.accepts(generation), lockState == .unlocked else { return nil }
        return requests
    }

    func unlockChoices(_ id: UUID) async throws -> [Int] {
        guard isAuthenticated, lockState == .unlocked else { throw JarvisAPIError.unauthorized }
        return try await auth.unlockChoices(id)
    }

    func approveUnlock(_ id: UUID, choice: Int) async throws {
        guard isAuthenticated, lockState == .unlocked else { throw JarvisAPIError.unauthorized }
        try await auth.approveUnlock(id, choice: choice, unlocked: stillUnlocked)
    }

    /// Checked again after Face ID: the app may have locked while the prompt was open.
    private var stillUnlocked: @Sendable () async -> Bool {
        { @MainActor [weak self] in
            guard let self else { return false }
            return self.isAuthenticated && self.lockState == .unlocked
        }
    }

    func denyUnlock(_ id: UUID) async throws {
        guard isAuthenticated, lockState == .unlocked else { throw JarvisAPIError.unauthorized }
        try await auth.denyUnlock(id, unlocked: stillUnlocked)
    }

    /// Changes the account password (one Face ID / Touch ID prompt). Core then ends every
    /// session, so this device signs in again with the new password.
    func changePassword(current: String, new: String) async throws {
        guard isAuthenticated, lockState == .unlocked else { throw JarvisAPIError.rejected(status: 423, message: "Unlock Jarvis first.") }
        try await auth.changePassword(current: current, new: new)
        invalidatePresentation(clearIdentity: true)
        realtime.stop(); speech.stop(); voiceReleaseTask?.cancel()
        enrollmentState = .signedOut
        notice = "Password changed. Sign in with the new password."
    }

    /// Starts a forgotten-password reset (one Face ID / Touch ID prompt). Nothing changes until
    /// the owner confirms the returned request id as root on the Home Node.
    func resetPassword(new: String) async throws -> UUID {
        guard isAuthenticated, lockState == .unlocked else { throw JarvisAPIError.rejected(status: 423, message: "Unlock Jarvis first.") }
        let id = try await auth.resetPassword(new: new)
        pendingPasswordReset = id
        return id
    }

    /// Revokes another device of this account (one Face ID / Touch ID prompt).
    func revokeDevice(_ id: UUID) async throws {
        guard isAuthenticated, lockState == .unlocked else { throw JarvisAPIError.rejected(status: 423, message: "Unlock Jarvis first.") }
        try await auth.revokeDevice(id, unlocked: stillUnlocked)
    }

    /// Erases this device here only; after a failed revoke this is the user's explicit choice.
    func eraseLocally() async {
        localEraseOffer = nil
        // Only once erasing is decided: a failed revoke kept by the user keeps its push target.
        await approvalPush.signingOut(reset: true)
        invalidatePresentation(clearIdentity: true)
        let generation = presentation.id
        enrollmentState = .notStarted
        voiceReleaseTask?.cancel()
        pendingRequests.clear()
        realtime.stop(); speech.stop()
        do {
            try await auth.eraseLocally()
            guard presentation.accepts(generation) else { return }
            enrollmentState = .notStarted
            lockState = .unlocked
            messages = []
            conversations = []
            currentConversationId = nil
            notice = "This device identity was removed locally. Request enrollment to create a new one."
        } catch { if presentation.accepts(generation) { handle(error) } }
    }

    func loadConversations() async {
        guard isAuthenticated, lockState == .unlocked else { return }
        let generation = presentation.id
        do {
            let snapshot = try await chat.conversations()
            guard presentation.accepts(generation) else { return }
            conversations = snapshot
            if let id = currentConversationId ?? conversations.first?.id {
                await openConversation(id)
            }
        } catch { if presentation.accepts(generation) { handle(error) } }
    }

    func openConversation(_ id: UUID) async {
        guard isAuthenticated, lockState == .unlocked else { return }
        let generation = presentation.id
        currentConversationId = id
        researchOffer = nil
        do {
            let conversation = try await chat.conversation(id: id)
            guard currentConversationId == id, presentation.accepts(generation) else { return }
            currentConversationId = conversation.id
            show(conversation)
        } catch { if presentation.accepts(generation) { handle(error) } }
    }

    private func show(_ conversation: ConversationResponse) {
        currentConversationTitle = conversation.title
        messages = conversation.messages
        isSending = conversation.assistantRunning == true
        let live = conversation.photos ?? []
        photoMeta = Dictionary(live.map { ($0.key, $0) }, uniquingKeysWith: { first, _ in first })
        // An older Core reports no photos at all: then none is assumed gone.
        removedPhotos = conversation.photos == nil ? [] : ChatPhotos.missing(conversation.messages, photos: live)
    }

    func newConversation() {
        currentConversationId = nil
        currentConversationTitle = "New conversation"
        messages = []
        isSending = false
        researchOffer = nil; photoMeta = [:]; removedPhotos = []
    }

    /// Delete the open conversation for good, its photos included (kept ones too), then
    /// open the most recent one left.
    func deleteConversation() async throws {
        guard let id = currentConversationId, isAuthenticated, lockState == .unlocked else { return }
        let generation = presentation.id
        do { try await chat.deleteConversation(id: id) } catch { throw failed(error, generation) }
        guard presentation.accepts(generation) else { return }
        conversations.removeAll { $0.id == id }
        guard currentConversationId == id else { return }
        if let next = conversations.first?.id { await openConversation(next) } else { newConversation() }
    }

    /// Only this tap sends a web search; the shown question is all that leaves.
    func searchWeb() async {
        guard realtimeAvailable, let offer = researchOffer, offer.conversationId == currentConversationId else { return }
        await send(offer.question, research: true)
    }

    /// Sends a message with the composer's photos (a photo alone is a message too).
    /// `research` only ever comes from the owner's tap on a Core offer. Returns true when
    /// nothing was sent, refused here or outright by Core, so the draft can come back.
    @discardableResult
    func send(_ text: String, research: Bool = false) async -> Bool {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let attached = research ? [] : chips.compactMap(\.photoId)
        guard !trimmed.isEmpty || !attached.isEmpty, isAuthenticated, lockState == .unlocked, !isSending,
              research || ChatPhotos.allReady(chips) else { return true }
        let generation = presentation.id
        let photoGeneration = photoSession.id
        if realtimeAvailable && pendingRequests.isFull {
            notice = "Too many unconfirmed requests. Reconnect before sending more; no automatic regeneration will occur."
            return true
        }
        // Photos go only with the runs API; the legacy chat endpoint refuses them.
        if !attached.isEmpty && !realtimeAvailable {
            reply("Photos need a live connection to your Home Node. Nothing was sent.")
            return true
        }
        researchOffer = nil
        let taken = attached.isEmpty ? [] : takeChips()
        let priorMessages = messages
        let now = ISO8601DateFormatter().string(from: Date())
        messages.append(ConversationMessage(role: "user", content: trimmed, model: nil, at: now,
                                            attachments: attached.isEmpty ? nil : attached.map { $0.uuidString.lowercased() }))
        isSending = true
        if realtimeAvailable {
            let requestId = UUID()
            guard let optimisticID = messages.last?.id, pendingRequests.insert(requestId, optimisticID: optimisticID) else {
                isSending = false
                messages.removeLast()
                restoreChips(taken)
                return true
            }
            let selected = currentConversationId
            do {
                let run = try await chat.submit(requestId: requestId, text: trimmed, conversationId: selected, history: priorMessages,
                                                research: research, attachments: attached)
                guard presentation.accepts(generation) else { return false }
                if selected == nil, currentConversationId == nil, pendingRequests[requestId] != nil { currentConversationId = run.conversation_id }
            } catch {
                let shown = presentation.accepts(generation)
                if case let .rejected(status, _)? = error as? JarvisAPIError, let refusal = ChatPhotos.runRefusal(status) {
                    // Core stored nothing: the message, its draft and its photos come back. After
                    // the background lock the chat was cleared, but the composer photos remain.
                    pendingRequests.removeValue(forKey: requestId)
                    if shown {
                        isSending = false
                        messages.removeAll { $0.id == optimisticID }
                        reply(refusal)
                    }
                    if photoSession.accepts(photoGeneration) { restoreChips(taken) }
                    return shown || photoSession.accepts(photoGeneration)
                }
                guard shown else { return false }
                isSending = false
                notice = "Delivery was not confirmed. Reconnect to check saved history; Jarvis will not automatically generate again."
            }
            return false
        }
        defer { if presentation.accepts(generation) { isSending = false } }
        do {
            let response = try await chat.send(
                text: trimmed,
                conversationId: currentConversationId,
                history: priorMessages
            )
            guard presentation.accepts(generation) else { return false }
            currentConversationId = response.conversationId
            currentConversationTitle = response.conversationTitle
            if response.newTopic && !priorMessages.isEmpty {
                await openConversation(response.conversationId)
            } else {
                messages.append(ConversationMessage(
                    role: "assistant",
                    content: response.reply,
                    model: response.model,
                    at: ISO8601DateFormatter().string(from: Date())
                ))
            }
            guard presentation.accepts(generation) else { return false }
            let snapshot = try await chat.conversations()
            guard presentation.accepts(generation) else { return false }
            conversations = snapshot
        } catch { if presentation.accepts(generation) { handle(error) } }
        return false
    }

    /// A local Jarvis line in the chat (not stored on the Home Node).
    private func reply(_ text: String) {
        messages.append(ConversationMessage(role: "assistant", content: text, model: nil, at: ISO8601DateFormatter().string(from: Date())))
    }

    private func apply(awaitResult result: AuthServiceOutcome) {
        // Not signed in (any more): the composer photos go too.
        if case .authenticated = result {} else { dropChips() }
        switch result {
        case .needsEnrollment: enrollmentState = .notStarted
        case .needsPassword: enrollmentState = .needsPassword
        case .needsActivation: enrollmentState = .needsActivation
        case let .awaitingApproval(expiresAt): enrollmentState = .awaitingApproval(expiresAt: expiresAt)
        case .authenticated: enrollmentState = .authenticated
        case .signedOut: enrollmentState = .signedOut
        }
    }

    private func receiveRealtime(_ event: RealtimeEvent) async throws {
        guard isAuthenticated, lockState == .unlocked else { return }
        let generation = presentation.id
        speech.receive(event)
        let payload = event.payload
        switch event.type {
        case "connection.ready":
            do {
                let origin = endpointStore.endpoint
                let recovered = try await chat.recover(requests: pendingRequests.requests)
                try Task.checkCancellation()
                guard presentation.accepts(generation), isAuthenticated, lockState == .unlocked, endpointStore.endpoint == origin else { return }
                for (request, result) in recovered { pendingRequests.reconcile(request, result: result) }
                let snapshot = try await chat.conversations()
                guard presentation.accepts(generation) else { return }
                conversations = snapshot
                if let selected = currentConversationId {
                    let snapshot = try await RealtimeEventDelivery.selectedHistory {
                        try await chat.conversation(id: selected)
                    }
                    guard presentation.accepts(generation) else { return }
                    if currentConversationId == selected {
                        if let snapshot { show(snapshot) } else { newConversation() }
                    }
                }
            } catch {
                if presentation.accepts(generation) { notice = "History reconciliation failed; reconnecting to retry." }
                throw error
            }
        case "conversation.created", "conversation.updated":
            if let id = payload.id, let title = payload.title, let at = payload.updated_at {
                conversations.removeAll { $0.id == id }
                conversations.append(ConversationSummary(id: id, title: title, updatedAt: at))
                conversations.sort { $0.updatedAt > $1.updatedAt }
            }
        case "conversation.deleted":
            conversations.removeAll { $0.id == payload.conversation_id }
            if currentConversationId == payload.conversation_id { newConversation() }
        case "message.created":
            if let message = payload.message {
                let optimistic = payload.request_id.flatMap { pendingRequests[$0] }
                if currentConversationId == nil, let optimistic, messages.contains(where: { $0.id == optimistic }) { currentConversationId = message.conversation_id }
                if currentConversationId == message.conversation_id {
                    upsertRealtime(message, optimistic: optimistic)
                }
            }
        case "assistant.started":
            if payload.conversation_id == currentConversationId, let run = payload.run_id {
                isSending = true
                if !messages.contains(where: { $0.id == "run:\(run)" }) {
                    webSearchRun = (payload.web_search == true || WebAnswer.startsAfterReply(messages)) ? run : nil
                    messages.append(ConversationMessage(role: "assistant", content: "", model: nil, at: "", canonicalId: "run:\(run)"))
                }
            }
        case "assistant.delta":
            if let run = payload.run, run.conversation_id == currentConversationId, let text = payload.text {
                isSending = true
                if !messages.contains(where: { $0.id == "run:\(run.run_id)" }) {
                    messages.append(ConversationMessage(role: "assistant", content: "", model: nil, at: "", canonicalId: "run:\(run.run_id)"))
                }
                guard let index = messages.firstIndex(where: { $0.id == "run:\(run.run_id)" }) else { return }
                let old = messages[index]
                if old.content.utf8.count + text.utf8.count <= 128 * 1024 {
                    messages[index] = ConversationMessage(role: "assistant", content: old.content + text, model: nil, at: old.at, canonicalId: old.id)
                }
            }
        case "assistant.completed":
            if let run = payload.run, let message = payload.message {
                pendingRequests.removeValue(forKey: run.request_id)
                if currentConversationId == message.conversation_id {
                    upsertRealtime(message, optimistic: "run:\(run.run_id)"); isSending = false
                    researchOffer = ResearchOffer.from(payload)
                }
            }
        case "assistant.failed":
            if let run = payload.run {
                pendingRequests.removeValue(forKey: run.request_id)
                if currentConversationId == run.conversation_id {
                    isSending = false; notice = "Response interrupted. Your message is saved."
                    // Drop the run's empty placeholder row (also an automatic web search's).
                    messages.removeAll { $0.id == "run:\(run.run_id)" && $0.content.isEmpty }
                    webSearchRun = nil
                }
            }
        default: break
        }
    }

    private func handle(_ error: Error) {
        if (error as? JarvisAPIError) == .unauthorized {
            invalidatePresentation(clearIdentity: true)
            realtime.stop(); speech.stop(); voiceReleaseTask?.cancel()
            enrollmentState = .signedOut
        } else if (error as? JarvisAPIError) == .unreachable ||
                    (error as? JarvisAPIError) == .timedOut {
            connectionState = .unreachable(safeMessage(error))
        } else {
            enrollmentState = .failed(safeMessage(error))
        }
        notice = safeMessage(error)
    }

    private func upsertRealtime(_ message: RealtimeMessage, optimistic: String?) {
        let row = message.conversationMessage
        var inserted = false
        messages = messages.compactMap { existing in
            guard existing.id == row.id || existing.id == optimistic else { return existing }
            if inserted { return nil }
            inserted = true
            return row
        }
        if !inserted { messages.append(row) }
    }

    private func safeMessage(_ error: Error) -> String { Self.safeMessage(error) }

    private static func safeMessage(_ error: Error) -> String {
        (error as? LocalizedError)?.errorDescription ?? "Jarvis could not complete the request."
    }
}
