package com.blaze.assistant.browser

import com.blaze.assistant.BrandName
import org.json.JSONArray
import org.json.JSONObject

/**
 * 2026-11-18 page tools for "Ask ARGUS about this page". The sheet could only talk about the page; asked to "fill this
 * in" or "click next" it could do nothing. Now it can act on the open page, and only on it:
 *
 *  - [TOOLS]: read, find, scroll, click, type, choose, press a key, go back, wait. Nothing else exists here: no other
 *    tabs or sites, apps, messages, files or memory. A call to any other tool gets [refusal] and the model tells the user
 *    to ask in the main chat ([RULE] says the same up front, so the model decides; no keyword router guesses for it).
 *  - At most [MAX_STEPS] tool rounds per question, then the model must answer with what it did and what's left.
 *  - A step that commits something (the model marks it `consequential`, it submits a form, or the target's label names
 *    paying, sending, deleting, booking…) waits for the user's Allow / Not now in the sheet itself, because the chat's
 *    confirm card never shows over the browser. Those answers belong to this sheet's own pending step only.
 *
 * The pure parts (schemas, allow-list, the Allow rule, step lines, messages) are unit-tested in PageAgentTest; [Runner]
 * drives BrowserEngine on the current tab.
 */
object PageAgent {
    const val MAX_STEPS = 12

    val NAMES = listOf("page_read", "page_find", "page_scroll", "page_click", "page_type", "page_select", "page_key", "page_back", "page_wait")
    val ALLOWED: Set<String> = NAMES.toSet()

    fun allowed(name: String): Boolean = name in ALLOWED

    const val RULE = "\n\nACTING ON THIS PAGE\n" +
        "- You can act on the open page with the page_ tools: read it, find words, scroll, click, type into fields, choose options, " +
        "press a key, go back, wait. Use them when the user asks you to do something here; for a question, just answer.\n" +
        "- Only this page and the pages it leads to in this tab. You can't open other tabs, apps, messages, files or memory from " +
        "here. If the user asks for that, say in one line that this sheet only works on the open page and that they can ask " +
        "${BrandName.NAME} in the main chat.\n" +
        "- Set consequential=true on any step that commits something for the user (pays, orders, books, sends, posts, deletes, " +
        "signs up, submits a form). The user is asked to Allow it first; if they say Not now, don't do it and say what's left.\n" +
        "- Never type passwords, codes or card numbers yourself; leave those fields for the user.\n" +
        "- At most $MAX_STEPS steps per request. When you're done, or blocked, answer in one or two short lines: what you did and what's left."

    private val RISKY = Regex(
        "(?i)\\b(pay|buy|purchase|order|checkout|check out|place order|book|reserve|send|post|publish|submit|delete|remove|" +
            "transfer|withdraw|confirm|subscribe|sign ?up|unsubscribe|cancel (?:my |the )?(?:order|subscription|booking|account)|donate)\\b")

    /** True when a page_ call commits something and needs the user's Allow first. */
    fun needsAllow(name: String, args: JSONObject): Boolean {
        if (args.optBoolean("consequential", false)) return true
        return when (name) {
            "page_type" -> args.optBoolean("submit", false)
            "page_click", "page_select" -> RISKY.containsMatchIn(args.optString("text") + " " + args.optString("label"))
            "page_key" -> args.optString("key").equals("Enter", true) && RISKY.containsMatchIn(args.optString("label"))
            else -> false
        }
    }

