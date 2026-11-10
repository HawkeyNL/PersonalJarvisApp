import assert from "node:assert/strict";
import test from "node:test";

import {
  CHANGED_ELSEWHERE, addLink, dependencyOptions, describeChange, describeOp, draftIssue, groupByPhase, isNewer, itemPatch,
  linkHref, linkInput, linkLabel, parseLink, parsePhases, readHistory, readList, readProposals, readSnapshot, roadmapChange,
  tally, toDraft, validHttpsUrl, validText, waitingOn, writeFailureText,
} from "../src/roadmaps.ts";

// Neutral test data only: no real roadmap content belongs in this repository.
const roadmap = {
  id: "r1", title: "Example", description: null, phases: ["Now", "Next", "Later"], revision: 4,
  created_at: "2026-11-01T10:00:00Z", updated_at: "2026-11-02T10:00:00Z", archived_at: null,
};
const item = (id, over = {}) => ({
  id, title: `Item ${id}`, status: "planned", auto_status: null, phase: "Now", position: 0, notes: null,
  depends_on: [], links: [], updated_at: "2026-11-02T10:00:00Z", ...over,
});
const clone = (x) => JSON.parse(JSON.stringify(x));

test("snapshot guard accepts Core's shape and fails closed", () => {
  const good = { roadmap, items: [item("a", { links: [{ kind: "pr", repo: "o/r", number: 3, state: "open" }], auto_status: "in_progress" })] };
  const snap = readSnapshot(clone(good));
  assert.equal(snap.items[0].links[0].state, "open");
  assert.equal(snap.items[0].auto_status, "in_progress");
  // Absent optional fields read as null.
  const raw = clone(good);
  delete raw.roadmap.description;
  delete raw.items[0].notes;
  assert.equal(readSnapshot(raw).roadmap.description, null);
  assert.equal(readSnapshot(raw).items[0].notes, null);
  for (const broken of [
    null, [], { roadmap },
    { ...good, roadmap: { ...roadmap, revision: "4" } },
    { ...good, items: [item("a", { status: "done" })] },
    { ...good, items: [item("a", { auto_status: "merged" })] },
    { ...good, items: [item("a", { links: [{ kind: "ftp", url: "x" }] })] },
    { ...good, items: [item("a", { depends_on: "b" })] },
    { ...good, items: [item("a", { position: -1 })] },
  ]) assert.equal(readSnapshot(broken), null, JSON.stringify(broken)?.slice(0, 80));
  assert.deepEqual(readList({ roadmaps: [roadmap] }), [roadmap]);
  assert.equal(readList({ roadmaps: [{ ...roadmap, phases: [1] }] }), null);
});

test("history and proposal guards", () => {
  const change = {
    id: "c1", revision: 5, at: "2026-11-02T10:00:00Z", author: "github", device_id: null, proposal_id: null,
    action: "item_updated", item_id: "a", title: "Item a", fields: ["status"], from_status: "in_progress", to_status: "ready",
  };
  assert.equal(readHistory({ changes: [change] })[0].author, "github");
  assert.equal(readHistory({ changes: [{ ...change, author: "someone" }] }), null);
  const proposal = {
    id: "p1", base_revision: 4, rationale: null, created_at: "2026-11-02T10:00:00Z", expires_at: "2026-11-16T10:00:00Z",
    ops: [{ op: "create_item", item: { title: "New" } }, { op: "update_item", item_id: "a", patch: { status: "ready", notes: null } }],
  };
  const [p] = readProposals({ proposals: [proposal] });
  assert.equal(p.ops[1].patch.notes, null);
  assert.equal(readProposals({ proposals: [{ ...proposal, ops: [{ op: "delete_item", item_id: "a" }] }] }), null);
  assert.equal(readProposals({ proposals: [{ ...proposal, ops: [{ op: "create_item", item: {} }] }] }), null);
});

test("text rules mirror Core", () => {
  assert.ok(validText("Ship it", 120));
  assert.ok(!validText("   ", 120));
  assert.ok(!validText("a".repeat(121), 120));
  assert.ok(!validText("two\nlines", 120));
  assert.ok(validText("two\nlines\tok", 4000, true));
  assert.ok(!validText("zero​width", 120));
  assert.ok(!validText("bidi‮flip", 4000, true));
  assert.ok(validText("Gus’s plan ✓", 120));
  assert.deepEqual(parsePhases("Now\n Next \n\nLater\n"), ["Now", "Next", "Later"]);
  assert.deepEqual(parsePhases(""), []);
  assert.equal(parsePhases("Now\nNow"), null);
  assert.equal(parsePhases(Array.from({ length: 21 }, (_, i) => `P${i}`).join("\n")), null);
});

