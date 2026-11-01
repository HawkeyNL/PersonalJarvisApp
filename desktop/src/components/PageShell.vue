<script setup lang="ts">
// Instrument page frame (canvas .main + .pagehead): the page's only h1 with an
// optional subtitle on the left, actions on the right, then the content. It
// renders inside App's <main> landmark, which the skip link focuses.
defineProps<{ title: string; subtitle?: string }>();
</script>

<template>
  <div class="main">
    <header class="pagehead">
      <div class="head">
        <slot name="crumb" />
        <div class="title">
          <h1>{{ title }}</h1>
          <slot name="badges" />
        </div>
        <slot name="subtitle">
          <p v-if="subtitle" class="sub t-sm t-2">{{ subtitle }}</p>
        </slot>
      </div>
      <div v-if="$slots.actions" class="actions"><slot name="actions" /></div>
    </header>
    <slot />
  </div>
</template>

<style scoped>
.head { min-width: 0; display: flex; flex-direction: column; }
.title { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
h1 { margin: 0; color: var(--text-primary); overflow-wrap: anywhere; }
.sub { margin-top: 4px; }
.actions { display: flex; gap: 8px; flex-wrap: wrap; }
</style>

<style>
/* Zero specificity: slotted paragraphs (crumb, subtitle) keep their own margins. */
:where(.pagehead > div) p { margin: 0; }
</style>
