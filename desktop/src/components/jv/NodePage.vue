<script setup lang="ts">
import { computed } from "vue";
import NavIcon, { type IconName } from "../NavIcon.vue";
import JvBackdrop from "./JvBackdrop.vue";
import JvTopBar from "./JvTopBar.vue";
import JvOrb from "./JvOrb.vue";
import JvSegmented from "./JvSegmented.vue";
import JvStatusDot from "./JvStatusDot.vue";
import type { Mood, Tone } from "../../hubModel";

// Shared layout of every module page: small orb with three satellites on each
// side, a tab bar with the same six items, and the detail panel (default slot)
// for the selected item. Satellites and tabs both select.
export type NodeItem = { id: string; label: string; title: string; icon: IconName; tone: Tone; status: string; description: string };
const props = withDefaults(defineProps<{ title: string; subtitle: string; items: NodeItem[]; modelValue: string; mood?: Mood }>(), {
  mood: "idle",
});
const emit = defineEmits<{ "update:modelValue": [id: string] }>();
const tabs = computed(() => props.items.map(({ id, label, icon }) => ({ id, label, icon })));
const selected = computed({ get: () => props.modelValue, set: (id: string) => emit("update:modelValue", id) });
</script>

<template>
  <div class="node-page">
    <JvBackdrop glow-y="27%" horizon="101px" />
    <JvTopBar variant="node" :title="title" :subtitle="subtitle" />

    <div class="stage">
      <svg v-if="items.length" class="wires" viewBox="0 0 981 363" aria-hidden="true">
        <g transform="translate(-234 -97)">
          <circle cx="725" cy="288" r="168" fill="none" stroke="rgba(150,255,210,.55)" stroke-width="1.5" />
          <circle cx="725" cy="288" r="180" fill="none" class="a20" stroke-width="1" />
          <circle cx="725" cy="288" r="212" fill="none" class="a22" stroke-width="1" stroke-dasharray="1 6" />
          <path d="M596.1 180.2L538 150M557.0 284.8L512 268M578.5 370.2L527 396M853.5 179.7L912 150M893.0 285.8L934 268M871.5 370.3L920 398M725 456V462" class="a55" stroke-width="1" />
          <g class="fill-accent"><circle cx="538" cy="150" r="2.5" /><circle cx="512" cy="268" r="2.5" /><circle cx="527" cy="396" r="2.5" /><circle cx="912" cy="150" r="2.5" /><circle cx="934" cy="268" r="2.5" /><circle cx="920" cy="398" r="2.5" /></g>
          <g fill="rgba(2,20,15,.9)" stroke="rgba(150,255,210,.85)" stroke-width="1.4"><circle cx="596.1" cy="180.2" r="6.5" /><circle cx="557" cy="284.8" r="6.5" /><circle cx="578.5" cy="370.2" r="6.5" /><circle cx="853.5" cy="179.7" r="6.5" /><circle cx="893" cy="285.8" r="6.5" /><circle cx="871.5" cy="370.3" r="6.5" /><circle cx="725" cy="456" r="5" /></g>
          <g class="fill-accent"><circle cx="596.1" cy="180.2" r="2" /><circle cx="557" cy="284.8" r="2" /><circle cx="578.5" cy="370.2" r="2" /><circle cx="853.5" cy="179.7" r="2" /><circle cx="893" cy="285.8" r="2" /><circle cx="871.5" cy="370.3" r="2" /><circle cx="725" cy="456" r="1.6" /></g>
        </g>
      </svg>
      <JvOrb class="orb" :size="290" :mood="mood" />

      <div class="sats">
        <button
          v-for="(item, i) in items.slice(0, 6)"
          :key="item.id"
          type="button"
          class="sat"
          :class="['s' + i, { on: item.id === selected }]"
          :aria-pressed="item.id === selected"
          @click="selected = item.id"
        >
          <span class="icon" aria-hidden="true"><NavIcon :name="item.icon" /></span>
          <span class="text">
            <span class="name">{{ item.title }}</span>
            <JvStatusDot :tone="item.tone" :label="item.status" :size="11" class="status" />
            <span class="desc">{{ item.description }}</span>
          </span>
        </button>
      </div>
    </div>

    <div class="detail">
      <JvSegmented v-if="items.length" v-model="selected" class="tabbar" variant="tabs" :items="tabs" :label="title" />
      <slot />
    </div>
  </div>
