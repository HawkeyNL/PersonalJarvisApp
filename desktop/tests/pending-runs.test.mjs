import test from "node:test";
import assert from "node:assert/strict";
import {PendingRuns} from "../src/pendingRuns.ts";

test("unknown outcomes are bounded and never evicted to allow new paid work",()=>{
  const runs=new PendingRuns(2);
  assert.equal(runs.set("a",{conversation:null,messageId:1}),true);
  assert.equal(runs.set("b",{conversation:"c",messageId:2}),true);
  assert.equal(runs.set("c",{conversation:null,messageId:3}),false);
  assert.equal(runs.full,true);
  assert.equal(runs.get("a").messageId,1);
  assert.equal(runs.entries().length,2);
});
test("late HTTP acknowledgement cannot resurrect a completed run",()=>{
  const runs=new PendingRuns();
  runs.set("request",{conversation:null,messageId:1});
  runs.acknowledge("request","conversation","run");
  assert.equal(runs.reconcile("request","different-run","completed"),false);
  assert.equal(runs.reconcile("request","run","unknown"),false);
  assert.equal(runs.reconcile("request","run","running"),false);
  assert.equal(runs.reconcile("request","run","completed"),true);
  runs.acknowledge("request","conversation","run");
  assert.equal(runs.get("request"),undefined);
});
test("reconciliation snapshots cannot mutate pending state",()=>{
  const runs=new PendingRuns();
  runs.set("request",{conversation:null,messageId:1});
  runs.acknowledge("request","conversation","run");
  runs.entries()[0][1].conversation="other";
  runs.get("request").messageId=99;
  assert.equal(runs.get("request").conversation,"conversation");
  assert.equal(runs.get("request").messageId,1);
  assert.equal(runs.reconcile("request","run","interrupted"),true);
});
