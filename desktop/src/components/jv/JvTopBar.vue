<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import NavIcon from "../NavIcon.vue";
import JvStatusDot from "./JvStatusDot.vue";
import { agentCount, coreOnline, useCoreStatus } from "../../coreStatus";
import { availableAppVersion, updateState } from "../../updates";

// Hub variant: brand on the left, a centre slot (the command bar).
// Node variant: "Back to Core" on the left, the page title in the centre.
defineProps<{ variant: "hub" | "node"; title?: string; subtitle?: string }>();

useCoreStatus();

const clock = ref("");
let timer: number | undefined;
function tick() {
  clock.value = new Date().toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit" });
}
onMounted(() => { tick(); timer = window.setInterval(tick, 10_000); });
onBeforeUnmount(() => clearInterval(timer));

const core = computed(() =>
  coreOnline.value === null
    ? { tone: "idle" as const, label: "Connecting…" }
    : coreOnline.value
      ? { tone: "ok" as const, label: "Core Online" }
      : { tone: "error" as const, label: "Core Offline" },
);
</script>

<template>
  <header class="jv-topbar" :class="`is-${variant}`">
    <div class="left">
      <RouterLink v-if="variant === 'hub'" to="/" class="brand" aria-label="Jarvis home">
        <span class="ring" aria-hidden="true"></span>
        <span class="words">
          <span class="name">JARVIS</span>
          <span class="tag">YOUR SECOND MIND</span>
        </span>
      </RouterLink>
      <RouterLink v-else to="/" class="back">
        <NavIcon name="arrow-left" />Back to Core
      </RouterLink>
    </div>

    <div class="center">
      <slot>
        <div v-if="title" class="title">
          <h1>{{ title }}</h1>
          <p v-if="subtitle">{{ subtitle }}</p>
        </div>
      </slot>
    </div>

    <div class="right">
      <div class="cluster">
        <RouterLink v-if="updateState === 'available'" to="/settings" class="update">
          Update v{{ availableAppVersion }}
        </RouterLink>
        <RouterLink v-else-if="updateState === 'ready_to_restart'" to="/settings" class="update">
          Restart pending
        </RouterLink>
        <JvStatusDot :tone="core.tone" :label="core.label" class="core" />
        <template v-if="agentCount.state === 'ok'">
          <span class="sep" aria-hidden="true"></span>
          <span class="item"><NavIcon name="user" />{{ agentCount.value }} Agents</span>
        </template>
        <span class="sep" aria-hidden="true"></span>
        <span class="item"><NavIcon name="clock" /><time>{{ clock }}</time></span>
      </div>
      <RouterLink to="/settings" class="profile" aria-label="Settings">
        <NavIcon name="user" />
        <span class="pdot" aria-hidden="true"></span>
      </RouterLink>
    </div>
  </header>
</template>

<style scoped>
.jv-topbar {
  position: relative;
  z-index: 5;
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto minmax(0, 1fr);
  align-items: center;
  gap: 24px;
  padding: 26px 32px 0 36px;
}
.left, .right { display: flex; align-items: center; min-width: 0; }
.right { justify-content: flex-end; gap: 22px; }

.brand { display: flex; align-items: center; gap: 22px; text-decoration: none; color: inherit; }
.ring {
  width: 34px; height: 34px; flex: none; box-sizing: border-box; border-radius: 50%;
  border: 3px solid var(--accent);
  box-shadow: 0 0 14px rgba(var(--accent-rgb), 0.7), inset 0 0 8px rgba(var(--accent-rgb), 0.45);
}
.words { display: flex; flex-direction: column; }
.name { font-family: var(--font-display); font-weight: 500; font-size: 19px; letter-spacing: 0.5em; line-height: 24px; color: #eaf7f1; }
.tag { margin-top: 2px; font-size: 11px; letter-spacing: 0.32em; color: var(--text-5); }

.back {
  display: inline-flex; align-items: center; justify-content: center; gap: 14px;
  height: 43px; padding: 0 24px; box-sizing: border-box;
  border-radius: var(--r-22); border: 1px solid rgba(var(--accent-rgb), 0.75);
  background: rgba(5, 40, 30, 0.65); box-shadow: 0 0 18px rgba(var(--accent-rgb), 0.25);
  color: #f1faf6; font-size: 14.5px; letter-spacing: 0.06em; text-decoration: none;
}
.back :deep(svg) { color: var(--accent); stroke-width: 2; }

.title { text-align: center; position: relative; }
.title h1 {
  margin: 0; font-family: var(--font-display); font-weight: 600; font-size: var(--fs-23);
  letter-spacing: 0.5em; padding-left: 0.5em; line-height: 30px; color: var(--text-0);
}
.title p { margin: 10px 0 0; font-size: 11px; letter-spacing: 0.38em; padding-left: 0.38em; color: #9fb8ad; }
.title::before, .title::after {
  content: ""; position: absolute; top: 20px; width: 68px; height: 1px;
}
.title::before { right: calc(100% + 0px); background: linear-gradient(90deg, transparent, rgba(var(--accent-rgb), 0.6)); }
.title::after { left: calc(100% + 0px); background: linear-gradient(90deg, rgba(var(--accent-rgb), 0.6), transparent); }

.cluster { display: flex; align-items: center; gap: 14px; font-size: 12px; letter-spacing: 0.04em; color: var(--text-2); white-space: nowrap; }
.cluster .core :deep(.label) { color: var(--text-2); }
.item { display: inline-flex; align-items: center; gap: 8px; font-variant-numeric: tabular-nums; }
.item :deep(svg) { width: 16px; height: 16px; color: var(--accent); }
.sep { width: 1px; height: 30px; background: rgba(var(--accent-rgb), 0.3); }
.update {
  padding: 4px 10px; border: 1px solid rgba(245, 184, 74, 0.5); border-radius: 999px;
  color: #fde68a; font-size: 11px; text-decoration: none;
}

.profile {
  position: relative; flex: none; width: 44px; height: 44px; box-sizing: border-box;
  display: grid; place-items: center; border-radius: 50%;
  border: 1.5px solid rgba(var(--accent-rgb), 0.7); background: rgba(5, 40, 30, 0.8);
  color: #f1faf6; box-shadow: 0 0 14px rgba(var(--accent-rgb), 0.25);
}
.profile :deep(svg) { width: 20px; height: 20px; }
.pdot { position: absolute; right: 1px; bottom: 1px; width: 9px; height: 9px; border-radius: 50%; background: var(--accent); box-shadow: var(--glow-sm); }

.is-hub .center { width: min(584px, 44vw); }
/* Narrow windows: the command bar or page title needs the room. */
@media (max-width: 1299px) { .cluster .item, .cluster .sep { display: none; } }

@media (max-width: 1099px), (max-height: 759px) {
  .jv-topbar { padding: 16px 16px 0; gap: 12px; grid-template-columns: auto minmax(0, 1fr) auto; }
  .is-hub .center { width: auto; }
  .tag, .cluster .item, .cluster .sep, .title::before, .title::after { display: none; }
  .ring { width: 28px; height: 28px; }
  .name { font-size: 18px; letter-spacing: 0.42em; }
  .title h1 { font-size: 19px; letter-spacing: 0.32em; }
  .title p { display: none; }
  .back { padding: 0 16px; gap: 10px; }
}
</style>
