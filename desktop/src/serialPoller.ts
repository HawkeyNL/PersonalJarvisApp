/** A single-flight poller: stopping and immediately restarting never creates
 * a second request while the first one is still in flight. */
export function createSerialPoller(
  refresh: () => Promise<unknown>,
  intervalMs: number,
  schedule: typeof setTimeout = setTimeout,
  cancel: typeof clearTimeout = clearTimeout,
) {
  let enabled = false;
  let inFlight = false;
  let timer: ReturnType<typeof setTimeout> | undefined;

  async function poll(): Promise<void> {
    timer = undefined;
    if (!enabled || inFlight) return;
    inFlight = true;
    try {
      await refresh();
    } catch {
      // A failed read must not stop future checks. The caller owns diagnostics.
    } finally {
      inFlight = false;
      if (enabled) timer = schedule(poll, intervalMs);
    }
  }

  return {
    start() {
      if (enabled) return;
      enabled = true;
      if (!inFlight) void poll();
    },
    stop() {
      enabled = false;
      if (timer !== undefined) cancel(timer);
      timer = undefined;
    },
  };
}
