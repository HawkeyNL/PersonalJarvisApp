<script setup lang="ts">
import { computed, onMounted } from "vue";
import { useRoute } from "vue-router";
import NavIcon from "./NavIcon.vue";
import { NAV, ariaCurrent } from "../navigation";
import { coreOnline, useCoreStatus } from "../coreStatus";
import { availableAppVersion, currentAppVersion, loadUpdateStatus, updateState } from "../updates";

// Desktop sidebar. The active entry comes from the route's meta.nav, so a
// sub-path (/trading/ibkr) or a view shared by two entries (Runs, Approvals)
// still marks the right one.
const route = useRoute();
const current = computed(() => route.meta.nav as string | undefined);
const tip = (label: string, badge?: string) => (badge ? `${label} (${badge})` : label);

// Footer: Home Node status, this app's version and a pending update. The
// sidebar only renders in app mode, so none of this shows before sign-in.
useCoreStatus({ agents: false });
onMounted(() => {
  if (updateState.value === "idle") void loadUpdateStatus(); // local read; no network check
});
const core = computed(() =>
  coreOnline.value === null
    ? { tone: "idle", label: "Connecting…" }
    : coreOnline.value
      ? { tone: "ok", label: "Home Node connected" }
      : { tone: "error", label: "Home Node offline" },
);
const footLabel = computed(() => (currentAppVersion.value ? `${core.value.label} · v${currentAppVersion.value}` : core.value.label));
const updateLabel = computed(() =>
  updateState.value === "available" ? `Update v${availableAppVersion.value}`
    : updateState.value === "ready_to_restart" ? "Restart pending" : null,
);
</script>

<template>
  <nav class="sidebar" aria-label="Main">
    <template v-for="(section, s) in NAV" :key="s">
      <p v-if="section.label" :id="`nav-section-${s}`" class="section">{{ section.label }}</p>
      <ul :aria-labelledby="section.label ? `nav-section-${s}` : undefined">
        <li v-for="item in section.items" :key="item.id">
          <RouterLink v-if="item.path" v-slot="{ href, navigate }" :to="item.path" custom>
            <a :href="href" class="entry" :title="tip(item.label, item.badge)"
              :aria-current="ariaCurrent(item.id, current)" @click="navigate">
              <NavIcon :name="item.icon" />
              <span class="label">{{ item.label }}</span>
              <span v-if="item.badge" class="badge">{{ item.badge }}</span>
            </a>
          </RouterLink>
          <span v-else class="entry static" :title="item.hint ?? tip(item.label, item.badge)">
            <NavIcon :name="item.icon" />
            <span class="label">{{ item.label }}</span>
            <span v-if="item.badge" class="badge">{{ item.badge }}</span>
            <span v-if="item.hint" class="sr-only">{{ item.hint }}</span>
          </span>
        </li>
      </ul>
    </template>

    <div class="foot">
      <!-- custom: a plain link, so it never takes aria-current from the Settings entry. -->
      <RouterLink v-if="updateLabel" v-slot="{ href, navigate }" to="/settings" custom>
        <a :href="href" class="entry update" :title="updateLabel" @click="navigate">
          <NavIcon name="restart" /><span class="label">{{ updateLabel }}</span>
        </a>
      </RouterLink>
      <p class="status" :title="footLabel">
        <span class="dot" :class="core.tone" aria-hidden="true"></span>
        <span class="label"><span role="status">{{ core.label }}</span><template v-if="currentAppVersion"> · v{{ currentAppVersion }}</template></span>
      </p>
    </div>
  </nav>
</template>

<style scoped>
/* Below every overlay. */
.sidebar {
  position: relative; z-index: 1; flex: none; width: 232px; overflow-y: auto;
  display: flex; flex-direction: column; padding: 20px 12px;
  background: var(--bg-surface); border-right: 1px solid var(--separator);
}
.section { margin: 0; padding: 16px 12px 6px; font-size: 12px; font-weight: 500; color: var(--text-secondary); }
.sidebar > .section:first-child { padding-top: 0; }
ul { margin: 0; padding: 0; list-style: none; display: flex; flex-direction: column; gap: 2px; }
ul + ul { margin-top: 2px; }

.entry {
  -webkit-tap-highlight-color: transparent;
  display: flex; align-items: center; gap: 10px; min-height: 40px; padding: 0 12px;
  border-radius: var(--r-md); color: var(--text-primary); font-size: 15px; text-decoration: none;
  transition: background-color var(--d-hover) ease, transform var(--d-press) var(--ease-out);
}
.entry :deep(svg) { flex: none; width: 18px; height: 18px; }
.label { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.entry[aria-current="page"] { background: var(--bg-selected); color: var(--accent-text); font-weight: 600; }
.static { color: var(--text-secondary); cursor: default; }
.badge {
  margin-left: auto; flex: none; height: 20px; padding: 0 7px; display: inline-flex; align-items: center;
  border-radius: var(--r-pill); background: var(--bg-sunken); color: var(--text-secondary);
  font-size: 12px; font-weight: 500; white-space: nowrap;
}

@media (hover: hover) and (pointer: fine) {
  a.entry:hover:not([aria-current="page"]) { background: var(--bg-hover); }
}
a.entry:active { background: var(--bg-selected); }
@media (prefers-reduced-motion: no-preference) {
  a.entry:active { transform: scale(0.98); }
}
a.entry:focus-visible { outline: 2px solid var(--focus-ring); outline-offset: -2px; }

.foot { margin-top: auto; padding-top: 12px; display: flex; flex-direction: column; gap: 2px; }
.update { color: var(--accent-text); font-size: 13px; }
.status { margin: 0; padding: 12px; display: flex; align-items: center; gap: 8px; font-size: 12px; line-height: 1.4; color: var(--text-secondary); }
.dot { flex: none; width: 8px; height: 8px; border-radius: var(--r-pill); background: var(--text-disabled); }
.dot.ok { background: var(--positive-solid); }
.dot.error { background: var(--danger-solid); }

.sr-only {
  position: absolute; width: 1px; height: 1px; margin: -1px; padding: 0; overflow: hidden;
  clip: rect(0 0 0 0); white-space: nowrap; border: 0;
}

/* Narrow windows: an icon rail. Labels stay for screen readers; the title tooltip names each entry. */
@media (max-width: 1279px) {
  .sidebar { width: 64px; padding: 16px 8px; }
  .entry, .status { justify-content: center; padding: 0; }
  .status { min-height: 40px; }
  .label, .badge, .section {
    position: absolute; width: 1px; height: 1px; margin: -1px; padding: 0; overflow: hidden;
    clip: rect(0 0 0 0); white-space: nowrap; border: 0;
  }
  ul + ul, .section + ul { margin-top: 12px; padding-top: 12px; border-top: 1px solid var(--separator); }
  .sidebar > ul:first-of-type { margin-top: 0; padding-top: 0; border-top: 0; }
}
</style>
