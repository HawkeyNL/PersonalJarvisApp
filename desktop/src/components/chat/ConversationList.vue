<script setup lang="ts">
import { computed, ref } from "vue";
import NavIcon from "../NavIcon.vue";
import { conversations, currentId } from "../../conversations";
import { openConversation, startNewConversation } from "../../assistant";
import { groupConversations } from "../../chatList";
import { relativeTime } from "../../hubModel";

// The chat home's conversation list (canvas D-Home): new, title search and the
// threads grouped by day. Opening one loads it into the chat pane; `new` lets
// the page move focus to the composer.
const props = defineProps<{ loading: boolean; error: string | null; now: number }>();
const emit = defineEmits<{ new: [] }>();

const query = ref("");
const searchEl = ref<HTMLInputElement | null>(null);
const groups = computed(() => groupConversations(conversations.value, query.value, props.now));

function open(id: string) {
  if (id !== currentId.value) void openConversation(id);
}
function newChat() {
  startNewConversation();
  emit("new");
}
function clearSearch() {
  query.value = "";
  searchEl.value?.focus();
}
</script>

<template>
  <aside class="list" aria-labelledby="conversations-title">
    <div class="head">
      <h2 id="conversations-title">Conversations</h2>
      <button type="button" class="btn btn-quiet btn-sm" @click="newChat">
        <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true"><path d="M12 5v14M5 12h14" /></svg>
        New
      </button>
    </div>
    <div class="search">
      <NavIcon name="search" />
      <input ref="searchEl" v-model="query" class="input" type="search" placeholder="Search conversations"
        aria-label="Search conversations by title" />
    </div>

    <div class="threads">
      <p v-if="error" class="state t-sm t-2" role="status">{{ error }}</p>
      <p v-else-if="loading && !conversations.length" class="state t-sm t-2" role="status">Loading conversations…</p>
      <div v-else-if="!conversations.length" class="empty">
        <h3>No conversations yet</h3>
        <p class="t-sm">Ask Jarvis something to start one.</p>
      </div>
      <div v-else-if="!groups.length" class="empty" role="status">
        <h3>Nothing found for “{{ query.trim() }}”</h3>
        <p class="t-sm">Search looks at conversation titles.</p>
        <button type="button" class="btn btn-secondary btn-sm" @click="clearSearch">Clear search</button>
      </div>
      <template v-else>
        <section v-for="group in groups" :key="group.label" class="group" :aria-labelledby="`day-${group.label}`">
          <h3 :id="`day-${group.label}`" class="day t-xs t-2">{{ group.label }}</h3>
          <ul>
            <li v-for="c in group.items" :key="c.id">
              <button type="button" class="thread" :aria-current="c.id === currentId ? 'true' : undefined" @click="open(c.id)">
                <span class="title">{{ c.title || "Untitled" }}</span>
                <span class="when t-xs t-2">{{ relativeTime(c.updated_at, now) }}</span>
              </button>
            </li>
          </ul>
        </section>
      </template>
    </div>

    <slot name="footer" />
  </aside>
</template>

<style scoped>
.list {
  flex: 0 1 320px; min-width: 260px; min-height: 0; display: flex; flex-direction: column; gap: 12px;
  padding: 20px 14px 14px; border-right: 1px solid var(--separator); background: var(--bg-page);
}
.head { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 0 4px; }
h2 { margin: 0; }
.search { position: relative; display: flex; align-items: center; }
.search :deep(svg) { position: absolute; left: 12px; width: 16px; height: 16px; color: var(--text-secondary); pointer-events: none; }
.search .input { padding-left: 36px; }
.threads { flex: 1 1 auto; min-height: 0; overflow-y: auto; display: flex; flex-direction: column; gap: 4px; margin: 0 -4px; padding: 0 4px; }
.state { margin: 0; padding: 8px 10px; }
.empty { padding: 32px 12px; }
.empty h3, .empty p { margin: 0; overflow-wrap: anywhere; }
.group { display: flex; flex-direction: column; gap: 2px; }
.day { margin: 0; padding: 8px 10px 4px; font-weight: 500; }
ul { margin: 0; padding: 0; list-style: none; display: flex; flex-direction: column; gap: 2px; }
.thread {
  -webkit-tap-highlight-color: transparent;
  width: 100%; min-height: 52px; padding: 8px 10px; display: flex; flex-direction: column; align-items: flex-start; gap: 2px;
  border-radius: var(--r-md); text-align: left; color: var(--text-primary);
  transition: background-color var(--d-hover) ease;
}
.thread[aria-current="true"] { background: var(--bg-selected); }
.thread[aria-current="true"] .title { color: var(--accent-text); }
.title, .when { width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.title { font-weight: 500; }
@media (hover: hover) and (pointer: fine) {
  .thread:not([aria-current="true"]):hover { background: var(--bg-hover); }
}
.thread:focus-visible { outline: 2px solid var(--focus-ring); outline-offset: -2px; }
</style>
