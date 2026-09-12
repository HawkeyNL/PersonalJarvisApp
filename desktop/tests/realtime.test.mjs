import test from "node:test";
import assert from "node:assert/strict";
import {mergeCanonical,appendVisualDelta} from "../src/realtimeProjection.ts";
import {readFile} from "node:fs/promises";

test("canonical completion replaces stream and reconciled REST duplicate",()=>{
  const rows=[{id:1,canonicalId:"message",text:"canonical"},{id:2,runId:"run",text:"partial"}];
  const final=mergeCanonical(rows,{id:3,canonicalId:"message",runId:"run",text:"canonical"});
  assert.equal(final.length,1);
  assert.equal(final[0].text,"canonical");
  assert.equal(final[0].id,1);
  assert.deepEqual(appendVisualDelta(final,"run","late duplicate"),final);
});
test("optimistic user message correlates by request id",()=>{
  const final=mergeCanonical([{id:1,requestId:"request",text:"hello"}],{id:2,requestId:"request",canonicalId:"server-id",text:"hello"});
  assert.equal(final.length,1); assert.equal(final[0].id,1); assert.equal(final[0].canonicalId,"server-id");
});
test("deltas are run scoped and bounded",()=>{
  const rows=[{id:1,runId:"a",text:"One"},{id:2,runId:"b",text:"Other"}];
  assert.deepEqual(appendVisualDelta(rows,"a"," answer").map(r=>r.text),["One answer","Other"]);
  assert.deepEqual(appendVisualDelta(rows,"a","x".repeat(129*1024)),rows);
});
test("frontend receives native events, not a websocket bearer",async()=>{
  const source=await readFile(new URL("../src/realtime.ts",import.meta.url),"utf8");
  assert.doesNotMatch(source,/new WebSocket|Authorization|Bearer|token:/);
  assert.match(source,/listen<RealtimeEvent>/);
});
