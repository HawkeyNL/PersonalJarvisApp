# Jarvis client applications

This repository is the canonical source for every end-user Jarvis client:

- `desktop/`: Vue 3 and Tauri 2 for Linux, Windows, and macOS;
- `android/`: native Kotlin and Jetpack Compose;
- `ios/`: native SwiftUI for iPhone and iPad.

Tauri's generated Apple scaffolding under `desktop/src-tauri/gen/apple/` belongs
to the desktop build system; it is not a second canonical native iOS client.

Jarvis Core, the Home Node, API, authenticated update delivery, and the
authoritative `jarvis-client-core` crate live in
[HawkeyNL/PersonalJarvis](https://github.com/HawkeyNL/PersonalJarvis). The
desktop crate consumes `jarvis-client-core` from one exact reviewed Core commit;
Android and iOS retain their reviewed native protocol models until a shared
cross-language schema exists.

The clients share one application SemVer and release tag, `app-vX.Y.Z`. Android
also has a monotonically increasing `versionCode`; iOS has a monotonically
increasing build number. These identities are independent of Core `vX.Y.Z`.
Wire-protocol compatibility—not matching product versions—controls whether a
client can use a Home Node.

Every client receives its credential-free Home Node HTTPS origin at runtime.
No production address is compiled into an app. Desktop uses the operating
system credential store, Android uses its keystore-backed encrypted storage,
and iOS uses Keychain for device/session secrets.

Application binaries must use private GHCR upstream storage, not public GitHub
Release or Actions artifacts. The release workflow transfers desktop/Android/iOS
builds privately and promotes a complete signed OCI release only after exact-byte
redownload validation. Home Node deployment and real-device acceptance are separate
owner steps, not implied by passing local tests. Desktop updates retain
Tauri signature validation; Android retains package/certificate/hash checks.
iOS includes an unsigned physical-device IPA installer for owner-side signing,
not automatic installation. See [iOS sideload candidates](docs/IOS_SIDELOAD.md).
No Apple account credentials or iOS provisioning secrets are used by CI.

See [release setup](docs/GITHUB_RELEASE_SETUP.md) for the protected release
environment, required variables/secrets, and the first-release checklist.

Source visibility does not grant an open-source license or permission to reuse
this software. Copyright © Gus Theunissen. All rights reserved.

## Development

Desktop (from `desktop/`):

```bash
npm ci
npm run check
npm run test:unit
npm run desktop:test:native
npm run desktop:build
```

The optional wake-word assets are fetched with `npm run setup-wakeword` and are
not committed. Linux Tauri builds require Node.js 24, Rust 1.97.1, WebKitGTK
4.1, and the dependencies installed by client CI.

Android (from `android/`, with JDK 17 and Android SDK Platform 37.0):

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug --no-daemon
```

iOS (from `ios/`, on macOS with Xcode 16):

```bash
xcodebuild -project Jarvis.xcodeproj -scheme Jarvis \
  -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' \
  CODE_SIGNING_ALLOWED=NO build
```

Normal CI never receives production signing credentials. Its iOS job uses an
unsigned simulator build. Distributable desktop and Android releases are
created by the protected workflow, either manually from `main` or from a stable
`app-vX.Y.Z` tag pointing to a reviewed commit reachable from `main`. The tag must
match the checked-in client versions; it does not silently rewrite them.

## Shared protocol pin

To update desktop's client-core dependency:

1. merge the compatible protocol/client-core change in PersonalJarvis;
2. review and copy its exact 40-character commit SHA;
3. update `desktop/src-tauri/Cargo.toml` and `desktop/src-tauri/Cargo.lock`;
4. run the complete client CI before releasing.

Never replace the pin with a branch, sibling path, or copied crate.

## Model access controls

Desktop and mobile model controls require an enrolled, approved device and a
Home Node advertising `device-signed-model-toggle-v1`. Confirming a change
opens native OS authentication: biometrics with the platform's credential
fallback where supported. Successful model authorization is remembered only in
native memory for a fixed five minutes (not extended by subsequent changes).
Each change still requires confirmation and its own fresh, action-bound signed
approval. Lock, logout, device reset and Home Node/session changes revoke the
window, including pending authentication. App restart also clears it. Wall-clock
changes cannot extend the monotonic limit; suspend beyond five minutes expires it.
No device password or private signing key is sent
to the Home Node or exposed to the desktop frontend.

Changing Home Node/session, cancelling authentication, an expired approval,
or a changed policy requires a new confirmation. The UI refreshes authoritative
state after a change; it does not optimistically claim success. Older Core
versions without the capability remain read-only. Core and client product
versions remain independent.

## Pairing approvals

A new device asks to pair with `POST /v1/auth/pairing/requests` (account
password required; Core answers `403 pairing_password_not_set` until an
account password has been set from an active device). While it waits, the new
device shows its own key fingerprint: the lowercase hex SHA-256 of its raw
32-byte Ed25519 public key, in groups of four.

An already paired device lists pending requests (`GET
/v1/auth/pairing/requests`) and shows, per request, the device name, platform,
model, app version, IP address, user agent, request time, a countdown to expiry
and the key fingerprint. Model, app version, IP address and user agent are
unauthenticated hints from the candidate (shown as "unknown" on Cores that do
not send them) and are rendered as bounded plain text only.

The fingerprint shown to the approver is always computed locally from
`candidate_public_key`, the key the approval signature binds. If Core's
`fingerprint` (16-hex prefix) or `fingerprint_full` disagrees with it, the app
shows an error and refuses to approve. The owner compares this fingerprint
with the one the new device shows before approving.

| Client | Where | Owner check before signing or denying |
|--------|-------|---------------------------------------|
| iOS | Context › Devices › Pairing requests, or `jarvis://approvals/pairing` | Face ID / Touch ID only (`deviceOwnerAuthenticationWithBiometrics`, no passcode fallback) for approve and deny |
| Android | Context › Devices › Pairing requests, or `jarvis://approvals/pairing` | `BiometricPrompt` with `BIOMETRIC_STRONG` only (no device credential) for approve and deny |
| Desktop | Overlay on every page | OS authentication in the native layer before signing an approval |

Approving signs the v1 `pairing_approval_message` of `jarvis-client-core`
(desktop in Rust; iOS and Android re-implement it and test against the same
golden vector). Without usable biometrics the mobile apps say "Biometrics
required" and offer no approve or deny buttons. The mobile list refreshes every
30 seconds while open (Core allows five list reads per source IP per minute).
The pairing request does not yet send `app_version` or `model`: Core rejects
unknown fields and exposes no version before sign-in, so a candidate cannot tell
whether the Home Node accepts them.

## Notification deep links

Core's owner notifications (ntfy `Click` URL) open the apps through exactly
two links. A link only navigates: it never approves, denies or changes
anything. A locked or signed-out app shows the screen after unlock and sign-in.
Any other `jarvis://` link is ignored and the app opens as usual.

| Link | iOS / Android | Desktop |
|------|---------------|---------|
| `jarvis://approvals/pairing` | Pairing requests (Context → Devices) | Hub, where pending pairing requests are shown |
| `jarvis://health/usage` | System Health → Usage | `/health?node=usage` |

iOS registers the scheme in `ios/Jarvis/Info.plist`, Android in the
`MainActivity` intent filter. The desktop app does not register the scheme
yet: on Linux and Windows that needs `tauri-plugin-deep-link` together with
`tauri-plugin-single-instance`, or every click would start a second app.
Desktop only has the tested in-app router (`deepLinkRoute` in
`desktop/src/hubModel.ts`) for when OS registration is added.
