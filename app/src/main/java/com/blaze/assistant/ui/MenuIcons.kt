package com.blaze.assistant.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddComment
import androidx.compose.material.icons.rounded.AddReaction
import androidx.compose.material.icons.rounded.AlternateEmail
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.AutoMode
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.DataSaverOn
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.LocationOff
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.SortByAlpha
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.SubtitlesOff
import androidx.compose.material.icons.rounded.Summarize
import androidx.compose.material.icons.rounded.Tab
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.TimerOff
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 0.52.4 icons everywhere: every menu row gets a leading icon from its label, so the ~20 menus in the app (chat, projects,
 * music, video, movies, gallery, browser) read like the rest of the app without each call site naming an icon.
 * [MenuIcons.forLabel] is pure (labels in, [MenuIcon] out, unit-tested); [vector] turns a key into its Material Rounded
 * vector (material-icons-extended 1.7.0, the Compose BOM 2024.09.00; auto-mirrored where 1.7 has a mirrored version).
 * A label with no match gets no icon; the menu card then indents it so the text still lines up.
 */
enum class MenuIcon {
    RENAME, EDIT, DELETE, COPY, PASTE, SHARE, QUOTE, RETRY, REACT, RECEIPT, INFO, SETTINGS,
    SORT, SORT_ALPHA, SORT_ARTIST, SORT_RECENT, REORDER, MOVE_UP, MOVE_DOWN,
    PLAY_NEXT, QUEUE_ADD, PLAYLIST_ADD, PLAYLIST_REMOVE, LIKE, UNLIKE, REPEAT, SPEED, EQUALISER, FADE, LYRICS, KARAOKE, DJ,
    TIMER, TIMER_OFF, SUBTITLES, SUBTITLES_OFF, LANGUAGE, TRANSLATE, RECAP, MARK, AUTOPLAY, AUTOPLAY_MODE, DOWNLOAD, EXPORT, FOLDER,
    NEW_CHAT, NEW_TAB, CLOSE, OPEN, PIN, GRID, TRASH, RESTORE, BACKUP, LOCATION_OFF, ADD, SEARCH,
    // settings rows: ToggleRow and friends
    VOICE, MIC, NOTIFY, LOCK, SHIELD, SYNC, DATA, VIBRATE, TOUCH, VISIBILITY, CAMERA, MEMORY, LEARN, HISTORY, SCHOOL,
    TERMINAL, COMPUTER, WIFI, BATTERY, BOLT, ANIMATION, DASHBOARD, CALL, BANK, STORE, AGENT, LIGHTBULB, SLEEP, CLEAN,
    PHOTOS, SCAN, ATTACH, MENTION, LOGIN, LOGOUT, PERSON, CHAT, TUNE,
}

