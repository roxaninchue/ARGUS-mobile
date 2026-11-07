package com.blazeinchue.argus.ui


import com.blazeinchue.argus.BrandName
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Every screen Argus has, in one place. Nothing was deleted in the 0.12 redesign: pages left the
 * sidebar and live here, reachable from /views, their own /command, Search, and open_view.
 * v0.24.0: the Archive (old chat list) is gone with the conversation switcher; old chats stay on disk and in
 * the journal, so Argus still finds them with search_chat / search_past_chats.
 *
 * [page] = a settings page id ("" = the settings list); [special] = a screen that isn't a settings page.
 */
data class ArgusView(val id: String, val name: String, val desc: String, val page: String? = null,
                     val special: String? = null, val aliases: List<String> = emptyList())

object Views {
    val all = listOf(
        // v0.39.0: Home (the Home cards). In Simple mode it sits under the chat: swipe up for Chat, pull down for Home.
        ArgusView("home", "Home", "The ${BrandName.UPPER} Home: the living orb, the ask bar and Spotlight (pull the top notch down). ${BrandName.NAME} opens here", special = "home", aliases = listOf("home screen", "board", "widgets", "heads-ups", "notifications page")),
        ArgusView("projects", "Projects", "Ongoing efforts with their own threads, brief and instructions", special = "projects", aliases = listOf("project")),
        ArgusView("design", "Design", "Live preview of anything visual: 3D scenes, art, sites, UI mockups, diagrams, charts, posters, slides", special = "design", aliases = listOf("preview", "studio", "3d", "designs")),
        ArgusView("memory", "Memory", "What ${BrandName.NAME} remembers and has learned", page = "memory", aliases = listOf("learning")),
        ArgusView("learn", "Learn", "Flashcards with spaced repetition, Classroom, low-data mode", page = "learn", aliases = listOf("flashcards", "study", "student", "classroom")),
        ArgusView("seller", "Seller", "Brand kit, store digest, posts, ads and listings", page = "seller", aliases = listOf("store", "shop", "marketing", "marketer", "brand")),
        ArgusView("terminal", "Terminal", "A real terminal: your Alpine Linux or the phone shell, Termux-style keys and tabs", special = "terminal", aliases = listOf("shell", "console", "termux", "bash", "alpine", "pty")),
        ArgusView("jobs", "Jobs", "Running and past jobs, the job log", page = "terminal", aliases = listOf("job", "job log")),
        ArgusView("schedule", "Schedule", "Routines and timed tasks", page = "routines", aliases = listOf("routines")),
        ArgusView("agents", "Agents", "Specialists and background agents", page = "specialists", aliases = listOf("specialists")),
        ArgusView("accounts", "Accounts", "Connected apps: Google, GitHub, Spotify, Notion", page = "connections", aliases = listOf("connections", "connectors")),
        ArgusView("router", "Brain routing", "${BrandName.NAME} Cloud picks the model for every message", page = "ai", aliases = listOf("argusrouter", "dashboard-router")),
        ArgusView("brain", "Brain", "${BrandName.NAME} Cloud: the best free model for every message", page = "ai", aliases = listOf("models", "model", "keys", "classic", "cloud")),
        ArgusView("skills", "Skills", "Skill library: on, off, restore", page = "skills"),
        ArgusView("music", "Music", "${BrandName.NAME} Music: your songs, playlists and a Store (Jamendo, Internet Archive" + (if (com.blazeinchue.argus.mediaapps.store.StoreSources.get("youtube") != null) ", YouTube" else "") + ") with downloads", special = "music", aliases = listOf("songs", "player", "music player", "playlist", "playlists")),
        ArgusView("video", "Video", "${BrandName.NAME} Video: your videos, continue watching, and a Store (public-domain films, Blender open movies" + (if (com.blazeinchue.argus.mediaapps.store.StoreSources.get("youtube") != null) ", YouTube" else "") + ")", special = "video", aliases = listOf("videos", "movies", "films", "video player")),
        ArgusView("apps", "App Store", "Free open-source apps from their developers' own releases. Not made by Blazeinchue; install at your own risk", page = "appstore", aliases = listOf("app store", "appstore", "open source apps", "foss apps", "get apps")),
        ArgusView("gallery", "Gallery", "Images, music and files ${BrandName.NAME} made", special = "gallery"),
        ArgusView("browser", "Browser", "${BrandName.NAME}'s own browser and tabs", special = "browser"),
        ArgusView("phonebrowser", "${BrandName.NAME} Phone", "${BrandName.NAME}'s own phone browser: watch it log in and book, take control, hand back", special = "phonebrowser", aliases = listOf("argus phone", "phone browser", "argus phone browser", "argus's phone")),
        ArgusView("doctor", "Doctor", "Phone health, self-check, crash report", page = "device", aliases = listOf("device", "diagnostics")),
        ArgusView("usage", "Usage", "Tokens and estimated cost", page = "usage", aliases = listOf("cost")),
        ArgusView("voice", "Voice", "Hey ${BrandName.NAME}, spoken replies, call", page = "voice"),
        ArgusView("control", "Phone control", "Accessibility, permissions", page = "control"),
        ArgusView("logins", "Saved logins", "Sign-ins from the secure form: view, edit, delete", page = "logins", aliases = listOf("vault", "passwords", "logins")),
        ArgusView("secrets", "Saved secrets", "Keys, tokens, logins and cards from the Secure Form", page = "secrets", aliases = listOf("saved secrets", "api keys", "tokens", "secrets")),
        ArgusView("proactive", "Proactive", "Speak up on its own, triggers", page = "proactive", aliases = listOf("triggers")),
        ArgusView("desktop", "${BrandName.NAME} Desktop", "Pair with ${BrandName.NAME} on your computer", page = "desktop", aliases = listOf("pc", "pair")),
        ArgusView("commands", "Commands", "Slash commands and prompt skills", page = "commands"),
        ArgusView("dashboard", "Dashboard", "Local web dashboard", page = "dashboard"),
        ArgusView("account", "${BrandName.NAME} account", "Sign in, shared with Desktop", page = "account"),
        // v0.40.0: optional end-to-end encrypted live sync between your phones (off by default).
        ArgusView("sync", "Cloud sync", "Keep chats, memory, settings and designs the same on all your phones: on/off, passphrase, what syncs, Sync now, delete the cloud copy", page = "sync", aliases = listOf("cloud sync", "sync", "backup to cloud", "other phone", "sync phones")),
        // v0.39.0 Simple mode: what Argus is doing on its own, and the living backgrounds.
        ArgusView("doing", "Things ${BrandName.NAME} is doing", "Routines and reminders with friendly names and on/off, and a \u201cToday I\u2026\u201d diary", page = "doing", aliases = listOf("things", "doing now", "diary", "activity", "today i")),
        ArgusView("look", "Look & feel", "Light / Dark / Auto, accent colour, the Home's tilt effect", page = "look", aliases = listOf("theme", "themes", "background", "backgrounds", "wallpaper", "look and feel", "look & feel")),
        ArgusView("settings", "Settings", "Behaviour, voice, privacy, backup", page = ""),
    )

