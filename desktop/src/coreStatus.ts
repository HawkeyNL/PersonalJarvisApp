// Shared, lightweight Core status for the top bar on every page: readiness
// (public /readyz) and the agent count (owner-only /v1/agents, which older Cores
// do not have). Polling runs only while a top bar is mounted and the window is
// visible.
import { onBeforeUnmount, onMounted, ref } from "vue";
import { getJson, getJsonAuth } from "./api";
import { currentAuthStatus } from "./auth";
import { createSerialPoller } from "./serialPoller";
import { availabilityFromError, type Availability } from "./hubModel";

export const coreOnline = ref<boolean | null>(null);
export const agentCount = ref<Availability<number>>({ state: "loading" });

/** GET an owner endpoint; a missing route (older Core) becomes "unsupported". */
export async function loadOptional<T>(path: string): Promise<Availability<T>> {
  try {
    const status = await currentAuthStatus();
    if (!status.authenticated) return { state: "error" };
    return { state: "ok", value: await getJsonAuth<T>(path) };
  } catch (error) {
    return availabilityFromError(error);
  }
}

async function refreshAgents(): Promise<void> {
  const result = await loadOptional<{ agent_count?: number; agents?: unknown[] }>("/v1/agents");
  agentCount.value = result.state === "ok"
    ? { state: "ok", value: result.value.agent_count ?? result.value.agents?.length ?? 0 }
    : result;
}

async function refresh(): Promise<void> {
  if (document.hidden) return;
  try {
    await getJson("/readyz");
    coreOnline.value = true;
  } catch {
    coreOnline.value = false;
  }
}

const poller = createSerialPoller(refresh, 15_000);
let users = 0;

/** Keep the shared status fresh while the calling component is mounted. */
export function useCoreStatus(): void {
  onMounted(() => {
    users++;
    poller.start();
    // Re-check the agent list on every page visit; an unsupported Core is final
    // until the app restarts (it has to be upgraded first).
    if (agentCount.value.state !== "unsupported") void refreshAgents();
  });
  onBeforeUnmount(() => {
    users--;
    if (users <= 0) poller.stop();
  });
}
