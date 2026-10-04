// Pure presentation helpers for the redesigned shell (hub, node pages).
// No Vue or Tauri imports, so behavioral tests can load this file directly.

/** Status colour of a dot/label. Every tone is always paired with text. */
export type Tone = "ok" | "idle" | "warn" | "error";

/** What the orb shows. */
export type Mood = "idle" | "listening" | "thinking";

/** Why an optional Core read failed. */
export type LoadFailure = "signin" | "forbidden" | "network" | "failed";

/** Result of an optional Core read. "unsupported" = the route does not exist
 *  on this Core (older version); it is shown as "Requires newer Core". */
export type Availability<T> =
  | { state: "loading" }
  | { state: "ok"; value: T }
  | { state: "unsupported" }
  | { state: "error"; reason: LoadFailure };

/** Classify a failed read: 404/405 means an older Core, 401 a session to
 *  renew, 403 a feature Core refuses (e.g. the agent kill switch); only a
 *  request that never got an answer is a connection problem. */
export function availabilityFromError(error: unknown): { state: "unsupported" } | { state: "error"; reason: LoadFailure } {
  const e = error as { name?: string; status?: number } | null;
  if (e?.name === "NetworkError") return { state: "error", reason: "network" };
  if (e?.name !== "ApiError") return { state: "error", reason: "failed" };
  if (e.status === 404 || e.status === 405) return { state: "unsupported" };
  if (e.status === 401) return { state: "error", reason: "signin" };
  return { state: "error", reason: e.status === 403 ? "forbidden" : "failed" };
}

/** One-line explanation of a failed read; `forbidden` lets a page name what
 *  Core turned off. */
export function failureText(reason: LoadFailure, forbidden = "Turned off in Core."): string {
  switch (reason) {
    case "signin": return "Sign in again on the Core page.";
    case "forbidden": return forbidden;
    case "network": return "Check the connection to your Home Node.";
    case "failed": return "Core returned an error. Try again later.";
  }
}

/** Animation-duration multiplier per mood (export: x1 / x0.7 / x0.4). */
export function moodFactor(mood: Mood): number {
  return mood === "thinking" ? 0.4 : mood === "listening" ? 0.7 : 1;
}

/** Orb mood from what the app already knows: a reply in progress wins over
 *  an open microphone. */
export function deriveMood(state: { thinking: boolean; listening: boolean }): Mood {
  if (state.thinking) return "thinking";
  if (state.listening) return "listening";
  return "idle";
}

const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

/** "just now", "4m ago", "2h ago", "3d ago"; "" for an unparseable time. */
export function relativeTime(iso: string, now: number): string {
  const at = Date.parse(iso);
  if (Number.isNaN(at)) return "";
  const diff = Math.max(0, now - at);
  if (diff < MINUTE) return "just now";
  if (diff < HOUR) return `${Math.floor(diff / MINUTE)}m ago`;
  if (diff < DAY) return `${Math.floor(diff / HOUR)}h ago`;
  return `${Math.floor(diff / DAY)}d ago`;
}

/** Number of items updated within the last `days` days. */
export function countSince(items: { updated_at: string }[], days: number, now: number): number {
  const since = now - days * DAY;
  return items.filter((item) => {
    const at = Date.parse(item.updated_at);
    return !Number.isNaN(at) && at >= since;
  }).length;
}

/** "14d 2h", "3h 12m", "5m" from seconds of uptime. */
export function formatUptime(seconds: number): string {
  const s = Math.max(0, Math.floor(seconds));
  const d = Math.floor(s / 86_400);
  const h = Math.floor((s % 86_400) / 3600);
  const m = Math.floor((s % 3600) / 60);
  if (d > 0) return `${d}d ${h}h`;
  if (h > 0) return `${h}h ${m}m`;
  return `${m}m`;
}

/** What a hub card shows: two short lines and a status tone. */
export type CardSummary = { lines: [string, string]; tone: Tone };

const CARD_HINT: Record<LoadFailure, string> = {
  signin: "Sign in again", forbidden: "Turned off in Core", network: "Check the connection", failed: "Core error",
};

/** Shared handling of loading / older Core / failed reads for a card. */
export function summarize<T>(source: Availability<T>, ok: (value: T) => CardSummary): CardSummary {
  switch (source.state) {
    case "loading": return { lines: ["Loading…", ""], tone: "idle" };
    case "unsupported": return { lines: ["Requires newer Core", ""], tone: "idle" };
    case "error": return { lines: ["Unavailable", CARD_HINT[source.reason]], tone: "warn" };
    case "ok": return ok(source.value);
  }
}