    fun find(id: String): ArgusView? {
        val k = id.trim().lowercase().removePrefix("/")
        return all.firstOrNull { it.id == k } ?: all.firstOrNull { k in it.aliases }
    }

    fun filter(q: String): List<ArgusView> {
        val f = q.trim().lowercase()
        if (f.isEmpty()) return all
        return all.filter { (it.name + " " + it.desc + " " + it.id + " " + it.aliases.joinToString(" ")).lowercase().contains(f) }
    }

    /** A request to open a view (from open_view, a /command, Search). ChatScreen consumes it. */
    data class Request(val id: String, val target: String = "", val n: Long = System.nanoTime())
    val request = MutableStateFlow<Request?>(null)
    fun open(id: String, target: String = "") { request.value = Request(id, target) }

    // ---------------- v0.24.6: ask before switching ----------------

    /** "Open in Design?": a chip in the chat that opens the view on one tap (Blazeinchue: Argus asks, it doesn't jump). */
    data class Offer(val id: String, val target: String = "", val n: Long = System.nanoTime())
    val offer = MutableStateFlow<Offer?>(null)

    fun offerLabel(id: String): String = "Open in ${find(id)?.name ?: id.replaceFirstChar { it.uppercase() }}?"

    /** True when the user's own words asked to open / show / switch to this view (then it opens straight away). */
    fun askedFor(text: String, id: String): Boolean {
        val v = find(id) ?: return false
        val names = (listOf(v.id, v.name) + v.aliases).map { Regex.escape(it.lowercase()) }.joinToString("|")
        val t = text.lowercase().trim()
        if (t.startsWith("/${v.id}")) return true
        return Regex("""\b(open|show|switch to|go to|take me to|bring up|launch)\b[^.?!]{0,30}\b($names)\b""").containsMatchIn(t) ||
            Regex("""\b(open (it|that|this)|show (it|me)|let me see|so i can see)\b""").containsMatchIn(t) && id == "design"
    }

