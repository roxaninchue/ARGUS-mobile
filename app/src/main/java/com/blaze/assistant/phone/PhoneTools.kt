package com.blaze.assistant.phone


import com.blaze.assistant.BrandName
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/**
 * The actions Argus can take on the phone, exposed to the AI as tools: all by intent (0.55.11: the screen tools
 * read_screen, look_at_screen, tap, type_text, scroll, press_button and wait went with phone control).
 */
object PhoneTools {

    private fun fn(name: String, desc: String, props: JSONObject = JSONObject(), required: List<String> = emptyList()) =
        JSONObject().put("type", "function").put(
            "function", JSONObject()
                .put("name", name)
                .put("description", desc)
                .put("parameters", JSONObject().put("type", "object").put("properties", props)
                    .put("required", JSONArray(required)))
        )

    private fun p(type: String, desc: String) = JSONObject().put("type", type).put("description", desc)

    fun definitions(): JSONArray = JSONArray().apply {
        put(fn("open_app", "Open an installed app by its name, e.g. 'WhatsApp', 'Camera', 'Settings'.",
            JSONObject().put("app_name", p("string", "App name as shown on the home screen")), listOf("app_name")))
        put(fn("search_in_app", "Open an app straight to search results for a query, with no tapping: YouTube, YouTube Music, Spotify, Play Store, Google Maps, Google, Chrome, TikTok, X, Netflix, Amazon. Use play=true to start playing the top music/video match where the app supports it. Best first step for 'play X on Y' or 'search Y for X'.",
            JSONObject().put("app", p("string", "App name, e.g. YouTube"))
                .put("query", p("string", "What to search for"))
                .put("play", p("boolean", "Try to start playing the first result (music/video apps)")), listOf("app", "query")))
        put(fn("open_url", "Open a web address in the browser, or a Google search for a query.",
            JSONObject().put("url", p("string", "Full URL, or plain words to search Google")), listOf("url")))
        put(fn("dial", "Open the phone dialer with a number filled in (the user presses call).",
            JSONObject().put("number", p("string", "Phone number")), listOf("number")))
        put(fn("compose_sms", "Open the messaging app with a text drafted to a number (the user presses send).",
            JSONObject().put("number", p("string", "Phone number"))
                .put("message", p("string", "Message text")), listOf("number", "message")))
        put(fn("set_alarm", "Set an alarm in the clock app.",
            JSONObject().put("hour", p("integer", "0-23")).put("minute", p("integer", "0-59"))
                .put("label", p("string", "Optional label")), listOf("hour", "minute")))
        put(fn("set_timer", "Start a countdown timer.",
            JSONObject().put("seconds", p("integer", "Length in seconds"))
                .put("label", p("string", "Optional label")), listOf("seconds")))
    }

    /** Human-friendly line shown in the chat while a tool runs. */
    fun describe(name: String, args: JSONObject): String = when (name) {
        "open_app" -> "Opening ${args.optString("app_name")}"
        "open_url" -> "Opening ${args.optString("url").take(40)}"
        "dial" -> "Opening the dialer"
        "compose_sms" -> "Drafting a text"
        "set_alarm" -> "Setting an alarm for %02d:%02d".format(args.optInt("hour"), args.optInt("minute"))
        "set_timer" -> "Starting a timer"
        "search_in_app" -> "Searching ${args.optString("app")} for \u201c${args.optString("query").take(40)}\u201d"
        else -> name
    }

