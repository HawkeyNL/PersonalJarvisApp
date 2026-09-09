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
Desktop's separate **Stop spraak** button clears the current native speech
buffer and cancels queued playback without changing its saved enable preference,
disconnecting realtime, or cancelling inference. Late deltas do not resume that
utterance; a later assistant run may speak. Server-side desktop ownership release
still requires completion. Native-engine failures are displayed using fixed
status labels only; OS error text and spoken content are never status payloads.
The offline engine interface is cancellation-safe and tested with fake engines:
disconnect/stop drops playback, an unavailable engine does not retry every phrase,
and a full 32-item queue stops speech for the remainder of that run. These tests
do not demonstrate audible OS playback or macOS/Windows runtime behavior.

## Validation and remaining acceptance

Run `npm ci`, `npm run check`, and `npm run test:unit` from `desktop/`, and
`cargo test --manifest-path desktop/src-tauri/Cargo.toml --locked` from the root.
Android: `./gradlew testDebugUnitTest lintDebug assembleDebug --no-daemon` from
`android/` with the SDK configured by the existing CI workflow.
The same application consumes `android/realtime-core/` for its pure event DTOs
and speech state. Run `./gradlew :realtime-core:test --no-daemon` from `android/`
to test that actual code on a JDK without an Android SDK or signing credentials.
iOS: the unsigned simulator build/test commands in `.github/workflows/ci.yml`
require a macOS/Xcode runner; no distribution signing credentials are involved.

Mocked Core integration tests cover two authenticated sockets, one inference,
identical canonical final text, voice-owner gating, retry deduplication and
REST recovery. They do not substitute for actual desktop/iPhone/Android audio,
sleep/wake, background/resume, or network-transition acceptance on devices.
