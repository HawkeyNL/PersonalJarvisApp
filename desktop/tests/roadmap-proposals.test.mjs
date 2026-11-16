import test from "node:test";
import assert from "node:assert/strict";
import {
  ADDED, ADD_NOT_CONFIRMED, ADD_REFUSED, ADD_TOO_OLD, addRoadmap, addRoadmapCard, proposalGroups, proposalHeading,
  proposalSummary, readRoadmapProposal, withRoadmapProposal,
} from "../src/roadmapProposals.ts";

// Neutral test data only: no real roadmap content belongs in this repository.
const id = (n) => `00000000-0000-4000-8000-00000000000${n}`;
const newBody = {
  title: "Example", description: "A plan", phases: ["Done", "Now"],
  items: [
    { title: "First", status: "live", phase: "Done", links: [{ kind: "pr", repo: "owner/repo", number: 12 }] },
    { title: "Second", status: "planned", phase: "Now", notes: "Line one\nLine two" },
    { title: "Third", status: "blocked" },
  ],
};
const itemsBody = { items: [{ title: "Card", status: "in_progress", phase: "Now", links: [{ kind: "release", repo: "owner/repo", tag: "v1.2.0" }] }] };
const fresh = { id: id(1), title: "Example", body: newBody };
const existing = { id: id(2), roadmap_id: id(9), title: "Example", body: itemsBody };
const read = (p) => readRoadmapProposal([p]);

test("no proposal: absent, empty or not a list", () => {
  for (const x of [undefined, null, [], {}, fresh]) assert.equal(readRoadmapProposal(x), null);
});

test("a valid new roadmap and a valid existing one; only the first entry counts", () => {
  assert.deepEqual(read(fresh), { id: id(1), roadmapId: null, title: "Example", body: newBody });
  assert.deepEqual(read(existing), { id: id(2), roadmapId: id(9), title: "Example", body: itemsBody });
  assert.equal(readRoadmapProposal([fresh, existing]).id, id(1));
  assert.equal(readRoadmapProposal([{ id: "x" }, existing]), null);
  // Core's defaults: no description, no phases, no items.
  assert.deepEqual(read({ id: id(3), title: "Bare", body: { title: "Bare", phases: [], items: [] } })?.roadmapId, null);
});

test("every invalid shape is skipped", () => {
  const bad = [
    { ...fresh, id: "not-a-uuid" },
    { ...fresh, extra: 1 },
    { ...fresh, title: "" },
    { ...fresh, body: "x" },
    { ...existing, roadmap_id: "r1" },
    { ...existing, roadmap_id: null },
    { ...existing, body: { items: [] } },
    { ...existing, body: { ...itemsBody, title: "x" } },
    { ...fresh, body: { ...newBody, items: undefined } },
    { ...fresh, body: { ...newBody, position: 1 } },
    { ...fresh, body: { ...newBody, title: "a".repeat(121) } },
    { ...fresh, body: { ...newBody, description: "a".repeat(2001) } },
    { ...fresh, body: { ...newBody, phases: Array.from({ length: 21 }, (_, n) => `P${n}`) } },
    { ...fresh, body: { ...newBody, phases: ["a".repeat(61)] } },
    { ...fresh, body: { ...newBody, phases: ["Now", "Now"] } },
    { ...fresh, body: { ...newBody, items: [{ title: "x", status: "planned", position: 0 }] } },
    { ...fresh, body: { ...newBody, items: [{ title: "x", status: "planned", depends_on: [] }] } },
    { ...fresh, body: { ...newBody, items: [{ title: "", status: "planned" }] } },
    { ...fresh, body: { ...newBody, items: [{ title: "a".repeat(201), status: "planned" }] } },
    { ...fresh, body: { ...newBody, items: [{ title: "two\nlines", status: "planned" }] } },
    { ...fresh, body: { ...newBody, items: [{ title: "x", status: "planned", notes: "a".repeat(4001) }] } },
    { ...fresh, body: { ...newBody, items: [{ title: "x" }] } },
    { ...fresh, body: { ...newBody, items: [{ title: "x", status: "planned", links: [{ kind: "pr", repo: "not a repo", number: 1 }] }] } },
    { ...fresh, body: { ...newBody, items: [{ title: "x", status: "planned", links: [{ kind: "pr", repo: "owner/repo", number: 0 }] }] } },
    { ...fresh, body: { ...newBody, items: [{ title: "x", status: "planned", links: [{ kind: "pr", repo: "owner/repo", number: 1, state: "open" }] }] } },
    { ...fresh, body: { ...newBody, items: [{ title: "x", status: "planned", links: [{ kind: "release", repo: "owner/repo", tag: "bad tag" }] }] } },
  ];
  for (const p of bad) assert.equal(read(p), null, JSON.stringify(p).slice(0, 200));
});

test("an unknown status is skipped", () => {
  assert.equal(read({ ...existing, body: { items: [{ title: "x", status: "done" }] } }), null);
});

