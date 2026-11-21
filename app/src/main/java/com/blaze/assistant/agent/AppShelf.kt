package com.blaze.assistant.agent

import com.blaze.assistant.BrandName
import com.blaze.assistant.connect.ArgusAppsModel.App
import com.blaze.assistant.team.ActionClass
import org.json.JSONArray
import org.json.JSONObject

/**
 * App shelf (21 Nov 2026, docs/APP-SHELF.md). The apps the user connects in Settings › Apps & connectors (argus-connect,
 * the account's own connections) reach the model the same way skills, specialists and views do: as a short shelf of
 * facts on every turn, plus two plain tools.
 *
 *  - [promptBlock]: one line per connected app (`id — what it holds`), one line naming the keyless apps, one line saying
 *    more can be connected. Byte-stable for the same list, so it rides in the cached SLOW tier.
 *  - [LIST] (`app_tools`): what one app can do (each action, its arguments, reads or changes), or the whole catalogue.
 *  - [RUN] (`app_run`): runs one action. Nothing here decides whether a message needs an app: the model reads the shelf
 *    and calls the tools itself.
 *  - [classOf]: the action class of one action, for the approval policy. The catalogue says whether an action only
 *    reads (HTTP GET, MCP readOnlyHint); for the rest the action's own name says whether it sends or deletes. This reads
 *    the catalogue's tool names, never the user's words (MODEL-FIRST "Still fine": permission gates that don't guess intent).
 *
 * Pure Kotlin (org.json only); AppShelfTest. The network half is ConnectedApps (connect package).
 */
object AppShelf {
    const val LIST = "app_tools"
    const val RUN = "app_run"
    val NAMES = setOf(LIST, RUN)

    /** Set by ConnectedApps: the account is signed in, so the two tools exist and ride every turn ([turnNames]). */
    @Volatile var live: Boolean = false

    /** Whether an action only reads (set by ConnectedApps from the app's action list; null = not read yet). Plan mode reads it. */
    @Volatile var readOnlyOf: (String) -> Boolean? = { null }

    /** The tools that join every turn's core while the account is signed in (ToolPick.core). */
    fun turnNames(): Set<String> = if (live) NAMES else emptySet()

    /** Shelf caps: the shelf stays small however many apps are on (AppShelfTest). */
    const val MAX_APPS = 30
    const val LINE_CHARS = 90
    const val KEYLESS_CHARS = 360
    const val BUDGET_CHARS = 3_400

    const val HEAD = "\n\nAPPS the user connected in Apps & connectors (id \u2014 what it holds). " +
        "$LIST with an app id lists its actions and their arguments; $RUN runs one. Actions that change, send or delete something " +
        "show the user an approval card first.\n"

    const val NONE = "\n\nAPPS: the user hasn't connected any apps in Apps & connectors yet. "

    const val TAIL = "Other apps can be connected: $LIST with no app lists them all, and request_app_connection asks the user to connect one.\n"

    /** One app's line: `- notion — Search, read and write Notion pages, blocks and databases`. */
    fun line(a: App): String = "- ${a.id} \u2014 ${short(a.description.ifBlank { a.name }, LINE_CHARS)}\n"

    /**
     * The shelf for [apps] (the account's list from argus-connect). Connected apps get a line each, sorted by id so the
     * bytes only change when the list does; keyless apps that are switched on are named on one line. Empty when [signedIn]
     * is false (no tool would work).
     */
    fun promptBlock(apps: List<App>, signedIn: Boolean): String {
        if (!signedIn) return ""
        val connected = apps.filter { it.connected && !it.none && it.enabled }.sortedBy { it.id }
        val keyless = apps.filter { it.none && it.enabled }.map { it.id }.sorted()
        val sb = StringBuilder()
        if (connected.isEmpty()) sb.append(NONE) else {
            sb.append(HEAD)
            for (a in connected.take(MAX_APPS)) sb.append(line(a))
            if (connected.size > MAX_APPS) sb.append("- \u2026and ${connected.size - MAX_APPS} more; $LIST with no app lists them.\n")
        }
        if (keyless.isNotEmpty()) sb.append("Keyless, always on: ").append(short(keyless.joinToString(", "), KEYLESS_CHARS)).append(".\n")
        sb.append(TAIL)
        val out = sb.toString()
        return if (out.length <= BUDGET_CHARS) out else out.take(BUDGET_CHARS - 2).trimEnd() + "\u2026\n"
    }

