// A roadmap a chat answer proposed (`roadmap_proposals` on
// `assistant.completed`, see "From chat" in Core's docs/ROADMAPS.md). The body
// is a checked `POST /v1/roadmaps` body, or with `roadmap_id` a
// `POST /v1/roadmaps/{id}/items/batch` body; nothing exists on Core until the
// owner clicks Add, which sends it as is. Cards live in memory only, for the
// conversation on screen. Pure: tests load this file directly.
import {
  LIMITS, STATUSES, TAG, failureOf, failureText, groupByPhase, readSnapshot, validRepo, validText,
  type Item, type Link, type NewItem, type Status,
} from "./roadmaps.ts";
import { ADD_NOT_CONFIRMED } from "./jobProposals.ts";

export { ADD_NOT_CONFIRMED };
export const ADDED = "Added to Roadmaps";
export const ADD_REFUSED = "Core refused this roadmap, for example because a phase changed or a limit was reached. Ask Jarvis again.";
export const ADD_TOO_OLD = "Update Jarvis Core to add roadmaps from chat.";
const MAX_ITEMS = 100;

export type ProposalItem = NewItem & { status: Status };
export type NewRoadmapBody = { title: string; description?: string; phases?: string[]; items: ProposalItem[] };
export type ItemsBody = { items: ProposalItem[] };
export type Proposal = {
  id: string; roadmapId: string | null; title: string; body: NewRoadmapBody | ItemsBody;
};
export type CardState = "open" | "adding" | "added" | "unconfirmed";
export type RoadmapCard = Proposal & {
  conversationId: string; messageId: string; state: CardState; viewId: string | null; error: string;
};

const isObj = (x: unknown): x is Record<string, unknown> => typeof x === "object" && x !== null && !Array.isArray(x);
const isId = (x: unknown): x is string => typeof x === "string" && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(x);
const onlyKeys = (x: Record<string, unknown>, keys: string[]) => Object.keys(x).every((k) => keys.includes(k));
const text = (x: unknown, max: number, multiline = false): x is string => typeof x === "string" && validText(x, max, multiline);
const optText = (x: unknown, max: number, multiline = false) => x === undefined || text(x, max, multiline);

/** A pr, issue or release on a valid repo; never a url or a cached state. */
function validLink(x: unknown): x is Link {
  if (!isObj(x) || !onlyKeys(x, ["kind", "repo", "number", "tag"]) || typeof x.repo !== "string" || !validRepo(x.repo)) return false;
  if (x.kind === "release") return x.number === undefined && typeof x.tag === "string" && TAG.test(x.tag);
  const n = x.number;
  return (x.kind === "pr" || x.kind === "issue") && x.tag === undefined
    && typeof n === "number" && Number.isInteger(n) && n >= 1 && n <= LIMITS.linkNumber;
}

/** 1 to 100 items (0 allowed for a new roadmap); with `phases`, each item's
 *  phase must be one of them. */
function validItems(x: unknown, phases: string[] | null): x is ProposalItem[] {
  if (!Array.isArray(x) || x.length > MAX_ITEMS || (phases === null && !x.length)) return false;
  return x.every((i) => isObj(i) && onlyKeys(i, ["title", "status", "phase", "notes", "links"])
    && text(i.title, LIMITS.itemTitle) && (STATUSES as readonly unknown[]).includes(i.status)
    && optText(i.phase, LIMITS.phase) && (i.phase === undefined || phases === null || phases.includes(i.phase as string))
    && optText(i.notes, LIMITS.notes, true)
    && (i.links === undefined || (Array.isArray(i.links) && i.links.length <= LIMITS.links && i.links.every(validLink))));
}

function validNewRoadmap(b: Record<string, unknown>): boolean {
  const phases = b.phases ?? [];
  if (!onlyKeys(b, ["title", "description", "phases", "items"]) || !text(b.title, LIMITS.roadmapTitle)) return false;
  if (!optText(b.description, LIMITS.description, true) || !Array.isArray(phases) || phases.length > LIMITS.phases) return false;
  if (!phases.every((p) => text(p, LIMITS.phase)) || new Set(phases).size !== phases.length) return false;
  return validItems(b.items, phases as string[]);
}

/** The proposal of an `assistant.completed` payload: only the first entry,
 *  and only when every field is one this build shows and Core accepts.
 *  Anything else is skipped (null), never failing the message. */
export function readRoadmapProposal(x: unknown): Proposal | null {
  const p: unknown = Array.isArray(x) ? x[0] : undefined;
  if (!isObj(p) || !onlyKeys(p, ["id", "roadmap_id", "title", "body"]) || !isId(p.id)) return null;
  if (!text(p.title, LIMITS.roadmapTitle) || !isObj(p.body)) return null;
  if (p.roadmap_id === undefined) {
    return validNewRoadmap(p.body) ? { id: p.id, roadmapId: null, title: p.title, body: p.body as NewRoadmapBody } : null;
  }
  if (!isId(p.roadmap_id) || !onlyKeys(p.body, ["items"]) || !validItems(p.body.items, null)) return null;
  return { id: p.id, roadmapId: p.roadmap_id, title: p.title, body: p.body as ItemsBody };
}

