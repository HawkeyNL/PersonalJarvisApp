<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { useRouter } from "vue-router";
import NodePage, { type NodeItem } from "../components/jv/NodePage.vue";
import JvPanel from "../components/jv/JvPanel.vue";
import JvSegmented, { type SegmentItem } from "../components/jv/JvSegmented.vue";
import JvTile from "../components/jv/JvTile.vue";
import JvActivityList, { type ActivityItem } from "../components/jv/JvActivityList.vue";
import JvUnavailable from "../components/jv/JvUnavailable.vue";
import { currentAuthStatus } from "../auth";
import { conversations, deleteConversation, loadConversations, setCurrent } from "../conversations";
import { useAppStore } from "../stores/app";
import { availabilityFromError, countSince, relativeTime, type Tone } from "../hubModel";
import { filterConversations } from "../nodeModels";

// Conversations: the real thread list from Core (search, open, delete).
// Channels, summaries, action items and linked memories need Core support that
// does not exist yet.
type Load = "loading" | "ok" | "signin" | "unsupported" | "error";
const state = ref<Load>("loading");
const now = ref(Date.now());
async function load() {
  try {
    if (!(await currentAuthStatus()).authenticated) { state.value = "signin"; return; }
    await loadConversations();
    state.value = "ok";
  } catch (error) {
    state.value = availabilityFromError(error).state;
  }
  now.value = Date.now();
}
onMounted(load);

const week = computed(() => countSince(conversations.value, 7, now.value));
const today = computed(() => countSince(conversations.value, 1, now.value));
const STATUS: Record<Exclude<Load, "ok">, [Tone, string]> = {
  loading: ["idle", "Loading…"], signin: ["idle", "Sign-in required"],
  unsupported: ["idle", "Requires newer Core"], error: ["warn", "Unavailable"],
};
const planned = (id: string, label: string, title: string, icon: NodeItem["icon"], description: string): NodeItem =>
  ({ id, label, title, icon, description, tone: "idle", status: "Not yet available" });
const items = computed<NodeItem[]>(() => {
  const ok = state.value === "ok";
  const [tone, status] = ok ? (["ok", ""] as [Tone, string]) : STATUS[state.value as Exclude<Load, "ok">];
  const n = conversations.value.length;
  return [
    { id: "all", label: "All", title: "All conversations", icon: "chat", description: "Every thread you and Jarvis had",
      tone: ok && !n ? "idle" : tone, status: ok ? `${n} total` : status },
    { id: "week", label: "This week", title: "This week", icon: "calendar", description: "Threads active in the last 7 days",
      tone: ok && !week.value ? "idle" : tone, status: ok ? `${week.value} active` : status },
    planned("channels", "Channels", "Channels", "phone", "Voice, desktop and mobile apart"),
    planned("summaries", "Summaries", "Summaries", "doc", "Short recap after every thread"),
    planned("actions", "Action items", "Action items", "tasks", "Todos pulled out of a thread"),
    planned("memories", "Memories", "Linked memories", "memory", "What Jarvis learned from it"),
  ];
});
const SUB_TABS: SegmentItem[] = [{ id: "overview", label: "Overview", icon: "doc" }, { id: "threads", label: "Threads", icon: "lines" }];
const selected = ref("all");
const sub = ref("overview");
watch(selected, () => { sub.value = "overview"; });
const current = computed(() => items.value.find((item) => item.id === selected.value)!);
const real = computed(() => selected.value === "all" || selected.value === "week");

const DETAILS: Record<string, string> = {
  all: "Every conversation is stored on your Home Node and follows you across devices.",
  week: "Conversations you or Jarvis added to in the last seven days.",
  channels: "Core does not record where a conversation took place yet, so threads cannot be split by voice, desktop or mobile.",
  summaries: "Core does not write summaries of conversations yet.",
  actions: "Core does not extract action items from conversations yet.",
  memories: "Core has no browsable memory store yet, so nothing can be linked to a conversation.",
};

// --- Threads: search, open in the chat overlay, delete with confirmation ------
const query = ref("");
const shown = computed(() =>
  filterConversations(conversations.value, query.value, selected.value === "week" ? 7 : null, now.value));
