# Unified Jarvis client release setup

Repository: `HawkeyNL/PersonalJarvisApp` (public). Releases use `app-vX.Y.Z`
and coordinate desktop, Android, and iOS from one exact source revision. This
document describes owner actions; it does not authorize a release.

## GitHub configuration

Create the protected Environment `application-release`. Where the GitHub plan
supports it, require owner approval and restrict deployments to `main`.
Ordinary `ci.yml` must remain read-only and must not reference this Environment.
Allow the release workflow's final job to use its repository-scoped
`GITHUB_TOKEN` with `contents: write`. No personal token, Home Node SSH access,
or third release repository is needed.

Repository variables:

- `TAURI_SIGNING_PUBLIC_KEY`: Tauri updater public key text;
- `ANDROID_SIGNING_CERTIFICATE_SHA256`: lowercase SHA-256 fingerprint of the
  stable APK signing certificate.

Environment secrets for Tauri updater signing:

- `TAURI_SIGNING_PRIVATE_KEY`
- `TAURI_SIGNING_PRIVATE_KEY_PASSWORD`

Environment secrets for Android:

- `ANDROID_RELEASE_KEYSTORE_BASE64`
- `ANDROID_RELEASE_KEY_ALIAS`
- `ANDROID_RELEASE_STORE_PASSWORD`
- `ANDROID_RELEASE_KEY_PASSWORD`

The same APK certificate must be retained for every Android upgrade. The
workflow checks the built APK fingerprint against the repository variable
before accepting the artifact.

Environment secrets for macOS desktop signing/notarization:

- `MACOS_DEVELOPER_ID_CERTIFICATE_P12_BASE64`
- `MACOS_DEVELOPER_ID_CERTIFICATE_PASSWORD`
- `MACOS_DEVELOPER_ID_APPLICATION`
- `MACOS_NOTARY_API_ISSUER_ID`
- `MACOS_NOTARY_API_KEY_ID`
- `MACOS_NOTARY_API_PRIVATE_KEY_BASE64`

Environment secrets for iOS/TestFlight:

- `APPLE_DISTRIBUTION_CERTIFICATE_P12_BASE64`
- `APPLE_DISTRIBUTION_CERTIFICATE_PASSWORD`
- `APPLE_APP_STORE_PROVISIONING_PROFILE_BASE64`
- `APPLE_TEAM_ID`
- `APP_STORE_CONNECT_API_ISSUER_ID`
- `APP_STORE_CONNECT_API_KEY_ID`
- `APP_STORE_CONNECT_API_PRIVATE_KEY_BASE64`

macOS release artifacts must be Developer ID signed, hardened, notarized, and
stapled. iOS is archived and uploaded to App Store Connect/TestFlight; no IPA is
published or mirrored. Windows artifacts receive Tauri updater signatures but
are not currently Authenticode-signed.

For `main`, recommend required pull requests, required client CI checks, blocked
force-pushes and branch deletion, and optional stale-review dismissal. Choose
the owner/admin bypass policy explicitly.

## Tauri updater key

Use the checked-in Tauri 2 tooling from `desktop/`. The command prompts for a
password; do not put that password on the command line:

```bash
cd desktop
npm ci
install -d -m 0700 "$HOME/.local/share/jarvis-signing"
umask 077
npm run tauri -- signer generate \
  --write-keys "$HOME/.local/share/jarvis-signing/desktop.key"
```

Never commit the private key. Keep the key and password in secure owner backups
and enter them through GitHub's secret UI. Put only the public key in
`TAURI_SIGNING_PUBLIC_KEY`. Losing this private key breaks updater continuity;
rotation requires a migration trusted by already-installed clients.

## First release: `app-v0.1.0`

1. Merge the reviewed Core cleanup and client-monorepo changes with CI green.
2. Configure `application-release`, the two variables, and all relevant secrets.
3. Back up the Tauri, Android, and Apple signing identities securely.
4. Confirm desktop npm/Cargo/Tauri versions, Android `versionName`, and iOS
   `MARKETING_VERSION` are all `0.1.0`.
5. Confirm Android `versionCode=1` and iOS build number `1` are unused and
   greater than any prior build distributed under the same identities.
6. Run:

   ```bash
   python3 update-release/client_release.py \
     --version 0.1.0 --android-version-code 1 --ios-build-number 1
   python3 -m unittest discover -s update-release/tests -v
   ```

7. Run the complete client CI on `main`.
8. Manually dispatch `.github/workflows/release.yml` with `version=0.1.0`,
   `android_version_code=1`, and `ios_build_number=1`.
9. Approve the `application-release` Environment jobs if configured.
10. Verify Linux, Windows, notarized macOS, signed APK/AAB, and TestFlight jobs.
11. Verify the final job creates `app-v0.1.0` only after redownloading and
    checking the complete same-revision artifact set and signed manifest.
12. Inspect the public release and TestFlight build. Never manually publish an
    incomplete draft.
13. Configure the server-owned Home Node mirror from PersonalJarvis, perform one
    manual sync, then enable its timer.
14. Test update capability/download with an enrolled client. A second signed
    release is required to prove an actual upgrade end to end.

Production signing, notarization, TestFlight upload, and public release
publication can only be verified in the protected GitHub workflow with the real
owner credentials; local tests deliberately cannot claim those outcomes.

## Updating the protocol dependency

`PersonalJarvis/crates/client-core` remains authoritative. Merge a compatible
change there, obtain the reviewed exact commit, then update
`desktop/src-tauri/Cargo.toml` and its lockfile. Core and app versions remain
independent; the explicit protocol fields in release/capability metadata are the
compatibility boundary.
