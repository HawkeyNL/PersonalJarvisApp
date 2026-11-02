<script setup lang="ts">
import { computed } from "vue";
import NavIcon, { type IconName } from "../NavIcon.vue";
import PageShell from "../PageShell.vue";
import { TONE_BADGE, type Tone } from "../../hubModel";

// Shared layout of the module pages (Agents, Integrations, System health), as
// the Settings page: the sections with their status on the left, the selected
// section as a card on the right. A page with nothing to select shows only its
// content (the default slot).
export type NodeItem = { id: string; title: string; icon: IconName; tone: Tone; status: string; description: string; meta?: string };
const props = defineProps<{ title: string; subtitle: string; items: NodeItem[] }>();
const selected = defineModel<string>({ required: true });
const current = computed(() => props.items.find((item) => item.id === selected.value));
</script>

<template>
  <PageShell :title="title" :subtitle="subtitle">
    <div v-if="current" class="np-layout">
      <nav class="np-sections" :aria-label="`${title} sections`">
        <button v-for="item in items" :key="item.id" type="button" class="np-section"
          :aria-current="item.id === selected ? 'true' : undefined" @click="selected = item.id">
          <span class="tile" :class="{ acc: item.id === selected }" aria-hidden="true"><NavIcon :name="item.icon" /></span>
          <span class="np-section-text">
            <span class="np-section-title">{{ item.title }}</span>
            <span class="np-section-status t-xs t-2"><span class="np-dot" :class="item.tone" aria-hidden="true"></span>{{ item.status }}</span>
          </span>
        </button>
      </nav>

      <section class="card np-panel" aria-labelledby="np-panel-title">
        <header class="np-head">
          <div class="np-head-text">
            <div class="np-title">
              <h2 id="np-panel-title">{{ current.title }}</h2>
              <span class="badge" :class="TONE_BADGE[current.tone]">{{ current.status }}</span>
            </div>
            <p class="t-sm t-2">{{ current.description }}</p>
            <p v-if="current.meta" class="t-xs t-2 np-meta">{{ current.meta }}</p>
          </div>
          <div v-if="$slots.actions" class="np-actions"><slot name="actions" /></div>
        </header>
        <slot />
      </section>
    </div>
    <slot v-else />
  </PageShell>
</template>

<style scoped>
.np-layout { display: flex; flex-wrap: wrap; gap: 28px; align-items: flex-start; }
.np-sections { flex: 0 1 240px; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.np-section {
  display: flex; align-items: center; gap: 12px; min-height: 56px; padding: 8px 10px; border-radius: var(--r-md);
  color: var(--text-primary); text-align: left; transition: background-color var(--d-hover) ease;
}
.np-section[aria-current="true"] { background: var(--bg-selected); }
.np-section[aria-current="true"] .np-section-title { color: var(--accent-text); font-weight: 600; }
.np-section:focus-visible { outline: 2px solid var(--focus-ring); outline-offset: 2px; }
@media (hover: hover) and (pointer: fine) {
  .np-section:not([aria-current="true"]):hover { background: var(--bg-hover); }
}
.np-section .tile :deep(svg) { width: 18px; height: 18px; }
.np-section-text { min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.np-section-title { font-size: 15px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.np-section-status { display: flex; align-items: center; gap: 6px; overflow-wrap: anywhere; }
.np-dot { flex: none; width: 6px; height: 6px; border-radius: var(--r-pill); background: var(--text-disabled); }
.np-dot.ok { background: var(--positive-solid); }
.np-dot.warn { background: var(--negative-solid); }
.np-dot.error { background: var(--danger-solid); }

.np-panel { flex: 999 1 560px; min-width: 0; display: flex; flex-direction: column; gap: 20px; }
.np-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; flex-wrap: wrap; }
.np-head-text { min-width: 0; flex: 1 1 320px; display: flex; flex-direction: column; gap: 6px; }
.np-head-text p { margin: 0; max-width: 72ch; }
.np-title { display: flex; align-items: baseline; gap: 12px; flex-wrap: wrap; }
.np-title h2 { margin: 0; overflow-wrap: anywhere; }
.np-meta { overflow-wrap: anywhere; }
.np-actions { display: flex; gap: 8px; flex-wrap: wrap; }
</style>

<style>
/* Shared by the node pages' section bodies (Agents, Integrations, Health). */
.np-facts { margin: 0; display: grid; grid-template-columns: repeat(auto-fill, minmax(160px, 1fr)); gap: 10px; }
.np-facts > div { min-width: 0; display: flex; flex-direction: column; gap: 2px; padding: 12px 14px; border-radius: var(--r-md); background: var(--bg-sunken); }
.np-facts dt { font-size: 12px; line-height: 1.4; color: var(--text-secondary); }
.np-facts dd { margin: 0; font-size: 14px; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
.np-seg { align-self: flex-start; max-width: 100%; }
.np-seg.jv-seg > button { flex: 0 0 auto; }
.np-cols { display: flex; flex-wrap: wrap; gap: 20px; align-items: flex-start; }
.np-cols > * { flex: 1 1 320px; min-width: 0; }
.np-note { margin: 0; font-size: 13px; line-height: 1.5; color: var(--text-secondary); max-width: 72ch; }
.np-note.err { color: var(--danger-text); overflow-wrap: anywhere; }
.np-empty { margin: 0; padding: 16px; border-radius: var(--r-md); background: var(--bg-sunken); font-size: 13px; line-height: 1.5; color: var(--text-secondary); }
.np-empty strong { display: block; margin-bottom: 2px; font-weight: 600; color: var(--text-primary); }
</style>