const rows = computed<ActivityItem[]>(() => shown.value.map((c) => ({
  id: c.id, icon: "chat", title: c.title || "Untitled", time: relativeTime(c.updated_at, now.value),
  tone: "ok", toneLabel: "Stored on Core",
})));
const lastActivity = computed(() => (conversations.value[0] ? relativeTime(conversations.value[0].updated_at, now.value) : "—"));

const app = useAppStore();
const router = useRouter();
function open(id: string | null) {
  // The console reopens the saved current conversation when the hub mounts.
  if (id) setCurrent(id);
  app.consoleOpen = true;
  void router.push("/");
}
const confirming = ref<string | null>(null);
const busy = ref(false);
const deleteError = ref<string | null>(null);
async function remove(id: string) {
  busy.value = true;
  deleteError.value = null;
  try {
    await deleteConversation(id);
    confirming.value = null;
  } catch (error) {
    deleteError.value = `Could not delete: ${error instanceof Error ? error.message : String(error)}`;
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <NodePage v-model="selected" title="CONVERSATIONS" subtitle="EVERYTHING YOU AND JARVIS DISCUSSED" :items="items">
    <JvPanel :icon="current.icon" :title="current.title" :tone="current.tone" :status="current.status"
      :description="DETAILS[current.id]" :quote="real ? 'Pick up any thread where you left it.' : undefined">
      <template v-if="real" #action>
        <button type="button" class="ghost-btn" :disabled="state !== 'ok'" @click="open(null)">New conversation</button>
      </template>

      <template v-if="real">
        <JvUnavailable v-if="state !== 'ok' && state !== 'loading'" title="Conversations"
          :kind="state === 'unsupported' ? 'core-update' : 'error'" icon="chat"
          :detail="state === 'signin' ? 'Sign in on the Core page first.' : 'Check the connection to your Home Node.'" />
        <p v-else-if="state === 'loading'" class="muted" role="status">Loading conversations…</p>
        <template v-else>
          <JvSegmented v-model="sub" :items="SUB_TABS" :label="`${current.title} sections`" />
          <div v-if="sub === 'overview'" class="node-split">
            <section class="node-tiles" aria-label="Conversation counts">
              <h3 class="eyebrow">AT A GLANCE</h3>
              <div class="node-grid">
                <JvTile icon="chat" label="Conversations" :value="String(conversations.length)" />
                <JvTile icon="calendar" label="Active this week" :value="String(week)" />
                <JvTile icon="sun" label="Active today" :value="String(today)" />
                <JvTile icon="clock" label="Last activity" :value="lastActivity" />
                <JvTile icon="home" label="Stored" value="On your Home Node" />
                <JvTile icon="search" label="Search" value="By title, under Threads" />
              </div>
            </section>
            <JvActivityList title="Recent conversations" :items="rows.slice(0, 5)" empty="No conversations yet.">
              <template #action>
                <button v-if="rows.length > 5" type="button" class="link-btn" @click="sub = 'threads'">View all</button>
              </template>
              <template #actions="{ item }">
                <button type="button" class="ghost-btn sm" @click="open(item.id)">Open</button>
              </template>
            </JvActivityList>
          </div>
          <div v-else>
            <input v-model="query" class="node-search" type="search" aria-label="Search conversations by title" placeholder="Search by title" />
            <p v-if="deleteError" class="err" role="alert">{{ deleteError }}</p>
            <JvActivityList :title="`${shown.length} of ${conversations.length}`" :items="rows"
              :empty="query ? 'No conversation matches this search.' : 'No conversations in this period.'">
              <template #actions="{ item }">
                <template v-if="confirming === item.id">
                  <button type="button" class="ghost-btn sm danger" :disabled="busy" @click="remove(item.id)">Delete for good</button>
                  <button type="button" class="ghost-btn sm" :disabled="busy" @click="confirming = null">Cancel</button>
                </template>
                <template v-else>
                  <button type="button" class="ghost-btn sm" @click="open(item.id)">Open</button>
                  <button type="button" class="ghost-btn sm" :aria-label="`Delete ${item.title}`" @click="confirming = item.id">Delete</button>
                </template>
              </template>
            </JvActivityList>
          </div>
        </template>
      </template>
      <JvUnavailable v-else :title="current.title" :icon="current.icon" :detail="DETAILS[current.id]" />
    </JvPanel>
  </NodePage>
</template>