test("GitHub URLs and shorthands parse into typed links", () => {
  const ok = (input, hint) => { const r = parseLink(input, hint); assert.ok(r.ok, input); return r.link; };
  assert.deepEqual(ok("https://github.com/owner/repo/pull/12"), { kind: "pr", repo: "owner/repo", number: 12 });
  assert.deepEqual(ok("https://github.com/owner/repo/pull/12/files#diff"), { kind: "pr", repo: "owner/repo", number: 12 });
  assert.deepEqual(ok("https://github.com/owner/repo/issues/7?x=1"), { kind: "issue", repo: "owner/repo", number: 7 });
  assert.deepEqual(ok(" https://github.com/o-1/r.e_p/releases/tag/v1.2.3 "), { kind: "release", repo: "o-1/r.e_p", tag: "v1.2.3" });
  assert.deepEqual(ok("https://github.com/o/r/releases/tag/app%2Fv1"), { kind: "release", repo: "o/r", tag: "app/v1" });
  assert.deepEqual(ok("owner/repo#5"), { kind: "pr", repo: "owner/repo", number: 5 });
  assert.deepEqual(ok("owner/repo#5", "issue"), { kind: "issue", repo: "owner/repo", number: 5 });
  assert.deepEqual(ok("owner/repo@v0.0.78"), { kind: "release", repo: "owner/repo", tag: "v0.0.78" });
  assert.deepEqual(ok("https://example.com/a?b=c"), { kind: "url", url: "https://example.com/a?b=c" });
  for (const bad of [
    "http://example.com", "javascript:alert(1)", "https://user:pw@example.com", "https://exa mple.com",
    "https://github.com/owner/repo/pull/0", "https://github.com/owner/repo/pull/abc", "https://github.com/-o/r/pull/1",
    "owner/repo#0", "owner#5", "../x#1", "owner/..#1", "", "data:text/html,x",
  ]) assert.equal(parseLink(bad).ok, false, bad);
  assert.equal(parseLink("http://example.com").error, "Only https links are allowed.");
  assert.ok(validHttpsUrl("https://example.com"));
  assert.ok(!validHttpsUrl(`https://example.com/${"a".repeat(500)}`));
});

test("links display, open only over https and never send the cached state", () => {
  const pr = { kind: "pr", repo: "o/r", number: 3, state: "merged" };
  assert.equal(linkLabel(pr), "o/r#3");
  assert.equal(linkHref(pr), "https://github.com/o/r/pull/3");
  assert.equal(linkHref({ kind: "issue", repo: "o/r", number: 4 }), "https://github.com/o/r/issues/4");
  assert.equal(linkHref({ kind: "release", repo: "o/r", tag: "app/v1+b" }), "https://github.com/o/r/releases/tag/app/v1%2Bb");
  assert.equal(linkHref({ kind: "url", url: "javascript:alert(1)" }), null);
  assert.deepEqual(linkInput(pr), { kind: "pr", repo: "o/r", number: 3 });
  assert.equal(pr.state, "merged");
  assert.equal(addLink([pr], { kind: "pr", repo: "o/r", number: 3 }), null);
  assert.equal(addLink(Array.from({ length: 10 }, (_, i) => ({ kind: "pr", repo: "o/r", number: i + 1 })), { kind: "url", url: "https://a.b" }), null);
  assert.equal(addLink([pr], { kind: "issue", repo: "o/r", number: 3 }).length, 2);
});

test("tally, phases and waiting on", () => {
  const items = [
    item("a", { status: "live", position: 2 }),
    item("b", { status: "blocked", phase: "Next", notes: "Needs a decision\nmore" }),
    item("c", { status: "planned", depends_on: ["b", "a"], position: 1 }),
    item("d", { status: "ready", phase: "Gone" }),
    item("e", { status: "planned", phase: null, depends_on: ["d"] }),
  ];
  assert.deepEqual(tally(items), [{ status: "live", count: 1 }, { status: "ready", count: 1 }, { status: "planned", count: 2 }, { status: "blocked", count: 1 }]);
  const groups = groupByPhase(roadmap.phases, items);
  assert.deepEqual(groups.map((g) => [g.phase, g.items.map((i) => i.id)]), [["Now", ["c", "a"]], ["Next", ["b"]], ["Later", []], [null, ["d", "e"]]]);
  // Blocked items, and open items with a dependency that is not ready or live.
  assert.deepEqual(waitingOn(items).map((w) => [w.item.id, w.unmet.map((d) => d.id)]), [["b", []], ["c", ["b"]]]);
});

