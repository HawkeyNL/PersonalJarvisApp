// Voice output policy + TTS. Decides WHEN Jarvis may speak aloud.
//
// The audio route (earbud vs built-in speaker) decides speech, not the input
// modality — so Jarvis speaks through your earbud even when you type back.
// See docs/blueprint/voice/CONVERSATION_AND_OUTPUT_POLICY.md and decisions/ADR-021.
import { ref } from "vue";
import { invoke } from "@tauri-apps/api/core";

export type AudioRoute = "headset" | "speaker" | "unknown";

const VKEY = "jarvis.voice.enabled";
const SKEY = "jarvis.voice.allowSpeaker";
const HKEY = "jarvis.voice.headset";

// New devices stay silent until explicitly enabled.
export const voiceEnabled = ref(localStorage.getItem(VKEY) === "true");
// Allow speaking on the open speaker route (default off — stay quiet in the open).
export const allowSpeaker = ref(localStorage.getItem(SKEY) === "true");
// Manual "earbud in" override until native route detection lands.
export const headset = ref(localStorage.getItem(HKEY) === "true");
// Detected route.
export const route = ref<AudioRoute>("unknown");

export function setVoiceEnabled(v: boolean) {
  voiceEnabled.value = v;
  localStorage.setItem(VKEY, String(v));
  syncNativeVoice();
}
export function setAllowSpeaker(v: boolean) {
  allowSpeaker.value = v;
  localStorage.setItem(SKEY, String(v));
  syncNativeVoice();
}
export function setHeadset(v: boolean) {
  headset.value = v;
  localStorage.setItem(HKEY, String(v));
  syncNativeVoice();
}

/** Ask the native layer for the route; fall back to the manual toggle. */
export async function refreshRoute(): Promise<AudioRoute> {
  try {
    const r = await invoke<string>("audio_output_route");
    if (r === "headset" || r === "speaker") {
      route.value = r;
      return route.value;
    }
  } catch {
    /* command not available yet — use the manual override */
  }
  route.value = headset.value ? "headset" : "unknown";
  return route.value;
}

function isPrivateRoute(): boolean {
  if (route.value === "headset") return true;
  if (route.value === "speaker") return false;
  return headset.value; // unknown → honour manual toggle
}

/** The policy: may Jarvis speak right now, and why? */
export function canSpeak(): { allowed: boolean; reason: string } {
  if (!voiceEnabled.value) return { allowed: false, reason: "spraak uit" };
  if (isPrivateRoute()) return { allowed: true, reason: "oortje verbonden" };
  if (allowSpeaker.value) return { allowed: true, reason: "luidspreker toegestaan" };
  return { allowed: false, reason: "geen oortje — stil" };
}

/** Speak text if the policy allows. Returns whether it spoke. */
export function speak(text: string): boolean {
  // Only native canonical events may feed local TTS. No browser/cloud voice.
  void text;
  return false;
}

function syncNativeVoice():void {
  void invoke("realtime_voice_enabled",{enabled:canSpeak().allowed}).catch(()=>{});
}

export function stopSpeaking(): void {
  // Stop this speech run, not the saved preference or shared inference.
  void invoke("realtime_stop_speech").catch(()=>{});
}
