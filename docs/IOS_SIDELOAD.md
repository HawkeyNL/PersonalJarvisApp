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
The OCI hash protects transport identity, but this candidate is not yet bound
to the production updater signing key. Full signed release promotion and the
Home Node's private-source adapter/download route are still required before
offering a production download URL or iOS update notification.

Install using an owner-controlled sideload signing tool, e.g. AltStore Classic
and AltServer on the owner's Mac. The downloaded unsigned IPA is not directly
installable by tapping it or dragging it into Xcode. Source-based Xcode Run
remains supported separately. With a free Apple account, provisioning expires
and needs renewal. Never provide Apple passwords to Jarvis, CI, or its UI.

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
None of these storage names depend on the application version.

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
