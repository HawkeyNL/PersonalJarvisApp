<script setup lang="ts">
import { BarController, BarElement, CategoryScale, Chart, Legend, LinearScale, Tooltip } from "chart.js";
import { onMounted, onBeforeUnmount, ref, watch } from "vue";

type DailyUsage = { day: string; spent_eur: number; total_tokens: number; input_tokens?: number; output_tokens?: number; cache_read_tokens?: number; cache_write_tokens?: number };
const props = defineProps<{ rows: DailyUsage[]; mode: "cost" | "tokens" }>();
const canvas = ref<HTMLCanvasElement | null>(null);
let chart: Chart<"bar", number[], string> | undefined;
Chart.register(BarController, BarElement, CategoryScale, LinearScale, Tooltip, Legend);
const euro = new Intl.NumberFormat("nl-NL", { style: "currency", currency: "EUR", maximumFractionDigits: 4 });
const integer = new Intl.NumberFormat("nl-NL");

function render() {
  chart?.destroy();
  chart = undefined;
  if (!canvas.value || !props.rows.length) return;
  const cost = props.mode === "cost";
  const split = props.rows.every(row => row.input_tokens != null && row.output_tokens != null && row.cache_read_tokens != null && row.cache_write_tokens != null);
  const datasets = cost
    ? [{ label: "Kosten", data: props.rows.map(row => row.spent_eur), backgroundColor: "#34f5a0" }]
    : split ? [
      { label: "Input", data: props.rows.map(row => row.input_tokens!), backgroundColor: "#34f5a0" },
      { label: "Output", data: props.rows.map(row => row.output_tokens!), backgroundColor: "#7dffc0" },
      { label: "Cache lezen", data: props.rows.map(row => row.cache_read_tokens!), backgroundColor: "#277b59" },
      { label: "Cache schrijven", data: props.rows.map(row => row.cache_write_tokens!), backgroundColor: "#f4c76b" },
    ] : [{ label: "Tokens", data: props.rows.map(row => row.total_tokens), backgroundColor: "#34f5a0" }];
  chart = new Chart(canvas.value, {
    type: "bar",
    data: { labels: props.rows.map(row => row.day), datasets },
    options: {
      responsive: true, maintainAspectRatio: false, animation: false,
      interaction: { intersect: false, mode: "index" },
      plugins: {
        legend: { position: "bottom", labels: { color: "#7d9a8e", boxWidth: 10 } },
        tooltip: { callbacks: { label: context => `${context.dataset.label}: ${cost ? euro.format(context.parsed.y ?? 0) : integer.format(context.parsed.y ?? 0)}` } },
      },
      scales: {
        x: { stacked: !cost, grid: { display: false }, ticks: { color: "#7d9a8e", maxTicksLimit: 8 } },
        y: { stacked: !cost, beginAtZero: true, ticks: { color: "#7d9a8e" }, grid: { color: "rgba(52,245,160,.08)" } },
      },
    },
  });
}
onMounted(render);
watch(() => [props.rows, props.mode], render, { deep: true });
onBeforeUnmount(() => chart?.destroy());
</script>

<template>
  <div class="usage-chart"><canvas ref="canvas" role="img" :aria-label="mode === 'cost' ? 'Kosten per dag' : 'Tokens per dag'" /></div>
  <details>
    <summary>Dagwaarden bekijken</summary>
    <ul><li v-for="row in rows" :key="row.day">{{ row.day }} · {{ mode === 'cost' ? euro.format(row.spent_eur) : integer.format(row.total_tokens) + ' tokens' }}</li></ul>
  </details>
</template>

<style scoped>
.usage-chart { height: 260px; position: relative; min-width: 0; }
details { margin-top: 12px; color: var(--muted); font-size: 12px; }
summary { cursor: pointer; }
ul { padding-left: 18px; line-height: 1.8; }
</style>
