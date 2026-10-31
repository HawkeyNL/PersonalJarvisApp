<script setup lang="ts">
import { computed } from "vue";
import NavIcon from "./NavIcon.vue";
import { pending, approve, deny, pick, picking, approving, approvalError } from "../unlockApprovals";
import { platformCapabilities } from "../platform";

// Only a Mac approves here, behind Touch ID; other desktops can deny.
const signer = computed(() => platformCapabilities.value.supportsBiometricSigning);
const two = (n: number) => String(n).padStart(2, "0");
</script>

<template>
  <Transition name="sheet">
    <div v-if="pending.length" class="wrap">
      <div class="sheet">
        <div class="lead"><NavIcon name="link" /> Unlock request</div>
        <template v-for="r in pending" :key="r.id">
          <div class="req">
            <div class="who">
              <strong>{{ r.device_name }}</strong>
              <span class="plat">{{ r.platform }}</span>
              wants to unlock
            </div>
            <div class="actions">
              <button class="deny" :disabled="approving === r.id" @click="deny(r)">Deny</button>
              <button
                v-if="signer && picking?.id !== r.id"
                class="approve"
                :disabled="approving === r.id"
                @click="approve(r)"
              >
                {{ approving === r.id ? "Verifying…" : "Approve" }}
              </button>
            </div>
          </div>
          <div v-if="picking?.id === r.id" class="pick">
            <p>Pick the number shown on that device. A wrong number denies the request.</p>
            <div class="choices">
              <button v-for="c in picking.choices" :key="c" class="choice" @click="pick(r.id, c)">
                {{ two(c) }}
              </button>
            </div>
          </div>
        </template>
        <p v-if="approvalError" class="err">{{ approvalError }}</p>
        <p class="hint">{{ signer ? "Confirm with Touch ID on this Mac." : "Approve on a Mac with Touch ID; you can deny here." }}</p>
      </div>
    </div>
  </Transition>
</template>

<style scoped>
.wrap {
  position: fixed;
  left: 0;
  right: 0;
  bottom: calc(96px + env(safe-area-inset-bottom));
  z-index: 180;
  display: flex;
  justify-content: center;
  padding: 0 14px;
  pointer-events: none;
}
.sheet {
  width: min(440px, 100%);
  pointer-events: auto;
  border-radius: 18px;
  padding: 14px 16px 12px;
  background: var(--bg-surface);
  border: 1px solid var(--accent);
  box-shadow: var(--shadow-pop);
  backdrop-filter: blur(22px) saturate(1.3);
  -webkit-backdrop-filter: blur(22px) saturate(1.3);
}
.lead {
  display: flex;
  align-items: center;
  gap: 8px;
  font-family: var(--mono);
  font-size: 11px;
  letter-spacing: 0.16em;
  color: var(--accent);
  margin-bottom: 10px;
}
.lead :deep(svg) {
  width: 15px;
  height: 15px;
}
.req {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 6px 0;
}
.who {
  font-size: 14px;
  color: var(--text);
}
.who .plat {
  font-family: var(--mono);
  font-size: 10px;
  color: var(--muted);
  margin: 0 4px;
}
.actions {
  margin-left: auto;
  flex: none;
  display: flex;
  gap: 8px;
}
.approve,
.deny {
  border-radius: 11px;
  padding: 9px 16px;
  font: inherit;
  font-size: 13px;
  font-weight: 700;
  cursor: pointer;
}
.approve {
  background: var(--accent-solid);
  color: var(--text-on-accent);
  border: none;
}
.deny {
  background: transparent;
  border: 1px solid var(--border);
  color: var(--muted);
  font-weight: 500;
}
.deny:hover:not(:disabled) {
  border-color: var(--danger-text);
  color: var(--danger-text);
}
.approve:disabled,
.deny:disabled {
  opacity: 0.6;
  cursor: default;
}
.pick p {
  font-size: 12px;
  color: var(--muted);
  margin: 6px 0 8px;
}
.choices {
  display: flex;
  gap: 10px;
}
.choice {
  flex: 1;
  border-radius: 11px;
  padding: 10px 0;
  font-family: var(--mono);
  font-size: 22px;
  font-weight: 700;
  background: transparent;
  border: 1px solid var(--accent);
  color: var(--text);
  cursor: pointer;
}
.err {
  color: var(--danger-text);
  font-size: 12px;
  margin: 6px 0 0;
}
.hint {
  color: var(--muted);
  font-size: 11px;
  margin: 8px 0 0;
}

.sheet-enter-active,
.sheet-leave-active {
  transition: transform 0.3s cubic-bezier(0.22, 1, 0.36, 1), opacity 0.3s ease;
}
.sheet-enter-from,
.sheet-leave-to {
  transform: translateY(16px);
  opacity: 0;
}
@media (prefers-reduced-motion: reduce) {
  .sheet-enter-active,
  .sheet-leave-active {
    transition: none;
  }
}
</style>
