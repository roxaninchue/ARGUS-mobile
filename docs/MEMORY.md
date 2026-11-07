# Argus tiered memory (0.49.0)

Argus keeps three tiers of memory so a turn never has to send everything, and a small free route
(Groq 8B at 6–8k tokens a minute) still fits, while nothing the user said or shared is lost.

```
            ┌───────────────────────────── one request ─────────────────────────────┐
            │ system prompt                                                         │
            │   WHAT YOU KNOW (pinned core + index)   ← LONG tier (Mind, argus.db)   │
            │   RELEVANT NOTES (top FTS hits, ≤800ch) ← LONG tier, per turn          │
            │   EARLIER IN THIS CHAT (rolling summary)← CHAT tier                    │
            │   CACHED DOCUMENTS (TOCs only, ≤2.4kch) ← CACHE tier                   │
            │ recent turns, word for word (≤70% of the chat budget)  ← CHAT tier    │
            └───────────────────────────────────────────────────────────────────────┘
   tools: memory_search/save/… (long) · search_chat/read_turn (chat) · cache_toc/read/search/summarize (cache)
```

## 1. Chat tier — working context

Code: `memory/ChatTier.kt`, `agent/AutoCompact.kt`, `agent/LongChat.kt`, `Agent.Session.compact()` / `requestMessages()`.

- **Budget** (`ChatTier.budget(window)`): 6,000 tokens when the route's context is unknown (the gateway's
  `auto` lineups), 45% of a window under 16k, else `window/8` clamped to 6k–24k. `Agent.budget()` uses it while the
  setting `tiered_memory` is on (default); off = the old LongChat budget (12k / 60%).
- **Tail cap** (`ChatTier.tailStart`): the word-for-word tail is at most `KEEP_TAIL` (12) messages *and* at most
  70% of the budget in tokens, always starting on a user message, never after the newest user message.
- **Folding**: when the history is over budget (or the whole request nears the window), older turns fold into the
  rolling state note (one background call). If the summariser fails and the history is over 2× the budget, a clipped
  transcript stands in (`AutoCompact.fallbackNote`), so the budget holds offline too.
- **Paging, never dropping**: the folded turns are written verbatim to the cache tier as document `chat-<chatId>`
  (`CacheTier.pageOut`), next to the existing journal / long-chat FTS indexes. `search_chat` and `cache_search`
  both find them. Private chats are never paged.

## 2. Long tier — durable memory (reused, not rebuilt)

Code: `agent/Mind.kt`, `agent/MindStore.kt`, `store/ArgusDb.kt`, `agent/Memory.kt`.

The existing One Mind store is the long tier: `memories` in `filesDir/argus.db` (the schema shared with Argus
Desktop / CLI, bundled SQLite with **FTS5** on every Android version), the `memory_save / memory_search / memory_update
/ memory_delete / memory_merge` tools, the idle review that extracts facts, and the existing cloud sync. Nothing about
its schema or sync changed.

New in 0.49.0: each turn the prompt also carries **RELEVANT NOTES** — `Mind.relevantBlock(lastUserText)` searches
the memory FTS with the message's content words (stop words removed), skips lines already in the pinned core, and
injects at most 5 lines / 800 characters (`Mind.RELEVANT_*`). It is built inside the per-turn memo
(`Memory.promptBlock`), off the main thread.

## 3. Cache tier — paged large text

Code: `memory/Chunker.kt`, `memory/CacheStore.kt`, `memory/CacheToc.kt`, `memory/MapReduce.kt`, `memory/CacheTier.kt`.

Storage: `filesDir/cache/` — `cache.db` (its own SQLite file, **not** argus.db: that schema is shared with other
apps) and `docs/<id>.txt` (the original text, verbatim).

