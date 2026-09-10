import test from "node:test";
import assert from "node:assert/strict";
import {ChatSession} from "../src/chatSession.ts";
import {PendingRuns} from "../src/pendingRuns.ts";

test("session invalidation clears metadata and rejects late completions",async()=>{
  const session=new ChatSession();
  const pending=new PendingRuns();
  const view=[];
  session.onReset(()=>{pending.clear();view.length=0;});
  pending.set("old",{conversation:"old",messageId:1});
  view.push("old message");
  const epoch=session.capture();
  let complete;
  const response=new Promise(resolve=>{complete=resolve;});
  const task=(async()=>{await response;if(session.current(epoch))view.push("late old result");})();
  session.invalidate();
  pending.set("new",{conversation:"new",messageId:2});
  view.push("new message");
  complete();await task;
  assert.deepEqual(view,["new message"]);
  assert.equal(pending.get("old"),undefined);
  assert.equal(pending.get("new").messageId,2);
});
test("returning to the same server does not restore an old session epoch",()=>{
  const session=new ChatSession();
  const old=session.capture();
  let count=0;const unsubscribe=session.onReset(()=>count++);
  session.invalidate();session.invalidate();
  assert.equal(session.current(old),false);
  assert.equal(count,2);
  unsubscribe();session.invalidate();assert.equal(count,2);
});
