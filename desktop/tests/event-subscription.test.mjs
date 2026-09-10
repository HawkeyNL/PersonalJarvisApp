import test from "node:test";
import assert from "node:assert/strict";
import {EventSubscription} from "../src/eventSubscription.ts";

function deferred(){let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b;});return{promise,resolve,reject};}

test("late registration cannot replace a newer listener or start a stale connection",async()=>{
  const subscription=new EventSubscription();
  const old=deferred();const seen=[];let oldReceive,newReceive,disposed=0,starts=0;
  const first=subscription.replace(receive=>{oldReceive=receive;return old.promise;},value=>seen.push(value),async()=>{starts++;});
  await subscription.replace(async receive=>{newReceive=receive;return()=>{disposed++;};},value=>seen.push(value),async()=>{starts++;});
  old.resolve(()=>{disposed++;});await first;
  oldReceive("stale");newReceive("current");
  assert.deepEqual(seen,["current"]);assert.equal(starts,1);assert.equal(disposed,1);
  subscription.clear();newReceive("after logout");
  assert.deepEqual(seen,["current"]);assert.equal(disposed,2);
});

test("logout during registration disposes its result without starting",async()=>{
  const subscription=new EventSubscription();const pending=deferred();let disposed=0,starts=0;
  const task=subscription.replace(()=>pending.promise,()=>{},async()=>{starts++;});
  subscription.clear();pending.resolve(()=>{disposed++;});await task;
  assert.equal(disposed,1);assert.equal(starts,0);
});

test("a failed old native start does not remove the new listener",async()=>{
  const subscription=new EventSubscription();const start=deferred();let receiveNew,disposedNew=0;
  const first=subscription.replace(async()=>()=>{},()=>{},()=>start.promise);
  await Promise.resolve();
  await subscription.replace(async receive=>{receiveNew=receive;return()=>{disposedNew++;};},()=>{},async()=>{});
  start.reject(new Error("start failed"));await assert.rejects(first,/start failed/);
  assert.equal(typeof receiveNew,"function");assert.equal(disposedNew,0);
  subscription.clear();assert.equal(disposedNew,1);
});