    /**
     * Opens [id] now when the user asked for it (or it's already on screen), else offers it with the chip.
     * Returns true when it opened. Call on the main thread.
     */
    fun openOrOffer(id: String, target: String, userText: String, autopilot: Boolean = false): Boolean {
        if (autopilot || showing.value == id || askedFor(userText, id)) { offer.value = null; open(id, target); return true }
        offer.value = Offer(id, target)
        return false
    }

    /** The view shown over the chat right now ("" = plain chat). Shown as the "DESIGN ✕ Exit" chip. */
    val showing = MutableStateFlow("")

    /** What the Design view previews: a URL or a file path. */
    val designTarget = MutableStateFlow("")

    // ---------------- open_view tool ----------------

    const val TOOL = "open_view"

    fun tool(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject().put("name", TOOL)
        .put("description", "Switch ${BrandName.NAME}'s own screen to one of its views: design (live preview of anything visual: a 3D scene, art, a site, UI mockup, diagram, chart, poster or slides; with no target it shows the current design; make or change designs with the design tool), " +
            "learn (flashcard decks; open it after flashcards add), memory, jobs, schedule, projects, agents, accounts, router (models/keys), skills, gallery, browser, doctor, usage, settings, " +
            "music, video, doing (things ${BrandName.NAME} is doing: routines, reminders, today's diary), look (Look & feel: backgrounds and theme), and every other id in the list. Views hidden in Simple mode still open. " +
            "Output that fits a view is made there and the view opened, never dumped in chat. " +
            "The user can always tap Exit to come back to the chat. For design pass target = the file path (e.g. ~/site/index.html) or URL to preview, or nothing for the current design.")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("view", JSONObject().put("type", "string").put("enum", JSONArray(all.map { it.id })))
            .put("target", JSONObject().put("type", "string").put("description", "design: file path or URL to preview"))
            .put("ask", JSONObject().put("type", "boolean").put("description", "true = ask the user before switching"))
            .put("reason", JSONObject().put("type", "string").put("description", "one short line: why switch")))
            .put("required", JSONArray(listOf("view")))))

    /** Kept for older callers; the full VIEWS block is agent/ViewRouter.PROMPT since v0.23.0. */
    const val PROMPT_LINE = "\nVIEWS: when a task is visual (a 3D scene, website, page, UI mockup, diagram, chart, poster, art), make it with the design tool (it opens Design), or call open_view view=design with the file or URL as target, without asking. " +
        "For other views, set ask=true unless the user asked for that view."

    /** Plain-words step line for the tool. */
    fun step(args: JSONObject): String = "Opening " + (find(args.optString("view"))?.name ?: args.optString("view"))
}
