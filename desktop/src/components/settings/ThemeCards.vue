<script setup lang="ts">
// Theme as native radio cards (canvas D-Settings): arrow keys move the choice,
// Tab enters the group at the checked card. Each card previews its theme.
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { themeCards, type ThemeChoice } from "../../theme";

const props = defineProps<{ modelValue: ThemeChoice }>();
const emit = defineEmits<{ "update:modelValue": [ThemeChoice] }>();

const query = window.matchMedia("(prefers-color-scheme: dark)");
const prefersDark = ref(query.matches);
const follow = () => { prefersDark.value = query.matches; };
onMounted(() => query.addEventListener("change", follow));
onBeforeUnmount(() => query.removeEventListener("change", follow));

const cards = computed(() => themeCards(props.modelValue, prefersDark.value));
</script>

<template>
  <section class="card col" aria-labelledby="theme-heading">
    <div class="col head">
      <h2 id="theme-heading">Theme</h2>
      <p id="theme-hint" class="t-sm t-2">System follows your computer's light or dark setting, also when it switches in the evening.</p>
    </div>
    <div class="choices" role="radiogroup" aria-labelledby="theme-heading" aria-describedby="theme-hint">
      <label v-for="card in cards" :key="card.id" class="choice" :class="{ on: card.checked }">
        <input class="sr-only" type="radio" name="theme" :value="card.id" :checked="card.checked"
          @change="emit('update:modelValue', card.id)" />
        <span class="preview" :class="card.preview" aria-hidden="true">
          <span class="side"><span class="bar acc"></span><span class="bar w85"></span><span class="bar w60"></span></span>
          <span class="body"><span class="bar title"></span><span class="block"></span><span class="bar text"></span></span>
        </span>
        <span class="foot">
          <span class="name">{{ card.label }}</span>
          <svg v-if="card.checked" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor"
            stroke-width="2.25" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M5 12.5l4.5 4.5L19 7.5" /></svg>
        </span>
      </label>
    </div>
  </section>
</template>

<style scoped>
.col { display: flex; flex-direction: column; }
.card { gap: 16px; }
.head { gap: 4px; }
.head p { margin: 0; }
.choices { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; }
@media (max-width: 560px) { .choices { grid-template-columns: minmax(0, 1fr); } }
.choice {
  position: relative; display: flex; flex-direction: column; gap: 10px; padding: 10px; cursor: pointer;
  background: var(--bg-surface); border-radius: var(--r-lg); box-shadow: var(--shadow-border);
  transition: box-shadow var(--d-hover) var(--ease-out);
}
.choice.on { box-shadow: 0 0 0 2px var(--focus-ring); }
.choice:has(input:focus-visible) { outline: 2px solid var(--focus-ring); outline-offset: 3px; }
@media (hover: hover) and (pointer: fine) {
  .choice:not(.on):hover { box-shadow: var(--shadow-border-hover); }
}
.foot { display: flex; align-items: center; justify-content: space-between; gap: 8px; min-height: 18px; }
.name { font-weight: 500; overflow-wrap: anywhere; }
.foot svg { flex: none; color: var(--accent-text); }
.sr-only {
  position: absolute; width: 1px; height: 1px; min-width: 0; padding: 0; margin: 0; border: 0;
  overflow: hidden; clip-path: inset(50%); white-space: nowrap;
}

/* The preview paints one theme whatever the app shows. Values mirror
   tokens.css (:root and :root[data-theme="dark"]); a unit test keeps them equal. */
.preview { display: flex; height: 96px; border-radius: 8px; overflow: hidden; box-shadow: 0 0 0 1px var(--border); }
.preview.light {
  --bg-page: oklch(0.985 0.003 255);
  --bg-surface: oklch(1 0 0);
  --bg-sunken: oklch(0.965 0.004 255);
  --border: oklch(0.88 0.006 255);
  --separator: oklch(0.925 0.005 255);
  --text-primary: oklch(0.22 0.01 255);
  --text-secondary: oklch(0.47 0.012 255);
  --accent-solid: oklch(0.52 0.17 258);
}
.preview.dark {
  --bg-page: oklch(0.165 0.008 255);
  --bg-surface: oklch(0.2 0.009 255);
  --bg-sunken: oklch(0.145 0.007 255);
  --border: oklch(0.33 0.011 255);
  --separator: oklch(0.27 0.01 255);
  --text-primary: oklch(0.95 0.004 255);
  --text-secondary: oklch(0.78 0.01 255);
  --accent-solid: oklch(0.55 0.15 258);
}
.side {
  width: 30%; padding: 8px; display: flex; flex-direction: column; gap: 5px;
  background: var(--bg-surface); border-right: 1px solid var(--separator);
}
.body { flex: 1; padding: 10px; display: flex; flex-direction: column; gap: 6px; background: var(--bg-page); }
.bar { height: 6px; border-radius: 3px; background: var(--bg-sunken); }
.bar.acc { width: 70%; background: var(--accent-solid); }
.w85 { width: 85%; }
.w60 { width: 60%; }
.bar.title { height: 8px; width: 50%; background: var(--text-primary); }
.bar.text { width: 70%; background: var(--text-secondary); }
.block { height: 30px; border-radius: 6px; background: var(--bg-surface); box-shadow: 0 0 0 1px var(--border); }
</style>
