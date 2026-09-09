// Presentation-only projection of client-core's native decoded event contract.
// Socket transport and bearer credentials are exclusively native Rust.
import { invoke } from "@tauri-apps/api/core";
import { listen, type UnlistenFn } from "@tauri-apps/api/event";
export interface Run { run_id:string; request_id:string; conversation_id:string }
export interface CanonicalMessage { id:string; conversation_id:string; role:"user"|"assistant"; content:string; model:string|null; created_at:string }
export interface Metadata { id:string; title:string; updated_at:string }
type Payloads = {
  "connection.ready":{device_id:string;reconcile:boolean};
  "conversation.created":Metadata;
  "conversation.updated":Metadata;
  "conversation.deleted":{conversation_id:string};
  "message.created":{request_id:string;message:CanonicalMessage};
  "assistant.started":Run;
  "assistant.delta":{run:Run;text:string};
  "assistant.completed":{run:Run;message:CanonicalMessage};
  "assistant.failed":{run:Run;reason:string};
  "voice.owner_changed":{device_id:string|null;run_id:string|null};
  "voice.started":{device_id:string;run_id:string};
  "voice.stopped":{device_id:string;run_id:string};
  "voice.failed":{device_id:string;run_id:string};
};
export type RealtimeEvent = {[K in keyof Payloads]:{protocol:1;epoch:string;sequence:number;event_id:string;type:K;payload:Payloads[K]}}[keyof Payloads];
let unlisten:UnlistenFn|undefined;
export async function startRealtime(receive:(event:RealtimeEvent)=>void):Promise<void> {
  unlisten?.();
  unlisten=await listen<RealtimeEvent>("jarvis-realtime",event=>receive(event.payload));
  await invoke("realtime_start");
}
