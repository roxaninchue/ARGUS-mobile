package com.blaze.assistant.agent

import org.json.JSONObject

/**
 * v0.44.0 verify before claiming. Every tool result the model sees now ends with an explicit status line:
 *
 *  - `[verified=true]` the tool read back what it did (a file's size, a setting's new state, the app in front),
 *    or it only looked something up, or it changed Blaze's own data (memory, routines,
 *    designs) where success IS the result.
 *  - `[verified=false: …]` it ran but nothing confirmed the effect: an intent handed to another app (timer, call,
 *    media), a settings page opened instead of the setting changed, an app that isn't in front.
 *  - `[verified=false: FAILED …]` it failed, or its check failed.
 *
 * [classify] is pure (the result's own words); [check] adds cheap post-condition probes for the tools that act outside
 * Blaze ([Probes], Android side in VerifyProbes). The system prompt ([PROMPT_RULE]) forbids claiming success without
 * verified=true, and ClaimCheck rewrites a final reply that still does. Pure Kotlin; ToolVerifyTest.
 */
object ToolVerify {
    enum class Status { VERIFIED, UNVERIFIED, FAILED }

    data class Verdict(val status: Status, val why: String = "") {
        val ok get() = status == Status.VERIFIED
    }

    /** Post-condition checks. null = can't tell (no permission, not installed): the verdict stays unverified. */
    interface Probes {
        /** Package of the app in front now. */
        suspend fun foreground(): String? = null
        /** Blaze's own package (an app that "opened" while Blaze is still in front didn't open). */
        val self: String get() = "com.blaze.assistant"
        /** (hour, minute) of the next alarm the system knows about. */
        suspend fun nextAlarm(): Pair<Int, Int>? = null
        /** Size in bytes of a file Blaze wrote (-1 = missing, null = can't tell). */
        fun fileSize(path: String): Long? = null
        /** v0.45.0 (#4): the setting read back from the system (wifi, bluetooth, airplane, do_not_disturb, mobile_data); null = can't tell. */
        suspend fun settingOn(setting: String): Boolean? = null
        /** v0.45.13: alarm [pid] at [hhmm] reads ON in the Clock app's list (phone control); null = can't read. */
        suspend fun clockAlarmOn(pid: String, hhmm: String): Boolean? = null
        /** v0.45.13: the calendar provider has an event titled [title] starting at [startMs] (±1 min); null = can't read. */
        suspend fun calendarHas(title: String, startMs: Long): Boolean? = null
        /** v0.45.13: the contacts provider has [name] (with [phone] when given); null = can't read. */
        suspend fun contactHas(name: String, phone: String): Boolean? = null
        /** v0.45.13: a Clock app timer is running (its ongoing notification); null = can't tell. */
        suspend fun timerRunning(): Boolean? = null
        /** v0.45.13: title of what [pkg] is playing now ("" = nothing playing), null = can't tell. */
        suspend fun nowPlaying(pkg: String): String? = null
        /** v0.45.13: the visible words on the screen (accessibility tree, lower case); null = phone control off. */
        suspend fun screenWords(): String? = null
    }

    object NoProbes : Probes

    private val ic = setOf(RegexOption.IGNORE_CASE)

    /** Tools that act through another app or the system with nothing to read back. */
    val HANDED_OFF = mapOf(
        "call_number" to "the call was handed to the phone app; nothing confirmed it connected",
        "call_contact" to "the call was handed to the phone app; nothing confirmed it connected",
        "media_control" to "the media key was sent; nothing confirmed what is playing",
        "spotify_play" to "Spotify was asked to play; nothing confirmed it is playing",
        "spotify_control" to "Spotify was asked; nothing confirmed the change",
        "system_action" to "the system action was sent; nothing read it back",
        "phone_mode" to "the mode change was sent; nothing read it back",
        "uninstall_app" to "the uninstall screen was opened; the user has to confirm it",
        "github_install_apk" to "the installer was opened; the user has to confirm it",
        "share_to_app" to "the share sheet / app was opened; nothing confirmed it was sent",
        "share_text" to "the share sheet was opened; nothing confirmed it was sent",
        "post_notification" to "the notification was posted; nothing read it back",
    )

    /** Tools whose effect is checked by a probe in [check]. */
    val PROBED = setOf("open_app", "open_settings_page", "open_camera", "open_url", "navigate_to", "set_alarm",
        "export_file", "download_file", "device_setting", "toggle_setting",
        // v0.45.13 verify-after-act: these are read back too.
        "set_timer", "add_calendar_event", "add_contact", "compose_sms", "search_in_app")

