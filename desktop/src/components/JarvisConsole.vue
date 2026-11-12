<script setup lang="ts">
import { ref, computed, watch, onMounted, onUnmounted, nextTick } from "vue";
import { listen, type UnlistenFn } from "@tauri-apps/api/event";
import { nextSpeechStatus } from "../speechStatus";
import NavIcon from "./NavIcon.vue";
import ChatPhoto from "./chat/ChatPhoto.vue";
import {
  messages,
  send,
  thinking,
  researchOffer,
  openConversation,
  startNewConversation,
  initChat,
} from "../assistant";
import { conversations, currentId, deleteConversation } from "../conversations";
import { renderMarkdown, handleMarkdownClick } from "../markdown";
import { splitSources, sourcePlace, type Source } from "../webAnswer";
import { chips, chipNotice, attach, removeChip, chipIds, takeChips, restoreChips, expireStaleChips, uploading, allReady, photoUrl, photoMeta, removedPhotos, keepPhoto, deletePhoto } from "../photos";
import { expiryHint, imageFiles, PHOTO_TYPES, ONLY_PHOTOS } from "../chatPhotos";
import { askedIn, dayOf, replyIsHere, type AskedIn } from "../chatList";
import { useMic } from "../useMic";
import { wakePulse } from "../voicewake";
import { locked } from "../lock";
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
  () => mic.listening.value || !!text.value.trim() || chips.value.length > 0,
  (value) => (consoleActive.value = value),
  { immediate: true },
);
onUnmounted(() => { consoleActive.value = false; });

// A verified "Hey Jarvis" starts listening, never behind the app lock.
watch(wakePulse, () => {
  if (!locked.value && !mic.listening.value) mic.toggle();
});
// The lock turns the mic off and drops what it has not recognised yet, also
// when the mic opened just as the lock came up.
watch(() => locked.value && mic.listening.value, (both) => { if (both) mic.stop(true); }, { flush: "sync" });

const current = computed(() => conversations.value.find((c) => c.id === currentId.value) ?? null);
const day = computed(() => (current.value ? dayOf(current.value.updated_at, Date.now()) : null));
// An assistant row exists (empty) from the moment a run starts; show it once text streams in.
// A web answer shows its trailing Sources as a list of links instead of in the text.
const shown = computed(() => messages.value.filter((m) => m.role === "user" || m.text || m.attachments?.length)
  .map((m) => ({ ...m, ...(m.web ? splitSources(m.text) : { body: m.text, sources: [] as Source[] }) })));
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
  () => [currentId.value, messages.value.length, thinking.value, last.value?.text] as const,
  ([id, length], [previousId, previousLength]) => {
    const el = logEl.value;
    const pinned = id !== previousId || length !== previousLength;
    if (el && (pinned || el.scrollHeight - el.scrollTop - el.clientHeight < 80)) void nextTick(scrollToBottom);
  },
);
// The log replaces the empty state once a conversation has messages.
watch(logEl, (el) => { if (el) scrollToBottom(); }, { flush: "post" });

// Screen readers: the log itself is not live (a reload or a stream of tokens
// would flood them). This status line says when Jarvis starts a reply and
// reads the reply once it is complete, for the conversation it was asked in.
const announcement = ref("");
let asked: AskedIn = { conversation: null, message: null };
const plain = (markdown: string) =>
  new DOMParser().parseFromString(renderMarkdown(markdown), "text/html").body.textContent?.trim() ?? "";
watch(thinking, (busy, before) => {
  if (busy) {
    asked = askedIn(currentId.value, messages.value);
    announcement.value = "Jarvis is replying…";
    return;
  }
  if (!before) return;
  const reply = last.value;
  const here = replyIsHere(asked, currentId.value, messages.value);
  announcement.value = here && reply?.role === "jarvis" && reply.text ? `Jarvis: ${plain(reply.text)}` : "";
});

