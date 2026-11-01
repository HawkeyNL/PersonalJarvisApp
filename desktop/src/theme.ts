// Light / Dark / System theme. The choice is persisted; "system" (the default)
// follows prefers-color-scheme and updates live when the OS switches.
// The resolved theme lands on <html data-theme="light|dark">, see tokens.css.
export type ThemeChoice = "light" | "dark" | "system";
export type Theme = "light" | "dark";

export const THEMES: ThemeChoice[] = ["light", "dark", "system"];

const KEY = "jarvis.theme";
const DARK_QUERY = "(prefers-color-scheme: dark)";

type Env = Pick<Window, "localStorage" | "matchMedia" | "document" | "requestAnimationFrame">;

export function currentTheme(env: Env = window): ThemeChoice {
  const v = env.localStorage.getItem(KEY);
  return v === "light" || v === "dark" ? v : "system";
}

export function resolveTheme(choice: ThemeChoice, prefersDark: boolean): Theme {
  return choice === "system" ? (prefersDark ? "dark" : "light") : choice;
}

function paint(env: Env): void {
  const root = env.document.documentElement;
  const next = resolveTheme(currentTheme(env), env.matchMedia(DARK_QUERY).matches);
  if (root.dataset.theme === next) return;
  // Swap without every colour transition firing at once.
  root.classList.add("no-tx");
  root.dataset.theme = next;
  void root.offsetHeight; // commit the new colours while transitions are off
  env.requestAnimationFrame(() => env.requestAnimationFrame(() => root.classList.remove("no-tx")));
}

export function setTheme(choice: ThemeChoice, env: Env = window): void {
  env.localStorage.setItem(KEY, choice);
  paint(env);
}

/** Apply the saved theme on app start and follow OS changes while on "system". */
export function initTheme(env: Env = window): void {
  env.localStorage.removeItem("jarvis.accent"); // the old accent picker's key
  paint(env);
  env.matchMedia(DARK_QUERY).addEventListener("change", () => paint(env));
}

export type ThemeCard = { id: ThemeChoice; label: string; preview: Theme; checked: boolean };

/** The Settings radio cards: each previews the theme it resolves to, so System
 *  shows (and names) what the OS currently picks. */
export function themeCards(choice: ThemeChoice, prefersDark: boolean): ThemeCard[] {
  const now = resolveTheme("system", prefersDark);
  const labels: Record<ThemeChoice, string> = { light: "Light", dark: "Dark", system: `System (now ${now})` };
  return THEMES.map((id) => ({ id, label: labels[id], preview: resolveTheme(id, prefersDark), checked: id === choice }));
}
