// Conversation state with Jarvis. The brain is the backend `/v1/assistant/chat`
// endpoint (DEC-001 = Claude, provider-abstracted with an Ollama fallback). The
// API key lives only in the backend — never here.
//
// Chats are persisted server-side and grouped into conversations ("tabs",
// ADR-030): every turn is stored, and when you switch topic Jarvis splits the
// thread into a new conversation. This module owns the *current* conversation's
// messages; the tab list lives in `conversations.ts`.
import { ref } from "vue";
import { canSpeak, voiceRate, restoreVoiceChoice } from "./voice";
import { ReconcileLoop } from "./reconcileLoop";
import { invoke } from "@tauri-apps/api/core";
import { startRealtime, type RealtimeEvent, type CanonicalMessage } from "./realtime";
import { mergeCanonical, appendVisualDelta } from "./realtimeProjection";
import { PendingRuns } from "./pendingRuns";
import { chatSession } from "./chatSession";
import { currentAuthStatus } from "./auth";
import { postJsonAuth, getJsonAuth } from "./api";
import {
  currentId,
  setCurrent,
  savedCurrentId,
  loadConversations,
  conversations,
} from "./conversations";

export type Role = "user" | "jarvis";
export interface Msg {
  id: number;
  role: Role;
  text: string;
  ts: string;
  spoken: boolean;
  canonicalId?: string;
  runId?: string;
  requestId?: string;
}

export const messages = ref<Msg[]>([]);
export const thinking = ref(false); // true while the brain is generating a reply
let idc = 0;
let realtimeAvailable=false;
const pending=new PendingRuns();
const reconciliation=new ReconcileLoop();
let buffered:RealtimeEvent[]=[];
chatSession.onReset(()=>{pending.clear();messages.value=[];thinking.value=false;buffered=[];reconciliation.reset();realtimeAvailable=false;});

function upsertCanonical(message:CanonicalMessage,requestId?:string,runId?:string) {
  if(currentId.value!==message.conversation_id) return;
  const row={id:idc++,role:(message.role==="assistant"?"jarvis":"user") as Role,text:message.content,ts:message.created_at.slice(11,16),spoken:false,canonicalId:message.id,runId,requestId};
  messages.value=mergeCanonical(messages.value,row);
}

async function reconcileRealtime() {
  const epoch=chatSession.capture();
  return reconciliation.request(async()=>{
    await loadConversations();
    if(!chatSession.current(epoch)) return;
    const selected=currentId.value;
    if(selected) await reloadConversation(selected);
    // Recover by run ID or original request ID. Never POST to recover an event.
    // At most 32 bounded requests, concurrently so one offline timeout does
    // not multiply into minutes of blocked event reconciliation.
    await Promise.all(pending.entries().map(async ([requestId,local])=>{
      try {
        const path=local.runId ? `/v1/assistant/runs/${local.runId}` : `/v1/assistant/requests/${requestId}`;
        const result=await getJsonAuth<{run_id:string;request_id:string;conversation_id:string;state:string}>(path);
        if(!chatSession.current(epoch)) return;
        if(result.request_id===requestId && (!local.runId || result.run_id===local.runId)) {
          pending.acknowledge(requestId,result.conversation_id,result.run_id);
          pending.reconcile(requestId,result.run_id,result.state);
        }
      } catch { /* Keep ambiguous metadata; do not automatically regenerate. */ }
    }));
  },()=>{
    if(chatSession.current(epoch)) {
      const events=buffered;buffered=[];
      for(const event of events) receiveRealtime(event);
    }
  });
}

