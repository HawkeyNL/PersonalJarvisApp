// Only fixed local labels/status codes reach the UI; never raw native errors,
// HTTP response bodies, passwords or activation headers.
export function accountFailure(error: unknown): string {
  const e = error as { name?: string; status?: number; path?: string; kind?: string } | null;
  if (e?.name === "ApiError") {
    const stage = e.path === "/v1/auth/bootstrap" ? "Eerste activatie" : "Aanmelden";
    switch (e.status) {
      case 400: return `${stage}: invoer afgewezen (HTTP 400). Controleer de apparaatnaam en het wachtwoord.`;
      case 401: return `${stage}: authenticatie geweigerd (HTTP 401).`;
      case 403: return `${stage}: geweigerd (HTTP 403). Bij activatie kan de code verlopen/ongeldig zijn, het clientnetwerk niet toegestaan zijn of activatie al gebruikt zijn.`;
      case 429: return `${stage}: te veel pogingen (HTTP 429). Wacht minstens een minuut voordat je opnieuw probeert.`;
      case 503: return `${stage}: tijdelijk niet beschikbaar (HTTP 503). Controleer de Core-status.`;
      default: return `${stage}: onverwachte HTTP-fout. Controleer de Core-status.`;
    }
  }
  if (e?.name === "NetworkError") return "Geen antwoord van de Home Node. Controleer HTTPS, de verbinding en het ingestelde adres.";
  return "Aanmelden of activeren mislukt in de app. Controleer of de OS-sleutelhanger beschikbaar is en probeer opnieuw.";
}
