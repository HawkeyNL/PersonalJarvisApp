<script setup lang="ts">
import { TONE_BADGE, type Tone } from "../hubModel";

// A titled list in a section body: one row per item with its detail, an
// optional time and a status badge (colour plus label). Row actions go in the
// "actions" slot, a heading action in "action".
export type Row = { id: string; title: string; detail?: string; time?: string; tone: Tone; status: string };
defineProps<{ title: string; rows: Row[]; empty: string }>();
</script>

<template>
  <section class="rl" :aria-label="title">
    <div class="rl-head">
      <h3>{{ title }}</h3>
      <slot name="action" />
    </div>
    <ul v-if="rows.length" class="rl-rows">
      <li v-for="row in rows" :key="row.id">
        <span class="rl-text">
          <span class="rl-title">{{ row.title }}</span>
          <span v-if="row.detail" class="t-xs t-2">{{ row.detail }}</span>
        </span>
        <span v-if="row.time" class="t-xs t-2 num rl-time">{{ row.time }}</span>
        <slot name="actions" :row="row" />
        <span class="badge" :class="TONE_BADGE[row.tone]">{{ row.status }}</span>
      </li>
    </ul>
    <p v-else-if="empty" class="np-empty">{{ empty }}</p>
  </section>
</template>

<style scoped>
.rl { min-width: 0; display: flex; flex-direction: column; gap: 10px; }
.rl-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; min-height: 24px; }
h3 { margin: 0; }
.rl-rows { list-style: none; margin: 0; padding: 0; border-radius: var(--r-md); box-shadow: var(--shadow-border); overflow: hidden; }
li { display: flex; align-items: center; gap: 12px; min-height: 48px; padding: 10px 14px; }
li + li { border-top: 1px solid var(--separator); }
.rl-text { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px; overflow-wrap: anywhere; }
.rl-title { font-size: 14px; font-weight: 500; }
.rl-time { flex: none; }
</style>
