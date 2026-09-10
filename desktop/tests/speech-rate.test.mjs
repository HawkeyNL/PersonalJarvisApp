import test from "node:test";
import assert from "node:assert/strict";
import {speechRate} from "../src/speechRate.ts";
test("device speech rate bounds invalid preferences without enabling voice",()=>{
  for(const value of [NaN,Infinity,-Infinity,undefined,null,"fast"]){assert.equal(speechRate(value),1);}
  assert.equal(speechRate(-100),0.5);assert.equal(speechRate(100),2);assert.equal(speechRate(1.25),1.25);
});