object MenuIcons {
    /**
     * Ordered rules: the first rule with a phrase found in the label (as whole words) wins. 0.52.4 CI fix (low-RAM smoke):
     * built on first use (lazy), never in a static initialiser, and warmed off the main thread after the first frame ([warm]).
     */
    private val RULES: List<Pair<MenuIcon, List<String>>> by lazy { listOf(
        // the most specific phrases first
        MenuIcon.NEW_CHAT to listOf("new chat", "new thread"),
        MenuIcon.NEW_TAB to listOf("new tab", "new private tab"),
        MenuIcon.PLAYLIST_ADD to listOf("add to playlist", "add to a playlist", "save to playlist"),
        MenuIcon.QUEUE_ADD to listOf("add to queue", "queue"),
        MenuIcon.PLAYLIST_REMOVE to listOf("remove from this playlist", "remove from playlist"),
        MenuIcon.UNLIKE to listOf("remove from liked", "unlike"),
        MenuIcon.LOCATION_OFF to listOf("remove location", "strip location"),
        MenuIcon.PLAY_NEXT to listOf("play next", "up next"),
        MenuIcon.SUBTITLES_OFF to listOf("subtitles off", "no subtitles"),
        MenuIcon.TRANSLATE to listOf("translate"),
        MenuIcon.SUBTITLES to listOf("subtitles", "captions", "subtitle"),
        MenuIcon.CLOSE to listOf("close all", "close tab", "close"),
        MenuIcon.TIMER_OFF to listOf("turn off", "timer off"),
        MenuIcon.TIMER to listOf("minutes", "end of this song", "sleep timer", "timer"),
        MenuIcon.FADE to listOf("fade", "crossfade"),
        MenuIcon.RENAME to listOf("rename"),
        MenuIcon.TRASH to listOf("trash", "bin"),
        MenuIcon.DELETE to listOf("delete", "remove", "clear"),
        MenuIcon.EDIT to listOf("edit", "name & details"),
        MenuIcon.COPY to listOf("copy", "duplicate"),
        MenuIcon.PASTE to listOf("paste"),
        MenuIcon.EXPORT to listOf("export to music", "export to movies", "export to downloads", "export", "save to phone"),
        MenuIcon.FOLDER to listOf("folder", "files"),
        MenuIcon.SHARE to listOf("share", "send to"),
        MenuIcon.QUOTE to listOf("quote", "reply"),
        MenuIcon.RETRY to listOf("retry", "try again", "regenerate", "refresh", "reload"),
        MenuIcon.REACT to listOf("react"),
        MenuIcon.RECEIPT to listOf("what i did", "receipt"),
        MenuIcon.INFO to listOf("what happened", "info", "details", "about", "properties"),
        MenuIcon.SETTINGS to listOf("settings", "preferences"),
        MenuIcon.SORT_ALPHA to listOf("name a", "name z", "title", "a–z", "z–a", "a-z", "z-a"),
        MenuIcon.SORT_ARTIST to listOf("artist"),
        MenuIcon.SORT_RECENT to listOf("recently added", "recent"),
        MenuIcon.REORDER to listOf("custom order", "reorder"),
        MenuIcon.SORT to listOf("sort", "newest first", "oldest first", "largest first", "smallest first", "date", "size"),
        MenuIcon.MOVE_UP to listOf("move up"),
        MenuIcon.MOVE_DOWN to listOf("move down"),
        MenuIcon.DJ to listOf("dj", "more like this"),
        MenuIcon.LIKE to listOf("like", "favourite", "favorite"),
        MenuIcon.REPEAT to listOf("repeat", "loop"),
        MenuIcon.SPEED to listOf("speed", "playback speed"),
        MenuIcon.EQUALISER to listOf("equaliser", "equalizer"),
        MenuIcon.LYRICS to listOf("lyrics"),
        MenuIcon.KARAOKE to listOf("karaoke", "sing"),
        MenuIcon.RECAP to listOf("recap", "summary", "summarise", "summarize"),
        MenuIcon.MARK to listOf("mark the end", "mark", "flag"),
        MenuIcon.AUTOPLAY to listOf("autoplay", "play now", "skip"),
        MenuIcon.DOWNLOAD to listOf("download", "smart downloads"),
        MenuIcon.OPEN to listOf("open"),
        MenuIcon.PIN to listOf("pin", "unpin"),
        MenuIcon.GRID to listOf("picture grid", "grid"),
        MenuIcon.RESTORE to listOf("restore", "undo"),
        MenuIcon.BACKUP to listOf("backup", "back up"),
        MenuIcon.SEARCH to listOf("search", "find"),
        MenuIcon.LANGUAGE to listOf("english", "french", "portuguese", "spanish", "swahili", "german", "italian", "arabic", "chinese",
            "japanese", "hindi", "language"),
        MenuIcon.ADD to listOf("add", "new", "create"),
    ) }

    // 0.52.4 CI fix: compiled once (lazily), not twice per label on every call.
    private val STATE_SUFFIX: Regex by lazy { Regex(":\\s*(on|off)\\s*$") }
    private val SPACES: Regex by lazy { Regex("\\s+") }

    /**
     * 0.52.4 CI fix: a label's or title's answer, worked out once. Menus and settings rows ask again on every
     * recomposition; the labels are a small fixed set, so the cache stays small (cleared if it ever grows past [CACHE_MAX]).
     */
    private const val CACHE_MAX = 1024
    private val NONE = Any()
    private val labelCache = java.util.concurrent.ConcurrentHashMap<String, Any>()
    private val settingCache = java.util.concurrent.ConcurrentHashMap<String, MenuIcon>()

    /** The words of a label, lower-cased, without a trailing "✓", "…" or ": on" / ": off" state. */
    internal fun normalise(label: String): String = label.lowercase()
        .replace("\u2713", " ").replace("\u2026", " ").replace("...", " ")
        .replace(STATE_SUFFIX, " ")
        .replace(SPACES, " ").trim()

