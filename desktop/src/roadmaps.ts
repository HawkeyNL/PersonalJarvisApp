// Roadmaps: Core's contract (/v1/roadmaps, docs/ROADMAPS.md in the Core
// repository), response guards, input rules, GitHub link parsing and the
// display helpers the pages share. Pure: no Vue or Tauri imports, so tests
// load this file directly.
//
// Every write names the revision it builds on (?expected_revision=N); Core
// answers 409 when the roadmap moved on, and the page then reloads. Accepting
// or rejecting a proposal takes no revision: Core marks a proposal made on an
// older revision stale (409). Owner edits never set an item's auto_status or a
// link's cached GitHub state; both are display only.

export const STATUSES = ["live", "ready", "in_progress", "planned", "blocked"] as const;
export type Status = (typeof STATUSES)[number];
export const STATUS_LABEL: Record<Status, string> = {
  live: "Live", ready: "Ready", in_progress: "In progress", planned: "Planned", blocked: "Blocked",
};
/** Badge tone (components.css); the label always carries the meaning too. */
export const STATUS_TONE: Record<Status, "" | "pos" | "acc" | "neg" | "dng"> = {
  live: "pos", ready: "acc", in_progress: "neg", planned: "", blocked: "dng",
};

export const LINK_KINDS = ["pr", "issue", "release", "url"] as const;
export type LinkKind = (typeof LINK_KINDS)[number];
export type Link = { kind: LinkKind; repo?: string; number?: number; tag?: string; url?: string; state?: string };

export type Roadmap = {
  id: string; title: string; description: string | null; phases: string[]; revision: number;
  created_at: string; updated_at: string; archived_at: string | null;
};
export type Item = {
  id: string; title: string; status: Status; auto_status: Status | null; phase: string | null; position: number;
  notes: string | null; depends_on: string[]; links: Link[]; updated_at: string;
};
export type Snapshot = { roadmap: Roadmap; items: Item[] };
export type Author = "owner" | "jarvis" | "github";
export type HistoryEntry = {
  id: string; revision: number; at: string; author: Author; device_id: string | null; proposal_id: string | null;
  action: string; item_id: string | null; title: string; fields: string[]; from_status: Status | null; to_status: Status | null;
};
export type NewItem = {
  title: string; status?: Status; phase?: string; position?: number; notes?: string; depends_on?: string[]; links?: Link[];
};
export type ItemPatch = {
  title?: string; status?: Status; phase?: string | null; position?: number; notes?: string | null;
  depends_on?: string[]; links?: Link[];
};
export type ProposalOp = { op: "create_item"; item: NewItem } | { op: "update_item"; item_id: string; patch: ItemPatch };
export type Proposal = {
  id: string; base_revision: number; ops: ProposalOp[]; rationale: string | null; created_at: string; expires_at: string;
};

export const LIMITS = {
  roadmaps: 20, items: 300, roadmapTitle: 120, itemTitle: 200, description: 2000, notes: 4000,
  phases: 20, phase: 60, dependsOn: 20, links: 10, url: 500, linkNumber: 1_000_000_000, rationale: 1000,
  ops: 20, history: 100, proposals: 10,
} as const;
export const DEFAULT_PHASES = ["Now", "Next", "Later"];

// ---- input rules (Core enforces the same; these only explain them early) --

// Core's no_hidden_chars denylist: control, invisible and bidi code points.
const HIDDEN = /[\p{Cc}\u00AD\u034F\u061C\u115F\u1160\u180E\u200B-\u200F\u2028-\u202E\u2060-\u2069\u3164\uFE00-\uFE0F\uFEFF\uFFF9-\uFFFB\u{E0000}-\u{E007F}]/u;

/** Non-blank, at most `max` characters, no hidden characters; multi-line
 *  text may also hold line breaks and tabs. */
export function validText(value: string, max: number, multiline = false): boolean {
  if (!value.trim() || [...value].length > max) return false;
  return (multiline ? value.split(/[\n\t]/) : [value]).every((part) => !HIDDEN.test(part));
}

/** Phases typed one per line: trimmed, blank lines dropped. Null when they
 *  break Core's rule (at most 20 distinct names of 1 to 60 characters). */