    /** What the sheet shows for a step, in plain words ("Clicking “Next”"). */
    fun stepLine(name: String, args: JSONObject): String {
        fun q(s: String) = "\u201c" + s.replace(Regex("\\s+"), " ").trim().take(48) + "\u201d"
        val target = args.optString("label").ifBlank { args.optString("text") }
        return when (name) {
            "page_read" -> "Reading the page"
            "page_find" -> "Looking for " + q(args.optString("query"))
            "page_scroll" -> if (args.optString("direction") == "up") "Scrolling up" else "Scrolling down"
            "page_click" -> if (target.isNotBlank()) "Tapping " + q(target) else "Tapping item ${args.optInt("n", -1)}"
            "page_type" -> (if (args.optBoolean("submit")) "Typing and sending " else "Typing ") + q(args.optString("text")) +
                (args.optString("label").takeIf { it.isNotBlank() }?.let { " into " + q(it) } ?: "")
            "page_select" -> "Choosing " + q(args.optString("option")) + (args.optString("label").takeIf { it.isNotBlank() }?.let { " in " + q(it) } ?: "")
            "page_key" -> "Pressing " + args.optString("key").ifBlank { "a key" }
            "page_back" -> "Going back"
            "page_wait" -> "Waiting for the page"
            else -> "Not available here"
        }
    }

    /** What the Allow row asks ("Allow: Tapping “Place order”?"). */
    fun allowQuestion(name: String, args: JSONObject): String = stepLine(name, args) + "?"

    fun refusal(name: String): String = "\u201c$name\u201d isn't available here: this sheet only acts on the open page. " +
        "Tell the user in one line that they can ask ${BrandName.NAME} in the main chat for that."

    const val DECLINED = "The user tapped Not now. Don't do this step; tell them in one line what's left for them to do."
    const val OUT_OF_STEPS = "That was the last step for this request. Answer now: what you did and what's left, in one or two short lines."