    /**
     * v0.45.0 (#4): tools that send something to a person. Only a result that says it went ("Sent to …", "Sent.")
     * counts as done; anything else (opened the chat, drafted, queued, a pop-up) is unverified at best.
     */
    val SEND_TOOLS = setOf("message_contact", "send_sms", "send_text", "reply_to_notification", "notif_reply", "gmail_send", "send_email", "slack_post")

    /** v0.45.0 (#4): settings the system can read back after a change ([Probes.settingOn]). */
    val READABLE_SETTINGS = setOf("wifi", "bluetooth", "airplane", "do_not_disturb", "mobile_data")

    private val sentWords = Regex("""^\s*(?:Sent\b|Message sent\b|Email sent\b|Posted\b|Replied\b|Delivered\b)""", ic)

    private val failWords = Regex("""\b(was NOT|NOT sent|NOT changed|NOT running|isn't running|is not running|never (?:appeared|started|opened)|nothing was (?:sent|done|changed|installed)|didn't (?:open|start|send|work))\b""")
    private val stateRead = Regex("""(?:\bis now\b|\bnow (?:on|off)\b|:\s*(?:on|off)\b|\b(?:flashlight|torch) (?:on|off)\b|\bvolume (?:is|:)\s*\d+|\b(?:on|off) now\b|\bset to\s+\d+)""", ic)
    private val openedOnly = Regex("""^\s*Opened\b[^.]*\b(?:settings|panel|page)\b""", ic)

    private fun firstLine(r: String) = r.trim().lineSequence().firstOrNull().orEmpty().take(140)

    /** The verdict from the result's own words, with no probe. */
    fun classify(name: String, args: JSONObject, result: String): Verdict {
        // v0.45.0: an action OneShot didn't repeat is judged by its earlier result.
        val r = result.trim().let { if (it.startsWith(OneShot.NOTE.trim())) it.removePrefix(OneShot.NOTE.trim()).trim() else it }
        if (r.startsWith("Couldn't confirm", true)) return Verdict(Status.UNVERIFIED, firstLine(r))
        // v0.45.13: a tool that read its own effect back says so with an ActCheck marker; that reading decides.
        ActCheck.verdictOf(r)?.let { return it }
        // v0.45.13: an alarm / timer acted on in the Clock app without a read-back is never done (17:13 "Alarm removed.").
        if (name == "process" && args.optString("action") in setOf("delete", "pause", "resume", "reschedule") &&
            Regex("(?i)^(A\\d+|T\\d+|clock@.*)$").matches(args.optString("id").trim().removePrefix("#")))
            return Verdict(Status.UNVERIFIED, "the Clock app wasn't read back")
        if (r.startsWith("Drafted", true)) return Verdict(Status.UNVERIFIED, "only drafted, not sent")
        if (!ClaimGuard.succeeded(r)) return Verdict(Status.FAILED, firstLine(r))
        if (failWords.containsMatchIn(r.take(400))) return Verdict(Status.FAILED, firstLine(r))
        HANDED_OFF[name]?.let { return Verdict(Status.UNVERIFIED, it) }
        // 0.45.19 Blaze Phone: a phone tool worked; whether its page PROVES a booking / order is in [why] ("page verified: …").
        if (name in com.blaze.assistant.browser.phone.PhonePolicy.TOOLS) return phoneVerdict(r)
        if (name in SEND_TOOLS) return if (sentWords.containsMatchIn(r)) Verdict(Status.VERIFIED, firstLine(r))
            else Verdict(Status.UNVERIFIED, "no “Sent” confirmation came back")
        return when (name) {
            "device_setting", "toggle_setting" -> when {
                stateRead.containsMatchIn(r) -> Verdict(Status.VERIFIED)
                openedOnly.containsMatchIn(r) -> Verdict(Status.UNVERIFIED, "only the settings screen was opened; the setting itself was not changed")
                else -> Verdict(Status.UNVERIFIED, "the new state wasn't read back")
            }
            "file_write", "file_edit" -> if (Regex("""\(\d+ bytes""").containsMatchIn(r)) Verdict(Status.VERIFIED) else Verdict(Status.UNVERIFIED, "the file wasn't read back")
            "add_calendar_event" -> if (r.startsWith("Opened the calendar", true)) Verdict(Status.UNVERIFIED, "the event form was opened; the user still has to tap Save")
                else Verdict(Status.UNVERIFIED, "not checked yet")
            in PROBED -> Verdict(Status.UNVERIFIED, "not checked yet")
            else -> Verdict(Status.VERIFIED)
        }
    }