</template>

<style scoped>
.node-page { position: relative; min-height: 100%; padding-bottom: 120px; box-sizing: border-box; }
.node-page > :not(.jv-backdrop) { position: relative; z-index: 1; }

.stage { width: 981px; height: 363px; margin: 18px auto 0; position: relative; }
.wires { position: absolute; inset: 0; width: 100%; height: 100%; overflow: visible; }
.a20 { stroke: rgba(var(--accent-rgb), 0.2); }
.a22 { stroke: rgba(var(--accent-rgb), 0.22); }
.a55 { stroke: rgba(var(--accent-rgb), 0.55); }
.fill-accent { fill: var(--accent); }
.orb { position: absolute !important; left: 346px; top: 46px; }

.sat {
  position: absolute; width: 277px; height: 102px; box-sizing: border-box;
  display: flex; align-items: center; gap: 24px; padding: 0 22px 0 20px; text-align: left;
  border-radius: var(--r-24); border: 1px solid var(--line-a45); background: var(--sat-bg);
  box-shadow: 0 0 22px rgba(var(--accent-rgb), 0.12); color: #f1faf6; font: inherit; font-weight: 400; cursor: pointer;
}
.sat:hover { filter: none; border-color: rgba(var(--accent-rgb), 0.7); }
.sat.on { border: 2px solid var(--accent); background: var(--sel-bg); box-shadow: 0 0 28px rgba(var(--accent-rgb), 0.45), inset 0 0 18px rgba(var(--accent-rgb), 0.12); }
.s0 { left: 25px; top: 0; }
.s1 { left: 0; top: 119px; }
.s2 { left: 11px; top: 238px; }
.s3 { left: 682px; top: 0; }
.s4 { left: 704px; top: 120px; }
.s5 { left: 694px; top: 238px; }
.icon {
  width: 66px; height: 66px; flex: none; box-sizing: border-box; display: grid; place-items: center;
  border-radius: 50%; border: 1.5px solid rgba(var(--accent-rgb), 0.7); color: var(--accent);
  box-shadow: inset 0 0 14px rgba(var(--accent-rgb), 0.18);
}
.on .icon { border-color: rgba(var(--accent-rgb), 0.85); }
.icon :deep(svg) { width: 30px; height: 30px; stroke-width: 1.6; }
.text { min-width: 0; display: flex; flex-direction: column; }
.name { font-size: var(--fs-15); font-weight: 500; letter-spacing: 0.04em; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.status { margin-top: 5px; font-size: var(--fs-13); }
.desc { margin-top: 6px; font-size: var(--fs-12); line-height: 1.35; color: var(--text-3); display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }

.detail { width: min(1148px, calc(100% - 48px)); margin: 21px auto 0; }
.tabbar { width: calc(100% - 86px); margin: 0 auto; }

@media (max-width: 1099px), (max-height: 759px) {
  .stage { width: auto; height: auto; padding: 18px 16px 0; top: 0; }
  .wires, .orb { display: none; }
  .sats { display: grid; grid-template-columns: repeat(auto-fill, minmax(230px, 1fr)); gap: 10px; }
  .sat { position: static; width: auto; height: auto; min-height: 76px; padding: 10px 14px; gap: 12px; border-radius: var(--r-16); }
  .icon { width: 44px; height: 44px; }
  .icon :deep(svg) { width: 22px; height: 22px; }
  .name { font-size: 14px; }
  .desc { display: none; }
  .detail { width: auto; margin: 16px 16px 0; }
  .tabbar { width: 100%; }
}
</style>
