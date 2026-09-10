# Realtime clients (protocol 1)

The authoritative protocol is `PersonalJarvis/crates/client-core`. Desktop pins
its exact reviewed Git revision in `desktop/src-tauri/Cargo.toml`; Android and
iOS decode the same public envelope through native DTOs. This branch requires
the corresponding Core feature branch to be reachable before a clean remote
Cargo fetch can succeed. No sibling path or floating branch dependency is used.

All clients negotiate `/v1/events/capability`, submit new chat runs over HTTP,
and receive native authenticated WebSocket events through `/v1/events`. Older
servers retain the existing synchronous HTTP fallback. HTTP submission failure
is not automatically retried as a new prompt: an ambiguous paid generation must
be reconciled first. Final REST/persisted message IDs replace provisional rows.

Tokens remain in the existing native secure-storage/authentication boundary.
Vue receives decoded public events only, never socket headers or bearer tokens.
Home Node origins remain runtime-configured HTTPS origins. The existing HTTPS
ingress carries WebSocket upgrades; no new public port is required.

Reconnect uses bounded exponential backoff with jitter. A ready event triggers
authoritative conversation-list/selected-history reconciliation. Events for a
different conversation update metadata without changing the selected screen.
Mobile clients stop sockets/speech on background or lock and reconnect after
foreground authentication. No permanent mobile foreground service, APNs, or
background socket guarantee is introduced.

## Local voice

Voice is off by default. Enabling it permits speech only when the server names
this authenticated device as the current run's voice owner. Speech consumes
assistant text directly, buffers phrases, skips fenced code, and flushes only
the unsaid suffix at completion. It never invokes an LLM or a cloud TTS API.

- iOS: `AVSpeechSynthesizer`, bounded to 32 queued phrases.
- Android: `TextToSpeech`, an installed voice that does not require a network
  connection, bounded to 32 queued phrases.
- macOS desktop: fixed `/usr/bin/say`, text supplied through stdin.
- Linux desktop: fixed `/usr/bin/espeak-ng` if locally installed; missing engine
  leaves text chat available and does not download a model or use cloud speech.
- Windows desktop: text/realtime supported; local speech backend not implemented.

Disabling speech stops presentation only, not the shared server run. Android
also has a Stop speech action that preserves the preference for future answers.
Its OS callbacks report one started/stopped/failed lifecycle per run through a
16-item native queue, using only the run ID and fixed status. Stale callbacks
are discarded, and Core atomically checks the current owner/run before fanout.
The report queue binds its credential snapshot to the enrolled origin and is
cancelled on logout/origin switch; reports never contain speech text or tokens.
Desktop/iOS playback reporting and device voice/rate selection remain open.
Android release commands now carry a snapshot of the currently observed owned
run ID, never an arbitrary device identity. A delayed release cannot clear a
newer run. Android and iOS clear old voice ownership on `connection.ready`;
reconnection requires a fresh ownership event before speech is allowed.
Android's shared JVM regression tests exercise this directly. The corresponding
iOS regression is present but still requires the macOS/Xcode CI runner.
iOS native completion/cancellation callbacks now remove individual utterance
identities from a lock-protected 32-entry registry. A late callback after stop
cannot decrement the newer queue, and duplicate callbacks cannot free extra
slots. The XCTest regression is added; it has not run on this Linux host.
Desktop's separate **Stop spraak** button clears the current native speech
buffer and cancels queued playback without changing its saved enable preference,
disconnecting realtime, or cancelling inference. Late deltas do not resume that
utterance; a later assistant run may speak. Desktop stop/mute also releases the
observed run through native authenticated HTTP. The one-pending-request queue
uses the socket's immutable origin/session snapshot, disables redirects, and is
cancelled on disconnect/logout/origin switch. Each release carries its run ID so
a delayed request cannot clear a newer voice lease on the same device.
Preference changes do not restart the text socket; enabling applies to the next
answer, not a replay of the current answer. Native-engine failures use fixed
status labels only; OS error text and spoken content are never status payloads.
The offline engine interface is cancellation-safe and tested with fake engines:
disconnect/stop drops playback, an unavailable engine does not retry every phrase,
and a full 32-item queue stops speech for the remainder of that run. These tests
do not demonstrate audible OS playback or macOS/Windows runtime behavior.

## Validation and remaining acceptance

Desktop chat presentation has a session generation separate from authorization.
Logout/reset/origin changes clear pending IDs, selected conversation and displayed
history. Late REST results and event callbacks from the old generation cannot
repopulate the new session. Logout also stops native realtime/speech before
waiting for best-effort remote revocation. Node tests exercise delayed completion
across invalidation; this does not replace native credential-boundary checks.

Desktop recovery retains at most 32 outstanding request correlations (IDs only,
not prompt copies). Unknown outcomes are never silently evicted to make room
for another paid submission. On reconnect, run IDs (or original request IDs when
the submission acknowledgement was lost) are queried through read-only HTTP;
terminal results clear correlations, while unknown failures do
not trigger regeneration. Late submit acknowledgements cannot resurrect a
completed correlation. Conversation REST `assistant_running` restores busy state
even when generation originated on a different device. Older Core responses
without that additive field remain readable.
Android and iOS also restore their busy/sending state from `assistant_running`
when opening or reconciling a conversation. Their DTO tests cover both older
responses without this field and active-generation responses. Those app-level
Android/iOS tests require the SDK/Xcode CI runners and have not run on this host.
Android's pending request registry is bounded to 32 ID-only correlations and
tested by the SDK-independent JVM suite. Reconnect queries the device-bound
request-status endpoint using a single origin/token snapshot, at most 32 GETs
with five-second request deadlines. Only matching terminal statuses clear an
entry; unavailable/unknown results never trigger another POST. Logout, device
reset and origin changes clear local correlations. Native HTTP integration still
requires Android SDK/runner validation; JVM tests do not establish that coverage.

iOS also bounds request correlations to 32 and performs read-only recovery by
original request ID. Matching terminal states clear entries; recovery never
resubmits prompts. Its configuration generation is captured before loading the
native token. Dispatch checks that binding and cancellation; production HTTP
rejects redirects. Origin changes (including A -> B -> A) invalidate the binding.
Logout/reset/origin changes clear pending state. Network-disabled XCTest
regressions are added but still require execution on a macOS/Xcode runner.

Run `npm ci`, `npm run check`, and `npm run test:unit` from `desktop/`, and
`cargo test --manifest-path desktop/src-tauri/Cargo.toml --locked` from the root.
Android: `./gradlew testDebugUnitTest lintDebug assembleDebug --no-daemon` from
`android/` with the SDK configured by the existing CI workflow.
The same application consumes `android/realtime-core/` for its pure event DTOs
and speech state. Run `./gradlew :realtime-core:test --no-daemon` from `android/`
to test that actual code on a JDK without an Android SDK or signing credentials.
iOS also exposes Stop speaking in its chat menu and settings. It preserves the
enabled preference, stops local audio immediately, and releases only its observed
owned run through native authenticated HTTP. Release is bound to the original
origin and configuration generation; logout/background/reset/origin change
cancels pending work. No text or device identity is supplied in that request.
XCTest coverage is added but has not been executed without Xcode.

iOS: the unsigned simulator build/test commands in `.github/workflows/ci.yml`
require a macOS/Xcode runner; no distribution signing credentials are involved.

Mocked Core integration tests cover two authenticated sockets, one inference,
identical canonical final text, voice-owner gating, retry deduplication and
REST recovery. They do not substitute for actual desktop/iPhone/Android audio,
sleep/wake, background/resume, or network-transition acceptance on devices.