    /**
     * 0.45.19 Blaze Phone: the newest verification line in a phone tool's result (PhonePolicy.Verdict.line). VERIFIED with
     * why "page verified: …" only when the page itself showed a confirmation; otherwise VERIFIED (the step worked) with
     * why "page not verified", which never backs a "booked / ordered / paid" claim (ClaimCheck BOOKED).
     */
    fun phoneVerdict(r: String): Verdict {
        val last = r.lineSequence().map { it.trim() }.lastOrNull { it.startsWith("VERIFIED:") || it.startsWith("NOT VERIFIED:") || it.startsWith("SIGNED IN:") }
        return if (last != null && last.startsWith("VERIFIED:")) Verdict(Status.VERIFIED, "page verified: " + last.removePrefix("VERIFIED:").trim().take(120))
            else Verdict(Status.VERIFIED, "page not verified" + if (last != null && last.startsWith("SIGNED IN:")) " (signed in)" else "")
    }

    /** v0.45.13: the destination of a Maps directions link (google.com/maps/dir/?…destination=X, google.navigation:q=X), else null. */
    fun mapsDest(url: String): String? {
        val u = url.trim()
        if (!Regex("(?i)google\\.[a-z.]+/maps/dir|maps\\.google\\.[a-z.]+/.*daddr=|^google\\.navigation:").containsMatchIn(u)) return null
        val raw = Regex("(?i)[?&](?:destination|daddr)=([^&]+)").find(u)?.groupValues?.get(1)
            ?: Regex("(?i)^google\\.navigation:q=([^&]+)").find(u)?.groupValues?.get(1)
            ?: Regex("(?i)/maps/dir/[^/]*/([^/?]+)").find(u)?.groupValues?.get(1)
            ?: return ""
        return try { java.net.URLDecoder.decode(raw, "UTF-8") } catch (_: Exception) { raw }
    }

    private val routeWords = Regex("\\b(\\d+ ?min|\\d+ ?hr|\\d+(?:[.,]\\d+)? ?km|\\d+(?:[.,]\\d+)? ?mi|start|steps)\\b")

    /** Maps in front and its screen names [dest] with a route (minutes / km / Start): directions really showing. */
    private suspend fun mapsRoute(dest: String, p: Probes, waitMs: Long, poll: suspend (Long, suspend () -> Boolean) -> Boolean): Verdict? {
        if (p.foreground() == null) return null
        val words = keyWords(dest)
        return if (poll(maxOf(waitMs, 3000)) { p.foreground().orEmpty().contains("maps", true) && p.screenWords().orEmpty().let { w -> (words.isEmpty() || words.any { it in w }) && routeWords.containsMatchIn(w) } })
            Verdict(Status.VERIFIED, "Maps is in front and shows a route to ${dest.take(40)}")
        else if (p.foreground().orEmpty().contains("maps", true)) Verdict(Status.UNVERIFIED, "Maps is open but no route to ${dest.take(40)} showed up yet")
        else Verdict(Status.UNVERIFIED, "Maps didn't come to the front")
    }