const plural = (n: number, one: string, many = `${one}s`) => `${n} ${n === 1 ? one : many}`;

export function conversationsCard(source: Availability<{ updated_at: string }[]>, now: number): CardSummary {
  return summarize(source, (items) => {
    if (!items.length) return { lines: ["No conversations yet", "Start one above"], tone: "idle" };
    const newest = items.reduce((a, b) => (Date.parse(b.updated_at) > Date.parse(a.updated_at) ? b : a));
    const last = relativeTime(newest.updated_at, now);
    return { lines: [`${countSince(items, 7, now)} this week`, last ? `Last: ${last}` : ""], tone: "ok" };
  });
}

export function agentsCard(source: Availability<number>): CardSummary {
  return summarize(source, (count) => ({
    lines: [plural(count, "agent"), count ? "Configured on Core" : "None configured"],
    tone: count ? "ok" : "idle",
  }));
}

export type CodingSession = { state: string };
export function tasksCard(sessions: Availability<CodingSession[]>, pending: Availability<unknown[]>): CardSummary {
  return summarize(sessions, (list) => {
    const active = list.filter((s) => s.state === "active").length;
    const waiting = pending.state === "ok" ? pending.value.length : null;
    return {
      lines: [`${active} active`, waiting === null ? "Approvals unavailable" : `${waiting} awaiting approval`],
      tone: waiting ? "warn" : active ? "ok" : "idle",
    };
  });
}

export type SoftwareItem = { name: string; present: boolean };
export function integrationsCard(source: Availability<{ software: SoftwareItem[] }>): CardSummary {
  return summarize(source, ({ software }) => {
    const present = software.filter((s) => s.present).length;
    const missing = software.length - present;
    return {
      lines: [`${present} of ${software.length} present`, missing ? `${missing} missing` : "All present"],
      tone: missing ? "warn" : "ok",
    };
  });
}

export function contextCard(source: Availability<unknown[]>): CardSummary {
  return summarize(source, (devices) => ({
    lines: [plural(devices.length, "device") + " linked", "Modes not yet available"],
    tone: devices.length ? "ok" : "idle",
  }));
}

export type LiveHost = { cpu_percent: number | null; memory_total_bytes: number; memory_used_bytes: number; uptime_seconds: number };
export function healthCard(online: boolean | null, registry: Availability<{ live_host?: LiveHost }>): CardSummary {
  if (online === null) return { lines: ["Checking…", ""], tone: "idle" };
  if (!online) return { lines: ["Core unreachable", "Check the Home Node"], tone: "error" };
  if (registry.state !== "ok") return { lines: ["Core ready", ""], tone: "ok" };
  const live = registry.value.live_host;
  if (!live) return { lines: ["Core ready", "Live vitals need newer Core"], tone: "ok" };
  const cpu = live.cpu_percent === null ? "" : ` · CPU ${Math.round(live.cpu_percent)}%`;
  return { lines: [`Up ${formatUptime(live.uptime_seconds)}`, `Core ready${cpu}`], tone: "ok" };
}

/** Text of the status pill under the orb. */
export function statusPill(mood: Mood, online: boolean | null): { label: string; hint: string; tone: Tone } {
  if (online === false) return { label: "Offline", hint: "Home Node unreachable", tone: "error" };
  if (online === null) return { label: "Connecting", hint: "Reaching your Home Node…", tone: "idle" };
  if (mood === "thinking") return { label: "Thinking", hint: "Working on your request…", tone: "ok" };
  if (mood === "listening") return { label: "Listening", hint: "Speak now…", tone: "ok" };
  return { label: "Ready", hint: "Type or talk to begin", tone: "ok" };
}

/** Host part of the Home Node origin for the footer; "" when not configured. */
export function originHost(origin: string | null): string {
  if (!origin) return "";
  try { return new URL(origin).host; } catch { return ""; }
}

/** Scale factor that fits a box laid out at `design` size into `room`,
 *  clamped to [min, max]; 1 while either size is unknown (not laid out). */
export function fitScale(
  room: { width: number; height: number }, design: { width: number; height: number }, max = 1, min = 0.4,
): number {
  if (!(room.width > 0 && room.height > 0 && design.width > 0 && design.height > 0)) return 1;
  return Math.min(max, Math.max(min, Math.min(room.width / design.width, room.height / design.height)));
}
