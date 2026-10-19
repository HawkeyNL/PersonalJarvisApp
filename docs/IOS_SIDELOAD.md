# iOS IPA for owner-side signing

The owner now permits downloadable unsigned IPA candidates. This supersedes
the previous source-only/no-IPA rule, but does not authorize App Store or
TestFlight distribution, signing credentials in CI, or automatic iOS install.

CI builds and tests the simulator, then builds a Release arm64 **iPhoneOS** app
with CODE_SIGNING_ALLOWED=NO and CODE_SIGNING_REQUIRED=NO. The packager checks
the bundle ID, version, build number, executable Mach-O platform, size/count
limits and absence of links/signing material. It produces Payload/Jarvis.app
inside Jarvis_VERSION_ios_arm64_unsigned.ipa. A simulator app is rejected.

After merge and green CI, dispatch `ios-candidate.yml` from main with the
checked-in version (initially 0.1.0) and approve `application-release`.
Only the ephemeral GITHUB_TOKEN is used for package upload. No iOS or macOS
signing secrets are referenced. The existing package must already be private.
The workflow redownloads its OCI digest and compares both files byte-for-byte.
It writes the immutable private reference in its summary; no GitHub Release,
Git tag, public Actions artifact, latest pointer or production deployment occurs.

The descriptor ios-candidate.json explicitly says manual-owner-signing,
apple_signed=false and automatic_install=false. It is NOT latest.json and
must not be accepted by the normal updater as an install authorization.

## Install and update (primary path)

On the iPhone, open `https://<home-node>/downloads/` in Safari and tap
**Add to AltStore** or **Add to SideStore** once. Then install and update
Jarvis from inside that app. The source lists only complete, verified
releases, never standalone `ios-candidate` builds. Never provide Apple
passwords to Jarvis, CI, or its UI.

Jarvis shows an informational banner on the hub ("Jarvis X.Y.Z is available")
with "Open in AltStore", "Open in SideStore" and "Downloads page" buttons. It
is checked on foreground after unlock, at most every 6 hours, via the
authenticated `GET /v1/app-updates/ios/{version}`. The banner can be dismissed
per version. The app never downloads or installs anything itself; only
`altstore://`, `sidestore://` and `https://` links are opened.

Core pins bundle id `com.hawkeynl.jarvis` and the iOS 17.0 deployment target
(`crates/app-downloads` `release_store.rs`). Any change to the bundle id or
target in `ios/project.yml`, and any new entitlement or Info.plist privacy
key, must be mirrored there.

## Approval notifications (APNs)

The app carries `aps-environment` in `ios/Jarvis/Jarvis.entitlements`
(declared in Core's AltStore source as well). Settings → **Approval
notifications** asks for notification permission only when turned on, then
registers this device's APNs token with Core (`PUT /v1/devices/self/push`).
Turning it off, logging out or resetting the device removes the token when
the Home Node is reachable (best effort, a few seconds at most); revoking the
device on the Home Node always removes it. A reset also turns the setting
off. A push only says "Approval waiting". Tapping a pairing push opens the
pairing requests; tapping an unlock push opens Jarvis, where the unlock
request is shown once the app has its unlock approval view. In the
foreground a push shows no banner; an open pairing requests page reloads at
once. Approving still needs Face ID / Touch ID. No silent pushes, no
notification actions and no notification service extension.

How sideloading affects it (from the AltStore and SideStore signing code; not
yet confirmed on a device):

- The CI IPA is unsigned and carries no entitlements. AltStore and SideStore
  sign it with entitlements taken from the owner's **development**
  provisioning profile, not from the app.
- They register the app ID as `com.hawkeynl.jarvis.<TEAMID>` and install the
  app under that id, so that is the APNs topic.
- They never enable Push Notifications on that app ID. With a paid Apple
  Developer account the owner enables it once in the developer portal and
  refreshes Jarvis; the new profile then carries
  `aps-environment = development`, which means the APNs **sandbox**. A free
  account cannot use push.
- Jarvis reads the environment from its embedded profile. Without the
  entitlement the setting says so and registers nothing.

The owner setup (APNs key, Core settings) is in Core's `docs/NOTIFICATIONS.md`.

Source-based Xcode Run remains supported separately. Because of the push
entitlement, Xcode's automatic signing now needs a paid team; with a free
Apple account, remove `CODE_SIGN_ENTITLEMENTS` locally (no approval
notifications then), and provisioning expires and needs renewal.
Paid-account signing is still to be verified on a device.

Before production promotion, test on a real iPhone:

- Sign and install the IPA using the chosen local tool.
- Pair with the runtime-configured HTTPS Home Node; confirm Keychain access.
- Install a newer build over it using the same signing identity and app ID.
- Verify session/Keychain and local settings survive; do not uninstall first.
- Test expiration/renewal and explicitly document the chosen tool's limits.

## Updating without losing the local configuration

Install the newer IPA over the existing Jarvis installation; do not delete the
app first. Use the same signing account/team and the same effective bundle ID
and Keychain access group in the sideload tool. Keep these stable across versions.
The runtime Home Node address and preferences live in the app's UserDefaults;
the registered device ID, private key and session live in the device-only Keychain.
None of these storage names depend on the application version, except the
device key described below.

## Device key behind Face ID / Touch ID

The device's Ed25519 private key is stored in a Keychain item that only
Face ID or Touch ID can read (`device-ed25519-seed-v2`, device-only, requires a
device passcode, bound to the currently enrolled biometrics). Its public key is
kept as a separate plain item so fingerprints and enrollment never prompt.
Signing in, every model or routing change and every pairing approval show a
Face ID / Touch ID prompt; the device passcode is not accepted for these. The
app lock keeps its passcode fallback.

On first launch after the update, Jarvis moves the older key
(`device-ed25519-seed-v1`) into the protected item without prompting and then
deletes the old item. The public key and the Home Node registration stay the
same, so no re-pairing is needed for the upgrade. The device must have Face ID
or Touch ID set up; otherwise the old key is kept and Jarvis asks to set it up.

- **Downgrade:** an older build cannot read the protected key. Installing a
  version from before this change requires resetting and re-pairing the device.
  After upgrading again, the key from that re-pairing is moved behind
  Face ID / Touch ID and replaces the older protected key, so no further
  re-pairing is needed.
- **Biometrics changed:** adding or removing a face or fingerprint, or removing
  the device passcode, invalidates the key by design. Jarvis then says so; open Settings, choose
  **Reset this device**, pair again, approve it from a trusted device and
  revoke the old registration.

Changing the signing identity/access group is not a normal app update: iOS can
deny access to the old Keychain. Jarvis cannot and must not bypass that isolation.
A missing key must not be silently regenerated for an already registered device.
If the original identity cannot be recovered, explicitly reset and re-enroll,
then approve the new device from the trusted Home Node administration interface.
Check/revoke any obsolete registration separately. Resetting local data while
offline does not prove the remote registration was revoked.

A refused device challenge and a refused password/signature login are different
failures. Retry a corrected password without resetting the identity. A revoked
device requires new owner approval, not merely a new password. Never include
passwords, activation codes, signatures or session tokens in bug reports.

Re-signing may change application identifiers/entitlements; preservation of
Keychain data is not guaranteed until this test passes. No live-device result
is claimed by simulator or packaging tests. macOS Developer ID signing and
notarization are independent and are not changed by this iOS candidate path.
