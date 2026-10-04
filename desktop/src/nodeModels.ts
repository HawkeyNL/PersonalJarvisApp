// Pure helpers for the module pages (conversations, tasks, integrations,
// agents, health). No Vue or Tauri imports, so behavioral tests can load this
// file directly.

const DAY = 86_400_000;

/** Conversations whose title contains `query` (case-insensitive) and, when
 *  `days` is set, were updated within the last `days` days. */
export function filterConversations<T extends { title: string; updated_at: string }>(
  list: T[], query: string, days: number | null, now: number,
): T[] {
  const q = query.trim().toLowerCase();
  const since = days === null ? -Infinity : now - days * DAY;
  return list.filter((c) => (c.title || "").toLowerCase().includes(q) && (Date.parse(c.updated_at) || 0) >= since);
}