test("dependency picker leaves out the item and everything that depends on it", () => {
  const items = [item("a"), item("b", { depends_on: ["a"] }), item("c", { depends_on: ["b"] }), item("d")];
  assert.deepEqual(dependencyOptions(items, "a").map((i) => i.id), ["d"]);
  assert.deepEqual(dependencyOptions(items, "c").map((i) => i.id), ["a", "b", "d"]);
  assert.equal(dependencyOptions(items, null).length, 4);
});

test("an item patch holds only what changed", () => {
  const base = item("a", { notes: "Old", links: [{ kind: "pr", repo: "o/r", number: 1, state: "open" }] });
  const draft = toDraft(base);
  assert.equal(itemPatch(base, draft), null);
  draft.title = "  Item a  ";
  assert.equal(itemPatch(base, draft), null);
  draft.status = "blocked";
  draft.phase = null;
  draft.notes = "  ";
  assert.deepEqual(itemPatch(base, draft), { status: "blocked", phase: null, notes: null });
  draft.links = [...draft.links, { kind: "url", url: "https://a.b" }];
  assert.deepEqual(itemPatch(base, draft).links, [{ kind: "pr", repo: "o/r", number: 1 }, { kind: "url", url: "https://a.b" }]);
  assert.equal(draftIssue(draft), null);
  draft.title = "";
  assert.match(draftIssue(draft), /title/);
});

test("proposal operations read as before and after", () => {
  const items = [item("a", { status: "in_progress", notes: "x" }), item("b")];
  const update = describeOp({ op: "update_item", item_id: "a", patch: { status: "ready", notes: null, depends_on: ["b"] } }, items);
  assert.equal(update.title, "Item a");
  assert.deepEqual(update.rows, [
    { field: "Status", before: "In progress", after: "Ready" },
    { field: "Notes", before: "x", after: "—" },
    { field: "Depends on", before: "—", after: "Item b" },
  ]);
  const create = describeOp({ op: "create_item", item: { title: "New", phase: "Next", links: [{ kind: "issue", repo: "o/r", number: 9 }] } }, items);
  assert.equal(create.kind, "create");
  assert.deepEqual(create.rows, [
    { field: "Status", before: "—", after: "Planned" },
    { field: "Phase", before: "—", after: "Next" },
    { field: "Links", before: "—", after: "o/r#9" },
  ]);
  assert.equal(describeOp({ op: "update_item", item_id: "zz", patch: { title: "T" } }, items).rows[0].before, "—");
});

test("history lines and write failures", () => {
  const c = { action: "item_updated", item_id: "a", title: "Item a", fields: ["status", "depends_on"], from_status: "planned", to_status: "ready" };
  assert.equal(describeChange(c), "Changed “Item a”: status Planned → Ready, depends on");
  assert.equal(describeChange({ ...c, action: "item_created", fields: [], from_status: null, to_status: null }), "Added “Item a”");
  assert.equal(describeChange({ ...c, action: "roadmap_archived", item_id: null }), "Archived the roadmap");
  const conflict = { name: "ApiError", status: 409 };
  assert.equal(writeFailureText(conflict, true), CHANGED_ELSEWHERE);
  assert.match(writeFailureText(conflict, false), /archived or a limit/);
  assert.match(writeFailureText({ name: "ApiError", status: 400 }, false), /invalid/);
  assert.match(writeFailureText({ name: "NetworkError" }, false), /unknown whether/);
});

test("roadmap.changed reads only the id and revision, and refetches only newer revisions", () => {
  const event = (payload, type = "roadmap.changed") => ({ protocol: 1, sequence: 9, type, payload });
  const change = roadmapChange(event({ id: "r1", revision: 5, title: "ignored" }));
  assert.deepEqual(change, { id: "r1", revision: 5 });
  for (const bad of [
    event({ id: "r1", revision: -1 }), event({ id: "r1", revision: 1.5 }), event({ id: "r1", revision: "5" }),
    event({ id: "r1", revision: 2 ** 53 }), event({ id: "x".repeat(65), revision: 1 }), event({ revision: 1 }),
    event({ id: "r1", revision: 5 }, "connection.ready"), { type: "roadmap.changed" }, null,
  ]) assert.equal(roadmapChange(bad), null, JSON.stringify(bad));
  assert.equal(isNewer(change, 4), true);
  assert.equal(isNewer(change, 5), false, "own write already on screen");
  assert.equal(isNewer(change, 6), false, "late event for an older revision");
  assert.equal(isNewer(change, undefined), true, "nothing on screen yet");
});
