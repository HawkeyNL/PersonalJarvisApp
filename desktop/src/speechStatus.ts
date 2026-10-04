// Fixed presentation labels only; native errors and assistant text are excluded.
export function speechStatusLabel(value: unknown): string | null {
  switch (value) {
    case "speaking": return "Local speech requested";
    case "idle": return "";
    case "unavailable": return "No supported local speech engine available";
    case "failed": return "Local speech failed; chat stays available";
    case "queue_full": return "Speech stopped: local queue full";
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