    /** v0.45.13: "2026-10-22 15:00" → epoch ms in the phone's zone; null when it doesn't parse. */
    fun startMs(s: String): Long? = try {
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).parse(s.trim())?.time
    } catch (_: Exception) { null }

    /** v0.45.13: search_in_app's app name → the package whose media session proves "playing". */
    fun mediaPkg(app: String): String? = when (app.trim().lowercase()) {
        "youtube" -> "com.google.android.youtube"
        "youtube music", "yt music" -> "com.google.android.apps.youtube.music"
        "spotify" -> "com.spotify.music"
        else -> null
    }

    /** Lower-case words of [s] with 3+ letters (for "the screen shows the destination / the draft"). */
    fun keyWords(s: String): List<String> = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 }.take(4)

    /** "Opened WhatsApp (com.whatsapp)." / "Opened com.whatsapp." → the package. */
    fun packageIn(result: String): String? =
        Regex("""\(([a-z][a-z0-9_]*(?:\.[a-z0-9_]+)+)\)""").find(result)?.groupValues?.get(1)
            ?: Regex("""^Opened ([a-z][a-z0-9_]*(?:\.[a-z0-9_]+)+)""").find(result.trim())?.groupValues?.get(1)

    /** A path Blaze wrote that the result names ("Saved to /sdcard/Download/a.pdf", "Downloaded … → /x/y"). */
    fun pathIn(result: String, args: JSONObject): String? =
        args.optString("path").takeIf { it.startsWith("/") }
            ?: Regex("""(/(?:sdcard|storage|data|root)[^\s"'),]+)""").find(result)?.groupValues?.get(1)?.trimEnd('.')

    /**
     * [classify] plus the cheap post-condition checks (each a few hundred ms at most; [poll] re-reads up to [waitMs]
     * for things that take a moment, like an app coming to the front).
     */
    suspend fun check(name: String, args: JSONObject, result: String, p: Probes = NoProbes, waitMs: Long = 1500,
                      poll: suspend (Long, suspend () -> Boolean) -> Boolean = ::defaultPoll): Verdict {
        val v = classify(name, args, result)
        // v0.45.0 (#4): a setting the system can read back is always read back (the tool's own words aren't proof).
        if ((name == "device_setting" || name == "toggle_setting") && v.status != Status.FAILED) {
            settingCheck(args, p, waitMs, poll)?.let { return it }
            return v
        }
        if (v.status != Status.UNVERIFIED || name !in PROBED) return v
        return try { probe(name, args, result, p, waitMs, poll) ?: v } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Throwable) { v }
    }

    private suspend fun probe(name: String, args: JSONObject, result: String, p: Probes, waitMs: Long,
                              poll: suspend (Long, suspend () -> Boolean) -> Boolean): Verdict? = when (name) {
        "open_app" -> {
            val want = packageIn(result)
            if (p.foreground() == null) Verdict(Status.UNVERIFIED, "couldn't see which app is in front (phone control is off)")
            else if (want != null && poll(waitMs) { p.foreground() == want }) Verdict(Status.VERIFIED, "$want is in front")
            else if (want == null && poll(waitMs) { p.foreground().let { it != null && it != p.self } }) Verdict(Status.VERIFIED, "${p.foreground()} is in front")
            else Verdict(Status.UNVERIFIED, "${want ?: "the app"} isn't in front (${p.foreground()} is)")
        }
        "open_settings_page" -> if (p.foreground() == null) null
            else if (poll(waitMs) { p.foreground().orEmpty().contains("settings") }) Verdict(Status.VERIFIED, "Settings is in front")
            else Verdict(Status.UNVERIFIED, "Settings isn't in front")
        "navigate_to" -> mapsRoute(args.optString("place").ifBlank { args.optString("destination") }, p, waitMs, poll)
        "open_url" -> mapsDest(args.optString("url"))?.let { mapsRoute(it, p, waitMs, poll) }
            ?: if (p.foreground() == null) null
            else if (poll(waitMs) { p.foreground().let { it != null && it != p.self } }) Verdict(Status.VERIFIED, "${p.foreground()} is in front")
            else Verdict(Status.UNVERIFIED, "no other app came to the front")
        "open_camera" -> if (p.foreground() == null) null
            else if (poll(waitMs) { p.foreground().let { it != null && it != p.self } }) Verdict(Status.VERIFIED, "${p.foreground()} is in front")
            else Verdict(Status.UNVERIFIED, "no other app came to the front")
        "set_alarm" -> {
            val h = args.optInt("hour", -1); val m = args.optInt("minute", 0)
            val pid = Regex("""\[ID (A\d+)]""").find(result)?.groupValues?.get(1)
            if (poll(waitMs) { p.nextAlarm() == (h to m) }) Verdict(Status.VERIFIED, "the system lists the next alarm at %02d:%02d".format(h, m))
            // v0.45.13: an earlier alarm hides this one from nextAlarmClock; read the Clock app's list instead.
            else if (pid != null && p.clockAlarmOn(pid, "%02d:%02d".format(h, m)) == true) Verdict(Status.VERIFIED, "the Clock app lists alarm $pid at %02d:%02d, switched on".format(h, m))
            else Verdict(Status.UNVERIFIED, "the Clock app didn't list an alarm at %02d:%02d as the next one".format(h, m))
        }
        "set_timer" -> when (p.timerRunning()) {
            null -> null
            true -> Verdict(Status.VERIFIED, "the Clock app shows a running timer")
            false -> if (poll(waitMs) { p.timerRunning() == true }) Verdict(Status.VERIFIED, "the Clock app shows a running timer")
                else Verdict(Status.UNVERIFIED, "no running timer showed up in the Clock app")
        }
        "add_calendar_event" -> {
            val t0 = startMs(args.optString("start"))
            val title = args.optString("title")
            if (t0 == null || title.isBlank() || result.trim().startsWith("Opened the calendar", true)) null else when (p.calendarHas(title, t0)) {
                null -> null
                true -> Verdict(Status.VERIFIED, "the phone's calendar lists \u201c$title\u201d at ${args.optString("start").trim()}")
                false -> if (poll(waitMs) { p.calendarHas(title, t0) == true }) Verdict(Status.VERIFIED, "the phone's calendar lists \u201c$title\u201d")
                    else Verdict(Status.FAILED, "the calendar has no \u201c$title\u201d at ${args.optString("start").trim()}")
            }
        }
        "add_contact" -> {
            val nm = args.optString("name")
            when (p.contactHas(nm, args.optString("phone"))) {
                null -> null
                true -> Verdict(Status.VERIFIED, "Contacts lists $nm" + args.optString("phone").takeIf { it.isNotBlank() }?.let { " with $it" }.orEmpty())
                false -> Verdict(Status.FAILED, "Contacts has no $nm")
            }
        }
        "compose_sms" -> {
            val words = keyWords(args.optString("message"))
            if (p.foreground() == null) null
            else if (poll(waitMs) { p.foreground().let { it != null && it != p.self } && p.screenWords().orEmpty().let { w -> words.isNotEmpty() && words.all { it in w } } })
                Verdict(Status.VERIFIED, "the draft is in ${p.foreground()}'s message box and has NOT been sent")
            else Verdict(Status.UNVERIFIED, "the drafted text didn't show up in a message box")
        }
        "search_in_app" -> {
            val pkg = mediaPkg(args.optString("app"))
            if (args.optBoolean("play") && pkg != null) {
                val words = keyWords(args.optString("query"))
                val got = if (poll(maxOf(waitMs, 4000)) { p.nowPlaying(pkg).orEmpty().lowercase().let { t -> t.isNotBlank() && (words.isEmpty() || words.any { it in t }) } }) p.nowPlaying(pkg) else p.nowPlaying(pkg)
                when {
                    got == null -> null
                    got.isNotBlank() && (words.isEmpty() || words.any { it in got.lowercase() }) -> Verdict(Status.VERIFIED, "${args.optString("app")} is playing \u201c${got.take(60)}\u201d")
                    got.isNotBlank() -> Verdict(Status.UNVERIFIED, "${args.optString("app")} is playing \u201c${got.take(60)}\u201d, which doesn't match the ask")
                    else -> Verdict(Status.UNVERIFIED, "the search opened but nothing is playing yet; tap the right video, then check again")
                }
            } else if (p.foreground() == null) null
            else if (poll(waitMs) { p.foreground().let { it != null && it != p.self } }) Verdict(Status.VERIFIED, "${p.foreground()} shows the search (opened only: finish the task)")
            else Verdict(Status.UNVERIFIED, "the app didn't come to the front")
        }
        "export_file", "download_file" -> pathIn(result, args)?.let { path ->
            when (val n = p.fileSize(path)) {
                null -> null
                in 1..Long.MAX_VALUE -> Verdict(Status.VERIFIED, "$path exists ($n bytes)")
                else -> Verdict(Status.FAILED, "$path is missing or empty")
            }
        }
        else -> null
    }

    /** "on"/"off"-ish value → wanted state (null = not a switch: volume levels, "toggle"). */
    fun wanted(value: String): Boolean? = when (value.trim().lowercase()) {
        "on", "enable", "enabled", "true", "1", "yes" -> true
        "off", "disable", "disabled", "false", "0", "no" -> false
        else -> null
    }

    private suspend fun settingCheck(args: JSONObject, p: Probes, waitMs: Long, poll: suspend (Long, suspend () -> Boolean) -> Boolean): Verdict? {
        val s = args.optString("setting").trim().lowercase()
        if (s !in READABLE_SETTINGS) return null
        val want = wanted(args.optString("value")) ?: return null
        if (p.settingOn(s) == null) return null
        val label = s.replace('_', ' ').replace("wifi", "Wi-Fi")
        return if (poll(waitMs) { p.settingOn(s) == want }) Verdict(Status.VERIFIED, "$label reads ${if (want) "on" else "off"}")
            else Verdict(Status.UNVERIFIED, "$label still reads ${if (want) "off" else "on"}")
    }

    private suspend fun defaultPoll(waitMs: Long, ok: suspend () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + waitMs
        while (true) {
            if (ok()) return true
            if (System.currentTimeMillis() >= end) return false
            kotlinx.coroutines.delay(250)
        }
    }

    /** The result as the model sees it: the original text plus one status line. */
    fun tag(result: String, v: Verdict): String {
        val line = when (v.status) {
            Status.VERIFIED -> "[verified=true]"
            Status.UNVERIFIED -> "[verified=false: ${v.why.ifBlank { "couldn't confirm it worked" }}. Don't say it worked; say you tried and couldn't confirm it.]"
            Status.FAILED -> "[verified=false: FAILED: ${v.why.ifBlank { "it didn't work" }}. Tell the user plainly it didn't work.]"
        }
        return result.trimEnd() + "\n" + line
    }

    /** True when [result] already carries a status line (a result handed on twice is tagged once). */
    fun tagged(result: String): Boolean = result.trimEnd().let { it.endsWith("]") && it.substringAfterLast('\n').startsWith("[verified=") }

    /** Static system-prompt rule (stays in the cached prefix). */
    const val PROMPT_RULE = "\nVERIFIED RESULTS: every tool result ends with [verified=true] or [verified=false: why]. " +
        "Say something worked (sent, set, opened, started, running, saved, turned on, installed, playing) ONLY when its result in THIS turn says verified=true. " +
        "When it says verified=false, say plainly \u201cI tried to … but couldn't confirm it\u201d (or that it failed and why) and offer the next step; " +
        "for a check you can do (a screenshot, read_screen) do it first. Earlier messages saying it worked are not proof. " +
        "A result marked \u201cchecked on the phone\u201d needs no extra check. verified=pending means the phone is still checking: say you started it, not that it's done. " +
        "Opening an app or a settings page is only the first step of a task, never the task: keep going (read_screen, tap, type, search) until the thing the user asked for is read back verified=true. " +
        "Say the exact time, name or value the tool result reports (an alarm set for 18:15 is 18:15)."
    // ---------------- P2-SF-07: verified done_job (A team v2.7 AT-20: chat.js verifyJob / checkFile) ----------------
    // Ported from A team app/www/chat.js (owner grant, MIT). A job is done only when its result exists, checks out and was
    // shown: a deliverable job needs a non-empty file written in this run; HTML must look like HTML and its inline scripts
    // must parse, JSON must parse, JS must parse, Python must py_compile and shell scripts must pass bash -n; code changed
    // after the last passing check (the CompletenessGate) blocks it; the file must have been attached / shown. A job with no
    // deliverable must at least have said something to the user. Cases: A team app/tests/v27.test.js §9 + the A team
    // file-check rules, as JUnit in ToolVerifyDoneTest. The problems go back to the model as the done_job result
    // ("not done: …"), and after the retry budget the job ends blocked with them.

    /** A workspace path's state (null from [DoneProbes.stat] = it does not exist or can't be read). */
    data class FileStat(val dir: Boolean = false, val size: Long = 0, val count: Int = 0)

    /** What done_job verification needs from the phone. Every method may say "can't tell" (null). */
    interface DoneProbes {
        /** The file or folder at [path], or null when it doesn't exist. */
        fun stat(path: String): FileStat? = null
        /** Text of [path] (up to a few MB), or null when it can't be read. */
        fun read(path: String): String? = null
        /** Run [cmd] in the Linux workspace: (exit code, output), or null when there is no workspace. */
        suspend fun exec(cmd: String): Pair<Int, String>? = null
        /** Does [code] parse as JavaScript? The error message, or null. The phone can use QuickJS / a WebView; default is [JsSyntax]. */
        fun jsError(code: String): String? = JsSyntax.error(code)
    }

    /** A job title that asks for something made (a file, page, script, image, document…). */
    val DELIVERABLE = Regex("""\b(create|build|make|write|generate|design|code|develop|draft|produce|game|website|site|web ?page|landing|app|script|program|document|doc|report|pdf|image|picture|logo|deck|slides?|spreadsheet|csv|file|html|page)\b""", ic)
    private val SHOWABLE = Regex("""\.(html?|png|jpe?g|gif|webp|svg|pdf|md|txt|docx?|xlsx?|pptx?|csv|zip|py|js|sh)$""", ic)

    /**
     * Why done_job for [title] can't be accepted yet (empty = done). [written] = files written this run, [attached] = paths
     * shown to the user, [said] = a real reply reached the user, [gate] = the run's CompletenessGate.
     */
    suspend fun doneProblems(title: String, notes: String = "", written: List<String>, attached: List<String>, said: Boolean,
                             gate: com.blaze.assistant.agent.guards.CompletenessGate?, p: DoneProbes): List<String> {
        val probs = ArrayList<String>()
        val files = written.distinct().filter { !it.startsWith("/tmp/") }
        val want = files.isNotEmpty() || DELIVERABLE.containsMatchIn("$title $notes")
        if (!want) {
            if (!said) probs += "you have not delivered anything to the user yet: write the result to them in this chat first"
            return probs
        }
        if (files.isEmpty()) { probs += "no deliverable file was created in this run: make it in the workspace first"; return probs }
        if (gate != null && gate.codeTouched && !gate.verified) probs += "you changed code (${gate.edited.take(3).joinToString(", ")}) but have not run its build, tests or demo successfully since the last edit: run them end to end first, or cut scope to a smaller version that works and say what you cut"
        val real = ArrayList<String>()
        for (f in files) {
            val st = p.stat(f)
            if (st == null) { if (Regex("""\.[a-z0-9]{1,6}$""", ic).containsMatchIn(f)) probs += "$f does not exist"; continue }
            if (st.dir) { if (st.count == 0) probs += "$f is an empty folder"; continue }
            if (st.size <= 0) { probs += "$f is empty"; continue }
            real += f
            checkFile(f, p).takeIf { it.isNotEmpty() }?.let { probs += it }
        }
        if (real.isEmpty() && probs.isEmpty()) probs += "no deliverable file was found"
        val shown = { f: String -> attached.any { it == f || f.startsWith("$it/") } }
        val top = real.filter { SHOWABLE.containsMatchIn(it) }
        if (top.isNotEmpty() && top.none(shown)) probs += "it was not shown to the user: attach ${top[0]}"
        return probs
    }

    private val scriptTag = Regex("""<script\b([^>]*)>([\s\S]*?)</script\s*>""", ic)
    private val moduleSyntax = Regex("""^\s*(import|export)\s""", RegexOption.MULTILINE)

    /** Syntax-level checks that need no network (HTML, JSON, JS, Python, bash). The problem, or "". */
    suspend fun checkFile(f: String, p: DoneProbes): String {
        val ext = f.substringAfterLast('.', "").lowercase()
        if (ext == "html" || ext == "htm") {
            val src = p.read(f) ?: return ""
            if (!Regex("""<(html|body|script|div|canvas|h1|p)\b""", ic).containsMatchIn(src)) return "$f doesn't look like HTML"
            for (m in scriptTag.findAll(src)) {
                val attrs = m.groupValues[1]
                if (Regex("""\bsrc\s*=""", ic).containsMatchIn(attrs)) continue
                val ty = Regex("""\btype\s*=\s*["']?([^"'\s>]+)""", ic).find(attrs)?.groupValues?.get(1)?.lowercase().orEmpty()
                if (ty.isNotEmpty() && !Regex("""javascript|ecmascript|^text/babel$""").containsMatchIn(ty)) continue
                if (moduleSyntax.containsMatchIn(m.groupValues[2])) continue
                p.jsError(m.groupValues[2])?.let { return "$f has a JavaScript error: $it" }
            }
            return ""
        }
        if (ext == "json") { val src = p.read(f) ?: return ""; return JsonStrict.error(src)?.let { "$f is not valid JSON: $it" } ?: "" }
        if (ext == "js" || ext == "cjs") {
            val src = p.read(f) ?: return ""
            if (moduleSyntax.containsMatchIn(src) || src.startsWith("#!")) return ""
            return p.jsError(src)?.let { "$f has a JavaScript error: $it" } ?: ""
        }
        if (ext == "py" || ext == "sh") {
            val q = "'" + f.replace("'", "'\\''") + "'"
            val r = p.exec(if (ext == "py") "python3 -m py_compile $q 2>&1" else "bash -n $q 2>&1") ?: return ""
            if (r.first != 0) return "$f fails its syntax check: " + r.second.trim().split("\n").takeLast(2).joinToString(" ").take(200)
        }
        return ""
    }

    /**
     * A pure JavaScript syntax screen for when no engine is at hand: unbalanced brackets, unterminated strings, template
     * literals, block comments and regex literals. It does not catch every error a real engine would (the phone passes a
     * QuickJS/WebView check through [DoneProbes.jsError]); it never fails code a real engine accepts for these reasons.
     */
    object JsSyntax {
        private val regexBefore = setOf('(', ',', '=', ':', '[', '!', '&', '|', '?', '{', '}', ';', '+', '-', '*', '%', '<', '>', '~', '^')
        private val regexKeywords = setOf("return", "typeof", "instanceof", "in", "of", "new", "delete", "void", "throw", "case", "do", "else", "yield", "await")

        fun error(code: String): String? {
            val stack = ArrayDeque<Pair<Char, Int>>()   // open '(' '[' '{', or '$' for a template's ${ … }, and where
            var i = 0; val n = code.length
            var lastSig = ' '; var lastWord = ""; var sep = false
            fun line(at: Int) = code.substring(0, at.coerceIn(0, n)).count { it == '\n' } + 1
            // Template text from [j]: the index after its closing '`', or after "${" (pushed as '$'); null when unterminated.
            fun tpl(start: Int): Int? {
                var j = start
                while (j < n) {
                    val d = code[j]
                    if (d == '\\') { j += 2; continue }
                    if (d == '`') return j + 1
                    if (d == '$' && j + 1 < n && code[j + 1] == '{') { stack.addLast('$' to j); return j + 2 }
                    j++
                }
                return null
            }
            while (i < n) {
                val c = code[i]
                if (c == '/' && i + 1 < n && code[i + 1] == '/') { while (i < n && code[i] != '\n') i++; continue }
                if (c == '/' && i + 1 < n && code[i + 1] == '*') { val e = code.indexOf("*/", i + 2); if (e < 0) return "unterminated comment (line ${line(i)})"; i = e + 2; continue }
                if (c == '\'' || c == '"') {
                    var j = i + 1
                    while (j < n && code[j] != c) { if (code[j] == '\\') j++ else if (code[j] == '\n') return "unterminated string (line ${line(i)})"; j++ }
                    if (j >= n) return "unterminated string (line ${line(i)})"
                    i = j + 1; lastSig = c; lastWord = ""; continue
                }
                if (c == '`') { i = tpl(i + 1) ?: return "unterminated template literal (line ${line(i)})"; lastSig = '`'; lastWord = ""; continue }
                if (c == '/' && (lastSig in regexBefore || lastSig == ' ' || lastWord in regexKeywords)) {
                    var j = i + 1; var cls = false
                    while (j < n) {
                        val d = code[j]
                        if (d == '\\') j++ else if (d == '[') cls = true else if (d == ']') cls = false else if (d == '/' && !cls) break else if (d == '\n') return "unterminated regular expression (line ${line(i)})"
                        j++
                    }
                    if (j >= n) return "unterminated regular expression (line ${line(i)})"
                    i = j + 1; while (i < n && code[i].isLetter()) i++
                    lastSig = '/'; lastWord = ""; continue
                }
                if (c == '(' || c == '[' || c == '{') stack.addLast(c to i)
                if (c == ')' || c == ']' || c == '}') {
                    val top = stack.removeLastOrNull() ?: return "unexpected '$c' (line ${line(i)})"
                    if (top.first == '$' && c == '}') { i = tpl(i + 1) ?: return "unterminated template literal (line ${line(top.second)})"; lastSig = '`'; lastWord = ""; continue }
                    val want = when (c) { ')' -> '('; ']' -> '['; else -> '{' }
                    if (top.first != want) return "unexpected '$c' (line ${line(i)})"
                }
                if (!c.isWhitespace()) {
                    val idc = c.isLetterOrDigit() || c == '_' || c == '$'
                    lastWord = if (!idc) "" else if (!sep && (lastSig.isLetterOrDigit() || lastSig == '_' || lastSig == '$')) lastWord + c else c.toString()
                    lastSig = c; sep = false
                } else sep = true
                i++
            }
            stack.lastOrNull()?.let { (b, at) -> return if (b == '$') "unterminated template literal (line ${line(at)})" else "unexpected end of input: '$b' (line ${line(at)}) is never closed" }
            return null
        }
    }

    /** A strict JSON (RFC 8259) check, like JSON.parse: the error, or null. org.json is lenient, so it isn't used here. */
    object JsonStrict {
        fun error(src: String): String? = try { val p = P(src); p.ws(); p.value(); p.ws(); if (p.i < src.length) p.fail("unexpected '${src[p.i]}'"); null } catch (e: IllegalArgumentException) { e.message }

        private class P(val s: String) {
            var i = 0
            fun fail(m: String): Nothing = throw IllegalArgumentException("$m at position $i")
            fun ws() { while (i < s.length && s[i] in " \t\n\r") i++ }
            fun lit(w: String) { if (!s.startsWith(w, i)) fail("unexpected token"); i += w.length }
            fun value() {
                if (i >= s.length) fail("unexpected end of JSON input")
                when (s[i]) {
                    '{' -> { i++; ws(); if (i < s.length && s[i] == '}') { i++; return }
                        while (true) { ws(); if (i >= s.length || s[i] != '"') fail("expected a property name"); string(); ws(); if (i >= s.length || s[i] != ':') fail("expected ':'"); i++; ws(); value(); ws()
                            if (i < s.length && s[i] == ',') { i++; continue }; if (i < s.length && s[i] == '}') { i++; return }; fail("expected ',' or '}'") } }
                    '[' -> { i++; ws(); if (i < s.length && s[i] == ']') { i++; return }
                        while (true) { ws(); value(); ws(); if (i < s.length && s[i] == ',') { i++; continue }; if (i < s.length && s[i] == ']') { i++; return }; fail("expected ',' or ']'") } }
                    '"' -> string()
                    't' -> lit("true"); 'f' -> lit("false"); 'n' -> lit("null")
                    else -> number()
                }
            }
            fun string() {
                i++
                while (i < s.length) {
                    val c = s[i]
                    when {
                        c == '"' -> { i++; return }
                        c == '\\' -> { i++; if (i >= s.length) fail("unterminated string"); val e = s[i]
                            if (e == 'u') { if (i + 4 >= s.length || !s.substring(i + 1, i + 5).all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) fail("bad unicode escape"); i += 4 }
                            else if (e !in "\"\\/bfnrt") fail("bad escape") }
                        c < ' ' -> fail("control character in string")
                    }
                    i++
                }
                fail("unterminated string")
            }
            fun number() {
                val m = java.util.regex.Pattern.compile("""-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?""").matcher(s).region(i, s.length)
                if (!m.lookingAt() || m.end() == i) fail("unexpected token")
                i = m.end()
            }
        }
    }
}
