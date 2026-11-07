import test from "node:test";
import assert from "node:assert/strict";
import {offerFrom} from "../src/researchOffer.ts";

const run={run_id:"r",request_id:"q",conversation_id:"c"};

test("an offer carries the owner's question for its own conversation",()=>{
  assert.deepEqual(offerFrom({run,research_offer:{question:" weer morgen? "}}),{conversationId:"c",question:"weer morgen?"});
});

test("older Cores and empty offers show no button",()=>{
  assert.equal(offerFrom({run}),null);
  assert.equal(offerFrom({run,research_offer:{question:"   "}}),null);
});
