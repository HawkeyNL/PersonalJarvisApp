export const AUTOMATIC_UPDATE_DELAY_MS = 2500;

/** Pure policy kept separate from the Tauri bridge so startup behavior is
 * deterministic and unit-testable.
 * @param {boolean} configured
 * @param {boolean} authenticated
 * @param {boolean} alreadyScheduled
 */
export function shouldScheduleAutomaticUpdateCheck(configured, authenticated, alreadyScheduled) {
  return configured && authenticated && !alreadyScheduled;
}

/** A verified update restarts Jarvis only when none of these is going on.
 * `speech` is the latest native "jarvis-local-speech" status.
 * @param {{ reply: boolean, console: boolean, speech: unknown, voiceCheck: boolean }} activity
 */
export function updateSessionActive(activity) {
  return activity.reply || activity.console || activity.speech === "speaking" || activity.voiceCheck;
}

/** Native update errors are fixed English copy with a typed cause; show it
 * instead of guessing. Anything else (a bridge failure) gets `fallback`.
 * @param {unknown} error
 * @param {string} fallback
 */
export function updateErrorMessage(error, fallback) {
  return typeof error === "string" && error.trim() && error.length <= 200 ? error : fallback;
}
