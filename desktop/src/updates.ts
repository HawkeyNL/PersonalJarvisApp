import { Channel, invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import { computed, ref, watch } from "vue";
import {
  AUTOMATIC_UPDATE_DELAY_MS,
  shouldScheduleAutomaticUpdateCheck,
  updateSessionActive,
} from "./updatePolicy.js";

type NativeUpdateState =
  | "ready"
  | "unconfigured"
  | "unauthenticated"
  | "unsupported"
  | "incompatible"
  | "unavailable"
  | "up_to_date"
  | "available"
  | "checking"
  | "downloading"
  | "ready_to_restart"
  | "error";

type NativeUpdateStatus = {
  state: NativeUpdateState;
  current_version: string;
  version: string | null;
  notes: string | null;
};

type DownloadEvent =
  | { event: "started"; data: { content_length: number | null } }
  | { event: "progress"; data: { chunk_length: number } }
  | { event: "finished" };

export type UpdateUiState = NativeUpdateState | "idle" | "installing";

export const updateState = ref<UpdateUiState>("idle");
export const currentAppVersion = ref("");
export const availableAppVersion = ref<string | null>(null);
export const updateNotes = ref<string | null>(null);
export const updateError = ref<string | null>(null);
export const updateProgress = ref<number | null>(null);

export const updateBusy = computed(() =>
  ["checking", "downloading", "installing"].includes(updateState.value),
);

function apply(status: NativeUpdateStatus) {
  updateState.value = status.state;
  currentAppVersion.value = status.current_version;
  availableAppVersion.value = status.version;
  updateNotes.value = status.notes;
}

export async function loadUpdateStatus(): Promise<void> {
  try {
    apply(await invoke<NativeUpdateStatus>("app_update_status"));
  } catch {
    updateState.value = "unsupported";
  }
}

export async function checkForUpdate(): Promise<void> {
  updateState.value = "checking";
  updateError.value = null;
  updateProgress.value = null;
  try {
    apply(await invoke<NativeUpdateStatus>("app_update_check"));
  } catch {
    updateState.value = "error";
    updateError.value = "The private update service is unreachable right now. Jarvis keeps working as usual.";
  }
}

export async function installAvailableUpdate(): Promise<void> {
  updateState.value = "downloading";
  updateError.value = null;
  let downloaded = 0;
  let total: number | null = null;
  const onEvent = new Channel<DownloadEvent>();
  onEvent.onmessage = (message) => {
    if (message.event === "started") {
      total = message.data.content_length;
      updateProgress.value = total ? 0 : null;
    } else if (message.event === "progress") {
      downloaded += message.data.chunk_length;
      updateProgress.value = total ? Math.min(100, Math.round((downloaded / total) * 100)) : null;
    } else {
      updateState.value = "installing";
      updateProgress.value = 100;
    }
  };
  try {
    apply(await invoke<NativeUpdateStatus>("app_update_install", { onEvent }));
  } catch {
    updateState.value = "error";
    updateError.value = "Download, signature check or installation failed. The current version is unchanged.";
  }
}

export async function restartAfterUpdate(): Promise<void> {
  try {
    await invoke("app_update_restart");
  } catch {
    updateState.value = "error";
    updateError.value = "Installation failed. The current version is unchanged.";
  }
}

/** True while the chat console listens or holds an unsent draft; set by JarvisConsole. */
export const consoleActive = ref(false);

/** Mirrors native update state changes (tray, periodic check) into the UI and
 * tells the native side when a reply, speech, mic, draft or voice check is
 * active, so a verified update restarts Jarvis only once it is idle. */
export function startUpdateSync(activity: { reply: () => boolean; voiceCheck: () => boolean }): void {
  void listen<NativeUpdateStatus>("app-update-status", (event) => {
    apply(event.payload);
    updateError.value =
      event.payload.state === "error"
        ? (updateError.value ?? "The update failed. The current version is unchanged.")
        : null;
  }).catch(() => {});
  const speech = ref<unknown>("idle");
  void listen<unknown>("jarvis-local-speech", (event) => {
    speech.value = event.payload;
  }).catch(() => {});
  watch(
    () =>
      updateSessionActive({
        reply: activity.reply(),
        console: consoleActive.value,
        speech: speech.value,
        voiceCheck: activity.voiceCheck(),
      }),
    (active) => void invoke("app_update_set_session_active", { active }).catch(() => {}),
    { immediate: true },
  );
}

let automaticCheckScheduled = false;

/** Run at most one gentle, non-blocking check after an authenticated startup.
 * The native side re-checks every six hours. Installing stays an explicit user
 * action; the restart that follows waits until no session is active. */
export function scheduleAutomaticUpdateCheck(configured: boolean, authenticated: boolean): void {
  if (!shouldScheduleAutomaticUpdateCheck(configured, authenticated, automaticCheckScheduled)) return;
  automaticCheckScheduled = true;
  window.setTimeout(() => {
    void (async () => {
      await loadUpdateStatus();
      if (updateState.value === "ready") await checkForUpdate();
    })();
  }, AUTOMATIC_UPDATE_DELAY_MS);
}
