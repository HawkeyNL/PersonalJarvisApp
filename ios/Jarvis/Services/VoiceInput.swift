import AVFoundation
import Observation
import Speech
import UIKit

/// The rules of chat voice input, apart from the audio for tests. Mirrors desktop useMic.ts.
enum VoiceInputRules {
    /// The mic turns itself off after this much quiet.
    static let silence: TimeInterval = 5
    /// A level above this counts as speech and keeps the mic on.
    static let speakLevel = 0.06
    /// The longest transcript taken from one listening.
    static let maxTranscript = 4000
    /// After the mic stops, the words heard so far are used if the recogniser stays silent this long.
    static let finishTimeout: Duration = .seconds(10)
    /// With VoiceOver on, capture starts after the button's own announcement.
    static let voiceOverDelay: Duration = .milliseconds(800)

    static let speechOff = "Speech recognition is off for Jarvis. Turn it on in Settings › Privacy & Security › Speech Recognition."
    static let microphoneOff = "Microphone access is off for Jarvis. Turn it on in Settings › Privacy & Security › Microphone."
    static let restricted = "Voice input is not allowed on this device."
    static let unavailable = "Speech recognition is not available right now. Try again later."
    static let failed = "Voice input could not start. Try again."
    static let serverNotice = "This iPhone cannot recognise Dutch speech on the device. What you say is sent to Apple to be turned into text; Jarvis gets only the text."

    /// Root mean square of one buffer of samples.
    static func rms<Samples: Collection>(_ samples: Samples) -> Double where Samples.Element == Float {
        guard !samples.isEmpty else { return 0 }
        let sum = samples.reduce(Float(0)) { $0 + $1 * $1 }
        return Double((sum / Float(samples.count)).squareRoot())
    }

    /// The meter rises with the voice at once and falls back slowly.
    static func level(previous: Double, rms: Double) -> Double {
        max(previous * 0.7, min(1, rms * 4))
    }

    static func isQuiet(since lastSpeech: Date, now: Date, after quiet: TimeInterval = silence) -> Bool {
        now.timeIntervalSince(lastSpeech) >= quiet
    }

    /// The draft with the recognised words added after it.
    static func appending(_ said: String, to draft: String) -> String {
        let said = String(said.trimmingCharacters(in: .whitespacesAndNewlines).prefix(maxTranscript))
        guard !said.isEmpty else { return draft }
        return draft.isEmpty || draft.last?.isWhitespace == true ? draft + said : draft + " " + said
    }
}

/// The microphone and the recogniser that VoiceInput drives; a seam for tests.
@MainActor
protocol VoiceCapture: AnyObject {
    /// Whether Dutch speech can be recognised now.
    var isAvailable: Bool { get }
    /// False when recognition sends the audio to Apple.
    var recognizesOnDevice: Bool { get }
    /// Told on the main actor when `isAvailable` changes.
    var availabilityChanged: ((Bool) -> Void)? { get set }
    /// Asks for speech and microphone access: nil when both are granted, else why not.
    func authorize() async -> String?
    /// Starts the microphone and the recognition. `level` gets each buffer's RMS, `heard` the
    /// text so far and whether it is final, `interrupted` a call or audio reconfiguration;
    /// any of them may come on another thread.
    func start(level: @escaping @Sendable (Double) -> Void, heard: @escaping @Sendable (String?, Bool) -> Void,
               interrupted: @escaping @Sendable () -> Void) throws
    /// No more audio: the recogniser gives its final text.
    func endAudio()
    /// Drops the recognition.
    func cancel()
    /// The microphone off and the audio session given back (spoken replies play again).
    func releaseAudio()
}

/// Push-to-talk for the chat composer: Dutch speech to text, on the device when it can.
/// The level follows the voice; the mic stops after 5 s of silence. The owner (the app
/// model) stops it with `discard` on every lock, reset and conversation change, so nothing
/// records while locked and no words reach another conversation.
@MainActor
@Observable
final class VoiceInput {
    private(set) var listening = false
    /// 0...1, for the meter.
    private(set) var level = 0.0
    /// Why the mic did not start; cleared by the next tap.
    var problem: String?
    /// Whether Dutch speech can be recognised now; the mic button hides otherwise.
    private(set) var available: Bool
    /// The mic stopped and the last words are on their way.
    private(set) var finishing = false

