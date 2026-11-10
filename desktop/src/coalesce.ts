// Pure: no Vue or Tauri imports, so tests load this file directly.

/** Wrap a reload so that calls while it runs queue one more run, not one
 *  each: a burst of realtime hints costs at most two reloads. A failed run
 *  is the reload's own business (it shows its error state); it never drops
 *  the queued run. */
export function coalesce(run: () => Promise<void>): () => Promise<void> {
  let running = false;
  let again = false;
  return async () => {
    if (running) { again = true; return; }
    running = true;
    try {
      do {
        again = false;
        try { await run(); } catch { /* the next hint or Refresh retries */ }
      } while (again);
    } finally {
      running = false;
    }
  };
}
