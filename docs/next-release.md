# Next release fixes

## Web search returns unrelated local results (ZRA / Zambia Revenue Authority)
Reported by Finn, 14 Nov 2026, on 0.53.x (screenshot: a "Dots 3 Note Preview" task; web_search for `"dots3-note-preview..."` came back with zra.org.zm, portal.zra.org.zm, portal-customs.zra.org.zm).

**Likely cause** (`agent/Web.kt`, `agent/SearchChain.kt`):
- No relevance gate. Whatever the first source returns is accepted, so when an engine can't match an odd query (quoted, hyphenated, a new model name) it falls back to popular *local* results for the device's IP (Zambia), and the agent spends steps reading them.
- DuckDuckGo HTML is called with no region (`kl`), so it localises by IP. Bing/Google feeds already force `cc=US` / `gl=US`.
- Quoted, hyphenated slugs (`"dots3-note-preview"`) are sent as-is, which makes a no-match (and the local fallback) more likely.

**Fix plan**
1. Relevance gate in `Web.search`: keep a result only if its title, URL or snippet contains at least one distinctive query word (ignore stop words; split slugs on `-`/`_`; case-insensitive). If nothing survives, note "irrelevant results" and move on to the next source instead of returning.
2. DuckDuckGo: add `&kl=wt-wt` (no region) unless the query itself names a place.
3. Normalise the query before sending: strip wrapping quotes, turn slug hyphens/underscores into spaces for the simpler-words retry.
4. Tests: pure tests for the gate (ZRA results vs a "dots3 note preview" query are dropped; on-topic results kept) in SearchChainTest / WebTest. No @Ignore.
