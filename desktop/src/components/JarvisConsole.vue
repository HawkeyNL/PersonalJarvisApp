<script setup lang="ts">
import { ref, computed, watch, onMounted, onUnmounted, nextTick } from "vue";
import { listen, type UnlistenFn } from "@tauri-apps/api/event";
import { nextSpeechStatus } from "../speechStatus";
import NavIcon from "./NavIcon.vue";
import {
  messages,
  send,
  thinking,
  openConversation,
  startNewConversation,
  initChat,
} from "../assistant";
import { conversations, currentId, deleteConversation } from "../conversations";
import { renderMarkdown, handleMarkdownClick } from "../markdown";
import { dayOf } from "../chatList";
import { useMic } from "../useMic";
import { wakePulse } from "../voicewake";
import { consoleActive } from "../updates";
import {
  voiceEnabled,
  voiceRate,
  setVoiceRate,
  voiceChoice,
  localVoices,
  voiceChoiceError,
  refreshLocalVoices,
  setVoiceChoice,
  headset,
  setVoiceEnabled,
  stopSpeaking,
  setHeadset,
  canSpeak,
  refreshRoute,
} from "../voice";

// The chat pane of the home (canvas D-Home): the current conversation and the
// composer. Sending, streaming and history all live in assistant.ts; this
// component only shows them and owns the mic and speech controls.
const text = ref("");
const inputEl = ref<HTMLTextAreaElement | null>(null);
const policy = computed(() => canSpeak());
const localSpeechStatus = ref("");
let speechUnlisten: UnlistenFn | undefined;
let disposed = false;
onUnmounted(() => { disposed = true; speechUnlisten?.(); });

const mic = useMic((said) => send(said));
// Listening or an unsent draft holds back an automatic update restart.
watch(
  () => mic.listening.value || !!text.value.trim(),
  (value) => (consoleActive.value = value),
  { immediate: true },
);
onUnmounted(() => { consoleActive.value = false; });

// A verified "Hey Jarvis" starts listening.
watch(wakePulse, () => {
  if (!mic.listening.value) mic.toggle();
});

const current = computed(() => conversations.value.find((c) => c.id === currentId.value) ?? null);
const day = computed(() => (current.value ? dayOf(current.value.updated_at, Date.now()) : null));
// An assistant row exists (empty) from the moment a run starts; show it once text streams in.
const shown = computed(() => messages.value.filter((m) => m.role === "user" || m.text));
const last = computed(() => messages.value[messages.value.length - 1]);
const waiting = computed(() => thinking.value && !(last.value?.role === "jarvis" && last.value.text));

// Pinned to the latest turn: a new turn always scrolls down, streamed text only
// while the reader is already at the bottom.
const logEl = ref<HTMLElement | null>(null);
function scrollToBottom() {
  const el = logEl.value;
  if (el) el.scrollTop = el.scrollHeight;
}
watch(
  () => [messages.value.length, thinking.value, last.value?.text] as const,
  ([length], [previous]) => {
    const el = logEl.value;
    if (el && (length !== previous || el.scrollHeight - el.scrollTop - el.clientHeight < 80)) void nextTick(scrollToBottom);
  },
);

// Screen readers: the log itself is not live (a reload or a stream of tokens
// would flood them). This status line says when Jarvis starts a reply and
// reads the reply once it is complete, for the conversation it was asked in.
const announcement = ref("");
let askedIn: string | null = null;
const plain = (markdown: string) =>
  new DOMParser().parseFromString(renderMarkdown(markdown), "text/html").body.textContent?.trim() ?? "";
watch(thinking, (busy, before) => {
  if (busy) {
    askedIn = currentId.value;
    announcement.value = "Jarvis is replying…";
    return;
  }
  if (!before) return;
  const reply = last.value;
  const same = askedIn === null || askedIn === currentId.value;
  announcement.value = same && reply?.role === "jarvis" && reply.text ? `Jarvis: ${plain(reply.text)}` : "";
});

const canSend = computed(() => !!text.value.trim() && !thinking.value);
function onSend() {
  // A reply in progress keeps the draft: send() would drop it.
  if (!canSend.value) return;
  void send(text.value);
  text.value = "";
  inputEl.value?.focus();
}
function onEnter(event: KeyboardEvent) {
  if (event.shiftKey || event.isComposing) return;
  event.preventDefault();
  onSend();
}

