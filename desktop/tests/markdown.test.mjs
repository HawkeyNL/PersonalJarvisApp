import assert from "node:assert/strict";
import test from "node:test";

import { renderMarkdown } from "../src/markdown.ts";

test("markdown images become plain links, never <img>", () => {
  const html = renderMarkdown('![leak](https://example.com/p.png?d=secret "t") and ![](https://example.com/x.png)');
  assert.doesNotMatch(html, /<img/i);
  assert.match(html, /<a href="https:\/\/example\.com\/p\.png\?d=secret">leak<\/a>/);
  assert.match(html, /<a href="https:\/\/example\.com\/x\.png">https:\/\/example\.com\/x\.png<\/a>/);
});

test("image alt text and URLs are escaped", () => {
  const html = renderMarkdown('![<b>"x"</b>](https://example.com/a"b.png)');
  assert.doesNotMatch(html, /<img|<b>/i);
  assert.doesNotMatch(html, /href="[^"]*"b/);
});

test("raw HTML and script URLs stay inert", () => {
  const html = renderMarkdown('<img src=x onerror=alert(1)> [x](javascript:alert(1)) ![y](javascript:alert(1))');
  assert.doesNotMatch(html, /<img|href="javascript/i);
});

test("tables get the Instrument table style in a scrolling card", () => {
  const html = renderMarkdown("| Instrument | Today |\n| --- | ---: |\n| MES | +42.10 |");
  assert.match(html, /^<div class="card md-table"><table class="tbl">/);
  assert.match(html, /<\/table><\/div>\n$/);
  assert.match(html, /<td style="text-align:right">\+42\.10<\/td>/);
});
