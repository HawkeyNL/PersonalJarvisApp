import AVFoundation
import Observation
import Speech

/// The rules of chat voice input, apart from the audio for tests. Mirrors desktop useMic.ts.
enum VoiceInputRules {
    /// The mic turns itself off after this much quiet.
    static let silence: TimeInterval = 5
    /// A level above this counts as speech and keeps the mic on.
    static let speakLevel = 0.06
    /// The longest transcript taken from one listening.
    static let maxTranscript = 4000

    static let speechOff = "Speech recognition is off for Jarvis. Turn it on in Settings › Privacy & Security › Speech Recognition."
    static let microphoneOff = "Microphone access is off for Jarvis. Turn it on in Settings › Privacy & Security › Microphone."
    static let restricted = "Voice input is not allowed on this device."
    static let failed = "Voice input could not start. Try again."

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

    static func isQuiet(since lastSpeech: Date, now: Date) -> Bool {
        now.timeIntervalSince(lastSpeech) >= silence
    }

    /// The draft with the recognised words added after it.
    static func appending(_ said: String, to draft: String) -> String {
        let said = String(said.trimmingCharacters(in: .whitespacesAndNewlines).prefix(maxTranscript))
        guard !said.isEmpty else { return draft }
        return draft.isEmpty || draft.last?.isWhitespace == true ? draft + said : draft + " " + said
    }
}

/// Push-to-talk for the chat composer: Dutch speech to text, on the device when it can.
/// The level follows the voice; the mic stops after 5 s of silence. The owner (the app
/// model) stops it with `discard` on every lock and reset, so nothing records while locked.
@MainActor
@Observable
final class VoiceInput {
    private(set) var listening = false
    /// 0...1, for the meter.
    private(set) var level = 0.0
    /// Why the mic did not start (permission off); cleared by the next tap.
    var problem: String?

    @ObservationIgnored private let recognizer = SFSpeechRecognizer(locale: Locale(identifier: "nl-NL"))
    @ObservationIgnored private let engine = AVAudioEngine()
    @ObservationIgnored private var request: SFSpeechAudioBufferRecognitionRequest?
    @ObservationIgnored private var holdsAudio = false
    @ObservationIgnored private var session = ChatPresentationLifetime()
    @ObservationIgnored private var lastSpeech = Date()
    @ObservationIgnored private var heard = ""
    @ObservationIgnored private var starting = false
    /// The recognition still running, also after the mic stopped and until its last words arrive.
    private var task: SFSpeechRecognitionTask?

    /// Whether Dutch speech can be recognised now; the mic button hides otherwise.
    var available: Bool { recognizer?.isAvailable == true }
    /// The mic stopped and the last words are on their way.
    var finishing: Bool { task != nil && !listening }

    /// Asks for speech and microphone access the first time, then listens while `allowed`
    /// holds. `deliver` gets the words heard, once, after the mic stopped.
    func start(while allowed: @escaping () -> Bool, deliver: @escaping (String) -> Void) async {
        guard !listening, !starting, task == nil, let recognizer, recognizer.isAvailable, allowed() else { return }
        starting = true
        defer { starting = false }
        problem = nil
        let generation = session.id
        if let refusal = await Self.authorize() {
            if session.accepts(generation) { problem = refusal }
            return
        }
        // A lock, reset or leaving the chat during the prompts discarded this attempt.
        guard session.accepts(generation), allowed() else { return }
        do {
            try begin(recognizer, generation: generation, deliver: deliver)
        } catch {
            stop(discard: true)
            problem = VoiceInputRules.failed
        }
    }

    /// Stops listening. The words heard so far still arrive, unless `discard` (the lock, a
    /// reset, leaving the chat): then they are dropped and nothing more is delivered.
    func stop(discard: Bool = false) {
        if discard {
            session.invalidate()
            task?.cancel(); task = nil; request = nil; heard = ""
        } else {
            request?.endAudio()
        }
        release()
    }

    private static func authorize() async -> String? {
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

    private func begin(_ recognizer: SFSpeechRecognizer, generation: UUID, deliver: @escaping (String) -> Void) throws {
        let audio = AVAudioSession.sharedInstance()
        holdsAudio = true
        try audio.setCategory(.record, mode: .measurement, options: .duckOthers)
        try audio.setActive(true, options: .notifyOthersOnDeactivation)
        let request = SFSpeechAudioBufferRecognitionRequest()
        // Words coming in keep the mic on, like a loud enough level.
        request.shouldReportPartialResults = true
        if recognizer.supportsOnDeviceRecognition { request.requiresOnDeviceRecognition = true }
        let input = engine.inputNode
        let format = input.outputFormat(forBus: 0)
        // No microphone (a simulator without input): installing a tap would crash.
        guard format.channelCount > 0, format.sampleRate > 0 else { throw CocoaError(.featureUnsupported) }
        input.installTap(onBus: 0, bufferSize: 1024, format: format,
                         block: Self.tap(request) { [weak self] rms in
                             Task { @MainActor in self?.hear(rms, generation: generation) }
                         })
        engine.prepare()
        try engine.start()
        self.request = request
        heard = ""
        lastSpeech = Date()
        listening = true
        task = recognizer.recognitionTask(with: request, resultHandler: Self.results { [weak self] text, final in
            Task { @MainActor in self?.recognized(text, final: final, generation: generation, deliver: deliver) }
        })
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

    private func hear(_ rms: Double, generation: UUID) {
        guard listening, session.accepts(generation) else { return }
        level = VoiceInputRules.level(previous: level, rms: rms)
        let now = Date()
        if level > VoiceInputRules.speakLevel { lastSpeech = now }
        else if VoiceInputRules.isQuiet(since: lastSpeech, now: now) { stop() }
    }

    private func recognized(_ text: String?, final: Bool, generation: UUID, deliver: (String) -> Void) {
        guard session.accepts(generation) else { return }
        if let text, !text.isEmpty {
            heard = String(text.prefix(VoiceInputRules.maxTranscript))
            lastSpeech = Date()
        }
        guard final else { return }
        let said = heard
        session.invalidate()
        task = nil; request = nil; heard = ""
        release()
        if !said.isEmpty { deliver(said) }
    }

    /// The mic off and the audio session given back (spoken replies play again).
    private func release() {
        listening = false
        level = 0
        guard holdsAudio else { return }
        holdsAudio = false
        engine.stop()
        engine.inputNode.removeTap(onBus: 0)
        let audio = AVAudioSession.sharedInstance()
        try? audio.setActive(false, options: .notifyOthersOnDeactivation)
        try? audio.setCategory(.soloAmbient)
    }
}
