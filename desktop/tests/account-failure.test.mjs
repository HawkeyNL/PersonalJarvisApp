import test from "node:test";
import assert from "node:assert/strict";
import { accountFailure } from "../src/accountFailure.ts";

test("activation failures distinguish status and never echo secrets or server text", () => {
  for (const status of [400,401,403,429,503]) {
    const text = accountFailure({name:"ApiError",status,path:"/v1/auth/bootstrap",message:"fixture-secret"});
    assert.match(text, /First activation/);
    assert.ok(text.includes(`HTTP ${status}`));
    assert.ok(!text.includes("fixture-secret"));
  }
  assert.match(accountFailure({name:"ApiError",status:401,path:"/v1/auth/login"}), /Sign-in/);
  assert.match(accountFailure({name:"NetworkError"}), /No response/);
  for(const error of [null,undefined,new Error("fixture-secret"),"fixture-secret"]) {
    assert.ok(!accountFailure(error).includes("fixture-secret"));
  }
});