// The mic ring follows your voice while listening.
const micStyle = computed(() =>
  mic.listening.value ? { boxShadow: `0 0 0 ${(2 + mic.level.value * 4).toFixed(1)}px var(--accent-subtle)` } : {},
);

function toggleVoice() {
  setVoiceEnabled(!voiceEnabled.value);
}
async function toggleHeadset() {
  setHeadset(!headset.value);
  await refreshRoute();
}

// Delete the open conversation, after a confirm step (ADR-030).
const confirming = ref(false);
const deleting = ref(false);
const deleteError = ref<string | null>(null);
const cancelEl = ref<HTMLButtonElement | null>(null);
watch(currentId, () => { confirming.value = false; deleteError.value = null; });
async function askDelete() {
  confirming.value = true;
  await nextTick();
  cancelEl.value?.focus();
}
async function removeCurrent() {
  const id = currentId.value;
  if (!id || deleting.value) return;
  deleting.value = true;
  deleteError.value = null;
  try {
    const next = await deleteConversation(id);
    if (next) await openConversation(next);
    else startNewConversation();
    confirming.value = false;
    inputEl.value?.focus();
  } catch (error) {
    deleteError.value = `Could not delete: ${error instanceof Error ? error.message : String(error)}`;
  } finally {
    deleting.value = false;
  }
}

// The home reads the mic state and focuses the composer (Ctrl/⌘K, New).
defineExpose({
  listening: mic.listening,
  startListening: () => { if (!mic.listening.value) mic.toggle(); },
  focusInput: () => inputEl.value?.focus(),
});

onMounted(async () => {
  try {
    const cleanup = await listen<unknown>("jarvis-local-speech", event => {
      if (disposed) return;
      localSpeechStatus.value = nextSpeechStatus(localSpeechStatus.value, event.payload);
    });
    if (disposed) { cleanup(); return; }
    speechUnlisten = cleanup;
  } catch { /* Browser-only preview has no native speech engine. */ }
  if (disposed) return;
  refreshRoute();
  await initChat();
  await nextTick();
  scrollToBottom();
});
</script>

