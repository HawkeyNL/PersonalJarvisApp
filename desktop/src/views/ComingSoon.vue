<script setup lang="ts">
import type { IconName } from "../components/NavIcon.vue";
import JvBackdrop from "../components/jv/JvBackdrop.vue";
import JvTopBar from "../components/jv/JvTopBar.vue";
import JvUnavailable from "../components/jv/JvUnavailable.vue";

// Placeholder for a sidebar entry whose screen is not built yet. It says so
// plainly and never shows sample data. Copy comes from navigation.ts.
defineProps<{ title: string; icon: IconName; detail: string }>();
</script>

<template>
  <div class="soon-page">
    <JvBackdrop glow-y="20%" horizon="80px" />
    <JvTopBar variant="node" :title="title.toUpperCase()" />
    <section class="soon">
      <JvUnavailable :title="title" :icon="icon" :detail="detail" />
    </section>
  </div>
</template>

<style scoped>
.soon-page { position: relative; min-height: 100%; }
.soon { position: relative; z-index: 1; max-width: 720px; margin: 0 auto; padding: 48px 24px; }
</style>
