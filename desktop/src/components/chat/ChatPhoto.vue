<script setup lang="ts">
import { ref, computed, onMounted } from "vue";
import { photoUrl, removedPhotos } from "../../photos";

// One photo of a message: a thumbnail that opens the lightbox, or a
// placeholder once the photo was deleted or expired.
const props = defineProps<{ id: string }>();
const emit = defineEmits<{ open: [id: string] }>();
const url = ref<string | null>(null);
const failed = ref(false);
const gone = ref(false);
const removed = computed(() => gone.value || removedPhotos.value.has(props.id));

onMounted(async () => {
  if (removed.value) return;
  try {
    url.value = await photoUrl(props.id);
    gone.value = url.value === null;
  } catch {
    failed.value = true;
  }
});
</script>

<template>
  <div v-if="removed" class="photo gone t-xs t-2" title="Photos are kept 90 days unless you keep them.">Photo removed</div>
  <div v-else-if="failed" class="photo gone t-xs t-2">Photo unavailable</div>
  <button v-else type="button" class="photo" aria-label="Open photo" @click="emit('open', id)">
    <img v-if="url" :src="url" alt="Photo" />
    <span v-else class="spinner" aria-hidden="true"></span>
  </button>
</template>

<style scoped>
.photo {
  width: 160px; height: 160px; padding: 0; border: 0; border-radius: var(--r-md); overflow: hidden;
  display: grid; place-items: center; background: var(--bg-sunken); box-shadow: var(--shadow-border); cursor: zoom-in;
}
.photo img { width: 100%; height: 100%; object-fit: cover; display: block; }
.photo:focus-visible { outline: 2px solid var(--focus-ring); outline-offset: 2px; }
.gone { cursor: default; padding: 8px; text-align: center; }
</style>