<template>
  <section class="chat" aria-labelledby="chat-title">
    <header class="chat-head">
      <div class="head-text">
        <h2 id="chat-title">{{ current ? current.title || "Untitled" : "New conversation" }}</h2>
        <span class="t-xs t-2">{{ day ? `${day} · Stored on your Home Node` : "Your first message starts it" }}</span>
      </div>
      <div v-if="current" class="head-actions">
        <template v-if="confirming">
          <button type="button" class="btn btn-danger-quiet btn-sm" :aria-busy="deleting" @click="removeCurrent">Delete for good</button>
          <button ref="cancelEl" type="button" class="btn btn-secondary btn-sm" :disabled="deleting" @click="confirming = false">Cancel</button>
        </template>
        <button v-else type="button" class="btn btn-ghost btn-sm" :aria-label="`Delete ${current.title || 'this conversation'}`" @click="askDelete">Delete</button>
      </div>
    </header>
    <p v-if="deleteError" class="err banner" role="alert">{{ deleteError }}</p>

    <div v-if="!shown.length && !waiting" class="empty start">
      <h3>Ask Jarvis anything</h3>
      <p class="t-sm">Type below, or use the microphone to talk. Every conversation is kept on your Home Node and follows you to your other devices.</p>
    </div>
    <!-- Not live on purpose: see the announcement below. Focusable so the keyboard can scroll it. -->
    <div v-else ref="logEl" class="log" role="log" aria-live="off" aria-label="Messages" tabindex="0">
      <div v-for="m in shown" :key="m.id" class="msg" :class="m.role">
        <template v-if="m.role === 'user'">
          <span class="sr-only">You:</span>
          <div class="bubble">{{ m.text }}</div>
          <span class="t-xs t-2">{{ m.ts }}</span>
        </template>
        <template v-else>
          <div class="who t-xs t-2">
            <span class="tile acc mark" aria-hidden="true">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="3.5" /><circle cx="12" cy="12" r="9" /></svg>
            </span>
            Jarvis<span aria-hidden="true">·</span>{{ m.ts }}<template v-if="m.spoken"><span aria-hidden="true">·</span>spoken</template>
          </div>
          <!-- Jarvis speaks Markdown (sanitised in markdown.ts); the user's own text stays literal. -->
          <div class="md" @click="handleMarkdownClick" v-html="renderMarkdown(m.text)"></div>
        </template>
      </div>
      <p v-if="waiting" class="thinking t-sm t-2"><span class="spinner" aria-hidden="true"></span>Jarvis is thinking…</p>
    </div>
    <p class="sr-only" role="status">{{ announcement }}</p>

    <div class="composer-wrap">
      <form class="composer card" @submit.prevent="onSend">
        <textarea
          ref="inputEl"
          v-model="text"
          rows="2"
          placeholder="Ask Jarvis something…"
          aria-label="Message to Jarvis"
          aria-describedby="composer-keys"
          aria-keyshortcuts="Control+K Meta+K"
          @keydown.enter="onEnter"
        ></textarea>
        <div class="tools">
          <div class="tool-group">
            <button
              type="button"
              class="btn btn-ghost btn-sm icon-btn"
              :class="{ live: mic.listening.value }"
              :style="micStyle"
              :disabled="!mic.available"
              :aria-pressed="mic.listening.value"
              :aria-label="mic.available ? 'Speak' : 'Voice input unavailable'"
              :title="mic.available ? (mic.listening.value ? 'Listening… (stops after 5 s of silence)' : 'Speak') : 'Voice input unavailable'"
              @click="mic.toggle"
            >
              <NavIcon name="mic" />
            </button>
            <button type="button" class="btn btn-ghost btn-sm icon-btn" :aria-pressed="voiceEnabled"
              aria-label="Speak replies aloud" :title="voiceEnabled ? 'Speech off' : 'Speech on'" @click="toggleVoice">
              <NavIcon :name="voiceEnabled ? 'sound-on' : 'sound-off'" />
            </button>
            <button type="button" class="btn btn-ghost btn-sm icon-btn" :aria-pressed="headset"
              aria-label="Earphones" title="Earphones on/off" @click="toggleHeadset">
              <NavIcon name="headset" />
            </button>
            <template v-if="voiceEnabled">
              <button type="button" class="btn btn-secondary btn-sm" title="Stop current speech" @click="stopSpeaking">Stop speech</button>
              <select class="input pick" :value="voiceRate" aria-label="Speech rate for the next fragments"
                @change="setVoiceRate(Number(($event.target as HTMLSelectElement).value))">
                <option v-for="rate in [0.5,0.75,1,1.25,1.5,1.75,2]" :key="rate" :value="rate">{{rate}}×</option>
              </select>
              <button type="button" class="btn btn-secondary btn-sm" @click="refreshLocalVoices">Voices</button>
              <select class="input pick" :value="voiceChoice" aria-label="Local voice"
                @change="setVoiceChoice(($event.target as HTMLSelectElement).value)">
                <option value="">System default</option>
                <option v-if="voiceChoice && !localVoices.some(v=>v.id===voiceChoice)" :value="voiceChoice">Saved voice</option>
                <option v-for="voice in localVoices" :key="voice.id" :value="voice.id">{{voice.label}}</option>
              </select>
              <span v-if="voiceChoiceError" class="t-xs err" role="status">{{voiceChoiceError}}</span>
            </template>
          </div>
          <div class="tool-group send-group">
            <span id="composer-keys" class="t-xs t-2">Enter to send · Shift+Enter for a new line</span>
            <button type="submit" class="btn btn-primary icon-btn" aria-label="Send" :aria-disabled="!canSend">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 19V5M6 11l6-6 6 6" /></svg>
            </button>
          </div>
        </div>
      </form>
      <p class="policy t-xs t-2">
        <span class="pdot" :class="{ on: policy.allowed }" aria-hidden="true"></span>
        {{ policy.allowed ? "Jarvis can speak" : "Jarvis is silent" }} · {{ policy.reason }}
        <span v-if="localSpeechStatus" role="status"> · {{ localSpeechStatus }}</span>
        <span class="keys"><kbd>Ctrl</kbd>/<kbd>⌘</kbd> <kbd>K</kbd> jumps here from any page</span>
      </p>
    </div>
  </section>