- **What goes in**:
  - a user/system message over `PASTE_OVER` = 12,000 chars (~3k tokens) — `Session.pageLargeInputs()` runs at the
    start of every turn and before every model step, caches it, and replaces the message in the history with a short
    reference (`[Cached document doc:<id> …]` + the first 600 chars);
  - a text attachment over 12,000 bytes — streamed from the content resolver straight into the cache
    (`Attachments.load` → `CacheTier.cacheAttachment`), never read whole into memory; claimed by the chat on its turn;
  - a tool result over `TOOL_OVER` = 16,000 chars — cached; the model gets the head, the TOC and the doc id
    (results 4k–16k keep LongChat's artifact aside);
  - chat turns that fold out of the chat tier (`chat-<chatId>`).
  So the app no longer sends >360k-char requests to the `long` route (`ModelRoute.AGENT_MAX_CHARS` is rarely hit now).
- **Chunking** (`Chunker`, streaming, 8 KB reads, holds ≤ one section): sections of ~8,000 chars (~2k tokens), cut at
  headings (Markdown `#`, "Chapter 3", "PART II", short ALL-CAPS lines; never inside ``` fences), then at blank lines,
  then at a sentence end, then a space, at most 10,000 chars, never inside a surrogate pair. Tiny sections merge.
  The pieces concatenate back to the exact input.
- **Index**: `sections(doc, idx, title, chapter, level, gist, start, len)` + `sections_fts` (FTS5 contentless,
  `unicode61 remove_diacritics 2`, BM25 with the title weighted 4×). Fallbacks: plain FTS5, then a file scan.
- **Gists**: every section starts with its first sentence; in the background the cheap `quick` route writes one-line
  gists for the first 48 sections, 6 per call (`CacheTier.gists`). Offline it just keeps the first sentence.
- **TOC** (`CacheToc`): ≤24 sections → flat (`§i title — gist`); more → hierarchical: chapters (or runs of 16
  sections) at the top level, `cache_toc(doc, chapter=k)` expands one. Only TOCs enter the prompt
  (`CACHED DOCUMENTS`, ≤2,400 chars, newest documents first; the history doc by header only).
- **Tools** (always in the tool list, read-only, allowed in Plan mode):
  - `cache_toc(doc, chapter?)`
  - `cache_read(doc, index, count≤3)` — reads the byte range of each section (RandomAccessFile), nothing else
  - `cache_search(doc|all, query)` — BM25 over one document or all of this chat's documents
  - `cache_summarize(doc, focus?)` — map-reduce (`MapReduce`): batches of ≤3k tokens → ≤120-word notes →
    combined until one answer is left; at most 120 sections, and it says when it stopped short.
- **Limits**: one document ≤16M chars (marked "cut"); the whole cache ≤48 MB, least-recently-used documents evicted
  after each write (`CacheStore.evict`, never the one just written). Deleting a chat deletes its documents
  (`Agent.deleteChat` → `CacheTier.deleteChat`).
- **Threads**: every cache call runs on `Dispatchers.IO`; the history is edited back on the loop's thread.

## 0.51.0: ArgusQuery documents
ArgusQuery (docs/ARGUSQUERY.md) stores what it fetches in this same cache: one document per topic (kind `argusquery`, in the
chat that asked) plus a `bq` row in cache.db (topic key, doc id, expiry, the judged evidence as JSON). Topics expire after
1 h (news) / 3 h (versions) / 6 h; storing a topic again replaces its document. The prompt gets only the compact evidence
pack; cache_search / cache_read reach the full text.

## Settings

- `tiered_memory` (bool, default true): off = the pre-0.49 behaviour (no paging of inputs, the old 12k budget).

## Tests

| Test | Covers |
|---|---|
| `ChunkerTest` (9) | headings / chapters, code fences, a huge single paragraph cut at sentences under the hard max, no-space text, Unicode + surrogate pairs, tiny-section merging, streaming 8 KB reads, pieces == input |
| `CacheStoreTest` (8) | contentless FTS5 ingest + read-back, BM25 picks the right chapter, chat-scoped search, history append, per-doc cap, LRU eviction keeps the cap and the newest, chat delete removes files/rows/index, gists + claims |
| `TieredMemoryTest` (16) | 6k default budget + route scaling, token-capped tail keeps the newest user turn, AutoCompact folds by tokens, paged-out turns verbatim + searchable, big paste → reference, tool output → cache, flat / chapter / run TOCs + TOC char budget, cache tools, model gists in batches + offline stop, map-reduce batching + summarize, RELEVANT NOTES |
| `HomeChatToolLoopTest` (7) | the Home-chat tool loop with a scripted model: go-ahead → tool lane, chat-lane rerun, one re-ask then the honest line, `argus_self` → `SelfSetup.open` → `ActivityStart` firing `ACTION_ACCESSIBILITY_SETTINGS` (from the Activity, no NEW_TASK), and `cache_search` / `cache_read` calls |

All green locally on 2026-11-01 (see `docs/BUILD-LOCAL.md`).

## Known gaps

- The tool list itself (~100 tools, sent whole by `ToolTruth.full`) is several thousand tokens; the chat budget does
  not shrink it. Small routes still depend on the gateway's size-aware routing for the full request.
- PDFs are still sent as page images (first 6 pages); their text is not extracted into the cache.
- Gists are written for the first 48 sections of a document only (free-tier quota); later ones keep their first
  sentence.
- Paged-out chat history lives in the cache and in the existing journal/long-chat indexes; it is not synced to the
  cloud (the cache is per phone; long-tier memories still sync as before).
- `HomeChatToolLoopTest` drives the same lane / guard / tool steps as `Agent.runLoop` in the same order, with the real
  `ChatLane`, `ActionPromise`, `SelfControl`/`SelfSetup`/`ActivityStart` and `CacheTier`; `runLoop` itself has no model
  seam, so it is not executed verbatim in a JVM test (same approach as `AgentLoopScriptTest`).
- The low-RAM gate (cold start + LowRamSmokeTest) needs an x86-64 emulator with KVM; it was not run for 0.49.0 mem1
  in the aarch64 build sandbox.
