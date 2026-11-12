// Pure presentation helpers shared by the store and behavioral tests.
export interface ProjectedRow { id:number; canonicalId?:string; requestId?:string; runId?:string; text:string }
export function mergeCanonical<T extends ProjectedRow>(rows:T[], row:T):T[] {
  const matches=(item:T)=>item.canonicalId===row.canonicalId || !!(row.runId && item.runId===row.runId) || !!(row.requestId && item.requestId===row.requestId);
  const index=rows.findIndex(matches);
  if(index<0) return [...rows,row];
  const canonical={...row,id:rows[index].id};
  return rows.flatMap((item,i)=>i===index?[canonical]:matches(item)?[]:[item]);
}
/** Drops a failed run's empty placeholder row; streamed text stays. */
export function dropFailedRun<T extends ProjectedRow>(rows:T[],runId:string):T[] {
  return rows.filter(row=>row.runId!==runId || !!row.text);
}
export function appendVisualDelta<T extends ProjectedRow>(rows:T[],runId:string,text:string):T[] {
  return rows.map(row=>row.runId===runId && !row.canonicalId && row.text.length+text.length<=128*1024?{...row,text:row.text+text}:row);
}
/** The runs API request for a new user turn. Photos are part of the request
 *  (and so of its idempotency hash); a text-only request keeps its old shape. */
export function runRequest(requestId:string,conversationId:string|null,history:{role:string;text:string}[],mode?:"research",attachments:string[]=[]) {
  return {request_id:requestId,conversation_id:conversationId,messages:history.map(m=>({role:m.role==="jarvis"?"assistant":"user",content:m.text})),...(mode?{mode}:{}),...(attachments.length?{attachments}:{})};
}