/** `cards` plus the proposal when it was not shown before (`seen`, which
 *  this updates). */
export function withRoadmapProposal(
  cards: RoadmapCard[], seen: Set<string>, proposal: Proposal | null, conversationId: string, messageId: string,
): RoadmapCard[] {
  if (!proposal || seen.has(proposal.id)) return cards;
  seen.add(proposal.id);
  return [...cards, { ...proposal, conversationId, messageId, state: "open", viewId: null, error: "" }];
}

// ---- wording -----------------------------------------------------------------

const count = (n: number, word: string) => `${n} ${word}${n === 1 ? "" : "s"}`;
const isNew = (p: Proposal): p is Proposal & { body: NewRoadmapBody } => p.roadmapId === null;

/** "New roadmap: Jarvis", or "Add 3 items to Jarvis". */
export function proposalHeading(p: Proposal): string {
  if (isNew(p)) return `New roadmap: ${p.body.title}`;
  return `Add ${count(p.body.items.length, "item")} to ${p.title}`;
}

/** "3 items in 2 phases"; for an existing roadmap only "3 items". */
export function proposalSummary(p: Proposal): string {
  const items = count(p.body.items.length, "item");
  return isNew(p) ? `${items} in ${count(p.body.phases?.length ?? 0, "phase")}` : items;
}

/** The items grouped as the Roadmaps view groups them: a new roadmap's
 *  phases in order (empty ones left out), an existing roadmap's in the order
 *  the items name them, then the items without a phase. */
export function proposalGroups(p: Proposal): { phase: string | null; items: Item[] }[] {
  const items: Item[] = p.body.items.map((i, n) => ({
    id: String(n), title: i.title, status: i.status, auto_status: null, phase: i.phase ?? null, position: n,
    notes: i.notes ?? null, depends_on: [], links: i.links ?? [], updated_at: "",
  }));
  const phases = isNew(p) ? p.body.phases ?? [] : [...new Set(items.flatMap((i) => (i.phase === null ? [] : [i.phase])))];
  return groupByPhase(phases, items).filter((g) => g.items.length);
}

// ---- add -----------------------------------------------------------------------

export type AddResult = Pick<RoadmapCard, "state" | "viewId" | "error">;
export type AddGuards = {
  locked: () => boolean;
  /** False once the owner left the session or conversation the add began in. */
  current: () => boolean;
  create: (body: NewRoadmapBody) => Promise<unknown>;
  get: (id: string) => Promise<unknown>;
  createItems: (id: string, revision: number, body: ItemsBody) => Promise<unknown>;
};

const opened = (error: string): AddResult => ({ state: "open", viewId: null, error });
const UNCONFIRMED: AddResult = { state: "unconfirmed", viewId: null, error: ADD_NOT_CONFIRMED };

/** One write. A 2xx must carry the roadmap; anything that may have landed
 *  (an unreadable 2xx, no answer, a broken answer, any 5xx) is not
 *  confirmed. A 4xx wrote nothing, so the owner may try again. */
async function write(send: () => Promise<unknown>): Promise<AddResult | "conflict"> {
  try {
    const snap = readSnapshot(await send());
    return snap ? { state: "added", viewId: snap.roadmap.id, error: "" } : UNCONFIRMED;
  } catch (e) {
    const err = e as { name?: string; status?: number } | null;
    const status = err?.name === "ApiError" ? err.status ?? 500 : 500;
    if (status >= 500) return UNCONFIRMED;
    if (status === 409) return "conflict";
    if (status === 404 || status === 405) return opened(ADD_TOO_OLD);
    if (status === 400 || status === 413) return opened(ADD_REFUSED);
    return opened(failureText(failureOf(e)));
  }
}

/** Sends the body exactly as Core proposed it. An existing roadmap's items
 *  go on its current revision, read first; a 409 (the roadmap moved on)
 *  retries once on a fresh revision. Null when the owner left or locked the
 *  app before the write. */
export async function addRoadmap(p: Proposal, g: AddGuards): Promise<AddResult | null> {
  if (isNew(p)) {
    const r = await write(() => g.create(p.body));
    return r === "conflict" ? opened(ADD_REFUSED) : r;
  }
  const id = p.roadmapId as string;
  for (let attempt = 0; attempt < 2; attempt++) {
    let revision: number;
    try {
      const snap = readSnapshot(await g.get(id));
      if (!snap) return opened(failureText("failed"));
      revision = snap.roadmap.revision;
    } catch (e) {
      return opened(failureText(failureOf(e)));
    }
    if (!g.current() || g.locked()) return null;
    const r = await write(() => g.createItems(id, revision, p.body));
    if (r !== "conflict") return r;
  }
  return opened(ADD_REFUSED);
}

/** Adds `card`: only from "open" and never behind the lock. The card turns
 *  "adding" before the request, so a second click does nothing; a result
 *  that is no longer current is dropped. */
export async function addRoadmapCard(card: RoadmapCard | undefined, g: AddGuards): Promise<void> {
  if (!card || g.locked() || card.state !== "open") return;
  card.state = "adding";
  card.error = "";
  const result = await addRoadmap(card, g);
  if (!g.current()) return;
  Object.assign(card, result ?? opened(""));
}
