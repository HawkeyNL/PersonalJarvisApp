// Pairing key fingerprints, mirroring client-core `key_fingerprint` and
// `PairingRequestItem::verified_fingerprint`: lowercase hex SHA-256 of the raw
// 32-byte Ed25519 public key. An approver displays the value computed here from
// `candidate_public_key` (the key the approval signs), never a server field.

export class PairingFingerprintMismatch extends Error {
  constructor() { super("The Home Node's fingerprint does not match the candidate key. Do not approve; check the Home Node."); }
}

const HEX_64 = /^[0-9a-fA-F]{64}$/;

/** Lowercase hex SHA-256 of a raw 32-byte public key given as 64 hex chars. */
export async function keyFingerprint(publicKeyHex: string): Promise<string> {
  if (!HEX_64.test(publicKeyHex)) throw new Error("invalid public key");
  const bytes = new Uint8Array(32);
  for (let i = 0; i < 32; i++) bytes[i] = parseInt(publicKeyHex.slice(i * 2, i * 2 + 2), 16);
  const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", bytes));
  return Array.from(digest, (byte) => byte.toString(16).padStart(2, "0")).join("");
}

/** Fingerprint to display for a pending request; throws on any disagreement
 *  with the server's legacy 16-hex `fingerprint` or optional `fingerprint_full`. */
export async function verifiedFingerprint(request: {
  candidate_public_key: string; fingerprint: string; fingerprint_full?: string | null;
}): Promise<string> {
  const local = await keyFingerprint(request.candidate_public_key);
  if (local.slice(0, 16) !== request.fingerprint
    || (request.fingerprint_full != null && request.fingerprint_full !== local)) {
    throw new PairingFingerprintMismatch();
  }
  return local;
}

/** Groups of four hex characters, as the candidate shows its own key. */
export function groupFingerprint(fingerprint: string): string {
  return fingerprint.match(/.{1,4}/g)?.join(" ") ?? "";
}

/** Bound an unauthenticated hint for display: control characters dropped,
 *  at most `max` characters. Rendered as plain text only. */
export function displayHint(value: string | null | undefined, max = 64): string {
  if (value == null) return "unknown";
  const clean = Array.from(value).filter((ch) => !/\p{Cc}|\p{Cf}/u.test(ch));
  const text = clean.length > max ? `${clean.slice(0, max).join("")}…` : clean.join("");
  return text.trim() || "unknown";
}
