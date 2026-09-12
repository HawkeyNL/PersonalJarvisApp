# Unified Jarvis client release setup

> Private GHCR distribution replaces public GitHub artifacts. The workflow and
> Rust mirror require separate review/CI/production acceptance before activation.
> iOS joins the private release as an unsigned, manual-owner-signing installer.
> The separate candidate workflow also remains available; see [IOS_SIDELOAD.md](IOS_SIDELOAD.md).

Repository: `HawkeyNL/PersonalJarvisApp` (public). Releases use `app-vX.Y.Z`
and coordinate distributable desktop and Android artifacts from one exact
source revision. iOS shares the version and provides a physical-device unsigned
IPA for local owner signing, never an automatic updater target. This document describes
owner actions; it does not authorize a release.

## GitHub configuration

Create the protected Environment `application-release`. Where the GitHub plan
supports it, require owner approval. Permit the `main` branch for manual releases
and the tag pattern `app-v*` for tag-triggered releases. The validation job also
requires the source commit to be reachable from `main`; the tag pattern alone
is not the trust boundary. Change these Environment rules manually after review.
Ordinary `ci.yml` must remain read-only and must not reference this Environment.
Allow the release/signing jobs to use their repository-scoped `GITHUB_TOKEN`
with `contents: read` and `packages: write`. The existing container package
`ghcr.io/hawkeynl/jarvis-client-artifacts` must be private and grant this repository
Actions access. Visibility is checked before/after transfers. No personal token,
Home Node SSH access, DNS secret or third release repository is needed in CI.
The Home Node alone stores a local read:packages token for outbound pulls.

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

macOS release artifacts must be Developer ID signed, hardened, notarized, and
stapled. These macOS identities are unrelated to iOS. This coordinated workflow
needs no iOS signing secret or team identifier. Windows artifacts receive Tauri updater signatures but are not
currently Authenticode-signed.

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
3. Back up the Tauri, Android, and macOS signing identities securely.
4. Confirm desktop npm/Cargo/Tauri versions, Android `versionName`, and iOS
   `MARKETING_VERSION` are all `0.1.0`.
5. Confirm Android `versionCode=1` is unused and greater than any previously
   distributed Android build. Keep the checked-in iOS build number positive
   and internally consistent for local Xcode development.
6. Run:

   ```bash
   python3 update-release/client_release.py \
     --version 0.1.0 --android-version-code 1
   python3 -m unittest discover -s update-release/tests -v
   ```

7. Run the complete client CI on `main`.
8. Either manually dispatch `.github/workflows/release.yml` from `main` with
   `version=0.1.0` and `android_version_code=1`, OR create and push `app-v0.1.0`
   at the reviewed main commit. Do not do both. The tag path reads the checked-in
   Android versionCode and validates all client version fields before signing.
9. Approve the `application-release` Environment jobs if configured.
10. Verify Linux, Windows, notarized macOS, signed APK/AAB and unsigned iPhone IPA
    jobs. The iOS job requires no Apple signing or distribution credentials.
11. Verify the final job promotes the private OCI tag `app-v0.1.0` and `stable`
    only after redownloading/checking the complete same-revision artifact set
    and signed manifest. No GitHub Release is created.
12. Inspect the private package and immutable digest reported in the job summary.
    Partial platform builds stay private and are never made active as `stable`.
13. Configure the server-owned Home Node mirror from PersonalJarvis, perform one
    manual sync, then enable its timer.
14. Test update capability/download with an enrolled client. A second signed
    release is required to prove an actual upgrade end to end.

Production desktop/Android signing, macOS notarization, and private release
publication can only be verified in the protected GitHub workflow with the real
owner credentials; local tests deliberately cannot claim those outcomes. iOS
device installation is performed locally from Xcode and is outside CI/CD.

## Tag-triggered releases

Only tags `app-vMAJOR.MINOR.PATCH` are accepted; Core tags `vX.Y.Z` do not release
clients. Update and review npm/Tauri/Cargo, Android versionName/versionCode and
iOS marketing/build metadata **before** making the tag. A tag does not rewrite
source metadata, increment Android versionCode or bypass CI. Mismatches fail
closed, rather than publishing clients that report different versions.

No private version may already exist under the OCI tag. A failed signing run can
be rerun with **all jobs**, so the run-attempt identities match, provided no
version has yet been promoted. If promotion partly succeeded, stop and inspect
its digest; do not force-push tags or overwrite immutable artifacts.
No Home Node SSH key, LAN address or GitHub personal access token belongs
in this workflow. The Home Node pulls the completed signed release outbound.

The Core repository documents the native Rust service in
`deploy/app-updates/PRIVATE_CLIENT_RELEASES.md`: separate public installer copies,
root-controlled `/var/lib/jarvis-app-updates`, pinned updater public key and APK
certificate, authenticated Core delivery, and optional approved stable tracking.
It is not a Docker service. Do not infer successful deployment or real device
updating from a green manifest unit test or a newly created tag.

## Updating the protocol dependency

`PersonalJarvis/crates/client-core` remains authoritative. Merge a compatible
change there, obtain the reviewed exact commit, then update
`desktop/src-tauri/Cargo.toml` and its lockfile. Core and app versions remain
independent; the explicit protocol fields in release/capability metadata are the
compatibility boundary.
