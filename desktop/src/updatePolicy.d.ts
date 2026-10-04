export const AUTOMATIC_UPDATE_DELAY_MS: number;
export function shouldScheduleAutomaticUpdateCheck(
  configured: boolean,
  authenticated: boolean,
  alreadyScheduled: boolean,
): boolean;
export function updateSessionActive(activity: {
  reply: boolean;
  console: boolean;
  speech: unknown;
  voiceCheck: boolean;
}): boolean;