    /** [phrase] in [text] starting at a word start; ending at a word end too unless [prefix] ("record" finds "recordings"). */
    private fun has(text: String, phrase: String, prefix: Boolean = false): Boolean {
        var i = text.indexOf(phrase)
        while (i >= 0) {
            val before = i == 0 || !text[i - 1].isLetterOrDigit()
            val end = i + phrase.length
            val after = prefix || end >= text.length || !text[end].isLetterOrDigit()
            if (before && after) return true
            i = text.indexOf(phrase, i + 1)
        }
        return false
    }

    /** The icon for a menu row's label, or null when nothing fits (the row then shows text only). */
    fun forLabel(label: String): MenuIcon? {
        val hit = labelCache[label]
        if (hit != null) return hit as? MenuIcon
        val found = matchLabel(label)
        if (labelCache.size >= CACHE_MAX) labelCache.clear()
        labelCache[label] = found ?: NONE
        return found
    }

    private fun matchLabel(label: String): MenuIcon? {
        val t = normalise(label)
        if (t.isEmpty()) return null
        if (t == "off") return MenuIcon.SUBTITLES_OFF   // the subtitles menu's first row
        return RULES.firstOrNull { (_, phrases) -> phrases.any { has(t, it) } }?.first
    }

    /** Settings rows (ToggleRow and friends): the first rule whose phrase is in the title, else a neutral Tune. */
    private val SETTING_RULES: List<Pair<MenuIcon, List<String>>> by lazy { listOf(
        MenuIcon.BANK to listOf("bank", "money"),
        MenuIcon.SHIELD to listOf("scam", "risky", "strict", "shield", "security", "privacy"),
        MenuIcon.LOCK to listOf("lock"),
        MenuIcon.CALL to listOf("call", "earpiece", "hangs up", "bye"),
        MenuIcon.VOICE to listOf("speak", "say it", "say hello", "out loud", "voice", "read aloud"),
        MenuIcon.MIC to listOf("listen", "hey", "wake", "record", "sound gate", "transcribe", "pocket"),
        MenuIcon.NOTIFY to listOf("notification", "heads-up", "brief", "digest", "sounds", "speak up"),
        MenuIcon.SYNC to listOf("sync", "cloud"),
        MenuIcon.DATA to listOf("low-data", "data"),
        MenuIcon.VIBRATE to listOf("haptic", "vibrat"),
        MenuIcon.TOUCH to listOf("where i tap", "phone control", "use this phone", "tap"),
        MenuIcon.VISIBILITY to listOf("glance", "screen", "what i see"),
        MenuIcon.CAMERA to listOf("camera"),
        MenuIcon.LEARN to listOf("learn", "habits", "automate"),
        MenuIcon.MEMORY to listOf("remember", "memory", "past conversations", "keep chat logs", "long chats", "logs"),
        MenuIcon.CHAT to listOf("name chats", "chats", "conversation", "stream replies"),
        MenuIcon.SCHOOL to listOf("classroom", "school"),
        MenuIcon.TERMINAL to listOf("terminal"),
        MenuIcon.COMPUTER to listOf("pc", "desktop", "computer"),
        MenuIcon.WIFI to listOf("wi-fi", "wifi", "router"),
        MenuIcon.BATTERY to listOf("charging", "battery"),
        MenuIcon.BOLT to listOf("fast lane", "speed", "fresh facts", "blazequery", "turn on by itself", "start with"),
        MenuIcon.ANIMATION to listOf("particles", "glow", "orb", "fade", "animation", "stage"),
        MenuIcon.DASHBOARD to listOf("panel", "task card", "dashboard", "badge"),
        MenuIcon.STORE to listOf("store", "seller"),
        MenuIcon.AGENT to listOf("specialists", "copilot", "agent"),
        MenuIcon.MEMORY to listOf("ask before saving", "keep"),
    ) }

    fun forSetting(title: String): MenuIcon {
        settingCache[title]?.let { return it }
        val t = normalise(title)
        val found = SETTING_RULES.firstOrNull { (_, phrases) -> phrases.any { has(t, it, prefix = true) } }?.first ?: MenuIcon.TUNE
        if (settingCache.size >= CACHE_MAX) settingCache.clear()
        settingCache[title] = found
        return found
    }

    /**
     * 0.52.4 CI fix: builds the rules, the two regexes and every key's vector on the calling thread (BlazeApp runs it on a
     * low-priority background thread after the first frame), so the first menu, settings page or top bar that needs them
     * does not build them on the main thread. Pure warm-up: safe to call more than once, never throws.
     */
    fun warm() {
        try {
            forLabel("Rename"); forSetting("Speak replies")
            for (k in MenuIcon.entries) k.vector()
        } catch (t: Throwable) {
            if (t is OutOfMemoryError) throw t
        }
    }
}

