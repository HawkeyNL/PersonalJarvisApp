import test from "node:test";
import assert from "node:assert/strict";
import { isWebModel, splitSources, sourcePlace, startsAfterReply, startsWebSearch } from "../src/webAnswer.ts";

const urls = (text) => splitSources(text).sources.map((s) => s.url);

test("only answers stored as web:<model> are web answers", () => {
  assert.ok(isWebModel("web:claude-opus-5"));
  for (const model of [null, undefined, "", "claude-opus-5", "research", "Web:x", "x web:y"]) assert.ok(!isWebModel(model));
});

test("the trailing Sources list becomes https links", () => {
  const text = "Rust 1.99 is out.\n\n**Sources:**\n- https://blog.rust-lang.org/2026/11/01/Rust-1.99.html\n* https://example.org\nhttps://news.example\n";
  assert.deepEqual(splitSources(text), {
    body: "Rust 1.99 is out.",
    sources: [{ url: "https://blog.rust-lang.org/2026/11/01/Rust-1.99.html" }, { url: "https://example.org/" }, { url: "https://news.example/" }],
  });
  assert.deepEqual(urls("A\nSources:\n- https://a.example\n- https://a.example"), ["https://a.example/"]);
  assert.deepEqual(splitSources("Het weer.\r\n## Bronnen\r\n- https://knmi.nl/a"), { body: "Het weer.", sources: [{ url: "https://knmi.nl/a" }] });
});

test("only plain https URLs with a host survive", () => {
  const bad = ["http://plain.example", "javascript:alert(1)", "https://user:pw@example.org", "https://a.example b",
    "[x](http://a.example)", "ftp://a.example", "https://", "see https://a.example"];
  assert.deepEqual(urls(`Answer\nSources:\n${bad.join("\n")}\n- https://ok.example/p?q=1`), ["https://ok.example/p?q=1"]);
});

test("the last Sources header counts and text without one is untouched", () => {
  assert.deepEqual(splitSources("No list here."), { body: "No list here.", sources: [] });
  assert.deepEqual(urls("Sources:\n- https://a.example\nMore\nSources\n- https://b.example"), ["https://b.example/"]);
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

test("titled sources keep a plain title under Core's URL rule", () => {
  // Core's own fixture (chat.rs https_sources_only test).
  const text = "Answer\nSources:\n" + [
    "- [Rust 1.97 released](https://blog.rust-lang.org/2026/rust-1.97)",
    "[Foo (bar) - Wiki](https://en.example.org/wiki/Foo)",
    "* [A](https://en.example.org/wiki/Foo_(bar))",
    "- [Plain title](http://plain.example/)",
    "- [Who](https://user:pw@evil.example/)",
    "- [](https://empty.example/)",
    "- [Spaced](https://example.org/a b)",
    "- [Click \u202Eevil](https://example.org/)",
    "- [Two](https://example.org/a) [Three](https://example.org/b)",
    "- [Nested [x]](https://example.org/)",
    "- [Tab\there](https://example.org/)",
    "- [Zero\u200Bwidth](https://example.org/)",
    "- [Line\u2028sep](https://example.org/)",
    "- [Soft\u00ADhyphen](https://example.org/)",
    "- [Unclosed](https://example.org/",
    "- [<b onmouseover=x>hi</b> &amp;](https://x.example/)",
    "- https://bare.example/p",
  ].join("\n");
  assert.deepEqual(splitSources(text), {
    body: "Answer",
    sources: [
      { url: "https://blog.rust-lang.org/2026/rust-1.97", title: "Rust 1.97 released" },
      { url: "https://en.example.org/wiki/Foo_(bar)", title: "A" },
      { url: "https://x.example/", title: "<b onmouseover=x>hi</b> &amp;" },
      { url: "https://bare.example/p" },
    ],
  });
  assert.equal(splitSources(`A\nSources:\n- [${"t".repeat(121)}](https://example.org/)`).sources.length, 0);
  assert.equal(splitSources(`A\nSources:\n- [${"é".repeat(120)}](https://example.org/)`).sources[0].title.length, 120);
  // Every hidden character of Core's validation::no_hidden_chars, at its edges.
  for (const hidden of ["\u0000", "\u009F", "\u00AD", "\u034F", "\u061C", "\u115F", "\u1160", "\u180E", "\u200B", "\u200F",
    "\u2028", "\u202E", "\u2060", "\u2065", "\u2069", "\u3164", "\uFE00", "\uFE0F", "\uFEFF", "\uFFF9", "\uFFFB",
    "\u{E0000}", "\u{E0001}", "\u{E007F}"]) {
    assert.equal(splitSources(`A\nSources:\n- [a${hidden}b](https://example.org/)`).sources.length, 0, hidden.codePointAt(0).toString(16));
  }
  // Their neighbours stay allowed, like in Core.
  for (const shown of ["\u00AE", "\u2027", "\u206A", "\uFE10", "\uFFFC", "\u{E0080}"]) {
    assert.equal(splitSources(`A\nSources:\n- [a${shown}b](https://example.org/)`).sources[0].title, `a${shown}b`);
  }
  // The first entry of a URL wins, titled or bare.
  assert.deepEqual(splitSources("A\nSources:\n- https://a.example/\n- [T](https://a.example/)").sources, [{ url: "https://a.example/" }]);
});

test("a card shows the host without www. and a short path", () => {
  assert.deepEqual(sourcePlace("https://www.example.org/"), { host: "example.org", path: "" });
  assert.deepEqual(sourcePlace("https://blog.rust-lang.org/2026/rust?x=1"), { host: "blog.rust-lang.org", path: "/2026/rust" });
  const long = sourcePlace(`https://a.example/${"p".repeat(60)}`).path;
  assert.equal(long.length, 40);
  assert.ok(long.endsWith("…"));
  assert.equal(sourcePlace("https://www2.example/x").host, "www2.example");
  assert.equal(sourcePlace("https://bücher.example/").host, "xn--bcher-kva.example");
});
