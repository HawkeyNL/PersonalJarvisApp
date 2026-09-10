import test from "node:test";
import assert from "node:assert/strict";
import {ReconcileLoop} from "../src/reconcileLoop.ts";
function deferred(){let resolve;const promise=new Promise(r=>{resolve=r;});return{promise,resolve};}
test("reconnect during recovery runs once more before replaying events",async()=>{
  const loop=new ReconcileLoop();const first=deferred();let calls=0,flushes=0;
  const work=async()=>{calls++;if(calls===1)await first.promise;};
  const task=loop.request(work,()=>{flushes++;});await Promise.resolve();
  for(let i=0;i<100;i++)loop.request(work,()=>assert.fail("must retain original completion"));
  assert.equal(calls,1);assert.equal(flushes,0);assert.equal(loop.busy,true);
  first.resolve();await task;
  assert.equal(calls,2);assert.equal(flushes,1);assert.equal(loop.busy,false);
});
test("old session recovery cannot finish or flush a new session",async()=>{
  const loop=new ReconcileLoop();const old=deferred(),current=deferred();let flushes=0;
  const prior=loop.request(()=>old.promise,()=>assert.fail("stale flush"));await Promise.resolve();
  loop.reset();const next=loop.request(()=>current.promise,()=>{flushes++;});await Promise.resolve();
  old.resolve();await prior;assert.equal(loop.busy,true);assert.equal(flushes,0);
  current.resolve();await next;assert.equal(flushes,1);assert.equal(loop.busy,false);
});
test("failed recovery can be superseded by a successful reconnect round",async()=>{
  const loop=new ReconcileLoop();const gate=deferred();let calls=0,flushes=0;
  const work=async()=>{if(++calls===1){await gate.promise;throw Error("offline");}};
  const task=loop.request(work,()=>{flushes++;});await Promise.resolve();loop.request(work,()=>{});
  gate.resolve();await task;assert.equal(calls,2);assert.equal(flushes,1);
});
