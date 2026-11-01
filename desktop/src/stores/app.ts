import { ref } from "vue";
import { defineStore } from "pinia";

/** Minimal app-wide store; grows as the client gains real state. */
export const useAppStore = defineStore("app", () => {
  const name = ref("Jarvis");
  const tagline = ref("Personal AI operating system");
  /** The chat overlay on the hub (Ctrl/⌘K, command bar, "Hey Jarvis"). */
  const consoleOpen = ref(false);
  /** A message that must survive the move to the sign-in screen (unlinking). */
  const notice = ref<string | null>(null);
  return { name, tagline, consoleOpen, notice };
});
