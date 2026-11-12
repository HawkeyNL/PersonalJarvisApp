// Web-derived answers. Core stores a Research answer with model "web:<model>"
// and ends it with a "Sources:" (or "Bronnen:") list of https URLs.

export const isWebModel = (model: string | null | undefined) => !!model?.startsWith("web:");

const SOURCES_HEADER = /^(sources|bronnen)$/i;

/** One Sources entry: an https URL, and the title Core's research gave it. */
export interface Source { url: string; title?: string }

// Bidi controls, zero-width, line/paragraph separators and other invisible
// format characters (Core's list): a title could hide or reorder text.
const INVISIBLE = /[\u00AD\u061C\u200B-\u200F\u2028-\u202E\u2060-\u2064\u2066-\u2069\uFEFF]/u;

/** A whole `[Title](url)` line, like Core: a plain title of 1 to 120
 *  characters without brackets, parentheses, control or invisible format
 *  characters. The title is only ever shown as text. */
function titledLink(candidate: string): { title: string; url: string } | null {
  if (!candidate.startsWith("[")) return null;
  const end = candidate.indexOf("](");
  if (end < 0 || !candidate.endsWith(")")) return null;
  const title = candidate.slice(1, end);
  const length = [...title].length;
  if (length < 1 || length > 120 || /[[\]()]|\p{Cc}/u.test(title) || INVISIBLE.test(title)) return null;
  return { title, url: candidate.slice(end + 2, -1) };
}

/** One https URL with a host and no user info, bare or titled, like Core's
 *  Sources filter. */
function httpsSource(line: string): Source | null {
  const candidate = line.trim().replace(/^[-*]+\s*/, "");
  const titled = titledLink(candidate);
  const text = titled?.url ?? candidate;
  if (!text || /\s/.test(text)) return null;
  try {
    const url = new URL(text);
    if (url.protocol !== "https:" || !url.hostname || url.username || url.password) return null;
    return titled ? { url: url.href, title: titled.title } : { url: url.href };
  } catch { return null; }
}

/** The answer without its trailing Sources list, and that list's distinct https
 *  sources (the first of a URL wins). Without a Sources header the whole text
 *  is the body. */
export function splitSources(text: string): { body: string; sources: Source[] } {
  const lines = text.split(/\r?\n/);
  // Core's rule: the last line that, without # * _ : and spaces, reads Sources.
  let header = lines.length - 1;
  while (header >= 0 && !SOURCES_HEADER.test(lines[header].trim().replace(/^[#*_: ]+|[#*_: ]+$/g, ""))) header--;
  if (header < 0) return { body: text, sources: [] };
  const seen = new Set<string>();
  const sources = lines.slice(header + 1).map(httpsSource)
    .filter((source): source is Source => !!source && !seen.has(source.url) && !!seen.add(source.url));
  return { body: lines.slice(0, header).join("\n").trimEnd(), sources };
}

/** A link card's second line: the host without "www." and a short path. */
export function sourcePlace(href: string): { host: string; path: string } {
  const url = new URL(href);
  const path = url.pathname === "/" ? "" : url.pathname;
  return { host: url.hostname.replace(/^www\./, ""), path: path.length > 40 ? `${path.slice(0, 39)}…` : path };
}

/** A run that starts right after a finished Jarvis reply, with no new question
 *  in between, is one Core started itself: its automatic web search. */
export const startsAfterReply = (rows: { role: string; text: string }[]) => {
  const last = rows[rows.length - 1];
  return last?.role === "jarvis" && !!last.text;
};

/** Core marks its automatic web search run with `web_search: true`; older
 *  Cores do not, so a run right after a finished reply counts too. */
export const startsWebSearch = (started: { web_search?: boolean }, rows: { role: string; text: string }[]) =>
  started.web_search === true || startsAfterReply(rows);