function receiveRealtime(event:RealtimeEvent) {
  if(event.type==="connection.ready") {
    // Old socket buffers must not overwrite the new authoritative snapshot.
    buffered=[];
    void reconcileRealtime().catch(()=>{});return;
  }
  if(reconciliation.busy) {
    if(buffered.length<256) buffered.push(event);
    else {buffered=[];void invoke("realtime_stop").then(()=>invoke("realtime_start"));}
    return;
  }
  switch(event.type) {
    case "conversation.created":case "conversation.updated": {
      const metadata=event.payload;
      conversations.value=[metadata,...conversations.value.filter(c=>c.id!==metadata.id)].sort((a,b)=>b.updated_at.localeCompare(a.updated_at));
      break;
    }
    case "conversation.deleted":
      conversations.value=conversations.value.filter(c=>c.id!==event.payload.conversation_id);
      if(currentId.value===event.payload.conversation_id) startNewConversation();
      break;
    case "message.created": {
      const local=pending.get(event.payload.request_id);
      if(local && local.conversation===null && currentId.value===null && messages.value.some(m=>m.id===local.messageId)) setCurrent(event.payload.message.conversation_id);
      upsertCanonical(event.payload.message,event.payload.request_id);
      pending.acknowledge(event.payload.request_id,event.payload.message.conversation_id);
      break;
    }
    case "assistant.started":
      pending.acknowledge(event.payload.request_id,event.payload.conversation_id,event.payload.run_id);
      if(currentId.value===event.payload.conversation_id) {
        thinking.value=true;
        if(!messages.value.some(m=>m.runId===event.payload.run_id)) messages.value.push({id:idc++,role:"jarvis",text:"",ts:stamp(),spoken:false,runId:event.payload.run_id});
      }
      break;
    case "assistant.delta": {
      if(currentId.value===event.payload.run.conversation_id) {
        thinking.value=true;
        if(!messages.value.some(m=>m.runId===event.payload.run.run_id)) messages.value.push({id:idc++,role:"jarvis",text:"",ts:stamp(),spoken:false,runId:event.payload.run.run_id});
        messages.value=appendVisualDelta(messages.value,event.payload.run.run_id,event.payload.text);
      }
      break;
    }
    case "assistant.completed":
      upsertCanonical(event.payload.message,undefined,event.payload.run.run_id);
      pending.delete(event.payload.run.request_id);
      if(currentId.value===event.payload.run.conversation_id) thinking.value=false;
      break;
    case "assistant.failed":
      pending.delete(event.payload.run.request_id);
      if(currentId.value===event.payload.run.conversation_id) {thinking.value=false;push("jarvis","Antwoord onderbroken. Je bericht is opgeslagen; er wordt niet automatisch opnieuw gegenereerd.");}
      break;
  }
}

function stamp(): string {
  const d = new Date();
  const p = (n: number) => String(n).padStart(2, "0");
  return `${p(d.getHours())}:${p(d.getMinutes())}`;
}

function push(role: Role, text: string, spoken = false) {
  messages.value.push({ id: idc++, role, text, ts: stamp(), spoken });
}

interface ChatReply {
  reply: string;
  model: string | null;
  stop_reason: string | null;
  conversation_id?: string;
  conversation_title?: string;
  new_topic?: boolean;
}

// Only send the most recent turns so a long chat can't grow the request (and
// token cost) without bound. The system prompt is added server-side.
const MAX_TURNS = 20;

async function ask(epoch:number): Promise<ChatReply> {
  const status = await currentAuthStatus();
  if (!status.authenticated || !chatSession.current(epoch)) throw new Error("niet ingelogd");
  const history = messages.value.slice(-MAX_TURNS).map((m) => ({
    role: m.role === "jarvis" ? "assistant" : "user",
    content: m.text,
  }));
  return await postJsonAuth<ChatReply>("/v1/assistant/chat", {
    messages: history,
    conversation_id: currentId.value,
  });
}

/** Load a conversation's history into the view and make it the current tab. */
export async function openConversation(id: string): Promise<void> {
  setCurrent(id);
  await reloadConversation(id);
}

async function reloadConversation(id:string):Promise<void> {
  const epoch=chatSession.capture();
  const status = await currentAuthStatus();
  if (!status.authenticated || !chatSession.current(epoch)) return;
  const res = await getJsonAuth<{
    id: string;
    title: string;
    messages: { id?:string; role: string; content: string; model: string | null; at: string }[];
    assistant_running?:boolean;
  }>(`/v1/conversations/${id}`);
  if(currentId.value!==id || !chatSession.current(epoch)) return;
  thinking.value=res.assistant_running===true;
  messages.value = res.messages.map((m) => ({
    id: idc++,
    role: m.role === "assistant" ? "jarvis" : "user",
    text: m.content,
    ts: m.at?.slice(11) || stamp(), // "YYYY-MM-DD HH:MM" → "HH:MM"
    spoken: false,
    canonicalId:m.id,
  }));
}

/** Start a fresh conversation: the next message opens a new tab server-side. */
export function startNewConversation(): void {
  setCurrent(null);
  messages.value = [];
  thinking.value = false;
}