</template>

<style scoped>
.chat { flex: 999 1 520px; min-width: 0; min-height: 0; display: flex; flex-direction: column; }
.chat-head {
  display: flex; align-items: center; justify-content: space-between; gap: 12px;
  padding: 18px 32px 14px; border-bottom: 1px solid var(--separator);
}
.head-text { min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.head-text h2 { margin: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.head-actions { display: flex; gap: 8px; flex: none; }
.banner { margin: 12px 32px 0; }

.start { flex: 1 1 auto; justify-content: center; }
.start h3, .start p { margin: 0; max-width: 44ch; }
.log {
  flex: 1 1 auto; min-height: 0; overflow-y: auto; overscroll-behavior: contain;
  display: flex; flex-direction: column; gap: 18px; padding: 28px 32px;
}
.log:focus-visible { outline: 2px solid var(--focus-ring); outline-offset: -2px; }
.msg { display: flex; flex-direction: column; gap: 4px; min-width: 0; }
.msg.user { align-self: flex-end; align-items: flex-end; max-width: 72%; }
.msg.jarvis { max-width: 78%; gap: 10px; }
.bubble {
  padding: 10px 14px; border-radius: 16px 16px 4px 16px;
  background: var(--accent-solid); color: var(--text-on-accent);
  white-space: pre-wrap; overflow-wrap: anywhere;
}
.who { display: flex; align-items: center; gap: 8px; }
.mark { width: 24px; height: 24px; border-radius: 7px; }
.thinking { margin: 0; display: flex; align-items: center; gap: 8px; }

/* Rendered Markdown (v-html, so :deep). Tables get the canvas .tbl in a card. */
.md { line-height: 1.6; overflow-wrap: anywhere; }
.md > :deep(:first-child) { margin-top: 0; }
.md > :deep(:last-child) { margin-bottom: 0; }
.md :deep(p), .md :deep(ul), .md :deep(ol) { margin: 0 0 0.75em; }
.md :deep(ul), .md :deep(ol) { padding-left: 1.4em; }
.md :deep(pre) { margin: 0 0 0.75em; padding: 12px 14px; border-radius: var(--r-md); background: var(--bg-sunken); overflow-x: auto; }
.md :deep(pre code) { padding: 0; background: none; }
.md :deep(a) { color: var(--accent-text); }
.md :deep(.md-table) { margin: 0 0 0.75em; padding: 4px 8px; overflow-x: auto; }
.md :deep(.md-table td) { overflow-wrap: normal; }

.composer-wrap { padding: 16px 32px 20px; display: flex; flex-direction: column; gap: 8px; }
.composer { padding: 10px 10px 10px 16px; display: flex; flex-direction: column; gap: 8px; }
.composer:focus-within { box-shadow: var(--shadow-border), 0 0 0 2px var(--focus-ring); }
textarea {
  width: 100%; min-height: 52px; max-height: 40vh; padding: 6px 0; border: 0; resize: none; field-sizing: content;
  background: transparent; color: var(--text-primary); font: 400 15px/1.5 var(--font-sans);
}
textarea:focus, textarea:focus-visible { outline: none; }
textarea::placeholder { color: var(--text-secondary); }
.tools { display: flex; align-items: center; justify-content: space-between; gap: 8px; flex-wrap: wrap; }
.tool-group { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.send-group { gap: 10px; margin-left: auto; }
.icon-btn.btn-sm { width: 36px; min-height: 36px; }
.icon-btn :deep(svg) { width: 18px; height: 18px; }
.btn[aria-pressed="true"] { background: var(--accent-subtle); color: var(--accent-text); }
.pick { width: auto; min-width: 0; min-height: 32px; padding: 0 8px; font-size: 13px; }
.policy { margin: 0 4px; display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.pdot { width: 6px; height: 6px; border-radius: var(--r-pill); background: var(--text-disabled); }
.pdot.on { background: var(--positive-solid); }
.keys { margin-left: auto; display: inline-flex; align-items: center; gap: 4px; }

.sr-only {
  position: absolute; width: 1px; height: 1px; margin: -1px; padding: 0; overflow: hidden;
  clip: rect(0 0 0 0); white-space: nowrap; border: 0;
}
</style>