test("a url link is skipped", () => {
  const link = { kind: "url", url: "https://example.com" };
  assert.equal(read({ ...existing, body: { items: [{ title: "x", status: "planned", links: [link] }] } }), null);
  assert.equal(read({ ...existing, body: { items: [{ title: "x", status: "planned", links: [{ kind: "pr", repo: "owner/repo", number: 1, url: "https://example.com" }] }] } }), null);
});

test("more than 100 items is skipped", () => {
  const items = (n) => Array.from({ length: n }, (_, i) => ({ title: `Item ${i}`, status: "planned" }));
  assert.ok(read({ ...existing, body: { items: items(100) } }));
  assert.equal(read({ ...existing, body: { items: items(101) } }), null);
  assert.equal(read({ ...fresh, body: { ...newBody, items: items(101) } }), null);
});

test("a phase that is not among the roadmap's phases is skipped", () => {
  assert.equal(read({ ...fresh, body: { ...newBody, items: [{ title: "x", status: "planned", phase: "Later" }] } }), null);
  assert.equal(read({ ...fresh, body: { title: "x", phases: [], items: [{ title: "x", status: "planned", phase: "Now" }] } }), null);
});

test("a duplicate id shows once, also after a dismiss", () => {
  const seen = new Set();
  const cards = withRoadmapProposal([], seen, read(fresh), "c", "m");
  assert.equal(cards.length, 1);
  assert.deepEqual([cards[0].state, cards[0].viewId, cards[0].error], ["open", null, ""]);
  assert.equal(withRoadmapProposal(cards, seen, read(fresh), "c", "m"), cards);
  assert.deepEqual(withRoadmapProposal([], seen, read(fresh), "c", "m"), []);
  assert.deepEqual(withRoadmapProposal([], seen, null, "c", "m"), []);
});

test("the card's wording and grouping", () => {
  assert.equal(proposalHeading(read(fresh)), "New roadmap: Example");
  assert.equal(proposalSummary(read(fresh)), "3 items in 2 phases");
  assert.equal(proposalHeading(read(existing)), "Add 1 item to Example");
  assert.equal(proposalSummary(read(existing)), "1 item");
  const two = read({ ...existing, body: { items: [...itemsBody.items, { title: "Other", status: "ready" }] } });
  assert.equal(proposalHeading(two), "Add 2 items to Example");
  assert.equal(proposalSummary(two), "2 items");
  assert.equal(proposalSummary(read({ ...fresh, body: { title: "x", phases: ["Now"], items: [{ title: "x", status: "planned", phase: "Now" }] } })), "1 item in 1 phase");
  assert.deepEqual(proposalGroups(read(fresh)).map((g) => [g.phase, g.items.map((i) => [i.title, i.status])]), [
    ["Done", [["First", "live"]]], ["Now", [["Second", "planned"]]], [null, [["Third", "blocked"]]],
  ]);
  assert.deepEqual(proposalGroups(two).map((g) => g.phase), ["Now", null]);
  assert.equal(ADDED, "Added to Roadmaps");
  assert.equal(ADD_NOT_CONFIRMED, "Not confirmed: it may have been added.");
  assert.equal(ADD_REFUSED, "Core refused this roadmap, for example because a phase changed or a limit was reached. Ask Jarvis again.");
  assert.equal(ADD_TOO_OLD, "Update Jarvis Core to add roadmaps from chat.");
});

// ---- add -------------------------------------------------------------------

const snapshot = (rid, revision) => ({
  roadmap: { id: rid, title: "Example", description: null, phases: ["Now"], revision, created_at: "t", updated_at: "t", archived_at: null },
  items: [],
});
const apiError = (status) => Object.assign(new Error("x"), { name: "ApiError", status });
const guards = (over = {}) => ({
  locked: () => false, current: () => true,
  create: async () => snapshot(id(7), 1),
  get: async () => snapshot(id(9), 4),
  createItems: async () => ({ ...snapshot(id(9), 5), item_ids: ["i1"] }),
  ...over,
});

test("2xx: a new roadmap is created with the body as is and opens the new roadmap", async () => {
  const sent = [];
  const r = await addRoadmap(read(fresh), guards({ create: async (b) => { sent.push(JSON.stringify(b)); return snapshot(id(7), 1); } }));
  assert.deepEqual(sent, [JSON.stringify(newBody)]);
  assert.deepEqual(r, { state: "added", viewId: id(7), error: "" });
});

test("2xx: items go on the existing roadmap's current revision", async () => {
  const sent = [];
  const r = await addRoadmap(read(existing), guards({ createItems: async (rid, rev, b) => { sent.push([rid, rev, b]); return snapshot(rid, rev + 1); } }));
  assert.deepEqual(sent, [[id(9), 4, itemsBody]]);
  assert.deepEqual(r, { state: "added", viewId: id(9), error: "" });
});

