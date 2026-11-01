<script setup lang="ts">
import {
  BarController, BarElement, CategoryScale, Chart, Filler, LinearScale, LineController, LineElement, PointElement, Tooltip,
  type ChartDataset, type Plugin,
} from "chart.js";
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { changePercent, chartNumber, formatDecimal, formatPrice, movingAverage, type Bar } from "../../trading";
import { themeChartColors, type ChartColors } from "./chartColors";

// Price or value chart on chart.js. Candles are two floating-bar datasets
// (wick low..high, body open..close), so no financial plugin is needed.
// Decimal strings become numbers only here, for drawing; the tooltip and the
// table below show the original strings.
const props = withDefaults(defineProps<{
  bars: Bar[]; mode?: "line" | "candle"; currency: string; label: string; ma?: number[]; volume?: boolean; height?: number;
}>(), { mode: "line", ma: () => [], volume: false, height: 300 });

Chart.register(LineController, LineElement, PointElement, BarController, BarElement, CategoryScale, LinearScale, Tooltip, Filler);

const canvas = ref<HTMLCanvasElement | null>(null);
/** The values table renders only while open: up to 5000 rows otherwise. */
const tableOpen = ref(false);
let chart: Chart | undefined;
/** Colours of the last draw; the crosshair plugin reuses them. */
let colors: ChartColors | undefined;

const intraday = computed(() => {
  const first = Date.parse(props.bars[0]?.t ?? "");
  const last = Date.parse(props.bars[props.bars.length - 1]?.t ?? "");
  return last - first < 2 * 86_400_000;
});
const long = computed(() => {
  const first = Date.parse(props.bars[0]?.t ?? "");
  const last = Date.parse(props.bars[props.bars.length - 1]?.t ?? "");
  return last - first > 400 * 86_400_000;
});
function when(iso: string, full = false): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "—";
  const opts: Intl.DateTimeFormatOptions = intraday.value
    ? { hour: "2-digit", minute: "2-digit", ...(full ? { day: "numeric", month: "short" } : {}) }
    : long.value && !full ? { month: "short", year: "numeric" } : { day: "numeric", month: "short", ...(full ? { year: "numeric" } : {}) };
  return d.toLocaleString("en", opts);
}

const money = (v: string) => formatPrice(v, props.currency);

/** Vertical line at the hovered bar. */
const crosshair: Plugin = {
  id: "td-crosshair",
  afterDatasetsDraw(c) {
    const active = c.tooltip?.getActiveElements();
    if (!active?.length) return;
    const { ctx, chartArea } = c;
    ctx.save();
    ctx.strokeStyle = colors?.strong ?? "";
    ctx.lineWidth = 1;
    ctx.beginPath();
    ctx.moveTo(active[0].element.x, chartArea.top);
    ctx.lineTo(active[0].element.x, chartArea.bottom);
    ctx.stroke();
    ctx.restore();
  },
};

