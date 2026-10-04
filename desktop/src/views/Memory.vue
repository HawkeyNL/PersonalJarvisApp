<script setup lang="ts">
import { computed, ref } from "vue";
import NodePage, { type NodeItem } from "../components/jv/NodePage.vue";
import JvPanel from "../components/jv/JvPanel.vue";
import JvUnavailable from "../components/jv/JvUnavailable.vue";

// Memory: Core keeps conversations but has no browsable memory store yet, so
// every category says so plainly; no sample data.
const planned = (id: string, title: string, icon: NodeItem["icon"], description: string): NodeItem =>
  ({ id, label: title, title, icon, description, tone: "idle", status: "Not yet available" });
const items: NodeItem[] = [
  planned("people", "People", "people", "Who matters and how you know them"),
  planned("projects", "Projects", "context", "Everything about what you build"),
  planned("preferences", "Preferences", "heart", "How you like things done"),
  planned("knowledge", "Knowledge", "book", "Notes, clips and saved insights"),
  planned("routines", "Routines", "repeat", "Your habits and recurring days"),
  planned("ideas", "Ideas", "bulb", "Sparks worth coming back to"),
];
const selected = ref("people");
const current = computed(() => items.find((item) => item.id === selected.value)!);
</script>

<template>
  <NodePage v-model="selected" title="MEMORY" subtitle="WHAT JARVIS KNOWS ABOUT YOUR WORLD" :items="items">
    <JvPanel :icon="current.icon" :title="current.title" tone="idle" :status="current.status" :description="current.description">
      <JvUnavailable :title="current.title" :icon="current.icon"
        detail="Jarvis keeps your conversations, but your Core does not have a browsable memory store yet. Nothing is shown until it does." />
    </JvPanel>
  </NodePage>
</template>
