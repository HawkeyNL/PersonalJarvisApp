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

Disabling speech stops presentation only, not the shared server run. Device
voice/rate selection UI and native playback-status reporting are not yet wired;
the server's typed claim/release/playback contract provides their boundary.

## Validation and remaining acceptance

Run `npm ci`, `npm run check`, and `npm run test:unit` from `desktop/`, and
`cargo test --manifest-path desktop/src-tauri/Cargo.toml --locked` from the root.
Android: `./gradlew testDebugUnitTest lintDebug assembleDebug --no-daemon` from
`android/` with the SDK configured by the existing CI workflow.
iOS: the unsigned simulator build/test commands in `.github/workflows/ci.yml`
require a macOS/Xcode runner; no distribution signing credentials are involved.

Mocked Core integration tests cover two authenticated sockets, one inference,
identical canonical final text, voice-owner gating, retry deduplication and
REST recovery. They do not substitute for actual desktop/iPhone/Android audio,
sleep/wake, background/resume, or network-transition acceptance on devices.