    private fun fn(name: String, desc: String, props: JSONObject, required: List<String> = emptyList()): JSONObject {
        props.put("consequential", JSONObject().put("type", "boolean").put("description", "true when this step commits something for the user (pay, order, book, send, post, delete, sign up, submit)"))
        return JSONObject().put("type", "function").put("function", JSONObject().put("name", name).put("description", desc)
            .put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(required))))
    }

    private fun s(d: String) = JSONObject().put("type", "string").put("description", d)
    private fun i(d: String) = JSONObject().put("type", "integer").put("description", d)

    /** The tool schemas this sheet sends: page_ tools only. */
    fun tools(): JSONArray = JSONArray()
        .put(fn("page_read", "Read the open page as it is now: text, and numbered buttons, links and fields.", JSONObject()))
        .put(fn("page_find", "Find words on the page and scroll the first match into view.", JSONObject().put("query", s("words to find")), listOf("query")))
        .put(fn("page_scroll", "Scroll the page up or down by most of a screen.", JSONObject().put("direction", JSONObject().put("type", "string").put("enum", JSONArray().put("down").put("up")))))
        .put(fn("page_click", "Tap a button, link or item: by its number from page_read, or by its visible text.",
            JSONObject().put("n", i("item number from page_read")).put("text", s("visible text of the item")).put("label", s("what the item is, in a few words"))))
        .put(fn("page_type", "Type into a field (by number or label). submit=true presses Enter / sends the form.",
            JSONObject().put("n", i("field number from page_read")).put("label", s("the field's label")).put("text", s("what to type"))
                .put("submit", JSONObject().put("type", "boolean")), listOf("text")))
        .put(fn("page_select", "Choose an option in a dropdown (by number or label).",
            JSONObject().put("n", i("dropdown number from page_read")).put("label", s("the dropdown's label")).put("option", s("the option to choose")), listOf("option")))
        .put(fn("page_key", "Press one key on the focused element (Enter, Tab, Escape, ArrowDown, ArrowUp…).",
            JSONObject().put("key", s("key name")).put("label", s("what pressing it does, in a few words")), listOf("key")))
        .put(fn("page_back", "Go back to the previous page in this tab.", JSONObject()))
        .put(fn("page_wait", "Wait up to 10 seconds for text to appear, or for the page to settle.", JSONObject().put("text", s("text to wait for")).put("seconds", i("1 to 10"))))

    /** The request: the page sheet's system prompt + [RULE] + the page, then the chat. */
    fun messages(page: PageAsk.Capture, history: List<Pair<Boolean, String>>, web: String?, lang: String): JSONArray {
        val a = PageAsk.messages(page, history, web, lang)
        val sys = a.getJSONObject(0)
        sys.put("content", sys.getString("content").let { c ->
            val cut = c.indexOf("\n\nPAGE ON THE USER'S SCREEN:")
            if (cut < 0) c + RULE else c.substring(0, cut) + RULE + c.substring(cut)
        })
        return a
    }

    /** A tool result as the model reads it: the browser's own wording, with its tool names mapped to this sheet's. */
    fun result(raw: String): String = raw.replace("browser_secure_input", "the user").replace("browser_", "page_").take(6000)

    /** One parsed tool call. */
    data class Call(val id: String, val name: String, val args: JSONObject)

    fun calls(reply: JSONObject): List<Call> {
        val a = reply.optJSONArray("tool_calls") ?: return emptyList()
        return (0 until a.length()).mapNotNull { k ->
            val c = a.optJSONObject(k) ?: return@mapNotNull null
            val f = c.optJSONObject("function") ?: return@mapNotNull null
            val args = try { JSONObject(f.optString("arguments").ifBlank { "{}" }) } catch (_: Exception) { JSONObject() }
            Call(c.optString("id").ifBlank { "p$k" }, f.optString("name"), args)
        }
    }

    /**
     * Runs one request on the current tab. [onStep] gets each step line, [allow] asks the user (Allow / Not now) and
     * suspends until they answer; [onText] streams the final answer. Returns the answer.
     */
    class Runner(
        private val ctx: android.content.Context,
        private val complete: suspend (JSONArray, JSONArray?, ((String) -> Unit)?) -> JSONObject,
    ) {
        suspend fun run(messages: JSONArray, onStep: (String) -> Unit, allow: suspend (String) -> Boolean, onText: (String) -> Unit): String {
            var steps = 0
            while (true) {
                val last = steps >= MAX_STEPS
                if (last) messages.put(JSONObject().put("role", "system").put("content", OUT_OF_STEPS))
                val reply = complete(messages, if (last) null else tools(), onText)
                val cs = if (last) emptyList() else calls(reply)
                if (cs.isEmpty()) return reply.optString("content")
                steps++
                messages.put(JSONObject().put("role", "assistant").put("content", reply.optString("content")).put("tool_calls", reply.optJSONArray("tool_calls")))
                for (c in cs) {
                    val out = when {
                        !allowed(c.name) -> refusal(c.name)
                        needsAllow(c.name, c.args) && !allow(allowQuestion(c.name, c.args)) -> { onStep("Skipped: " + stepLine(c.name, c.args)); DECLINED }
                        else -> { onStep(stepLine(c.name, c.args)); result(exec(c)) }
                    }
                    messages.put(JSONObject().put("role", "tool").put("tool_call_id", c.id).put("name", c.name).put("content", out))
                }
            }
        }

        private suspend fun exec(c: Call): String {
            val a = c.args
            fun n(): Int? = if (a.has("n")) a.optInt("n", -1).takeIf { it >= 0 } else null
            fun str(k: String): String? = a.optString(k).takeIf { it.isNotBlank() }
            return try {
                when (c.name) {
                    "page_read" -> BrowserEngine.read(ctx)
                    "page_find" -> BrowserEngine.find(ctx, a.optString("query"))
                    "page_scroll" -> BrowserEngine.scroll(ctx, a.optString("direction") != "up")
                    "page_click" -> BrowserEngine.click(ctx, n(), str("text") ?: str("label"))
                    "page_type" -> BrowserEngine.type(ctx, n(), str("label"), a.optString("text"), a.optBoolean("submit", false))
                    "page_select" -> BrowserEngine.select(ctx, n(), str("label"), a.optString("option"))
                    "page_key" -> BrowserEngine.key(ctx, a.optString("key"))
                    "page_back" -> BrowserEngine.back(ctx)
                    "page_wait" -> BrowserEngine.waitFor(ctx, null, str("text"), false, a.optInt("seconds", 5).coerceIn(1, 10))
                    else -> refusal(c.name)
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { "That step failed: " + (e.message ?: "the page didn't answer").take(160) }
        }
    }
}
