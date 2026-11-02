<script setup lang="ts">
import NavIcon from "../components/NavIcon.vue";
import PageShell from "../components/PageShell.vue";

// Memory (canvas D-Memory): Core keeps conversations but has no browsable
// memory store yet, so the page says so plainly and shows no sample data.
</script>

<template>
  <PageShell title="Memory" subtitle="What Jarvis remembers about you">
    <section class="card empty" aria-labelledby="memory-empty">
      <span class="tile acc" aria-hidden="true"><NavIcon name="memory" /></span>
      <h2 id="memory-empty">No memory store yet</h2>
      <p class="t-sm">
        Jarvis keeps your conversations on your Home Node, but your Core has no browsable memory store yet.
        Facts Jarvis remembers will show here once it does, and you will be able to make it forget any of them.
      </p>
    </section>
  </PageShell>
</template>

<style scoped>
.empty h2 { margin: 0; font-size: 15px; line-height: 1.4; color: var(--text-primary); }
.empty p { margin: 0; max-width: 460px; }
</style>