/** On launch, restore the tab list and reopen the last (or most recent) chat,
 *  so the conversation is right there after an app restart. */
export async function initChat(): Promise<void> {
  const epoch=chatSession.capture();
  try {
    await loadConversations();
    if(!chatSession.current(epoch)) return;
    const saved = savedCurrentId();
    const exists = saved && conversations.value.some((c) => c.id === saved);
    const target = exists ? saved! : (conversations.value[0]?.id ?? null);
    if (target) await openConversation(target);
    else startNewConversation();
    if(!chatSession.current(epoch)) return;
    try {
      const capability=await getJsonAuth<{protocol:number;asynchronous_chat:boolean}>("/v1/events/capability");
      if(!chatSession.current(epoch)) return;
      realtimeAvailable=capability.protocol===1 && capability.asynchronous_chat;
      if(realtimeAvailable) {
        await startRealtime(event=>{if(chatSession.current(epoch)) receiveRealtime(event);});
        if(!chatSession.current(epoch)) return;
        await invoke("realtime_voice_rate",{rate:voiceRate.value});
        await restoreVoiceChoice();
        await invoke("realtime_voice_enabled",{enabled:canSpeak().allowed});
      }
    } catch {if(chatSession.current(epoch)) realtimeAvailable=false;}
  } catch {
    // Offline or not logged in yet — leave the chat empty; it'll load later.
  }
}

/** Send a user message; Jarvis replies (and speaks if the policy allows). */
export async function send(input: string): Promise<void> {
  const epoch=chatSession.capture();
  const t = input.trim();
  if (!t || thinking.value) return;
  if(realtimeAvailable && pending.full) {
    push("jarvis","Te veel onbevestigde verzoeken. Herstel eerst de verbinding; er wordt niets opnieuw verstuurd.");
    return;
  }
  push("user", t);
  thinking.value = true;
  if(realtimeAvailable) {
    const requestId=crypto.randomUUID();
    const optimistic=messages.value[messages.value.length-1];
    optimistic.requestId=requestId;
    pending.set(requestId,{conversation:currentId.value,messageId:optimistic.id});
    const request={request_id:requestId,conversation_id:currentId.value,messages:messages.value.slice(-MAX_TURNS).map(m=>({role:m.role==="jarvis"?"assistant":"user",content:m.text}))};
    try {
      const result=await postJsonAuth<{conversation_id:string;run_id:string}>("/v1/assistant/runs",request);
      if(!chatSession.current(epoch)) return;
      const local=pending.get(requestId);
      if(local?.conversation===null && currentId.value===null && messages.value.some(m=>m.id===local.messageId)) setCurrent(result.conversation_id);
      pending.acknowledge(requestId,result.conversation_id,result.run_id);
      // Completion arrives by event; a lost socket is reconciled from REST.
    } catch {
      if(!chatSession.current(epoch)) return;
      thinking.value=false;
      push("jarvis","Verzending niet bevestigd. Niet automatisch opnieuw verstuurd; verbind opnieuw om de opgeslagen geschiedenis te controleren.");
    }
    return;
  }
  const hadHistory = messages.value.length > 1;
  try {
    const res = await ask(epoch);
    if(!chatSession.current(epoch)) return;
    const spoken = false; // Legacy Core has no authoritative voice owner.

    // The server may have placed this turn in a different conversation: the very
    // first message (no tab yet) or a mid-chat topic split. Follow it.
    const moved = !!res.conversation_id && res.conversation_id !== currentId.value;
    if (moved) setCurrent(res.conversation_id!);

    if (res.new_topic && moved && hadHistory) {
      // A topic split: reload the new thread so the tab shows only its own turns.
      await openConversation(res.conversation_id!);
    } else {
      push("jarvis", res.reply, spoken);
    }
    // Refresh the tab list (new tab / updated title + order).
    void loadConversations();
  } catch (e) {
    if(!chatSession.current(epoch)) return;
    const detail = e instanceof Error ? e.message : "onbekende fout";
    push(
      "jarvis",
      `Mijn brein is even niet bereikbaar (${detail}). Controleer JARVIS_LLM_API_KEY in de backend of start Ollama lokaal.`,
      false,
    );
  } finally {
    if(chatSession.current(epoch)) thinking.value = false;
  }
}
