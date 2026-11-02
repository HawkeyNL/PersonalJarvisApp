// Which shell the window shows: only the setup/sign-in screen, or the app with
// its sidebar. Pure, so tests can load this file directly.

/** `null`: not read yet, or unreadable. */
export type ShellState = { configured: boolean | null; authenticated: boolean | null };
export type ShellMode = "setup" | "signin" | "app";

/** The app (sidebar, command palette, app pages) needs a configured Home Node
 *  and a signed-in session. Anything unknown fails closed. */
export function shellMode(state: ShellState | null): ShellMode {
  if (state?.configured !== true) return "setup";
  return state.authenticated === true ? "app" : "signin";
}

/** The setup and sign-in forms live on the hub route. */
export const SETUP_PATH = "/";

/** Router guard: until a session exists every route leads to the setup or
 *  sign-in screen. A failed read counts as no session. */
export function shellGuard(read: () => Promise<ShellState>) {
  return async (to: { path: string }): Promise<true | string> => {
    if (to.path === SETUP_PATH) return true;
    const state = await read().catch(() => null);
    return shellMode(state) === "app" ? true : SETUP_PATH;
  };
}

/** The onboarding step on the home gate (canvas D-Onboarding): 0 connect,
 *  1 sign in or activate, 2 waiting for approval on another device; null when
 *  no step shows (connecting, or an error notice). The setup and sign-in
 *  forms win, as the gate always did. */
export function onboardingStep(state: {
  backend: string; changingOrigin: boolean; accountMode: string | null; auth: string;
}): 0 | 1 | 2 | null {
  if (state.backend === "unconfigured" || state.changingOrigin) return 0;
  if (state.accountMode && state.backend === "ok") return 1;
  return state.auth === "wachten" ? 2 : null;
}

/** For async reads that can resolve out of order: a value lands only when no
 *  read or write begun after it has landed already. */
export function latestWins<T>(set: (value: T) => void) {
  let begun = 0;
  let landed = 0;
  return {
    begin: () => ++begun,
    apply(seq: number, value: T): void {
      if (seq <= landed) return;
      landed = seq;
      set(value);
    },
  };
}
