<script setup lang="ts">
import { computed } from "vue";
import NavIcon, { type IconName } from "../NavIcon.vue";
import type { Tone } from "../../hubModel";

// A hub module card: a link with icon, name and two lines of live summary.
const props = defineProps<{ to: string; icon: IconName; title: string; lines: string[]; tone: Tone }>();
const accessibleName = computed(() => [props.title, ...props.lines.filter(Boolean)].join(", "));
</script>

<template>
  <RouterLink :to="to" class="jv-card" :aria-label="accessibleName">
    <span class="icon" aria-hidden="true"><NavIcon :name="icon" /></span>
    <span class="text" aria-hidden="true">
      <span class="title">{{ title }}</span>
      <span v-for="(line, i) in lines" :key="i" class="line">{{ line }}</span>
    </span>
    <span class="dot" :class="tone" aria-hidden="true"></span>
  </RouterLink>
</template>

<style scoped>
.jv-card {
  position: relative; display: flex; align-items: center; gap: 22px;
  box-sizing: border-box; min-height: 87px; padding: 14px 30px 14px 20px;
  border-radius: var(--r-22); border: 1px solid var(--line); background: var(--card-bg);
  color: inherit; text-decoration: none;
  transition: border-color 0.2s ease, box-shadow 0.2s ease;
}
.jv-card:hover { border-color: var(--line-a45); box-shadow: 0 0 22px rgba(var(--accent-rgb), 0.12); }
.icon {
  width: 58px; height: 58px; flex: none; box-sizing: border-box; display: grid; place-items: center;
  border-radius: 50%; border: 1.5px solid var(--line-a55); color: var(--accent);
}
.icon :deep(svg) { width: 26px; height: 26px; stroke-width: 1.5; }
.text { display: flex; flex-direction: column; min-width: 0; }
.title { font-size: var(--fs-12); font-weight: 600; letter-spacing: 0.1em; color: var(--text-0); }
.line { font-size: var(--fs-11); letter-spacing: 0.06em; color: var(--text-5); line-height: 1.5; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.title + .line { margin-top: 5px; }
.dot { position: absolute; top: 16px; right: 18px; width: 5px; height: 5px; border-radius: 50%; background: var(--idle); }
.dot.ok { background: var(--accent); box-shadow: var(--glow-sm); }
.dot.warn { background: var(--warn); box-shadow: 0 0 8px var(--warn); }
.dot.error { background: var(--danger); box-shadow: 0 0 8px var(--danger); }

/* Compact grid tile (core-mobile). */
.jv-card.compact {
  min-height: 72px; gap: 10px; padding: 10px 22px 10px 12px; border-radius: var(--r-16);
  border-color: rgba(var(--accent-rgb), 0.35); background: var(--sat-bg);
}
.jv-card.compact .icon { width: 40px; height: 40px; border-color: rgba(var(--accent-rgb), 0.6); }
.jv-card.compact .icon :deep(svg) { width: 18px; height: 18px; stroke-width: 1.7; }
.jv-card.compact .title { letter-spacing: 0.06em; }
.jv-card.compact .line { font-size: 11px; line-height: 1.3; }
.jv-card.compact .title + .line { margin-top: 3px; }
.jv-card.compact .dot { top: 12px; right: 12px; }
</style>
