// Conversation list of the chat home: title search and local-day groups.
// Pure, so tests can load this file directly.
import { filterConversations } from "./nodeModels.ts";

export type Day = "Today" | "Yesterday" | "Earlier";
export type DayGroup<T> = { label: Day; items: T[] };

const startOfDay = (ms: number) => new Date(ms).setHours(0, 0, 0, 0);

/** Local calendar day of an ISO time relative to `now`. Unparseable times
 *  count as Earlier; a clock ahead of this one counts as Today. */
export function dayOf(iso: string, now: number): Day {
  const at = Date.parse(iso);
  if (Number.isNaN(at)) return "Earlier";
  const today = startOfDay(now);
  if (at >= today) return "Today";
  // Yesterday's midnight from the last millisecond before today: DST-safe.
  return at >= startOfDay(today - 1) ? "Yesterday" : "Earlier";
}

/** Conversations matching `query` (title, case-insensitive), grouped by day.
 *  Order within a group is kept; empty groups are left out. */
export function groupConversations<T extends { title: string; updated_at: string }>(
  list: T[], query: string, now: number,
): DayGroup<T>[] {
  const groups: DayGroup<T>[] = (["Today", "Yesterday", "Earlier"] as const).map((label) => ({ label, items: [] }));
  for (const c of filterConversations(list, query, null, now)) {
    groups.find((g) => g.label === dayOf(c.updated_at, now))!.items.push(c);
  }
  return groups.filter((g) => g.items.length);
}