// Photos must all be uploaded first; a photo alone is a message too.
const canSend = computed(() => (!!text.value.trim() || chips.value.length > 0) && allReady() && !thinking.value);

// Attach: the picker, a paste or a drop. Shrinking and uploading start at once.
const fileEl = ref<HTMLInputElement | null>(null);
function onPick(event: Event) {
  const input = event.target as HTMLInputElement;
  if (input.files) attach(input.files);
  input.value = "";
}
function onPaste(event: ClipboardEvent) {
  const files = imageFiles(event.clipboardData?.files);
  if (!files.length) return;
  event.preventDefault();
  attach(files);
}
const dropping = ref(false);
const carriesFiles = (event: DragEvent) => !!event.dataTransfer?.types.includes("Files");
function onDragOver(event: DragEvent) {
  if (!carriesFiles(event)) return;
  event.preventDefault();
  dropping.value = true;
}
function onDragLeave(event: DragEvent) {
  if (!(event.currentTarget as Node).contains(event.relatedTarget as Node | null)) dropping.value = false;
}
function onDrop(event: DragEvent) {
  dropping.value = false;
  if (!carriesFiles(event)) return;
  event.preventDefault();
  const files = imageFiles(event.dataTransfer?.files);
  if (files.length) attach(files);
  else chipNotice.value = ONLY_PHOTOS;
}
// Said once, the first time photos are in the composer.
const DISCLOSED = "jarvis.photos.disclosed";
const disclosed = ref(localStorage.getItem(DISCLOSED) === "1");
function markDisclosed() {
  if (disclosed.value) return;
  disclosed.value = true;
  localStorage.setItem(DISCLOSED, "1");
}
// Only this click sends a web search; the shown question is all that leaves.
function onResearch() {
  const question = researchOffer.value?.question;
  if (question && !thinking.value && !locked.value) void send(question, "research");
}

