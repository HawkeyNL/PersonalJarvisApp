// Only fixed local labels/status codes reach the UI; never raw native errors,
// HTTP response bodies, passwords or activation headers.
export function accountFailure(error: unknown): string {
  const e = error as { name?: string; status?: number; path?: string; kind?: string } | null;
  if (e?.name === "ApiError") {
    const stage = e.path === "/v1/auth/bootstrap" ? "First activation" : "Sign-in";
    switch (e.status) {
      case 400: return `${stage}: input rejected (HTTP 400). Check the device name and password.`;
      case 401: return `${stage}: authentication refused (HTTP 401).`;
      case 403: return `${stage}: refused (HTTP 403). For activation, the code may be expired or invalid, the client network not allowed, or activation already used.`;
      case 429: return `${stage}: too many attempts (HTTP 429). Wait at least a minute before trying again.`;
      case 503: return `${stage}: temporarily unavailable (HTTP 503). Check the Core status.`;
      default: return `${stage}: unexpected HTTP error. Check the Core status.`;
    }
  }
  if (e?.name === "NetworkError") return "No response from the Home Node. Check HTTPS, the connection and the configured address.";
  return "Sign-in or activation failed in the app. Check that the OS keychain is available and try again.";
}
