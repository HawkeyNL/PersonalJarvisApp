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
