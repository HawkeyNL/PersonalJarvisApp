<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { getCurrentWindow, UserAttentionType } from "@tauri-apps/api/window";
import { pairingRequests } from "../pairingApprovals";
import { pending } from "../unlockApprovals";
import { shownShellMode as mode } from "../lock";
import { alertText, nextAlert, titleWithCount, type WaitingRequest } from "../approvalAlerts";

// Signed-in app only: lists from an earlier session never raise an alert.
const waiting = computed<WaitingRequest[]>(() => mode.value !== "app" ? [] : [
  ...pairingRequests.value.map((r) => ({ kind: "pairing" as const, id: r.id, device_name: r.device_name })),
  ...pending.value.map((r) => ({ kind: "unlock" as const, id: r.id, device_name: r.device_name })),
]);
const alert = ref<WaitingRequest | null>(null);
let seen = new Set<string>();

watch(waiting, (current) => {
  // Tauri does not copy document.title to the window, so set both.
  const title = titleWithCount(document.title, current.length);
  if (title !== document.title) {
    document.title = title;
    void getCurrentWindow().setTitle(title).catch(() => {});
  }
  const next = nextAlert(seen, current);
  seen = next.seen;
  if (next.alert) {
    alert.value = next.alert;
    // Flashes the taskbar or bounces the Dock; shows nothing about the request.
    if (!document.hasFocus()) void getCurrentWindow().requestUserAttention(UserAttentionType.Informational).catch(() => {});
  } else if (alert.value && !current.some((r) => r.kind === alert.value?.kind && r.id === alert.value.id)) {
    alert.value = null; // decided or expired
  }
}, { immediate: true });

function review() {
  const target = document.getElementById(alert.value?.kind === "pairing" ? "pairing-approvals" : "unlock-approvals");
  target?.scrollIntoView({ block: "nearest" });
  target?.focus();
  alert.value = null;
}
</script>

<template>
  <div class="approval-alert" role="status">
    <div v-if="alert" class="banner">
      <span>{{ alertText(alert) }}</span>
      <button type="button" class="btn btn-secondary btn-sm" @click="review">Review</button>
      <button type="button" class="btn btn-secondary btn-sm" @click="alert = null">Dismiss</button>
    </div>
  </div>
</template>

<style scoped>
.approval-alert { position: fixed; top: 12px; left: 0; right: 0; z-index: 190; display: flex; justify-content: center; padding: 0 14px; pointer-events: none; }
.banner {
  pointer-events: auto; display: flex; align-items: center; gap: 8px; max-width: min(560px, 100%);
  padding: 8px 8px 8px 14px; border-radius: 14px; overflow-wrap: anywhere;
  background: var(--bg-surface); color: var(--text-primary); border: 1px solid var(--accent-text); box-shadow: var(--shadow-pop);
}
.banner span { flex: 1; font-size: 13px; }
</style>
