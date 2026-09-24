import assert from "node:assert/strict";
import test from "node:test";
import { createSerialPoller } from "../src/serialPoller.ts";

function fakeClock() {
  const timers = new Map();
  let nextId = 0;
  return {
    timers,
    schedule(callback, delay) {
      const id = ++nextId;
      timers.set(id, { callback, delay });
      return id;
    },
    cancel(id) { timers.delete(id); },
    fire() {
      const [id, timer] = timers.entries().next().value;
      timers.delete(id);
      timer.callback();
      return timer.delay;
    },
  };
}

const settle = () => new Promise(resolve => setImmediate(resolve));

test("pairing poll is single-flight and uses a one-minute interval", async () => {
  const clock = fakeClock();
  let calls = 0;
  const poller = createSerialPoller(async () => { calls++; }, 60_000, clock.schedule, clock.cancel);
  poller.start();
  poller.start();
  assert.equal(calls, 1);
  await settle();
  assert.equal(clock.timers.size, 1);
  assert.equal(clock.fire(), 60_000);
  assert.equal(calls, 2);
  await settle();
  poller.stop();
  assert.equal(clock.timers.size, 0);
});

test("stop then restart during a pending request cannot overlap polls", async () => {
  const clock = fakeClock();
  let release;
  let calls = 0;
  const poller = createSerialPoller(() => {
    calls++;
    return calls === 1 ? new Promise(resolve => { release = resolve; }) : Promise.resolve();
  }, 60_000, clock.schedule, clock.cancel);
  poller.start();
  poller.stop();
  poller.start();
  assert.equal(calls, 1);
  release();
  await settle();
  assert.equal(clock.timers.size, 1);
  clock.fire();
  assert.equal(calls, 2);
  poller.stop();
});

test("a failed read does not permanently stop pairing checks", async () => {
  const clock = fakeClock();
  let calls = 0;
  const poller = createSerialPoller(async () => {
    if (++calls === 1) throw new Error("temporary network failure");
  }, 60_000, clock.schedule, clock.cancel);
  poller.start();
  await settle();
  assert.equal(clock.timers.size, 1);
  clock.fire();
  assert.equal(calls, 2);
  poller.stop();
});
