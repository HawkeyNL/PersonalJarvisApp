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
Home Node advertising `device-signed-model-toggle-v1`. On macOS, every model or
routing change opens its own Touch ID prompt (no computer-password fallback)
before the device key signs that one action-bound approval; there is no window.
Windows Hello always offers its PIN and Linux has no biometric prompt, so both
refuse to sign: change models from your phone or the Home Node. Lock, logout,
device reset and Home Node/session changes cancel a change still waiting for
its prompt. No device password or private signing key is sent to the Home Node
or exposed to the desktop frontend.

The desktop Touch ID requirement is a prompt gate in the native layer: the
macOS device key itself is stored in the keychain without biometric access
control. Binding the key to biometry is a follow-up.

The desktop app lock is a privacy screen and signs nothing. It unlocks with
the local OS prompt (Touch ID, Windows Hello or the computer password); where
that prompt is unavailable, "Sign out instead" ends the session and signing in
again needs the account password. Linux has no such prompt, so the lock is off
there.

On iOS there is no window: every model or routing change, like sign-in and
pairing approval, needs its own Face ID / Touch ID prompt (no passcode), and
the device key itself can only be read after that prompt.

Changing Home Node/session, cancelling authentication, an expired approval,
or a changed policy requires a new confirmation. The UI refreshes authoritative
state after a change; it does not optimistically claim success. Older Core
versions without the capability remain read-only. Core and client product
versions remain independent.

Android likewise: every model change needs its own `BIOMETRIC_STRONG` prompt that
unlocks the device signing key, with no device-credential fallback.

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
| Desktop | Overlay on every page | Shows requests and can deny them; approve from your phone |

Core accepts pairing, unlock, account and agent approvals only from iOS and
Android devices, so the desktop app signs none of them. Today the phones
approve pairing requests (and model changes); unlock, account-password and
agent approvals are not available on any client yet. A device is revoked on
the Home Node with `sudo jarvis devices revoke <id>`. Approving signs the v1
`pairing_approval_message` of `jarvis-client-core` (iOS and Android
re-implement it and test against its golden vector). Without usable
biometrics the mobile apps say "Biometrics required" and offer no approve or
deny buttons. The mobile list refreshes every
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
