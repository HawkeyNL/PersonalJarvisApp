import assert from "node:assert/strict";
import test from "node:test";
import {createMemoryHistory, createRouter} from "vue-router";

import {NAV, PAGES, REDIRECTS, ariaCurrent, buildRoutes, tasksNode, tasksPath} from "../src/navigation.ts";

const VIEWS = ["home", "conversations", "agents", "integrations", "health", "memory", "trading", "desk", "stocks", "stock", "positions", "tasks", "context", "settings", "soon"];
const stub = name => ({name, render: () => null});

async function routerAt(path) {
  const router = createRouter({history: createMemoryHistory(), routes: buildRoutes(Object.fromEntries(VIEWS.map(v => [v, stub(v)])))});
  await router.push(path);
  return router.currentRoute.value;
}

test("sidebar follows the agreed sections and entries", () => {
  assert.deepEqual(NAV.map(s => [s.label ?? null, s.items.map(i => i.label)]), [
    ["Jarvis", ["Chat", "Memory"]],
    ["Trading", ["Dashboard", "Stocks", "Memecoin", "Futures", "Positions", "Autonomy"]],
    ["Activity", ["Approvals", "Runs", "Notifications"]],
    [null, ["Content"]],
    ["System", ["Settings", "Devices", "Core Admin"]],
  ]);
  const byId = Object.fromEntries(NAV.flatMap(s => s.items).map(i => [i.id, i]));
  assert.equal(byId.futures.badge, "Later");
  assert.equal(byId.content.disabled, true);
  assert.equal(byId.content.path, undefined);
  // Core Admin is a separate app: a hint, never a link into this one.
  assert.equal(byId["core-admin"].path, undefined);
});

test("every sidebar link opens a page that highlights that same entry", async () => {
  for (const item of NAV.flatMap(s => s.items).filter(i => i.path)) {
    const route = await routerAt(item.path);
    assert.equal(route.path, item.path);
    assert.equal(route.meta.nav, item.id, item.path);
    assert.ok(route.matched[0].components.default, item.path);
  }
});

test("pages map onto the existing views", async () => {
  const view = async path => (await routerAt(path)).matched[0].components.default.name;
  assert.equal(await view("/"), "home");
  assert.equal(await view("/memory"), "memory");
  assert.equal(await view("/trading"), "desk");
  assert.equal(await view("/trading/stocks"), "stocks");
  assert.equal(await view("/trading/positions"), "positions");
  assert.equal(await view("/trading/holdings"), "trading");
  assert.equal(await view("/trading/ibkr"), "trading");
  assert.equal(await view("/runs"), "tasks");
  assert.equal(await view("/approvals"), "tasks");
  assert.equal(await view("/settings"), "settings");
  assert.equal(await view("/devices"), "context");
  for (const path of ["/conversations", "/agents", "/integrations", "/health"]) {
    assert.equal(await view(path), path.slice(1), path);
  }
  // Approvals opens the Tasks view on the waiting node; Runs on its default.
  assert.deepEqual(PAGES.find(p => p.path === "/approvals").props, {node: "waiting"});
  assert.equal(PAGES.find(p => p.path === "/runs").props, undefined);
});

test("sub-paths and hub-only pages highlight their parent entry", async () => {
  assert.equal((await routerAt("/trading/ibkr")).meta.nav, "dashboard");
  assert.equal((await routerAt("/trading/holdings")).meta.nav, "dashboard");
  assert.equal((await routerAt("/conversations")).meta.nav, "chat");
  assert.equal((await routerAt("/agents")).meta.nav, undefined);
});

test("placeholders say what is missing and carry no sample data", () => {
  const soon = PAGES.filter(p => p.view === "soon");
  assert.deepEqual(soon.map(p => p.path).sort(), [
    "/content", "/notifications", "/trading/autonomy", "/trading/futures", "/trading/memecoin",
  ]);
  for (const page of soon) {
    assert.deepEqual(Object.keys(page.props).sort(), ["detail", "icon", "title"], page.path);
    assert.ok(page.props.detail.length > 10, page.path);
  }
});

test("old paths redirect to the new ones, keeping query and hash", async () => {
  for (const [from, to] of Object.entries(REDIRECTS)) {
    assert.equal((await routerAt(from)).path, to, from);
  }
  assert.equal((await routerAt("/tasks")).path, "/runs");
  assert.equal((await routerAt("/context")).path, "/devices");
  assert.equal((await routerAt("/status?node=usage")).fullPath, "/health?node=usage");
  assert.equal((await routerAt("/broker#x")).fullPath, "/trading/ibkr#x");
});

test("paths are unique and redirects never shadow or chain", () => {
  const paths = PAGES.map(p => p.path);
  assert.equal(new Set(paths).size, paths.length);
  assert.equal(new Set(PAGES.map(p => p.name)).size, PAGES.length);
  for (const [from, to] of Object.entries(REDIRECTS)) {
    assert.ok(!paths.includes(from), `${from} is both a page and a redirect`);
    assert.ok(paths.includes(to), `${from} redirects to unknown ${to}`);
  }
});

test("exactly one sidebar entry is aria-current on every sidebar page", async () => {
  const items = NAV.flatMap(s => s.items);
  for (const page of PAGES.filter(p => p.nav)) {
    const nav = (await routerAt(page.path.replace(/:conid\(.*\)/, "265598"))).meta.nav;
    const current = items.filter(i => ariaCurrent(i.id, nav) === "page").map(i => i.id);
    assert.deepEqual(current, [page.nav], page.path);
  }
  const nav = (await routerAt("/health")).meta.nav;
  assert.deepEqual(items.filter(i => ariaCurrent(i.id, nav)), []);
});

test("Tasks keeps the route on Approvals only for the waiting node", async () => {
  assert.equal(tasksPath("waiting"), "/approvals");
  for (const node of ["active", "completed", "scheduled"]) assert.equal(tasksPath(node), "/runs");
  // Both paths resolve to a page whose props open the matching node.
  for (const node of ["waiting", "active"]) {
    const page = PAGES.find(p => p.path === tasksPath(node));
    assert.equal(tasksNode(page.props?.node, node), node);
  }
  // Clicking Approvals opens waiting; leaving it for Runs drops waiting but keeps any other choice.
  assert.equal(tasksNode("waiting", "completed"), "waiting");
  assert.equal(tasksNode(undefined, "waiting"), "active");
  assert.equal(tasksNode(undefined, "completed"), "completed");
});

test("Devices names its own heading", () => {
  assert.equal(PAGES.find(p => p.path === "/devices").props.title, "DEVICES");
});

test("a stock detail page opens by numeric IBKR conid under Stocks", async () => {
  const route = await routerAt("/trading/stocks/265598");
  assert.equal(route.matched[0].components.default.name, "stock");
  assert.equal(route.params.conid, "265598");
  assert.equal(route.meta.nav, "stocks");
  // Anything but digits is no instrument page.
  assert.equal((await routerAt("/trading/stocks/AAPL")).matched.length, 0);
  assert.equal((await routerAt("/portfolio")).path, "/trading/holdings");
});
