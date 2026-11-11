# 02 · A-team feature inventory + port notes

> Slice 2 of the ARGUS overhaul. **Status: partial (first pass in progress)** — full inventory and design-language section follow in the next commit.
> Source: `gitlab.com/blazeinchue/a-team` `main` @ 7c4b459a (v2.18, versionCode 25), read from a local snapshot; unmerged branches checked through the GitLab API.

A-team ("ARGUS Agents", package `ai.argus.agents`) is a no-Gradle Android app: a Java WebView shell (`MainActivity` + `Native` bridge) around a plain-JS UI in `app/www/`, plus the Cloudflare Worker `argus-agents-api` (REST `/v1`, MCP `/mcp`, KV). Chat-first: every bot is a chat, jobs live inline, a built-in Manager leads the team, each bot works in a real Ubuntu 24.04 (PRoot) workspace.

Branch reality: `main` already contains the content of v2.14 (cloud computer), v2.15 (Manager) and v2.16 (notch/notifications/review) — those MRs (!10, !11) are still "open" but their files were folded into main (squash). The only branch with code not on main is **`v2.19-chat-drawer`** (MR !12: saved chats + left drawer on the Chat page, `talks.js`).