    /** First sentence, no trailing full stop, cut at a word boundary under [max]. Deterministic. */
    fun short(text: String, max: Int): String {
        var s = text.replace(Regex("\\s+"), " ").trim()
        val dot = s.indexOf(". ")
        if (dot in 1 until s.length) s = s.substring(0, dot)
        s = s.trimEnd('.', ' ')
        if (s.length <= max) return s
        val cut = s.take(max - 1)
        val sp = cut.lastIndexOf(' ')
        return (if (sp > max / 2) cut.substring(0, sp) else cut).trimEnd(',', ';', ':', ' ') + "\u2026"
    }

    // ---------------- tools ----------------

    private fun fn(name: String, desc: String, props: JSONObject, req: List<String>) = JSONObject().put("type", "function").put("function",
        JSONObject().put("name", name).put("description", desc).put("parameters",
            JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(req))))

    fun toolDefs(): List<JSONObject> = listOf(
        fn(LIST, "What one of the user's connected apps can do: each action's full name, what it does, its arguments " +
            "(* = required) and whether it only reads or changes something. With no app: every app ${BrandName.NAME} can use, and whether it's connected.",
            JSONObject().put("app", JSONObject().put("type", "string").put("description", "App id from the APPS list, e.g. notion or google_calendar")),
            emptyList()),
        fn(RUN, "Run one action of a connected app with its arguments, as $LIST lists them. Returns the app's answer as JSON. " +
            "Actions that change, send or delete something show the user an approval card first.",
            JSONObject()
                .put("tool", JSONObject().put("type", "string").put("description", "The action's full name from $LIST, e.g. notion_search"))
                .put("arguments", JSONObject().put("type", "object").put("description", "The action's arguments").put("additionalProperties", true)),
            listOf("tool")),
    )

    // ---------------- one app's actions ----------------

    /** One action of an app, from argus-connect's MCP tools/list. */
    data class Action(val name: String, val description: String, val schema: JSONObject, val readOnly: Boolean)

    /** Parses an MCP `tools/list` result (`{tools:[{name, description, inputSchema, annotations:{readOnlyHint}}]}`); skips the skill tools. */
    fun parseActions(result: JSONObject?): List<Action> {
        val arr = result?.optJSONArray("tools") ?: return emptyList()
        val out = ArrayList<Action>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name").trim()
            if (name.isEmpty() || name.startsWith("argus_")) continue
            // argus-connect prefixes "[App name] "; the shelf already says which app it is
            val d = o.optString("description").replaceFirst(Regex("^\\[[^\\]]{1,60}\\]\\s*"), "")
            out += Action(name, d, o.optJSONObject("inputSchema") ?: JSONObject(), o.optJSONObject("annotations")?.optBoolean("readOnlyHint", false) ?: false)
        }
        return out
    }

    /** What [LIST] returns for one app: a header, then one block per action. Capped at [LIST_CHARS]. */
    fun renderActions(appId: String, actions: List<Action>): String {
        if (actions.isEmpty()) return "$appId has no actions switched on. The user can switch them on in Apps & connectors."
        val sb = StringBuilder("$appId \u2014 ${actions.size} actions. Reads run at once; changes ask the user first. * = required.\n")
        for (a in actions) {
            if (sb.length > LIST_CHARS) { sb.append("\u2026cut here; ${actions.size} actions in all.\n"); break }
            sb.append("\n").append(a.name).append(" [").append(if (a.readOnly) "reads" else "changes").append("] ")
                .append(short(a.description, 220)).append("\n")
            val props = a.schema.optJSONObject("properties")
            val req = a.schema.optJSONArray("required")?.let { r -> (0 until r.length()).map { r.optString(it) }.toSet() } ?: emptySet()
            props?.keys()?.asSequence()?.sorted()?.forEach { k ->
                val p = props.optJSONObject(k) ?: JSONObject()
                sb.append("  ").append(k).append(if (k in req) "*" else "").append(": ").append(typeOf(p))
                val pd = p.optString("description")
                if (pd.isNotBlank()) sb.append(" \u2014 ").append(short(pd, 140))
                sb.append("\n")
            }
        }
        return sb.toString().trimEnd()
    }

    const val LIST_CHARS = 9_000

    private fun typeOf(p: JSONObject): String {
        val t = p.optString("type").ifBlank { "any" }
        p.optJSONArray("enum")?.let { e -> return "one of " + (0 until e.length()).joinToString(", ") { e.optString(it) } }
        if (t == "array") return "array of " + (p.optJSONObject("items")?.optString("type")?.ifBlank { null } ?: "any")
        return t
    }

    /** Switched on and connected (or keyless): its actions can run. */
    fun usable(a: App): Boolean = a.enabled && a.ready

    /** What [LIST] returns with no app: the catalogue, connected apps first. */
    fun renderCatalogue(apps: List<App>): String {
        if (apps.isEmpty()) return "No apps to show. The user may need to sign in to their ${BrandName.NAME} account."
        val ready = apps.filter { usable(it) }.sortedBy { it.id }
        val rest = apps.filter { !usable(it) }.sortedBy { it.id }
        val sb = StringBuilder()
        if (ready.isNotEmpty()) {
            sb.append("Ready to use (").append(ready.size).append("):\n")
            for (a in ready) sb.append("- ").append(a.id).append(" \u2014 ").append(short(a.description.ifBlank { a.name }, LINE_CHARS)).append("\n")
        }
        if (rest.isNotEmpty()) {
            sb.append("Not connected (").append(rest.size).append("; request_app_connection asks the user):\n")
            for (a in rest) sb.append("- ").append(a.id).append(" (").append(a.name).append(", ").append(a.category).append(")\n")
        }
        return sb.toString().trimEnd()
    }

    /** The app an action belongs to: the longest known app id that prefixes `<id>_`. Null when none does. */
    fun appOf(tool: String, appIds: Collection<String>): String? =
        appIds.filter { tool.startsWith(it + "_") && tool.length > it.length + 1 }.maxByOrNull { it.length }

    // ---------------- approval class ----------------

    private val SEND_WORDS = setOf("send", "reply", "post", "publish", "comment", "share", "invite", "notify", "tweet", "toot", "forward", "dm")
    private val DELETE_WORDS = setOf("delete", "remove", "trash", "destroy", "revoke", "clear", "purge", "unsubscribe")

    /**
     * The action class of [tool] (an app action's full name). [readOnly]: the catalogue's answer (true = only reads), or
     * null when the action's list hasn't been read yet. [appId] is cut off the front, so a word in the app's own id
     * ("google_contacts") never counts as the action's verb.
     */
    fun classOf(tool: String, readOnly: Boolean?, appId: String? = null): ActionClass {
        if (readOnly == true) return ActionClass.READ
        val action = if (appId != null && tool.startsWith(appId + "_")) tool.substring(appId.length + 1) else tool
        val words = action.lowercase().split('_', '-', '.').filter { it.isNotEmpty() }
        return when {
            words.any { it in DELETE_WORDS } -> ActionClass.DELETE
            words.any { it in SEND_WORDS } -> ActionClass.SEND
            else -> ActionClass.WRITE_REMOTE
        }
    }

    // ---------------- results ----------------

    const val RESULT_CHARS = 12_000

    /** What [RUN] returns from `/v1/me/execute`'s answer (`{ok, data}` or `{ok:false, error:{code, message}}`). */
    fun renderResult(tool: String, appId: String?, code: Int, body: String): String {
        val o = try { JSONObject(body) } catch (_: Exception) { null }
        if (o != null && o.optBoolean("ok")) {
            val data = o.opt("data")
            val text = when (data) {
                null, JSONObject.NULL -> "Done (no content)."
                is JSONObject -> data.toString(1)
                is JSONArray -> data.toString(1)
                else -> data.toString()
            }
            return if (text.length <= RESULT_CHARS) text
            else text.take(RESULT_CHARS) + "\n\u2026cut at ${RESULT_CHARS / 1000}k chars of ${text.length}; ask for less (a limit, a filter or one id)."
        }
        val err = o?.optJSONObject("error")
        val ec = err?.optString("code").orEmpty()
        val msg = err?.optString("message").orEmpty()
        val app = appId ?: tool.substringBefore('_')
        return when {
            ec == "not_connected" || code == 401 && ec.isEmpty() -> "$app isn't connected for this account. request_app_connection asks the user to connect it."
            ec == "unknown_tool" -> "There's no action named $tool. $LIST $app lists the actions."
            ec == "disabled" -> "$tool is switched off for this account in Apps & connectors."
            code == -1 -> "Couldn't reach the ${BrandName.NAME} gateway: ${msg.ifBlank { "network error" }}."
            msg.isNotBlank() -> "$tool failed: $msg"
            else -> "$tool failed (HTTP $code)."
        }
    }

    /** The step line in the chat: "Notion · search". */
    fun step(tool: String, app: App?): String {
        val name = app?.name ?: tool.substringBefore('_')
        val action = if (app != null && tool.startsWith(app.id + "_")) tool.substring(app.id.length + 1) else tool
        return "$name \u00b7 ${action.replace('_', ' ')}"
    }
}