export function parsePhases(text: string): string[] | null {
  const phases = text.split("\n").map((p) => p.trim()).filter(Boolean);
  const ok = phases.length <= LIMITS.phases && new Set(phases).size === phases.length
    && phases.every((p) => validText(p, LIMITS.phase));
  return ok ? phases : null;
}

const REPO = /^[A-Za-z0-9][A-Za-z0-9-]{0,38}\/[A-Za-z0-9._-]{1,100}$/;
const TAG = /^[A-Za-z0-9._+/-]{1,100}$/;
export const validRepo = (repo: string) => REPO.test(repo) && !/\/\.{1,2}$/.test(repo);
const validNumber = (n: number) => Number.isInteger(n) && n >= 1 && n <= LIMITS.linkNumber;

/** An absolute https URL with a host, no credentials, printable ASCII. */
export function validHttpsUrl(value: string): boolean {
  if (value.length > LIMITS.url || !/^https:\/\/[!-~]+$/.test(value)) return false;
  try {
    const url = new URL(value);
    return url.protocol === "https:" && !!url.hostname && !url.username && !url.password;
  } catch {
    return false;
  }
}

export type ParsedLink = { ok: true; link: Link } | { ok: false; error: string };

/** One link from what the owner typed or pasted. A GitHub URL names its own
 *  kind (pull → pr, issues → issue, releases/tag → release); `owner/name#12`
 *  becomes the `hint` kind (pr or issue) and `owner/name@v1.2.0` a release.
 *  Anything else must be an https URL. */
