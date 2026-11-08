import test from "node:test";
import assert from "node:assert/strict";
import { isWebModel, splitSources, startsAfterReply, startsWebSearch } from "../src/webAnswer.ts";

test("only answers stored as web:<model> are web answers", () => {
  assert.ok(isWebModel("web:claude-opus-5"));
  for (const model of [null, undefined, "", "claude-opus-5", "research", "Web:x", "x web:y"]) assert.ok(!isWebModel(model));
});

test("the trailing Sources list becomes https links", () => {
  const text = "Rust 1.99 is out.\n\n**Sources:**\n- https://blog.rust-lang.org/2026/11/01/Rust-1.99.html\n* https://example.org\nhttps://news.example\n";
  assert.deepEqual(splitSources(text), {
    body: "Rust 1.99 is out.",
    sources: ["https://blog.rust-lang.org/2026/11/01/Rust-1.99.html", "https://example.org/", "https://news.example/"],
  });
  assert.deepEqual(splitSources("A\nSources:\n- https://a.example\n- https://a.example").sources, ["https://a.example/"]);
  assert.deepEqual(splitSources("Het weer.\r\n## Bronnen\r\n- https://knmi.nl/a"), { body: "Het weer.", sources: ["https://knmi.nl/a"] });
});

test("only plain https URLs with a host survive", () => {
  const bad = ["http://plain.example", "javascript:alert(1)", "https://user:pw@example.org", "https://a.example b",
    "[x](https://a.example)", "ftp://a.example", "https://", "see https://a.example"];
  assert.deepEqual(splitSources(`Answer\nSources:\n${bad.join("\n")}\n- https://ok.example/p?q=1`).sources, ["https://ok.example/p?q=1"]);
});

test("the last Sources header counts and text without one is untouched", () => {
  assert.deepEqual(splitSources("No list here."), { body: "No list here.", sources: [] });
  assert.deepEqual(splitSources("Sources:\n- https://a.example\nMore\nSources\n- https://b.example").sources, ["https://b.example/"]);
  assert.deepEqual(splitSources("Our sources: many\nhttps://a.example"), { body: "Our sources: many\nhttps://a.example", sources: [] });
});

test("a run right after a finished reply is Core's own web search", () => {
  const user = { role: "user", text: "Weer morgen?" };
  const reply = { role: "jarvis", text: "Probably dry." };
  assert.ok(startsAfterReply([user, reply]));
  // Our own, another device's or a clicked research run follows a question.
  assert.ok(!startsAfterReply([user]));
  assert.ok(!startsAfterReply([reply, user]));
  assert.ok(!startsAfterReply([user, { role: "jarvis", text: "" }]));
  assert.ok(!startsAfterReply([]));
});

test("Core's web_search marker names the search; older Cores fall back to the heuristic", () => {
  const user = { role: "user", text: "Weer morgen?" };
  const reply = { role: "jarvis", text: "Probably dry." };
  assert.ok(startsWebSearch({ web_search: true }, [user]));
  assert.ok(startsWebSearch({ web_search: true }, []));
  assert.ok(startsWebSearch({}, [user, reply]));
  assert.ok(!startsWebSearch({}, [user]));
  assert.ok(!startsWebSearch({ web_search: false }, [reply, user]));
});
