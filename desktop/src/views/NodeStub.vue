<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { useRoute } from "vue-router";
import NodePage, { type NodeItem } from "../components/jv/NodePage.vue";
import JvPanel from "../components/jv/JvPanel.vue";
import JvUnavailable from "../components/jv/JvUnavailable.vue";
import NavIcon from "../components/NavIcon.vue";
import { agentCount } from "../coreStatus";

// Module pages whose live views are not built yet. They keep the node layout
// and say plainly that nothing is shown yet; no sample data.
type Page = { title: string; subtitle: string; detail: string; items: Omit<NodeItem, "tone" | "status">[]; trading?: boolean };
const PAGES: Record<string, Page> = {
  conversations: {
    title: "CONVERSATIONS", subtitle: "EVERYTHING YOU AND JARVIS DISCUSSED",
    detail: "Browse your conversations from the chat (Ctrl/⌘K) for now. Channels, summaries and linked memories need Core support that does not exist yet.",
    items: [
      { id: "voice", label: "Voice", title: "Voice", icon: "mic", description: "Talk hands-free, anywhere at home" },
      { id: "coding", label: "Coding", title: "Coding sessions", icon: "code", description: "Goals, PR reviews and plans" },
      { id: "research", label: "Research", title: "Research threads", icon: "search", description: "Deep dives you can pick up later" },
      { id: "desktop", label: "Desktop", title: "Desktop app", icon: "monitor", description: "Your main place to chat with Jarvis" },
      { id: "mobile", label: "Mobile", title: "Mobile app", icon: "phone", description: "Quick questions on the go" },
      { id: "briefs", label: "Briefs", title: "Daily briefs", icon: "sun", description: "Morning summary of your day" },
    ],
  },
  agents: {
    title: "AGENTS", subtitle: "YOUR PERSONAL AI SYSTEM", trading: true,
    detail: "The agent overview reads the agent list from your Core.",
    items: [],
  },
  tasks: {
    title: "TASKS", subtitle: "WHAT JARVIS IS WORKING ON",
    detail: "Coding sessions and approvals will appear here. Scheduled and recurring tasks and goals need Core support that does not exist yet.",
    items: [
      { id: "active", label: "Active", title: "Active", icon: "play", description: "Work Jarvis is doing right now" },
      { id: "scheduled", label: "Scheduled", title: "Scheduled", icon: "calendar", description: "Runs at a set time or day" },
      { id: "waiting", label: "Waiting", title: "Waiting on you", icon: "alert", description: "Needs your OK to continue" },
      { id: "completed", label: "Completed", title: "Completed", icon: "tasks", description: "Finished, with results attached" },
      { id: "recurring", label: "Recurring", title: "Recurring", icon: "repeat", description: "Things Jarvis does every week" },
      { id: "goals", label: "Goals", title: "Goals", icon: "goal", description: "Bigger outcomes split into tasks" },
    ],
  },
  integrations: {
    title: "INTEGRATIONS", subtitle: "THE SYSTEMS JARVIS CAN REACH", trading: true,
    detail: "Models, tools and the IBKR broker link will appear here. Model access is managed under System Health for now.",
    items: [
      { id: "claude", label: "Claude", title: "Claude", icon: "spark", description: "Main brain for reasoning and code" },
      { id: "codex", label: "Codex", title: "Codex", icon: "terminal", description: "Second opinion, behind the broker" },
      { id: "github", label: "GitHub", title: "GitHub", icon: "branch", description: "Repos, PRs and releases" },
      { id: "sandbox", label: "Sandbox", title: "OpenSandbox", icon: "sandbox", description: "Disposable space for code runs" },
      { id: "surreal", label: "SurrealDB", title: "SurrealDB", icon: "memory", description: "Memory, runs and state" },
      { id: "node", label: "Home Node", title: "Home Node", icon: "home", description: "Your mini-PC running Jarvis" },
    ],
  },
  memory: {
    title: "MEMORY", subtitle: "WHAT JARVIS KNOWS ABOUT YOUR WORLD",
    detail: "Jarvis keeps your conversations, but your Core does not have a browsable memory store yet. Nothing is shown until it does.",
    items: [
      { id: "people", label: "People", title: "People", icon: "people", description: "Who matters and how you know them" },
      { id: "projects", label: "Projects", title: "Projects", icon: "context", description: "Everything about what you build" },
      { id: "preferences", label: "Preferences", title: "Preferences", icon: "heart", description: "How you like things done" },
      { id: "knowledge", label: "Knowledge", title: "Knowledge", icon: "book", description: "Notes, clips and saved insights" },
      { id: "routines", label: "Routines", title: "Routines", icon: "repeat", description: "Your habits and recurring days" },
      { id: "ideas", label: "Ideas", title: "Ideas", icon: "bulb", description: "Sparks worth coming back to" },
    ],
  },
};

const route = useRoute();
const page = computed(() => PAGES[String(route.name)] ?? PAGES.memory);
const items = computed<NodeItem[]>(() =>
  page.value.items.map((item) => ({ ...item, tone: "idle", status: "Not yet available" })),
);
const selected = ref("");
watch(items, (list) => { selected.value = list[0]?.id ?? ""; }, { immediate: true });
const current = computed(() => items.value.find((item) => item.id === selected.value));
const agentsUnsupported = computed(() => route.name === "agents" && agentCount.value.state === "unsupported");
</script>

<template>
  <NodePage v-model="selected" :title="page.title" :subtitle="page.subtitle" :items="items">
    <JvPanel
      v-if="current"
      :icon="current.icon"
      :title="current.title"
      tone="idle"
      :status="current.status"
      :description="current.description"
    >
      <JvUnavailable :title="current.title" :detail="page.detail" :icon="current.icon" />
    </JvPanel>
    <div v-else class="solo">
      <JvUnavailable
        :title="page.title.charAt(0) + page.title.slice(1).toLowerCase()"
        :kind="agentsUnsupported ? 'core-update' : 'planned'"
        :detail="page.detail"
      />
    </div>
    <RouterLink v-if="page.trading" to="/trading" class="trading-link">
      <NavIcon name="trend" />Open the trading desk<NavIcon name="arrow-right" />
    </RouterLink>
  </NodePage>
</template>

<style scoped>
.solo { padding: 24px; border-radius: var(--r-26); border: 1.5px solid var(--line-a55); background: var(--panel-bg); }
.trading-link {
  display: inline-flex; align-items: center; gap: 10px; margin-top: 16px; height: 40px; padding: 0 18px;
  border-radius: var(--r-10); border: 1px solid rgba(var(--accent-rgb), 0.5); background: rgba(4, 30, 24, 0.7);
  color: var(--text-1); font-size: var(--fs-13); text-decoration: none;
}
.trading-link :deep(svg) { width: 16px; height: 16px; color: var(--accent); }
</style>
