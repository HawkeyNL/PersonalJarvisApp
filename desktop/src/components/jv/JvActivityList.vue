<script setup lang="ts">
import NavIcon, { type IconName } from "../NavIcon.vue";
import type { Tone } from "../../hubModel";

// Recent items next to the tile grid. Each row: icon, title, detail, time,
// optional row actions (slot "actions"), status. `fullDetail` shows the whole
// detail (wrapped, scrolling when long) instead of one truncated line.
export type ActivityItem = { id: string; icon: IconName; title: string; detail?: string; time?: string; tone: Tone; toneLabel: string };
defineProps<{ title: string; items: ActivityItem[]; empty: string; fullDetail?: boolean }>();
</script>

<template>
  <div class="jv-activity">
    <div class="head">
      <h3>{{ title }}</h3>
      <slot name="action" />
    </div>
    <ul v-if="items.length" class="list">
      <li v-for="item in items" :key="item.id">
        <span class="icon" aria-hidden="true"><NavIcon :name="item.icon" /></span>
        <span class="text">
          <span class="title">{{ item.title }}</span>
          <span v-if="item.detail" class="detail" :class="{ full: fullDetail }" :tabindex="fullDetail ? 0 : undefined">{{ item.detail }}</span>
        </span>
        <span v-if="item.time" class="time">{{ item.time }}</span>
        <span v-if="$slots.actions" class="actions"><slot name="actions" :item="item" /></span>
        <span class="dot" :class="item.tone" role="img" :aria-label="item.toneLabel" :title="item.toneLabel"></span>
      </li>
    </ul>
    <p v-else class="empty">{{ empty }}</p>
  </div>
</template>

<style scoped>
.head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; min-height: 16px; }
h3 { margin: 0; font-size: 11px; font-weight: 400; letter-spacing: 0.3em; color: var(--text-5); text-transform: uppercase; }
.list {
  list-style: none; margin: 0; padding: 0; overflow: hidden;
  border: 1px solid var(--line-a30); border-radius: var(--r-12); background: rgba(8, 40, 32, 0.45);
}
li { display: flex; align-items: center; gap: 14px; min-height: 46px; padding: 6px 14px 6px 12px; box-sizing: border-box; }
li + li { border-top: 1px solid var(--line-a18); }
.icon {
  width: 38px; height: 34px; flex: none; display: grid; place-items: center; border-radius: var(--r-8);
  background: rgba(6, 30, 24, 0.9); border: 1px solid rgba(var(--accent-rgb), 0.2); color: var(--text-1);
}
.icon :deep(svg) { width: 18px; height: 18px; }
.text { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.title, .detail { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.title { font-size: 12px; font-weight: 500; color: #f1faf6; }
.detail { margin-top: 2px; font-size: 11px; color: var(--text-5); }
.detail.full {
  white-space: pre-wrap; overflow-wrap: anywhere; overflow: auto; max-height: 240px; margin-top: 6px; padding: 8px 10px;
  font-family: ui-monospace, monospace; color: var(--text-3); border-radius: var(--r-8); background: rgba(6, 30, 24, 0.9);
}
.time { flex: none; font-size: 11px; color: var(--text-5); }
.actions { flex: none; display: flex; gap: 8px; }
.dot { flex: none; width: 10px; height: 10px; border-radius: 50%; background: var(--idle); }
.dot.ok { background: var(--accent); box-shadow: var(--glow-sm); }
.dot.warn { background: var(--warn); box-shadow: 0 0 8px var(--warn); }
.dot.error { background: var(--danger); box-shadow: 0 0 8px var(--danger); }
.empty {
  margin: 0; padding: 18px 16px; font-size: var(--fs-12); color: var(--text-4);
  border: 1px dashed var(--line-a30); border-radius: var(--r-12);
}
</style>