    suspend fun run(ctx: Context, name: String, args: JSONObject): String {
        return try {
            when (name) {
                "open_app" -> openApp(ctx, args.getString("app_name"))
                "search_in_app" -> AppSearch.run(ctx, args.optString("app"), args.optString("query"), args.optBoolean("play"))
                "open_url" -> {
                    val raw = com.blaze.assistant.agent.ToolLeak.fixBlazeUrl(args.getString("url").trim())
                    val url = if (raw.startsWith("http://") || raw.startsWith("https://")) raw
                    else if (raw.contains('.') && !raw.contains(' ')) "https://$raw"
                    else "https://www.google.com/search?q=" + Uri.encode(raw)
                    // v0.33.3: a Play Store page for an app that is installed opens the app instead; a details link
                    // for an app that isn't there is checked first (a guessed id shows "Item not found").
                    AppFinder.playId(url)?.let { id ->
                        if (InstalledApps.launch(ctx, id)) return "That app ($id) is installed, so I opened it instead of the Play Store. Never offer to install it."
                        if (kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { playMissing(id) })
                            return "There is no Play Store app with the id $id (a guessed id). Don't guess ids: open a Play Store search instead, " +
                                "https://play.google.com/store/search?q=<name>&c=apps, or check installed apps with list_apps."
                    }
                    start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    "Opened $url"
                }
                "dial" -> { start(ctx, Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + args.getString("number")))); "Dialer opened." }
                "compose_sms" -> {
                    val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + args.getString("number")))
                        .putExtra("sms_body", args.getString("message"))
                    start(ctx, i); "Message drafted; the user needs to press send."
                }
                "set_alarm" -> {
                    // The registry ID goes into the clock label so the alarm can be found and deleted later.
                    val h = args.getInt("hour"); val m = args.getInt("minute")
                    val pid = com.blaze.assistant.agent.Processes.nextId("alarm")
                    val name = args.optString("label").trim()
                    val label = (if (name.isNotBlank()) "$name " else "") + "(${BrandName.NAME} $pid)"
                    val i = Intent(AlarmClock.ACTION_SET_ALARM)
                        .putExtra(AlarmClock.EXTRA_HOUR, h)
                        .putExtra(AlarmClock.EXTRA_MINUTES, m)
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                        .putExtra(AlarmClock.EXTRA_MESSAGE, label)
                    start(ctx, i)
                    com.blaze.assistant.agent.Processes.register("alarm", name.ifBlank { "Alarm %02d:%02d".format(h, m) }, label,
                        com.blaze.assistant.agent.Processes.nextAt(h, m),
                        org.json.JSONObject().put("label", label).put("name", name).put("time", "%02d:%02d".format(h, m)), id = pid)
                    // v0.45.13 (phone 17:12, "reminder at 18:30" for an 18:15 alarm): the result names the exact time and label set.
                    "Alarm set for ${"%02d:%02d".format(h, m)}, label \u201c$label\u201d. [ID $pid]"
                }
                "set_timer" -> {
                    val secs = args.getInt("seconds")
                    val i = Intent(AlarmClock.ACTION_SET_TIMER)
                        .putExtra(AlarmClock.EXTRA_LENGTH, secs)
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    if (args.has("label")) i.putExtra(AlarmClock.EXTRA_MESSAGE, args.getString("label"))
                    start(ctx, i)
                    val pid = com.blaze.assistant.agent.Processes.register("timer",
                        args.optString("label").ifBlank { if (secs >= 60) "${secs / 60} min timer" else "$secs s timer" },
                        System.currentTimeMillis().toString(), System.currentTimeMillis() + secs * 1000L,
                        org.json.JSONObject().put("seconds", secs))
                    "Timer started for ${if (secs >= 60) "${secs / 60} min" + (if (secs % 60 > 0) " ${secs % 60} s" else "") else "$secs s"}. [ID $pid]"
                }
                else -> "Unknown tool $name"
            }
        } catch (e: ActivityNotFoundException) {
            "No app on this phone can do that."
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    /** True only when Play answers 404 for this id (network trouble or anything else = not proven missing). */
    private fun playMissing(id: String): Boolean = try {
        val c = java.net.URL("https://play.google.com/store/apps/details?id=" + Uri.encode(id) + "&hl=en").openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 5000; c.readTimeout = 5000; c.instanceFollowRedirects = true; c.requestMethod = "GET"
        try { c.responseCode == 404 } finally { c.disconnect() }
    } catch (_: Exception) { false }

    private fun start(ctx: Context, i: Intent) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }

    private suspend fun openApp(ctx: Context, name: String): String {
        val pm = ctx.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)
        AppFinder.pkgIn(name)?.let { p -> pm.getLaunchIntentForPackage(p)?.let { i ->
            start(ctx, i)
            return "Opened $p. It is installed, so never offer to install it." } }
        val q = AppMatch.clean(name)
        if (q.isEmpty()) return "Couldn't tell which app '$name' means. Ask the user, or use the earlier context."
        // v0.45.6: Argus's own screens open in-app (open_view), never by launching a look-alike app.
        AppMatch.blazeView(name)?.let { v -> return "\u201c$name\u201d is part of ${BrandName.NAME} itself: call open_view with view=$v (or music_ops for playback) instead of open_app." }
        val labelled = apps.map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
        // v0.45.6: exact package or exact label only, never a fuzzy match ("Blaze*" opened BlazeFX).
        val best = AppMatch.exact(name, labelled, ctx.packageName)
            ?: return AppMatch.near(name, labelled, ctx.packageName).let { near ->
                if (near.isNotEmpty()) "No installed app is called exactly '$name'. Close names: ${near.joinToString(", ")}. Ask the user which one they mean; never open a near match yourself."
                else "No installed app matches '$name' exactly (checked every launchable app by name and package). Installed apps include: " +
                    labelled.map { it.first }.sorted().take(60).joinToString(", ") +
                    ". Only if the user wants to get it: open a Play Store SEARCH (https://play.google.com/store/search?q=...&c=apps), never a guessed details?id= link."
            }
        val intent = pm.getLaunchIntentForPackage(best.first)
            ?: return "Couldn't open ${best.second}."
        start(ctx, intent)
        return "Opened ${best.second} (${best.first}). It is installed, so never offer to install it."
    }
}