    @ObservationIgnored private let capture: VoiceCapture
    @ObservationIgnored private let defaults: UserDefaults
    @ObservationIgnored private var session = ChatPresentationLifetime()
    @ObservationIgnored private var lastSpeech = Date()
    @ObservationIgnored private var heard = ""
    @ObservationIgnored private var starting = false
    @ObservationIgnored private var deliver: ((String) -> Void)?
    /// The silence watch while listening, the finish timeout after.
    @ObservationIgnored private var timer: Task<Void, Never>?
    /// The rules' timings; tests shorten them.
    @ObservationIgnored var silence = VoiceInputRules.silence
    @ObservationIgnored var finishTimeout = VoiceInputRules.finishTimeout

    private static let serverNoticeKey = "jarvis.voice.serverNoticeShown"

    convenience init() { self.init(capture: SpeechCapture(), defaults: .standard) }

    init(capture: VoiceCapture, defaults: UserDefaults) {
        self.capture = capture
        self.defaults = defaults
        available = capture.isAvailable
        capture.availabilityChanged = { [weak self] available in self?.available = available }
    }

    /// Recognition would send the audio to Apple and the owner has not been told yet.
    var needsServerNotice: Bool { !capture.recognizesOnDevice && !defaults.bool(forKey: Self.serverNoticeKey) }
    func acknowledgeServerNotice() { defaults.set(true, forKey: Self.serverNoticeKey) }

    /// Asks for speech and microphone access the first time, then listens while `allowed`
    /// holds. `deliver` gets the words heard, once, after the mic stopped.
    func start(while allowed: @escaping () -> Bool, deliver: @escaping (String) -> Void) async {
        guard !listening, !finishing, !starting, allowed() else { return }
        guard capture.isAvailable else { problem = VoiceInputRules.unavailable; return }
        starting = true
        defer { starting = false }
        problem = nil
        let generation = session.id
        if let refusal = await capture.authorize() {
            if session.accepts(generation) { problem = refusal }
            return
        }
        // VoiceOver reads the button on activation; the mic must not type that.
        if UIAccessibility.isVoiceOverRunning { try? await Task.sleep(for: VoiceInputRules.voiceOverDelay) }
        // A lock, reset, conversation change or leaving the chat meanwhile discarded this attempt.
        guard session.accepts(generation), allowed() else { return }
        heard = ""
        lastSpeech = Date()
        self.deliver = deliver
        do {
            try capture.start(
                level: { [weak self] rms in Task { @MainActor in self?.hear(rms, generation: generation) } },
                heard: { [weak self] text, final in
                    Task { @MainActor in self?.recognized(text, final: final, generation: generation) }
                },
                interrupted: { [weak self] in
                    Task { @MainActor in if self?.session.accepts(generation) == true { self?.stop() } }
                })
        } catch {
            stop(discard: true)
            problem = VoiceInputRules.failed
            return
        }
        listening = true
        watchSilence(generation)
    }

    /// Stops listening. The words heard so far still arrive, unless `discard` (the lock, a
    /// reset, a conversation change, leaving the chat): then they are dropped and nothing
    /// more is delivered.
    func stop(discard: Bool = false) {
        if discard {
            session.invalidate()
            capture.cancel()
            heard = ""
            deliver = nil
            finishing = false
            release()
            return
        }
        guard listening else { return }
        capture.endAudio()
        release()
        finishing = true
        let generation = session.id
        let wait = finishTimeout
        timer = Task { [weak self] in
            try? await Task.sleep(for: wait)
            guard !Task.isCancelled, let self, self.session.accepts(generation) else { return }
            self.finish()
        }
    }

    /// Like desktop's timer: the mic stops after 5 s without speech, also when no audio comes in.
    private func watchSilence(_ generation: UUID) {
        timer = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(250))
                guard !Task.isCancelled, let self, self.listening, self.session.accepts(generation) else { return }
                if VoiceInputRules.isQuiet(since: self.lastSpeech, now: Date(), after: self.silence) {
                    self.stop()
                    return
                }
            }
        }
    }

    private func hear(_ rms: Double, generation: UUID) {
        guard listening, session.accepts(generation) else { return }
        level = VoiceInputRules.level(previous: level, rms: rms)
        if level > VoiceInputRules.speakLevel { lastSpeech = Date() }
    }

    private func recognized(_ text: String?, final: Bool, generation: UUID) {
        guard session.accepts(generation) else { return }
        if let text, !text.isEmpty {
            heard = String(text.prefix(VoiceInputRules.maxTranscript))
            // Words coming in keep the mic on, like a loud enough level.
            lastSpeech = Date()
        }
        if final { finish() }
    }

    /// The recognition is over (its final text, an error or the timeout): deliver once.
    private func finish() {
        let said = heard
        let deliver = deliver
        session.invalidate()
        capture.cancel()
        heard = ""
        self.deliver = nil
        finishing = false
        release()
        if !said.isEmpty { deliver?(said) }
    }

    private func release() {
        timer?.cancel()
        timer = nil
        listening = false
        level = 0
        capture.releaseAudio()
    }
}

