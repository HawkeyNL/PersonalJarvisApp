// Fixed presentation labels only; native errors and assistant text are excluded.
export function speechStatusLabel(value: unknown): string | null {
  switch (value) {
    case "speaking": return "Lokale spraak aangevraagd";
    case "idle": return "";
    case "unavailable": return "Geen ondersteunde lokale spraakengine beschikbaar";
    case "failed": return "Lokale spraak mislukt; chat blijft beschikbaar";
    case "queue_full": return "Spraak gestopt: lokale wachtrij vol";
    default: return null;
  }
}

/** Completion/cancellation acknowledgements must not erase a failure banner. */
export function nextSpeechStatus(current: string, value: unknown): string {
  const label = speechStatusLabel(value);
  if (label === null) return current;
  if (value === "idle" && current !== speechStatusLabel("speaking")) return current;
  return label;
}
