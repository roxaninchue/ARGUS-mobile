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

## Low search success rate overall
Reported by Finn, 14 Nov 2026 (same "dots3 note preview" task: the agent found the Rednote press release and a benchmark chart image on finance.yahoo.com, then gave up and told Finn to look on Hugging Face himself).

**Likely causes**
- Every source is keyless scraping (DuckDuckGo HTML, Bing/Google RSS, Wikipedia). They rate-limit, localise by IP, and are weak on brand-new names, so many searches come back blocked, thin or off-topic.
- The agent stops at "couldn't find" instead of going to the obvious page: it named `dots-studio/dots3-note-preview` on Hugging Face but never fetch_page'd `https://huggingface.co/dots-studio/dots3-note-preview`.
- A chart image it already had (benchmarks) wasn't read.

**Fix plan**
1. Give search a real API path through the Blaze Gateway (key held server-side, keyless chain stays as the fallback). Pick the provider with Finn; it may cost money, so it needs his approval.
2. "Go to the source" rule: when the answer names a likely canonical page (Hugging Face model card, GitHub repo, official docs), fetch_page it before telling the user to look themselves. Never end with "search for X yourself" while a fetch is untried.
3. When a found page carries a chart/table image relevant to the ask, read it (vision) before concluding the data isn't available.
4. Measure: log per-search outcome (source, ok/blocked/empty/irrelevant) so success rate is visible in the gateway admin, and track it release to release.

### Search API shortlist (prices checked 14 Nov 2026, from each provider's pricing page)
| Provider | Free | Paid | Notes |
|---|---|---|---|
| Exa | $10 credits every month, no card (~2,500 Lite searches) | Lite search $4 / 1k | Built for agents; page text in results |
| Brave Search API | $5 credits every month (~1,000 searches); card required | $5 / 1k | Own index (no Google/Bing), fastest in AIMultiple test |
| Tavily | 1,000 credits / month, no card | $0.008 / credit PAYG, or $30/mo for 4,000 | Agent-oriented, extract endpoint too |
| Serper (Google results) | 2,500 queries once | $50 for 50k ($1 / 1k), credits last 6 months | Cheapest at volume; scraped Google |
Proposal: Exa as primary (largest monthly free allowance, no card), keyless chain as fallback. Needs Finn's OK.