/// The real microphone and recogniser: Dutch, on the device when the device can.
@MainActor
final class SpeechCapture: NSObject, VoiceCapture, SFSpeechRecognizerDelegate {
    var availabilityChanged: ((Bool) -> Void)?
    private let recognizer = SFSpeechRecognizer(locale: Locale(identifier: "nl-NL"))
    private let engine = AVAudioEngine()
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var task: SFSpeechRecognitionTask?
    private var observers: [NSObjectProtocol] = []
    private var holdsAudio = false

    override init() {
        super.init()
        recognizer?.delegate = self
    }

    var isAvailable: Bool { recognizer?.isAvailable == true }
    var recognizesOnDevice: Bool { recognizer?.supportsOnDeviceRecognition == true }

    nonisolated func speechRecognizer(_ speechRecognizer: SFSpeechRecognizer, availabilityDidChange available: Bool) {
        Task { @MainActor [weak self] in self?.availabilityChanged?(available) }
    }

    func authorize() async -> String? {
        let speech = await withCheckedContinuation { done in
            SFSpeechRecognizer.requestAuthorization { done.resume(returning: $0) }
        }
        switch speech {
        case .authorized: break
        case .restricted: return VoiceInputRules.restricted
        default: return VoiceInputRules.speechOff
        }
        return await AVAudioApplication.requestRecordPermission() ? nil : VoiceInputRules.microphoneOff
    }

    func start(level: @escaping @Sendable (Double) -> Void, heard: @escaping @Sendable (String?, Bool) -> Void,
               interrupted: @escaping @Sendable () -> Void) throws {
        guard let recognizer else { throw CocoaError(.featureUnsupported) }
        let audio = AVAudioSession.sharedInstance()
        holdsAudio = true
        try audio.setCategory(.record, mode: .measurement, options: .duckOthers)
        try audio.setActive(true, options: .notifyOthersOnDeactivation)
        let request = SFSpeechAudioBufferRecognitionRequest()
        request.shouldReportPartialResults = true
        if recognizer.supportsOnDeviceRecognition { request.requiresOnDeviceRecognition = true }
        let input = engine.inputNode
        let format = input.outputFormat(forBus: 0)
        // No microphone (a simulator without input): installing a tap would crash.
        guard format.channelCount > 0, format.sampleRate > 0 else { throw CocoaError(.featureUnsupported) }
        input.installTap(onBus: 0, bufferSize: 1024, format: format, block: Self.tap(request, meter: level))
        // A call, Siri or a new audio route ends the listening.
        let center = NotificationCenter.default
        observers = [
            center.addObserver(forName: AVAudioSession.interruptionNotification, object: audio, queue: nil) { _ in interrupted() },
            center.addObserver(forName: .AVAudioEngineConfigurationChange, object: engine, queue: nil) { _ in interrupted() },
        ]
        engine.prepare()
        try engine.start()
        self.request = request
        task = recognizer.recognitionTask(with: request, resultHandler: Self.results(heard))
    }

    func endAudio() { request?.endAudio() }

    func cancel() {
        task?.cancel()
        task = nil
        request = nil
    }

    func releaseAudio() {
        for observer in observers { NotificationCenter.default.removeObserver(observer) }
        observers = []
        guard holdsAudio else { return }
        holdsAudio = false
        engine.stop()
        engine.inputNode.removeTap(onBus: 0)
        let audio = AVAudioSession.sharedInstance()
        try? audio.setActive(false, options: .notifyOthersOnDeactivation)
        try? audio.setCategory(.soloAmbient)
    }

    /// Built outside the main actor: the tap runs on the audio thread.
    private nonisolated static func tap(_ request: SFSpeechAudioBufferRecognitionRequest,
                                        meter: @escaping @Sendable (Double) -> Void) -> @Sendable (AVAudioPCMBuffer, AVAudioTime) -> Void {
        { buffer, _ in
            request.append(buffer)
            guard let samples = buffer.floatChannelData else { return }
            meter(VoiceInputRules.rms(UnsafeBufferPointer(start: samples[0], count: Int(buffer.frameLength))))
        }
    }

    /// Built outside the main actor: results arrive on a background queue.
    private nonisolated static func results(_ handle: @escaping @Sendable (String?, Bool) -> Void)
        -> @Sendable (SFSpeechRecognitionResult?, Error?) -> Void {
        { result, error in handle(result?.bestTranscription.formattedString, result?.isFinal == true || error != nil) }
    }
}
