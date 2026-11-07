# Terminal first (0.51.0)

Many tasks are faster and more reliable as a command in Argus Terminal (BusyBox shell built in; Alpine/proot for curl,
Python, git, the `argus` CLI) than by opening apps and tapping, or driving the browser. 0.51.0 makes that the default.

Code: `app/src/main/java/com/blazeinchue/argus/agent/ExecRoute.kt` (pure, `ExecRouteTest`), wired in `Agent.Session.runLoop`.

| Route | When | Effect |
|---|---|---|
| TERMINAL | easy as a command: web search / fetch (curl, http_request, fetch_page), file work (zip, rename, find, sizes), sums and conversions, git, package / version lookups, API calls, JSON / CSV, text processing, downloads, network checks (ping, DNS, my IP), hashes / base64, scripts | the `terminal` tool group is added to the turn, a "ROUTE FOR THIS MESSAGE: terminal" hint goes in the prompt |
| HTTP | the same kind of task on a low-RAM phone (the low-RAM rule wins: Linux isn't started) or when the terminal isn't ready | a hint to use the built-in HTTP tools (web_search, fetch_page, http_request) — still never the browser UI |
| PHONE | needs a GUI app or a signed-in app session: WhatsApp / Telegram / Instagram, calls, SMS, camera, alarms, toggles, music, maps, tapping | no change (phone control as before) |
| BROWSER | needs a website session or interaction: log in, book, buy, order, fill a form, my account | no change (Argus's browser as before) |
| ANY | chat, writing, questions | no change |

PHONE and BROWSER win over TERMINAL when a message needs both ("log in to my bank and download the statement" → browser).

Every prompt also carries the rule (`ExecRoute.PROMPT`, in the `search` prompt part): prefer run_shell / run_code /
http_request / fetch_page over opening apps, tapping or the browser when a command does it; phone control only for GUI
apps or signed-in app sessions; the browser only for sites that need a login, a form or a click.

**Safety is unchanged.** The router only adds a hint and a tool group; it never removes a tool. Every command still goes
through `CommandPolicy` and the confirm card: destructive or sending commands (rm, force-push, `curl … | sh`, mail, posts,
payments) always ask (`ExecRoute.needsConfirm` mirrors that rule for tests). Autopilot keeps asking for destructive ones.

ArgusQuery follows the same rule: its retrieval is plain HTTP from the app (`QueryRuntime.net`), never the browser UI.
