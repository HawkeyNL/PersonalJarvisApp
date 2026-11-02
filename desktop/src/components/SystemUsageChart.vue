<script setup lang="ts">
import { BarController, BarElement, CategoryScale, Chart, Legend, LinearScale, Tooltip } from "chart.js";
import { onMounted, onBeforeUnmount, ref, watch } from "vue";
import { themeChartColors } from "./trading/chartColors";

type DailyUsage = { day: string; spent_eur: number; total_tokens: number; input_tokens?: number; output_tokens?: number; cache_read_tokens?: number; cache_write_tokens?: number };
const props = defineProps<{ rows: DailyUsage[]; mode: "cost" | "tokens" }>();
const canvas = ref<HTMLCanvasElement | null>(null);
let chart: Chart<"bar", number[], string> | undefined;
Chart.register(BarController, BarElement, CategoryScale, LinearScale, Tooltip, Legend);
const euro = new Intl.NumberFormat("en", { style: "currency", currency: "EUR", maximumFractionDigits: 4 });
const integer = new Intl.NumberFormat("en");

function render() {
  chart?.destroy();
  chart = undefined;
  if (!canvas.value || !props.rows.length) return;
  const cost = props.mode === "cost";
  // Chart.js cannot read CSS variables: the theme's tokens, read at draw time.
  const c = themeChartColors();
  const series = [c.accent, c.soft, c.strong, c.text];
  const { text, grid } = c;
  const split = props.rows.every(row => row.input_tokens != null && row.output_tokens != null && row.cache_read_tokens != null && row.cache_write_tokens != null);
  const datasets = cost
    ? [{ label: "Cost", data: props.rows.map(row => row.spent_eur), backgroundColor: series[0] }]
    : split ? [
      { label: "Input", data: props.rows.map(row => row.input_tokens!), backgroundColor: series[0] },
      { label: "Output", data: props.rows.map(row => row.output_tokens!), backgroundColor: series[1] },
      { label: "Cache read", data: props.rows.map(row => row.cache_read_tokens!), backgroundColor: series[2] },
      { label: "Cache write", data: props.rows.map(row => row.cache_write_tokens!), backgroundColor: series[3] },
    ] : [{ label: "Tokens", data: props.rows.map(row => row.total_tokens), backgroundColor: series[0] }];
  chart = new Chart(canvas.value, {
    type: "bar",
    data: { labels: props.rows.map(row => row.day), datasets },
    options: {
      responsive: true, maintainAspectRatio: false, animation: false,
      interaction: { intersect: false, mode: "index" },
      plugins: {
        legend: { position: "bottom", labels: { color: text, boxWidth: 10 } },
        tooltip: { callbacks: { label: context => `${context.dataset.label}: ${cost ? euro.format(context.parsed.y ?? 0) : integer.format(context.parsed.y ?? 0)}` } },
      },
      scales: {
        x: { stacked: !cost, grid: { display: false }, ticks: { color: text, maxTicksLimit: 8 } },
        y: { stacked: !cost, beginAtZero: true, ticks: { color: text }, grid: { color: grid } },
      },
    },
  });
}
// Redraw in the new colours when the theme switches.
const themeObserver = new MutationObserver(render);
onMounted(() => {
  render();
  themeObserver.observe(document.documentElement, { attributeFilter: ["data-theme"] });
});
watch(() => [props.rows, props.mode], render, { deep: true });
onBeforeUnmount(() => {
  themeObserver.disconnect();
  chart?.destroy();
});
</script>

<template>
  <div class="usage-chart"><canvas ref="canvas" role="img" :aria-label="mode === 'cost' ? 'Cost per day' : 'Tokens per day'" /></div>
  <details>
    <summary>Show daily values</summary>
    <ul><li v-for="row in rows" :key="row.day">{{ row.day }} · {{ mode === 'cost' ? euro.format(row.spent_eur) : integer.format(row.total_tokens) + ' tokens' }}</li></ul>
  </details>
</template>

<style scoped>
.usage-chart { height: 260px; position: relative; min-width: 0; }
details { margin-top: 12px; color: var(--text-secondary); font-size: 12px; }
summary { cursor: pointer; }
ul { padding-left: 18px; line-height: 1.8; }
</style>