/**
 * The + menu's rows by id ([plusItems]): what each attaches or switches. Plan / Build and Auto / Autopilot show the mode
 * they are in now.
 */
fun plusIcon(id: String, on: Boolean = false): MenuIcon = when (id) {
    "camera" -> MenuIcon.CAMERA
    "blazecam" -> MenuIcon.SCAN
    "photos" -> MenuIcon.PHOTOS
    "files" -> MenuIcon.ATTACH
    "atfile" -> MenuIcon.MENTION
    "paste" -> MenuIcon.PASTE
    "voice" -> MenuIcon.MIC
    "call" -> MenuIcon.CALL
    "plan" -> if (on) MenuIcon.LIGHTBULB else MenuIcon.EDIT
    "approval" -> if (on) MenuIcon.AUTOPLAY_MODE else MenuIcon.SHIELD
    "views" -> MenuIcon.DASHBOARD
    "new" -> MenuIcon.NEW_CHAT
    "clear" -> MenuIcon.CLEAN
    else -> MenuIcon.ADD
}

/** The Material Rounded vector for a key (auto-mirrored where 1.7 mirrors it for right-to-left). */
fun MenuIcon.vector(): ImageVector = when (this) {
    MenuIcon.RENAME -> Icons.Rounded.DriveFileRenameOutline
    MenuIcon.EDIT -> Icons.Rounded.Edit
    MenuIcon.DELETE -> Icons.Rounded.Delete
    MenuIcon.COPY -> Icons.Rounded.ContentCopy
    MenuIcon.PASTE -> Icons.Rounded.ContentPaste
    MenuIcon.SHARE -> Icons.Rounded.Share
    MenuIcon.QUOTE -> Icons.Rounded.FormatQuote
    MenuIcon.RETRY -> Icons.Rounded.Refresh
    MenuIcon.REACT -> Icons.Rounded.AddReaction
    MenuIcon.RECEIPT -> Icons.AutoMirrored.Rounded.ReceiptLong
    MenuIcon.INFO -> Icons.Rounded.Info
    MenuIcon.SETTINGS -> Icons.Rounded.Settings
    MenuIcon.SORT -> Icons.AutoMirrored.Rounded.Sort
    MenuIcon.SORT_ALPHA -> Icons.Rounded.SortByAlpha
    MenuIcon.SORT_ARTIST -> Icons.Rounded.Person
    MenuIcon.SORT_RECENT -> Icons.Rounded.Schedule
    MenuIcon.REORDER -> Icons.Rounded.DragHandle
    MenuIcon.MOVE_UP -> Icons.Rounded.KeyboardArrowUp
    MenuIcon.MOVE_DOWN -> Icons.Rounded.KeyboardArrowDown
    MenuIcon.PLAY_NEXT -> Icons.AutoMirrored.Rounded.PlaylistPlay
    MenuIcon.QUEUE_ADD -> Icons.AutoMirrored.Rounded.QueueMusic
    MenuIcon.PLAYLIST_ADD -> Icons.AutoMirrored.Rounded.PlaylistAdd
    MenuIcon.PLAYLIST_REMOVE -> Icons.Rounded.Delete
    MenuIcon.LIKE -> Icons.Rounded.Favorite
    MenuIcon.UNLIKE -> Icons.Rounded.FavoriteBorder
    MenuIcon.REPEAT -> Icons.Rounded.Repeat
    MenuIcon.SPEED -> Icons.Rounded.Speed
    MenuIcon.EQUALISER -> Icons.Rounded.Equalizer
    MenuIcon.FADE -> Icons.Rounded.Waves
    MenuIcon.LYRICS -> Icons.Rounded.Lyrics
    MenuIcon.KARAOKE -> Icons.Rounded.Mic
    MenuIcon.DJ -> Icons.Rounded.Headphones
    MenuIcon.TIMER -> Icons.Rounded.Timer
    MenuIcon.TIMER_OFF -> Icons.Rounded.TimerOff
    MenuIcon.SUBTITLES -> Icons.Rounded.Subtitles
    MenuIcon.SUBTITLES_OFF -> Icons.Rounded.SubtitlesOff
    MenuIcon.LANGUAGE -> Icons.Rounded.Language
    MenuIcon.TRANSLATE -> Icons.Rounded.Translate
    MenuIcon.RECAP -> Icons.Rounded.Summarize
    MenuIcon.MARK -> Icons.Rounded.Flag
    MenuIcon.AUTOPLAY -> Icons.Rounded.SkipNext
    MenuIcon.AUTOPLAY_MODE -> Icons.Rounded.AutoMode
    MenuIcon.DOWNLOAD -> Icons.Rounded.Download
    MenuIcon.EXPORT -> Icons.Rounded.SaveAlt
    MenuIcon.FOLDER -> Icons.Rounded.Folder
    MenuIcon.NEW_CHAT -> Icons.Rounded.AddComment
    MenuIcon.NEW_TAB -> Icons.Rounded.Tab
    MenuIcon.CLOSE -> Icons.Rounded.Close
    MenuIcon.OPEN -> Icons.AutoMirrored.Rounded.OpenInNew
    MenuIcon.PIN -> Icons.Rounded.PushPin
    MenuIcon.GRID -> Icons.Rounded.GridView
    MenuIcon.TRASH -> Icons.Rounded.Delete
    MenuIcon.RESTORE -> Icons.Rounded.Restore
    MenuIcon.BACKUP -> Icons.Rounded.Backup
    MenuIcon.LOCATION_OFF -> Icons.Rounded.LocationOff
    MenuIcon.ADD -> Icons.Rounded.Add
    MenuIcon.SEARCH -> Icons.Rounded.Search
    MenuIcon.VOICE -> Icons.AutoMirrored.Rounded.VolumeUp
    MenuIcon.MIC -> Icons.Rounded.Mic
    MenuIcon.NOTIFY -> Icons.Rounded.Notifications
    MenuIcon.LOCK -> Icons.Rounded.Lock
    MenuIcon.SHIELD -> Icons.Rounded.Shield
    MenuIcon.SYNC -> Icons.Rounded.CloudSync
    MenuIcon.DATA -> Icons.Rounded.DataSaverOn
    MenuIcon.VIBRATE -> Icons.Rounded.Vibration
    MenuIcon.TOUCH -> Icons.Rounded.TouchApp
    MenuIcon.VISIBILITY -> Icons.Rounded.Visibility
    MenuIcon.CAMERA -> Icons.Rounded.CameraAlt
    MenuIcon.MEMORY -> Icons.Rounded.Memory
    MenuIcon.LEARN -> Icons.Rounded.Psychology
    MenuIcon.HISTORY -> Icons.Rounded.History
    MenuIcon.SCHOOL -> Icons.Rounded.School
    MenuIcon.TERMINAL -> Icons.Rounded.Terminal
    MenuIcon.COMPUTER -> Icons.Rounded.Computer
    MenuIcon.WIFI -> Icons.Rounded.Wifi
    MenuIcon.BATTERY -> Icons.Rounded.BatteryChargingFull
    MenuIcon.BOLT -> Icons.Rounded.Bolt
    MenuIcon.ANIMATION -> Icons.Rounded.Animation
    MenuIcon.DASHBOARD -> Icons.Rounded.Dashboard
    MenuIcon.CALL -> Icons.Rounded.Call
    MenuIcon.BANK -> Icons.Rounded.AccountBalance
    MenuIcon.STORE -> Icons.Rounded.Storefront
    MenuIcon.AGENT -> Icons.Rounded.SmartToy
    MenuIcon.LIGHTBULB -> Icons.Rounded.Lightbulb
    MenuIcon.SLEEP -> Icons.Rounded.Bedtime
    MenuIcon.CLEAN -> Icons.Rounded.CleaningServices
    MenuIcon.PHOTOS -> Icons.Rounded.PhotoLibrary
    MenuIcon.SCAN -> Icons.Rounded.DocumentScanner
    MenuIcon.ATTACH -> Icons.Rounded.AttachFile
    MenuIcon.MENTION -> Icons.Rounded.AlternateEmail
    MenuIcon.LOGIN -> Icons.AutoMirrored.Rounded.Login
    MenuIcon.LOGOUT -> Icons.AutoMirrored.Rounded.Logout
    MenuIcon.PERSON -> Icons.Rounded.Person
    MenuIcon.CHAT -> Icons.AutoMirrored.Rounded.Chat
    MenuIcon.TUNE -> Icons.Rounded.Tune
}