export function parseLink(input: string, hint: "pr" | "issue" = "pr"): ParsedLink {
  const text = input.trim();
  const gh = /^https:\/\/github\.com\/([^/?#]+\/[^/?#]+)\/(pull|issues|releases\/tag)\/([^?#]+?)\/?(?:[?#].*)?$/.exec(text);
  if (gh) {
    const [, repo, kind, rest] = gh;
    if (kind === "releases/tag") {
      let tag: string;
      try { tag = decodeURIComponent(rest); } catch { return { ok: false, error: "That release link is not valid." }; }
      if (validRepo(repo) && TAG.test(tag)) return { ok: true, link: { kind: "release", repo, tag } };
      return { ok: false, error: "That release link is not valid." };
    }
    const number = /^\d+(?:\/.*)?$/.test(rest) ? parseInt(rest, 10) : NaN;
    if (validRepo(repo) && validNumber(number)) {
      return { ok: true, link: { kind: kind === "pull" ? "pr" : "issue", repo, number } };
    }
    return { ok: false, error: "That GitHub link is not valid." };
  }
  const short = /^([^#@\s]+)#(\d+)$/.exec(text);
  if (short && validRepo(short[1]) && validNumber(Number(short[2]))) {
    return { ok: true, link: { kind: hint, repo: short[1], number: Number(short[2]) } };
  }
  const release = /^([^#@\s]+)@(\S+)$/.exec(text);
  if (release && validRepo(release[1]) && TAG.test(release[2])) {
    return { ok: true, link: { kind: "release", repo: release[1], tag: release[2] } };
  }
  if (/^http:\/\//i.test(text)) return { ok: false, error: "Only https links are allowed." };
  if (validHttpsUrl(text)) return { ok: true, link: { kind: "url", url: text } };
  return { ok: false, error: "Paste a GitHub link, owner/name#12, owner/name@v1.2.0 or an https URL." };
}

export function linkLabel(link: Link): string {
  if (link.kind === "url") return link.url ?? "";
  if (link.kind === "release") return `${link.repo}@${link.tag}`;
  return `${link.repo}#${link.number}`;
}

/** Where a link opens; null when it does not hold an https target. */
export function linkHref(link: Link): string | null {
  const base = `https://github.com/${link.repo}`;
  switch (link.kind) {
    case "pr": return `${base}/pull/${link.number}`;
    case "issue": return `${base}/issues/${link.number}`;
    case "release": return `${base}/releases/tag/${(link.tag ?? "").split("/").map(encodeURIComponent).join("/")}`;
    case "url": return link.url && validHttpsUrl(link.url) ? link.url : null;
  }
}

const sameTarget = (a: Link, b: Link) =>
  a.kind === b.kind && a.repo === b.repo && a.number === b.number && a.tag === b.tag && a.url === b.url;

/** A link as Core accepts it: the cached `state` is Core's, never sent. */
export function linkInput(link: Link): Link {
  const rest = { ...link };
  delete rest.state;
  return rest;
}

export function addLink(links: Link[], link: Link): Link[] | null {
  if (links.length >= LIMITS.links || links.some((l) => sameTarget(l, link))) return null;
  return [...links, link];
}

// ---- response guards: anything unexpected reads as null (fail closed) -----

const isObj = (x: unknown): x is Record<string, unknown> => typeof x === "object" && x !== null && !Array.isArray(x);
const str = (x: unknown, max = 10_000): x is string => typeof x === "string" && x.length <= max;
const optStr = (x: unknown, max?: number) => x === undefined || x === null || str(x, max);
const int = (x: unknown): x is number => typeof x === "number" && Number.isInteger(x) && x >= 0;
const isStatus = (x: unknown): x is Status => (STATUSES as readonly unknown[]).includes(x);
const strList = (x: unknown, max: number): x is string[] => Array.isArray(x) && x.length <= max && x.every((s) => str(s, 200));

function readLink(x: unknown): Link | null {
  if (!isObj(x) || !(LINK_KINDS as readonly unknown[]).includes(x.kind)) return null;
  if (!optStr(x.repo, 200) || !optStr(x.tag, 200) || !optStr(x.url, LIMITS.url) || !optStr(x.state, 40)) return null;
  if (x.number !== undefined && x.number !== null && !int(x.number)) return null;
  const link: Link = { kind: x.kind as LinkKind };
  for (const key of ["repo", "tag", "url", "state"] as const) if (typeof x[key] === "string") link[key] = x[key] as string;
  if (typeof x.number === "number") link.number = x.number;
  return link;
}

function readRoadmap(x: unknown): Roadmap | null {
  if (!isObj(x) || !str(x.id, 64) || !str(x.title, 1000) || !optStr(x.description) || !strList(x.phases, LIMITS.phases)) return null;
  if (!int(x.revision) || !str(x.created_at, 64) || !str(x.updated_at, 64) || !optStr(x.archived_at, 64)) return null;
  return {
    id: x.id, title: x.title, description: (x.description as string | null | undefined) ?? null, phases: x.phases,
    revision: x.revision, created_at: x.created_at, updated_at: x.updated_at,
    archived_at: (x.archived_at as string | null | undefined) ?? null,
  };
}

function readItem(x: unknown): Item | null {
  if (!isObj(x) || !str(x.id, 64) || !str(x.title, 1000) || !isStatus(x.status)) return null;
  if (x.auto_status != null && !isStatus(x.auto_status)) return null;
  if (!optStr(x.phase, 200) || !int(x.position) || !optStr(x.notes, 20_000) || !str(x.updated_at, 64)) return null;
  if (!strList(x.depends_on, LIMITS.dependsOn) || !Array.isArray(x.links) || x.links.length > LIMITS.links) return null;
  const links = x.links.map(readLink);
  if (links.some((l) => !l)) return null;
  return {
    id: x.id, title: x.title, status: x.status, auto_status: (x.auto_status as Status | null | undefined) ?? null,
    phase: (x.phase as string | null | undefined) ?? null, position: x.position,
    notes: (x.notes as string | null | undefined) ?? null, depends_on: x.depends_on, links: links as Link[], updated_at: x.updated_at,
  };
}

export function readList(x: unknown): Roadmap[] | null {
  if (!isObj(x) || !Array.isArray(x.roadmaps) || x.roadmaps.length > 200) return null;
  const roadmaps = x.roadmaps.map(readRoadmap);
  return roadmaps.every(Boolean) ? (roadmaps as Roadmap[]) : null;
}

export function readSnapshot(x: unknown): Snapshot | null {
  if (!isObj(x) || !Array.isArray(x.items) || x.items.length > LIMITS.items) return null;
  const roadmap = readRoadmap(x.roadmap);
  const items = x.items.map(readItem);
  return roadmap && items.every(Boolean) ? { roadmap, items: items as Item[] } : null;
}

export function readHistory(x: unknown): HistoryEntry[] | null {
  if (!isObj(x) || !Array.isArray(x.changes) || x.changes.length > LIMITS.history) return null;
  const out: HistoryEntry[] = [];
  for (const c of x.changes) {
    if (!isObj(c) || !str(c.id, 64) || !int(c.revision) || !str(c.at, 64) || !["owner", "jarvis", "github"].includes(c.author as string)) return null;
    if (!optStr(c.device_id, 64) || !optStr(c.proposal_id, 64) || !str(c.action, 40) || !optStr(c.item_id, 64) || !str(c.title, 1000)) return null;
    if (!strList(c.fields, 20) || (c.from_status != null && !isStatus(c.from_status)) || (c.to_status != null && !isStatus(c.to_status))) return null;
    out.push({
      id: c.id, revision: c.revision, at: c.at, author: c.author as Author,
      device_id: (c.device_id as string | null | undefined) ?? null, proposal_id: (c.proposal_id as string | null | undefined) ?? null,
      action: c.action, item_id: (c.item_id as string | null | undefined) ?? null, title: c.title, fields: c.fields,
      from_status: (c.from_status as Status | null | undefined) ?? null, to_status: (c.to_status as Status | null | undefined) ?? null,
    });
  }
  return out;
}

function readFields(x: Record<string, unknown>): ItemPatch | null {
  const out: ItemPatch = {};
  if (x.title !== undefined) { if (!str(x.title, 1000)) return null; out.title = x.title; }
  if (x.status !== undefined) { if (!isStatus(x.status)) return null; out.status = x.status; }
  if (x.phase !== undefined) { if (!optStr(x.phase, 200)) return null; out.phase = x.phase as string | null; }
  if (x.position !== undefined) { if (!int(x.position)) return null; out.position = x.position; }
  if (x.notes !== undefined) { if (!optStr(x.notes, 20_000)) return null; out.notes = x.notes as string | null; }
  if (x.depends_on !== undefined) { if (!strList(x.depends_on, LIMITS.dependsOn)) return null; out.depends_on = x.depends_on; }
  if (x.links !== undefined) {
    if (!Array.isArray(x.links) || x.links.length > LIMITS.links) return null;
    const links = x.links.map(readLink);
    if (links.some((l) => !l)) return null;
    out.links = links as Link[];
  }
  return out;
}

function readOp(x: unknown): ProposalOp | null {
  if (!isObj(x)) return null;
  if (x.op === "create_item" && isObj(x.item)) {
    const item = readFields(x.item);
    return item && typeof item.title === "string" ? { op: "create_item", item: item as NewItem } : null;
  }
  if (x.op === "update_item" && str(x.item_id, 64) && isObj(x.patch)) {
    const patch = readFields(x.patch);
    return patch ? { op: "update_item", item_id: x.item_id, patch } : null;
  }
  return null;
}

export function readProposals(x: unknown): Proposal[] | null {
  if (!isObj(x) || !Array.isArray(x.proposals) || x.proposals.length > 100) return null;
  const out: Proposal[] = [];
  for (const p of x.proposals) {
    if (!isObj(p) || !str(p.id, 64) || !int(p.base_revision) || !Array.isArray(p.ops) || p.ops.length > LIMITS.ops) return null;
    if (!optStr(p.rationale, 4000) || !str(p.created_at, 64) || !str(p.expires_at, 64)) return null;
    const ops = p.ops.map(readOp);
    if (ops.some((o) => !o)) return null;
    out.push({
      id: p.id, base_revision: p.base_revision, ops: ops as ProposalOp[],
      rationale: (p.rationale as string | null | undefined) ?? null, created_at: p.created_at, expires_at: p.expires_at,
    });
  }
  return out;
}

// ---- display -----------------------------------------------------------

export type Tally = { status: Status; count: number }[];
/** Items per status, in STATUSES order, statuses without items left out. */
export function tally(items: Item[]): Tally {
  return STATUSES.map((status) => ({ status, count: items.filter((i) => i.status === status).length }))
    .filter((t) => t.count > 0);
}

const byPosition = (a: Item, b: Item) => a.position - b.position || a.title.localeCompare(b.title);

/** The rail: one group per roadmap phase in its order, then items without a
 *  (known) phase. Empty phases stay, so the rail shows the whole plan. */
export function groupByPhase(phases: string[], items: Item[]): { phase: string | null; items: Item[] }[] {
  const groups = phases.map((phase) => ({ phase: phase as string | null, items: items.filter((i) => i.phase === phase).sort(byPosition) }));
  const rest = items.filter((i) => i.phase === null || !phases.includes(i.phase)).sort(byPosition);
  return rest.length ? [...groups, { phase: null, items: rest }] : groups;
}

/** A dependency is met once it is ready (merged) or live. */
export const isDone = (status: Status) => status === "live" || status === "ready";

/** What holds the plan up: blocked items, and open items with an unmet
 *  dependency. Done items wait on nothing. */
export function waitingOn(items: Item[]): { item: Item; unmet: Item[] }[] {
  const byId = new Map(items.map((i) => [i.id, i]));
  return items
    .filter((i) => !isDone(i.status))
    .map((item) => ({
      item,
      unmet: item.depends_on.map((id) => byId.get(id)).filter((d): d is Item => !!d && !isDone(d.status)),
    }))
    .filter((w) => w.item.status === "blocked" || w.unmet.length > 0)
    .sort((a, b) => byPosition(a.item, b.item));
}

/** Items `id` may depend on: not itself and none that already depends on it,
 *  directly or through others (Core refuses cycles). */
export function dependencyOptions(items: Item[], id: string | null): Item[] {
  if (id === null) return [...items].sort(byPosition);
  const dependents = new Set<string>([id]);
  for (let grew = true; grew;) {
    grew = false;
    for (const i of items) {
      if (!dependents.has(i.id) && i.depends_on.some((d) => dependents.has(d))) { dependents.add(i.id); grew = true; }
    }
  }
  return items.filter((i) => !dependents.has(i.id)).sort(byPosition);
}

/** The sheet's editable fields. */
export type ItemDraft = { title: string; status: Status; phase: string | null; notes: string; depends_on: string[]; links: Link[] };

export const toDraft = (item: Item): ItemDraft => ({
  title: item.title, status: item.status, phase: item.phase, notes: item.notes ?? "",
  depends_on: [...item.depends_on], links: item.links.map((l) => ({ ...l })),
});

const sameLinks = (a: Link[], b: Link[]) => a.length === b.length && a.every((l, i) => sameTarget(l, b[i]));

/** Only what changed, so an unchanged sheet sends nothing (null). Empty
 *  notes clear them. */
export function itemPatch(item: Item, draft: ItemDraft): ItemPatch | null {
  const patch: ItemPatch = {};
  const title = draft.title.trim();
  if (title !== item.title) patch.title = title;
  if (draft.status !== item.status) patch.status = draft.status;
  if (draft.phase !== item.phase) patch.phase = draft.phase;
  const notes = draft.notes.trim() ? draft.notes : null;
  if (notes !== item.notes) patch.notes = notes;
  if (draft.depends_on.join("\n") !== item.depends_on.join("\n")) patch.depends_on = draft.depends_on;
  if (!sameLinks(draft.links, item.links)) patch.links = draft.links.map(linkInput);
  return Object.keys(patch).length ? patch : null;
}

/** Why a draft cannot be saved yet; null when Core should accept it. */
export function draftIssue(draft: ItemDraft): string | null {
  if (!validText(draft.title.trim(), LIMITS.itemTitle)) return "The title must be 1 to 200 characters on one line.";
  if (draft.notes.trim() && !validText(draft.notes, LIMITS.notes, true)) return "Notes must be at most 4000 characters, without hidden characters.";
  return null;
}

export type DiffRow = { field: string; before: string; after: string };
export type OpView = { kind: "create" | "update"; title: string; rows: DiffRow[] };

const NONE = "—";

/** One proposal operation as before → after rows, in the roadmap's words. */
export function describeOp(op: ProposalOp, items: Item[]): OpView {
  const byId = new Map(items.map((i) => [i.id, i]));
  const deps = (ids: string[] | undefined) => (ids?.length ? ids.map((id) => byId.get(id)?.title ?? "Unknown item").join(", ") : NONE);
  const links = (list: Link[] | undefined) => (list?.length ? list.map(linkLabel).join(", ") : NONE);
  const value = (field: keyof ItemPatch, v: unknown): string => {
    if (v === undefined || v === null || v === "") return NONE;
    if (field === "status") return STATUS_LABEL[v as Status];
    if (field === "depends_on") return deps(v as string[]);
    if (field === "links") return links(v as Link[]);
    return String(v);
  };
  const LABEL: Record<keyof ItemPatch, string> = {
    title: "Title", status: "Status", phase: "Phase", position: "Position", notes: "Notes", depends_on: "Depends on", links: "Links",
  };
  const fields = Object.keys(LABEL) as (keyof ItemPatch)[];
  if (op.op === "create_item") {
    const item: ItemPatch = { status: "planned", ...op.item };
    return {
      kind: "create", title: op.item.title,
      rows: fields.filter((f) => f !== "title" && f !== "position" && item[f] !== undefined)
        .map((f) => ({ field: LABEL[f], before: NONE, after: value(f, item[f]) })),
    };
  }
  const current = byId.get(op.item_id);
  return {
    kind: "update", title: current?.title ?? "An item that no longer exists",
    rows: fields.filter((f) => op.patch[f] !== undefined)
      .map((f) => ({ field: LABEL[f], before: current ? value(f, current[f]) : NONE, after: value(f, op.patch[f]) })),
  };
}

const ACTION_LABEL: Record<string, string> = {
  roadmap_created: "Created the roadmap", roadmap_updated: "Changed the roadmap", roadmap_archived: "Archived the roadmap",
  item_created: "Added", item_updated: "Changed", item_deleted: "Deleted",
};
export const AUTHOR_LABEL: Record<Author, string> = { owner: "You", jarvis: "Jarvis", github: "GitHub" };

/** One history line, e.g. `Changed "Sync": status Planned → Ready`. */
export function describeChange(c: HistoryEntry): string {
  const action = ACTION_LABEL[c.action] ?? "Changed";
  const subject = c.item_id ? `${action} “${c.title}”` : action;
  const status = c.from_status && c.to_status ? `status ${STATUS_LABEL[c.from_status]} → ${STATUS_LABEL[c.to_status]}` : null;
  const others = c.fields.filter((f) => !(status && f === "status")).map((f) => f.replace(/_/g, " "));
  const detail = [status, ...others].filter(Boolean).join(", ");
  return detail && c.action.endsWith("updated") ? `${subject}: ${detail}` : subject;
}

// ---- failures ------------------------------------------------------------

export type Failure = "no-api" | "unreachable" | "signin" | "not-found" | "unavailable" | "failed";

export function failureOf(error: unknown): Failure {
  const e = error as { name?: string; status?: number } | null;
  if (e?.name === "NetworkError") return "unreachable";
  if (e?.name === "ApiError") {
    if (e.status === 401) return "signin";
    if (e.status === 404) return "not-found";
    if (e.status === 405 || e.status === 501) return "no-api";
    if (e.status === 503) return "unavailable";
  }
  return "failed";
}

export function failureText(f: Failure): string {
  switch (f) {
    case "no-api": return "This Core has no roadmaps yet. Update Core on your Home Node.";
    case "unreachable": return "No answer from your Home Node. Check the connection.";
    case "signin": return "Your session is not valid. Sign in again on the Core page.";
    case "not-found": return "This roadmap does not exist on your Home Node (or this Core has no roadmaps yet).";
    case "unavailable": return "Core could not read the roadmaps just now. Try again in a minute.";
    case "failed": return "Core returned an answer this app does not understand, or an error. Try again later.";
  }
}

export const CHANGED_ELSEWHERE = "Changed elsewhere, reloaded.";

/** The notice after a failed write. A 409 is a conflict when the reload
 *  shows a newer revision; otherwise Core refused for a limit or an
 *  archived roadmap. A 400 means a rule this app did not catch. */
export function writeFailureText(error: unknown, revisionMoved: boolean): string {
  const e = error as { name?: string; status?: number } | null;
  if (e?.name === "ApiError" && e.status === 409) {
    return revisionMoved ? CHANGED_ELSEWHERE : "Core refused this change: the roadmap is archived or a limit is reached.";
  }
  if (e?.name === "ApiError" && e.status === 400) return "Core refused this change as invalid. Check the fields and try again.";
  if (e?.name === "NetworkError") return "No answer from your Home Node. It is unknown whether the change arrived; reloaded to check.";
  return `Not saved. ${failureText(failureOf(error))}`;
}
