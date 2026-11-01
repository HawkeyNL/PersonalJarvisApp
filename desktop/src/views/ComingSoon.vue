<script setup lang="ts">
import NavIcon, { type IconName } from "../components/NavIcon.vue";
import PageShell from "../components/PageShell.vue";

// Placeholder for a sidebar entry whose screen is not built yet. It says so
// plainly and never shows sample data. Copy comes from navigation.ts.
defineProps<{ title: string; icon: IconName; detail: string }>();
</script>

<template>
  <PageShell :title="title">
    <section class="card empty" aria-labelledby="soon-title">
      <span class="tile acc" aria-hidden="true"><NavIcon :name="icon" /></span>
      <h2 id="soon-title">Not available yet</h2>
      <p class="t-sm">{{ detail }}</p>
    </section>
  </PageShell>
</template>

<style scoped>
.empty h2 { margin: 0; font-size: 15px; line-height: 1.4; color: var(--text-primary); }
.empty p { margin: 0; max-width: 420px; }
</style>