test("4xx: the card opens again with the refusal text", async () => {
  for (const status of [400, 413]) {
    assert.deepEqual(await addRoadmap(read(fresh), guards({ create: async () => { throw apiError(status); } })), { state: "open", viewId: null, error: ADD_REFUSED });
  }
  // A new roadmap's 409 is a limit: no retry.
  let calls = 0;
  assert.deepEqual(await addRoadmap(read(fresh), guards({ create: async () => { calls++; throw apiError(409); } })), { state: "open", viewId: null, error: ADD_REFUSED });
  assert.equal(calls, 1);
  const signin = await addRoadmap(read(existing), guards({ createItems: async () => { throw apiError(401); } }));
  assert.equal(signin.error, "Your session is not valid. Sign in again on the Core page.");
  // The revision read failed: nothing was written.
  const gone = await addRoadmap(read(existing), guards({ get: async () => { throw Object.assign(new Error("x"), { name: "NetworkError" }); } }));
  assert.deepEqual(gone, { state: "open", viewId: null, error: "No answer from your Home Node. Check the connection." });
});

test("409: one retry on a fresh revision, then the refusal text", async () => {
  const revs = [];
  let gets = 0;
  const once = guards({
    get: async () => snapshot(id(9), 4 + gets++),
    createItems: async (rid, rev) => { revs.push(rev); if (revs.length === 1) throw apiError(409); return snapshot(rid, rev + 1); },
  });
  assert.deepEqual(await addRoadmap(read(existing), once), { state: "added", viewId: id(9), error: "" });
  assert.deepEqual(revs, [4, 5]);
  let posts = 0;
  const twice = guards({ createItems: async () => { posts++; throw apiError(409); } });
  assert.deepEqual(await addRoadmap(read(existing), twice), { state: "open", viewId: null, error: ADD_REFUSED });
  assert.equal(posts, 2);
});

test("5xx, no answer or an unreadable answer: not confirmed", async () => {
  const unconfirmed = { state: "unconfirmed", viewId: null, error: ADD_NOT_CONFIRMED };
  const fails = [
    async () => { throw apiError(500); }, async () => { throw apiError(503); },
    async () => { throw Object.assign(new Error("timeout"), { name: "NetworkError" }); },
    async () => { throw new Error("Home Node response is invalid"); },
    async () => ({ ok: true }),
  ];
  for (const f of fails) {
    assert.deepEqual(await addRoadmap(read(fresh), guards({ create: f })), unconfirmed);
    assert.deepEqual(await addRoadmap(read(existing), guards({ createItems: f })), unconfirmed);
  }
});

test("404/405: an older Core", async () => {
  for (const status of [404, 405]) {
    const tooOld = { state: "open", viewId: null, error: ADD_TOO_OLD };
    assert.deepEqual(await addRoadmap(read(fresh), guards({ create: async () => { throw apiError(status); } })), tooOld);
    assert.deepEqual(await addRoadmap(read(existing), guards({ createItems: async () => { throw apiError(status); } })), tooOld);
  }
});

const card = (p = existing) => ({ ...read(p), conversationId: "c", messageId: "m", state: "open", viewId: null, error: "x" });

test("locked: nothing is sent, also when the lock comes between the read and the write", async () => {
  let calls = 0;
  const count = async () => { calls++; return snapshot(id(9), 1); };
  const c = card();
  await addRoadmapCard(c, guards({ locked: () => true, create: count, get: count, createItems: count }));
  assert.deepEqual([c.state, c.error, calls], ["open", "x", 0]);
  let isLocked = false;
  const late = card();
  await addRoadmapCard(late, guards({ locked: () => isLocked, get: async () => { isLocked = true; return snapshot(id(9), 4); }, createItems: count }));
  assert.deepEqual([late.state, late.error, calls], ["open", "", 0]);
});

test("Add guards: a double click sends once; a finished card sends nothing; a stale result is dropped", async () => {
  let calls = 0;
  let release;
  const c = card(fresh);
  const g = guards({ create: () => { calls++; return new Promise((r) => { release = () => r(snapshot(id(7), 1)); }); } });
  const first = addRoadmapCard(c, g);
  assert.deepEqual([c.state, c.error], ["adding", ""]);
  await addRoadmapCard(c, g);
  release();
  await first;
  assert.equal(calls, 1);
  assert.deepEqual([c.state, c.viewId], ["added", id(7)]);
  await addRoadmapCard({ ...card(), state: "unconfirmed" }, guards({ createItems: async () => { calls++; } }));
  await addRoadmapCard(undefined, guards());
  assert.equal(calls, 1);
  const left = card(fresh);
  await addRoadmapCard(left, guards({ current: () => false }));
  assert.deepEqual([left.state, left.viewId], ["adding", null]);
  // Leaving between the read and the write sends nothing.
  let current = true;
  await addRoadmapCard(card(), guards({ current: () => current, get: async () => { current = false; return snapshot(id(9), 4); }, createItems: async () => { calls++; } }));
  assert.equal(calls, 1);
});