function render() {
  chart?.destroy();
  chart = undefined;
  if (!canvas.value || !props.bars.length) return;
  const p = (colors = themeChartColors());
  const font = { family: getComputedStyle(canvas.value).fontFamily, size: 12 };
  const bars = props.bars;
  const close = bars.map((b) => chartNumber(b.c));
  // Rising, falling, or neutral when open or close is missing (never "0").
  const dir = bars.map((b) => {
    const o = chartNumber(b.o);
    const c = chartNumber(b.c);
    return o === null || c === null ? 0 : c >= o ? 1 : -1;
  });
  const color = dir.map((d) => (d > 0 ? p.up : d < 0 ? p.down : p.strong));
  const candle = props.mode === "candle";

  /** A floating bar from two prices; a gap when either is missing. */
  const span = (a: string, b: string): [number, number] | null => {
    const x = chartNumber(a);
    const y = chartNumber(b);
    return x === null || y === null ? null : [x, y];
  };
  const datasets: ChartDataset[] = candle
    ? [
      { type: "bar", label: "Range", data: bars.map((b) => span(b.l, b.h)), backgroundColor: color, barThickness: 1, grouped: false, order: 2 },
      { type: "bar", label: "Open–close", data: bars.map((b) => span(b.o, b.c)), backgroundColor: color, barPercentage: 0.7, categoryPercentage: 0.9, minBarLength: 1, grouped: false, order: 1 },
    ]
    : [{ type: "line", label: "Close", data: close, borderColor: p.accent, backgroundColor: p.fill, fill: "start", borderWidth: 2, pointRadius: 0, pointHoverRadius: 3, tension: 0 }];
  // Short average solid in --chart-ma, long one dashed in --text-secondary.
  props.ma.forEach((n) => datasets.push({
    type: "line", label: `MA ${n}`, data: movingAverage(close, n), borderColor: n >= 200 ? p.text : p.ma, borderWidth: 1.5,
    borderDash: n >= 200 ? [5, 4] : [], pointRadius: 0, pointHoverRadius: 0, fill: false, order: 0,
  }));
  const vols = bars.map((b) => chartNumber(b.v));
  const maxVol = vols.reduce<number>((max, v) => (v !== null && v > max ? v : max), 0);
  if (props.volume) {
    datasets.push({ type: "bar", label: "Volume", data: vols, backgroundColor: dir.map((d) => (d > 0 ? p.strong : p.vol)), yAxisID: "vol", grouped: false, order: 3 });
  }

  chart = new Chart(canvas.value, {
    type: "bar", // category offsets for candles; each dataset sets its own type
    data: { labels: bars.map((b) => when(b.t)), datasets },
    plugins: [crosshair],
    options: {
      responsive: true, maintainAspectRatio: false, animation: false,
      interaction: { intersect: false, mode: "index" },
      plugins: {
        legend: { display: false },
        tooltip: {
          filter: (item) => item.datasetIndex === 0,
          displayColors: false,
          backgroundColor: p.tipBg, borderColor: p.tipBorder, borderWidth: 1, titleColor: p.tipText, bodyColor: p.tipText,
          titleFont: { ...font, weight: 500 }, bodyFont: font, padding: 10, cornerRadius: 10,
          callbacks: {
            title: (items) => when(bars[items[0].dataIndex].t, true),
            label: (item) => {
              const b = bars[item.dataIndex];
              const lines = candle ? [`O ${money(b.o)}  H ${money(b.h)}`, `L ${money(b.l)}  C ${money(b.c)}`] : [money(b.c)];
              if (item.dataIndex > 0) lines.push(`${changePercent(bars[item.dataIndex - 1].c, b.c)} vs previous`);
              if (props.volume && b.v) lines.push(`Volume ${formatDecimal(b.v, { places: 0 })}`);
              return lines;
            },
          },
        },
      },
      scales: {
        x: { grid: { display: false }, border: { display: false }, ticks: { color: p.text, font, maxTicksLimit: 6, maxRotation: 0 } },
        // Bar charts start at zero by default; prices must not.
        y: { position: "right", beginAtZero: false, border: { display: false }, grid: { color: p.grid }, ticks: { color: p.text, font, maxTicksLimit: 5 } },
        vol: { type: "linear", axis: "y", display: false, beginAtZero: true, max: maxVol ? maxVol * 4 : 1 },
      },
    },
  });
}

// Draw again in the new token colours when the theme flips, and once the
// web font has loaded so the axis labels use it.
const themeObserver = new MutationObserver(render);
onMounted(() => {
  render();
  if (document.fonts.status !== "loaded") void document.fonts.ready.then(() => chart && render());
  themeObserver.observe(document.documentElement, { attributeFilter: ["data-theme"] });
});
watch(() => [props.bars, props.mode, props.ma, props.volume], render);
onBeforeUnmount(() => {
  themeObserver.disconnect();
  chart?.destroy();
  chart = undefined;
});
</script>

<template>
  <div class="td-chart" :style="{ height: `${height}px` }">
    <canvas ref="canvas" role="img" :aria-label="label" />
  </div>
  <details class="td-chart-table" @toggle="tableOpen = ($event.target as HTMLDetailsElement).open">
    <summary>Show the values as a table</summary>
    <div v-if="tableOpen" class="scroll" tabindex="0" role="region" :aria-label="`${label}, values`">
      <table>
        <thead>
          <tr><th scope="col">Time</th><template v-if="mode === 'candle'"><th scope="col">Open</th><th scope="col">High</th><th scope="col">Low</th></template><th scope="col">{{ mode === "candle" ? "Close" : "Value" }}</th><th v-if="volume" scope="col">Volume</th></tr>
        </thead>
        <tbody>
          <tr v-for="b in bars" :key="b.t">
            <td>{{ when(b.t, true) }}</td>
            <template v-if="mode === 'candle'"><td>{{ money(b.o) }}</td><td>{{ money(b.h) }}</td><td>{{ money(b.l) }}</td></template>
            <td>{{ money(b.c) }}</td>
            <td v-if="volume">{{ formatDecimal(b.v, { places: 0 }) }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </details>
</template>

<style scoped>
.td-chart { position: relative; min-width: 0; }
.td-chart-table { margin-top: 10px; font-size: 12px; color: var(--text-secondary); }
summary { cursor: pointer; width: max-content; }
.scroll { max-height: 280px; overflow: auto; overscroll-behavior: contain; margin-top: 8px; }
table { border-collapse: collapse; width: 100%; font-variant-numeric: tabular-nums; }
th, td { padding: 4px 10px 4px 0; text-align: right; white-space: nowrap; }
th:first-child, td:first-child { text-align: left; }
th { font-weight: 500; position: sticky; top: 0; background: var(--bg-surface); }
</style>
