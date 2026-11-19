package com.argus.assistant.ui.living

/**
 * Living UI 0.2.2: a steps row says what was done ("Checked the weather · Ndola", "Searched your mail · invoices"), not
 * the generic group label twice ("Looked things up", "Looked things up"). [label] = our own words for the tools whose
 * shared label says nothing, else the app's label (StepTool, passed in), plus the call's main argument as the detail.
 * [merge] folds true duplicates (same words, one after the other) into one row with a count. Pure: LivingModelTest.
 */
object LivingSteps {
    /** Our own (running, done) words for tools whose shared label is only "Looking things up". */
    val OWN: Map<String, Pair<String, String>> = mapOf(
        "get_weather" to ("Checking the weather" to "Checked the weather"),
        "get_location" to ("Finding your location" to "Found your location"),
        "calendar_events" to ("Checking your calendar" to "Checked your calendar"),
        "gcal_events" to ("Checking your calendar" to "Checked your calendar"),
        "gmail_search" to ("Searching your mail" to "Searched your mail"),
        "gmail_read" to ("Reading an email" to "Read an email"),
        "read_messages" to ("Reading messages" to "Read messages"),
        "recent_calls" to ("Checking recent calls" to "Checked recent calls"),
        "find_contact" to ("Finding a contact" to "Found a contact"),
        "battery_level" to ("Checking the battery" to "Checked the battery"),
        "device_status" to ("Checking the phone" to "Checked the phone"),
        "list_apps" to ("Listing your apps" to "Listed your apps"),
        "github_repo" to ("Opening a repo" to "Opened a repo"),
        "github_read" to ("Reading code" to "Read code"),
        "github_commits" to ("Checking commits" to "Checked commits"),
        "github_search_code" to ("Searching code" to "Searched code"),
        "github_pr" to ("Checking a pull request" to "Checked a pull request"),
    )

    /** The arguments that name what a call is about, most telling first (numbers like lat / lon never show). */
    private val MAIN = listOf("query", "q", "city", "place", "location", "title", "summary", "name", "contact", "to", "from", "app",
        "repo", "path", "file", "url", "setting", "text", "prompt", "days")

    const val DETAIL_MAX = 36

    /** The row's words for [name] with [args]; [base] = the app's label; [place] = the locality this turn found, if any. */
    fun label(name: String, args: Map<String, String>, running: Boolean, base: String, place: String = ""): String {
        val n = name.lowercase()
        val words = OWN[n]?.let { if (running) it.first else it.second } ?: base
        val d = detail(n, args, place)
        return if (d.isBlank() || words.contains(d, ignoreCase = true)) words else "$words \u00b7 $d"
    }

    /** The call's main argument, short: "07:00" for an alarm, "10 min" for a timer, the host of a url, else the first named one. */
    fun detail(name: String, a: Map<String, String>, place: String = ""): String {
        fun q(s: String) = s.trim().removeSurrounding("\"").trim()
        when (name) {
            "set_alarm" -> return ResultCards.alarm(a)?.time.orEmpty()
            "set_timer" -> return a["minutes"]?.toIntOrNull()?.let { "$it min" } ?: a["seconds"]?.toIntOrNull()?.let { "$it s" }.orEmpty()
            "get_weather", "get_location" -> return place
            "calendar_events", "gcal_events" -> return a["days"]?.toIntOrNull()?.let { if (it <= 1) "today" else "next $it days" }.orEmpty()
        }
        for (k in MAIN) {
            val v = a[k]?.let(::q)?.takeIf { it.isNotBlank() && it != "null" && it.toDoubleOrNull() == null } ?: continue
            val shown = if (k == "url" || v.startsWith("http")) LivingModel.host(v) else if (k == "path" || k == "file") v.substringAfterLast('/') else v
            return LivingModel.clip(shown.replace(Regex("\\s+"), " "), DETAIL_MAX)
        }
        return ""
    }

    /** Rows one after the other with the same words (and kind) fold into one, "×2": the last state and output win. */
    fun merge(rows: List<LivingModel.Row>): List<LivingModel.Row> {
        val out = ArrayList<LivingModel.Row>()
        var count = 1
        fun base(t: String) = t.replace(Regex(" \u00d7\\d+$"), "")
        for (r in rows) {
            val prev = out.lastOrNull()
            if (prev != null && !r.command && !prev.command && base(prev.text) == r.text) {
                count++
                out[out.lastIndex] = r.copy(text = "${r.text} \u00d7$count", output = r.output ?: prev.output)
            } else { out += r; count = 1 }
        }
        return out
    }
}
