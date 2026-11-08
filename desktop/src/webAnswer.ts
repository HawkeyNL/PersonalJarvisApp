// Web-derived answers. Core stores a Research answer with model "web:<model>"
// and ends it with a "Sources:" (or "Bronnen:") list of https URLs.

export const isWebModel = (model: string | null | undefined) => !!model?.startsWith("web:");

const SOURCES_HEADER = /^(sources|bronnen)$/i;

/** One https URL with a host and no user info, like Core's Sources filter. */
function httpsSource(line: string): string | null {
  const candidate = line.trim().replace(/^[-*]+\s*/, "");
  if (!candidate || /\s/.test(candidate)) return null;
  try {
    const url = new URL(candidate);
    return url.protocol === "https:" && url.hostname && !url.username && !url.password ? url.href : null;
  } catch { return null; }
}

/** The answer without its trailing Sources list, and that list's distinct https URLs.
 *  Without a Sources header the whole text is the body. */
export function splitSources(text: string): { body: string; sources: string[] } {
  const lines = text.split(/\r?\n/);
  // Core's rule: the last line that, without # * _ : and spaces, reads Sources.
  let header = lines.length - 1;
  while (header >= 0 && !SOURCES_HEADER.test(lines[header].trim().replace(/^[#*_: ]+|[#*_: ]+$/g, ""))) header--;
  if (header < 0) return { body: text, sources: [] };
  const sources = lines.slice(header + 1).map(httpsSource).filter((url): url is string => !!url);
  return { body: lines.slice(0, header).join("\n").trimEnd(), sources: [...new Set(sources)] };
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
