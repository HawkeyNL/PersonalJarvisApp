<script setup lang="ts">
import NavIcon from "../NavIcon.vue";

// "Ask Jarvis anything…": the main bar opens the console; the waveform button
// opens it and starts listening.
// Disabled until the device is signed in (the chat needs a session).
defineProps<{ disabled?: boolean }>();
defineEmits<{ open: []; mic: [] }>();
const mac = typeof navigator !== "undefined" && /Mac|iPhone|iPad/.test(navigator.platform);
</script>

<template>
  <div class="jv-command" :class="{ disabled }">
    <button type="button" class="mic" aria-label="Talk to Jarvis" :disabled="disabled" @click="$emit('mic')">
      <NavIcon name="wave" />
    </button>
    <button type="button" class="ask" :disabled="disabled" aria-keyshortcuts="Control+K Meta+K" @click="$emit('open')">
      <span class="placeholder">Ask Jarvis anything…</span>
      <kbd>{{ mac ? "⌘ K" : "Ctrl K" }}</kbd>
    </button>
  </div>
</template>

<style scoped>
.jv-command {
  display: flex; align-items: center; height: 50px; box-sizing: border-box;
  border-radius: var(--r-16); border: 1px solid rgba(160, 255, 215, 0.14);
  background: var(--field-bg);
}
.disabled { opacity: 0.55; }
button:disabled { cursor: default; }
.jv-command:not(.disabled):hover, .jv-command:focus-within { border-color: rgba(var(--accent-rgb), 0.45); }
button { background: transparent; border: none; color: inherit; font: inherit; cursor: pointer; padding: 0; }
button:hover { filter: none; }
.mic {
  flex: none; width: 54px; height: 100%; display: grid; place-items: center;
  border-radius: var(--r-16) 0 0 var(--r-16); color: var(--accent);
}
.mic :deep(svg) { width: 22px; height: 22px; }
.ask { flex: 1; min-width: 0; height: 100%; display: flex; align-items: center; gap: 16px; padding: 0 12px 0 0; text-align: left; font-weight: 400; border-radius: 0 var(--r-16) var(--r-16) 0; }
.placeholder { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: var(--fs-13); letter-spacing: 0.22em; color: var(--text-5); }
kbd {
  flex: none; min-width: 60px; height: 26px; box-sizing: border-box; padding: 0 8px;
  display: grid; place-items: center; border-radius: var(--r-8); background: rgba(255, 255, 255, 0.06);
  font: 12px var(--font-body); letter-spacing: 0.1em; color: var(--text-4);
}
@media (max-width: 1099px), (max-height: 759px) {
  .jv-command { height: 46px; }
  .placeholder { letter-spacing: 0.12em; }
}
</style>