function onSend() {
  expireStaleChips();
  // A reply in progress keeps the draft: send() would drop it.
  if (!canSend.value) return;
  const draft = text.value;
  const photos = chipIds();
  const sent = send(draft, undefined, photos);
  // send() starts a reply synchronously, or refuses (lock, too many pending,
  // photos without a live connection): then the draft and photos stay.
  if (!thinking.value) return;
  const taken = takeChips();
  if (photos.length) markDisclosed();
  text.value = "";
  inputEl.value?.focus();
  // Core refused it outright and stored nothing: the draft and photos return.
  void sent.then((refused) => {
    if (!refused) return;
    if (!text.value.trim()) text.value = draft;
    restoreChips(taken);
  });
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

// The lightbox: one photo large, to keep past its 90 days or delete.
const lightboxEl = ref<HTMLDialogElement | null>(null);
const viewing = ref<{ id: string; url: string | null } | null>(null);
const photoBusy = ref(false);
const photoConfirm = ref(false);
const photoError = ref("");
const viewingMeta = computed(() => (viewing.value ? photoMeta.value.get(viewing.value.id) : undefined));
async function openPhoto(id: string) {
  viewing.value = { id, url: null };
  photoConfirm.value = false;
  photoError.value = "";
  lightboxEl.value?.showModal();
  try {
    const url = await photoUrl(id);
    if (viewing.value?.id === id) viewing.value = { id, url };
  } catch {
    if (viewing.value?.id === id) photoError.value = "This photo could not be loaded.";
  }
}
async function toggleKeep() {
  const id = viewing.value?.id;
  if (!id || photoBusy.value) return;
  photoBusy.value = true;
  photoError.value = "";
  try {
    await keepPhoto(id, !viewingMeta.value?.saved);
    // Gone on the Home Node: its message now shows "Photo removed".
    if (removedPhotos.value.has(id)) lightboxEl.value?.close();
  } catch {
    photoError.value = "Could not change this photo. Try again.";
  } finally {
    photoBusy.value = false;
  }
}
async function removePhoto() {
  const id = viewing.value?.id;
  if (!id || photoBusy.value) return;
  photoBusy.value = true;
  photoError.value = "";
  try {
    await deletePhoto(id);
    lightboxEl.value?.close();
  } catch {
    photoError.value = "Could not delete this photo. Try again.";
  } finally {
    photoBusy.value = false;
  }
}
// The lightbox shows the open conversation's photos only.
watch(currentId, () => lightboxEl.value?.close());
const hasPhotos = computed(() => messages.value.some((m) => m.attachments?.length));

// Delete the open conversation, after a confirm step (ADR-030).
const confirming = ref(false);
const deleting = ref(false);
const deleteError = ref<string | null>(null);
const cancelEl = ref<HTMLButtonElement | null>(null);
const deleteEl = ref<HTMLButtonElement | null>(null);
watch(currentId, () => { confirming.value = false; deleteError.value = null; });
async function askDelete() {
  confirming.value = true;
  await nextTick();
  cancelEl.value?.focus();
}
async function cancelDelete() {
  if (deleting.value) return;
  confirming.value = false;
  await nextTick();
  deleteEl.value?.focus();
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

// The home focuses the composer (Ctrl/⌘K, New).
defineExpose({ focusInput: () => inputEl.value?.focus() });

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
      <div v-if="current" class="head-actions" @keydown.esc="cancelDelete">
        <template v-if="confirming">
          <span v-if="hasPhotos" class="t-xs t-2">Its photos go too, kept ones included.</span>
          <button type="button" class="btn btn-danger-quiet btn-sm" :aria-busy="deleting" @click="removeCurrent">Delete for good</button>
          <button ref="cancelEl" type="button" class="btn btn-secondary btn-sm" :disabled="deleting" @click="cancelDelete">Cancel</button>
        </template>
        <button v-else ref="deleteEl" type="button" class="btn btn-ghost btn-sm" :aria-label="`Delete ${current.title || 'this conversation'}`" @click="askDelete">Delete</button>
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
          <div v-if="m.attachments?.length" class="photos">
            <ChatPhoto v-for="id in m.attachments" :key="id" :id="id" @open="openPhoto" />
          </div>
          <div v-if="m.text || !m.attachments?.length" class="bubble">{{ m.text }}</div>
          <span class="t-xs t-2">{{ m.ts }}</span>
        </template>
        <template v-else>
          <div class="who t-xs t-2">
            <span class="tile acc mark" aria-hidden="true">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="3.5" /><circle cx="12" cy="12" r="9" /></svg>
            </span>
            Jarvis<span aria-hidden="true">·</span>{{ m.ts }}<template v-if="m.spoken"><span aria-hidden="true">·</span>spoken</template>
            <span v-if="m.web" class="badge acc" title="Answer from a web search">Web</span>
          </div>
          <!-- Jarvis speaks Markdown (sanitised in markdown.ts); the user's own text stays literal. -->
          <div class="md" @click="handleMarkdownClick" v-html="renderMarkdown(m.body)"></div>
          <div v-if="m.attachments?.length" class="photos">
            <ChatPhoto v-for="id in m.attachments" :key="id" :id="id" @open="openPhoto" />
          </div>
          <!-- Titles are plain text from Core's checked Sources list; nothing is fetched. -->
          <div v-if="m.sources.length" class="sources t-sm" @click="handleMarkdownClick">
            <span class="t-xs t-2">Sources</span>
            <ul>
              <li v-for="source in m.sources" :key="source.url">
                <a :href="source.url" class="source-card" :title="source.url">
                  <span v-if="source.title" class="source-title">{{ source.title }}</span>
                  <span class="source-place t-xs t-2">{{ sourcePlace(source.url).host }}<span v-if="sourcePlace(source.url).path" class="source-path">{{ sourcePlace(source.url).path }}</span></span>
                </a>
              </li>
            </ul>
          </div>
        </template>
      </div>
      <div v-if="researchOffer && researchOffer.conversationId === currentId && !thinking && !chips.length" class="offer">
        <button type="button" class="btn btn-secondary btn-sm" :disabled="locked" @click="onResearch">Search the web</button>
        <span class="t-xs t-2">Sends only “{{ researchOffer.question }}” to your subscription provider, which searches the web.</span>
      </div>
      <p v-if="waiting" class="thinking t-sm t-2"><span class="spinner" aria-hidden="true"></span>{{ last?.searching ? "Searching the web…" : "Jarvis is thinking…" }}</p>
    </div>
    <p class="sr-only" role="status">{{ announcement }}</p>

    <dialog ref="lightboxEl" class="lightbox card" aria-label="Photo" @close="viewing = null">
      <template v-if="viewing">
        <div class="lightbox-image">
          <img v-if="viewing.url" :src="viewing.url" alt="Photo" />
          <p v-else-if="photoError" class="t-sm t-2">{{ photoError }}</p>
          <span v-else class="spinner" aria-hidden="true"></span>
        </div>
        <div class="lightbox-bar">
          <span class="t-xs t-2">{{ expiryHint(viewingMeta) }}</span>
          <p v-if="photoError && viewing.url" class="err" role="alert">{{ photoError }}</p>
          <div class="lightbox-actions">
            <template v-if="photoConfirm">
              <button type="button" class="btn btn-danger-quiet btn-sm" :aria-busy="photoBusy" @click="removePhoto">Delete for good</button>
              <button type="button" class="btn btn-secondary btn-sm" :disabled="photoBusy" @click="photoConfirm = false">Cancel</button>
            </template>
            <template v-else>
              <button type="button" class="btn btn-secondary btn-sm" :aria-pressed="!!viewingMeta?.saved" :aria-busy="photoBusy" @click="toggleKeep">
                <svg width="16" height="16" viewBox="0 0 24 24" :fill="viewingMeta?.saved ? 'currentColor' : 'none'" stroke="currentColor" stroke-width="2" stroke-linejoin="round" aria-hidden="true"><path d="M12 3l2.7 5.6 6.1.9-4.4 4.3 1 6.1L12 17l-5.4 2.9 1-6.1-4.4-4.3 6.1-.9z" /></svg>
                {{ viewingMeta?.saved ? "Kept" : "Keep" }}
              </button>
              <button type="button" class="btn btn-ghost btn-sm" @click="photoConfirm = true">Delete</button>
            </template>
            <button type="button" class="btn btn-ghost btn-sm" @click="lightboxEl?.close()">Close</button>
          </div>
        </div>
      </template>
    </dialog>

    <div class="composer-wrap">
      <form class="composer card" :class="{ dropping }" @submit.prevent="onSend" @dragover="onDragOver" @dragleave="onDragLeave" @drop="onDrop">
        <ul v-if="chips.length" class="chips" aria-label="Photos to send">
          <li v-for="chip in chips" :key="chip.key" class="chip" :class="chip.state">
            <img v-if="chip.url" :src="chip.url" alt="" />
            <span v-if="chip.state === 'preparing' || chip.state === 'uploading'" class="chip-busy" role="status">
              <span class="spinner" aria-hidden="true"></span><span class="sr-only">{{ chip.state === "preparing" ? "Preparing photo" : "Uploading photo" }}</span>
            </span>
            <button type="button" class="chip-x" aria-label="Remove photo" @click="removeChip(chip.key)">✕</button>
          </li>
        </ul>
        <p v-for="chip in chips.filter((c) => c.error)" :key="`e${chip.key}`" class="err t-xs" role="alert">{{ chip.error }}</p>
        <p v-if="chipNotice" class="t-xs t-2" role="status">{{ chipNotice }}</p>
        <p v-if="chips.length && !disclosed" class="t-xs t-2">Photos go to your Claude/Codex subscription to be read; never to web search.</p>
        <textarea
          ref="inputEl"
          v-model="text"
          rows="2"
          placeholder="Ask Jarvis something…"
          aria-label="Message to Jarvis"
          aria-describedby="composer-keys"
          aria-keyshortcuts="Control+K Meta+K"
          @keydown.enter="onEnter"
          @paste="onPaste"
        ></textarea>
        <div class="tools">
          <div class="tool-group">
            <input ref="fileEl" type="file" :accept="PHOTO_TYPES.join(',')" multiple hidden @change="onPick" />
            <button type="button" class="btn btn-ghost btn-sm icon-btn" aria-label="Attach photos" title="Attach photos (up to 4)"
              :disabled="chips.length >= 4" @click="fileEl?.click()">
              <NavIcon name="photo" />
            </button>
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
            <button type="submit" class="btn btn-primary icon-btn" :aria-label="uploading() ? 'Send (waiting for photos)' : 'Send'" :aria-disabled="!canSend">
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
.photos { display: flex; flex-wrap: wrap; gap: 6px; }
.msg.user .photos { justify-content: flex-end; }
.lightbox {
  width: min(92vw, 1100px); max-height: 92vh; padding: 12px; border: 0;
  flex-direction: column; gap: 10px; color: var(--text-primary);
}
.lightbox[open] { display: flex; }
.lightbox::backdrop { background: var(--bg-scrim); }
.lightbox-image { min-height: 120px; display: grid; place-items: center; overflow: hidden; }
.lightbox-image img { max-width: 100%; max-height: calc(92vh - 96px); object-fit: contain; border-radius: var(--r-md); }
.lightbox-bar { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.lightbox-actions { display: flex; gap: 8px; margin-left: auto; }
.mark { width: 24px; height: 24px; border-radius: 7px; }
.offer { align-self: flex-start; display: flex; flex-wrap: wrap; align-items: center; gap: 8px; max-width: 78%; }
.thinking {
  margin: 0; align-self: flex-start; display: flex; align-items: center; gap: 8px;
  padding: 8px 12px; border-radius: 10px; background: var(--bg-sunken);
}

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
.sources { display: flex; flex-direction: column; gap: 4px; overflow-wrap: anywhere; }
.sources ul { margin: 0; padding: 0; list-style: none; display: flex; flex-wrap: wrap; gap: 6px; }
.source-card {
  display: flex; flex-direction: column; gap: 2px; max-width: 280px; padding: 8px 12px;
  border-radius: var(--r-md); background: var(--bg-sunken); color: var(--text-primary); text-decoration: none;
}
.source-card:hover { background: var(--bg-hover); }
.source-card:focus-visible { outline: 2px solid var(--focus-ring); outline-offset: 2px; }
.source-title { color: var(--accent-text); overflow: hidden; text-overflow: ellipsis; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; }
.source-place { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.composer-wrap { padding: 16px 32px 20px; display: flex; flex-direction: column; gap: 8px; }
.composer { padding: 10px 10px 10px 16px; display: flex; flex-direction: column; gap: 8px; }
.chips { margin: 0; padding: 0; list-style: none; display: flex; gap: 8px; flex-wrap: wrap; }
.chip {
  position: relative; width: 64px; height: 64px; border-radius: var(--r-md); overflow: hidden;
  background: var(--bg-sunken); box-shadow: var(--shadow-border);
}
.chip img { width: 100%; height: 100%; object-fit: cover; display: block; }
.chip.failed img { opacity: 0.4; }
.chip-busy { position: absolute; inset: 0; display: grid; place-items: center; background: var(--bg-scrim); }
.chip-x {
  position: absolute; top: 3px; right: 3px; width: 22px; height: 22px; padding: 0; border: 0; border-radius: var(--r-pill);
  display: grid; place-items: center; font-size: 11px; line-height: 1; cursor: pointer;
  background: var(--bg-scrim); color: var(--text-on-accent);
}
.chip-x:focus-visible { outline: 2px solid var(--focus-ring); outline-offset: 1px; }
.composer:focus-within, .composer.dropping { box-shadow: var(--shadow-border), 0 0 0 2px var(--focus-ring); }
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
