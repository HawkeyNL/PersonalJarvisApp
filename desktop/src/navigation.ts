// Desktop information architecture: the sidebar sections, the route behind
// every entry and the redirects from old paths. Pure data plus one builder,
// no .vue imports, so tests can load this file directly.
import type { RouteComponent, RouteRecordRaw } from "vue-router";
import type { IconName } from "./components/NavIcon.vue";

export type NavItem = {
  id: string;
  label: string;
  icon: IconName;
  /** Absent: the entry is not a link (disabled, or only a hint). */
  path?: string;
  badge?: string;
  disabled?: boolean;
  hint?: string;
};
/** A section without a label is a lone entry (Content). */
export type NavSection = { label?: string; items: NavItem[] };

export const NAV: NavSection[] = [
  { label: "Jarvis", items: [
    { id: "chat", label: "Chat", icon: "chat", path: "/" },
    { id: "memory", label: "Memory", icon: "memory", path: "/memory" },
  ] },
  { label: "Trading", items: [
    { id: "dashboard", label: "Dashboard", icon: "chart", path: "/trading" },
    { id: "stocks", label: "Stocks", icon: "stocks", path: "/trading/stocks" },
    { id: "memecoin", label: "Memecoin", icon: "coin", path: "/trading/memecoin" },
    { id: "futures", label: "Futures", icon: "futures", path: "/trading/futures", badge: "Later" },
    { id: "positions", label: "Positions", icon: "orders", path: "/trading/positions" },
    { id: "autonomy", label: "Autonomy", icon: "autonomy", path: "/trading/autonomy" },
  ] },
  { label: "Activity", items: [
    { id: "approvals", label: "Approvals", icon: "approve", path: "/approvals" },
    { id: "runs", label: "Runs", icon: "runs", path: "/runs" },
    { id: "notifications", label: "Notifications", icon: "bell", path: "/notifications" },
  ] },
  { items: [{ id: "content", label: "Content", icon: "content", badge: "Later", disabled: true }] },
  { label: "System", items: [
    { id: "settings", label: "Settings", icon: "gear", path: "/settings" },
    { id: "devices", label: "Devices", icon: "monitor", path: "/devices" },
    // A separate app with root rights and its own sign-in; this app does no administration.
    { id: "core-admin", label: "Core Admin", icon: "server", badge: "Separate app",
      hint: "Core Admin is a separate app with its own sign-in." },
  ] },
];

export type View =
  | "home" | "conversations" | "agents" | "integrations" | "health" | "memory"
  | "trading" | "desk" | "stocks" | "stock" | "positions" | "memecoin" | "tasks" | "context" | "settings" | "soon";

/** One screen: its path, the view that renders it and the sidebar entry it highlights. */
export type Page = { path: string; name: string; view: View; nav?: string; props?: Record<string, string> };

const item = (id: string) => NAV.flatMap((s) => s.items).find((i) => i.id === id)!;
/** Honest placeholder for an entry whose screen is not built yet. */
const soon = (id: string, detail: string): Page => {
  const { path, label, icon } = item(id);
  return { path: path ?? `/${id}`, name: id, view: "soon", nav: id, props: { title: label, icon, detail } };
};

export const PAGES: Page[] = [
  { path: "/", name: "home", view: "home", nav: "chat" },
  { path: "/conversations", name: "conversations", view: "conversations", nav: "chat" },
  { path: "/memory", name: "memory", view: "memory", nav: "memory" },

  // Trading: the dashboard and the read-only IBKR screens (/v1/trading/*).
  { path: "/trading", name: "trading", view: "desk", nav: "dashboard" },
  { path: "/trading/stocks", name: "stocks", view: "stocks", nav: "stocks" },
  { path: "/trading/stocks/:conid(\\d+)", name: "stock", view: "stock", nav: "stocks" },
  { path: "/trading/positions", name: "positions", view: "positions", nav: "positions" },
  // Manual holdings and the IBKR status: one older view, tab driven by the path.
  { path: "/trading/holdings", name: "trading-holdings", view: "trading", nav: "dashboard" },
  { path: "/trading/ibkr", name: "trading-ibkr", view: "trading", nav: "dashboard" },
  // The memecoin PAPER trader (/v1/trading/meme/*); a simulation, no wallet.
  { path: "/trading/memecoin", name: "memecoin", view: "memecoin", nav: "memecoin" },
  soon("futures", "Futures through IBKR come later."),
  soon("autonomy", "Setting what Jarvis may do per pot is not built yet."),

  // Activity: approvals and runs share the Tasks view, opened on a different node.
  { path: "/approvals", name: "approvals", view: "tasks", nav: "approvals", props: { node: "waiting" } },
  { path: "/runs", name: "runs", view: "tasks", nav: "runs" },
  soon("notifications", "A history of notifications is not built yet."),
  soon("content", "Trends, scripts and publishing proposals come after trading."),

  { path: "/settings", name: "settings", view: "settings", nav: "settings" },
  { path: "/devices", name: "devices", view: "context", nav: "devices",
    props: { title: "Devices", subtitle: "The apps linked to Jarvis" } },

  // Reached from the hub; not in the sidebar.
  { path: "/agents", name: "agents", view: "agents" },
  { path: "/integrations", name: "integrations", view: "integrations" },
  { path: "/health", name: "health", view: "health" },
];

/** Old path → new path. Query and hash carry over (vue-router string redirects). */
export const REDIRECTS: Record<string, string> = {
  "/status": "/health",
  "/portfolio": "/trading/holdings",
  "/broker": "/trading/ibkr",
  "/tasks": "/runs",
  "/context": "/devices",
};

/** aria-current for a sidebar entry, given the route's meta.nav. */
export const ariaCurrent = (itemId: string, nav: unknown) => (itemId === nav ? "page" : undefined);

/** Tasks serves two entries: its waiting node is Approvals, every other node is Runs. */
export const tasksPath = (node: string) => (node === "waiting" ? "/approvals" : "/runs");
/** Node to show once the Tasks route changes: the route's own node, else the
 *  current choice, except that leaving Approvals leaves the waiting node. */
export const tasksNode = (routeNode: string | undefined, selected: string) =>
  routeNode ?? (selected === "waiting" ? "active" : selected);

export function buildRoutes(views: Record<View, RouteComponent>): RouteRecordRaw[] {
  return [
    ...PAGES.map(({ path, name, view, nav, props }): RouteRecordRaw =>
      ({ path, name, component: views[view], meta: { nav }, props: props ?? false })),
    ...Object.entries(REDIRECTS).map(([path, redirect]): RouteRecordRaw => ({ path, redirect })),
  ];
}
