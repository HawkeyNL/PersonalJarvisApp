// Unlock number matching, mirroring client-core `unlock_code`: the first two
// bytes (big-endian) of SHA-256("jarvis-unlock-code-v1\0" || request id bytes
// || 32-byte nonce), modulo 100, shown as two digits. This device shows it
// while it waits; the approver must pick the same number.

const UUID = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;
const HEX_64 = /^[0-9a-fA-F]{64}$/;
const DOMAIN = new TextEncoder().encode("jarvis-unlock-code-v1\0");

function hexBytes(hex: string): Uint8Array {
  const out = new Uint8Array(hex.length / 2);
  for (let i = 0; i < out.length; i++) out[i] = parseInt(hex.slice(i * 2, i * 2 + 2), 16);
  return out;
}

/** Two-digit code for one unlock request ("00" to "99"). */
export async function unlockCode(requestId: string, nonceHex: string): Promise<string> {
  if (!UUID.test(requestId) || !HEX_64.test(nonceHex)) throw new Error("invalid unlock request");
  const input = new Uint8Array(DOMAIN.length + 48);
  input.set(DOMAIN);
  input.set(hexBytes(requestId.replace(/-/g, "")), DOMAIN.length);
  input.set(hexBytes(nonceHex), DOMAIN.length + 16);
  const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", input));
  return String(((digest[0] << 8) | digest[1]) % 100).padStart(2, "0");
}
