package com.blaze.assistant.agent


import com.blaze.assistant.BrandName
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * v0.39.0 Errors as fixes: when a tool result says an access Argus needs is off, the chat shows a one-button card
 * ("Turn it on") that opens the right system screen, instead of an error. The matching is pure (FixCardsTest);
 * the card and the intent live in ui/SimpleCards.
 */
object FixCards {
    enum class Kind(val title: String, val body: String, val action: String) {
        NOTIFICATIONS("Turn on notification access?",
            "This lets me see new messages so I can tell you what they say. I never send anything without asking you.",
            "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"),
        USAGE("Turn on usage access?",
            "This lets me see which apps you used today, so I can help at the right moment.",
            "android.settings.USAGE_ACCESS_SETTINGS"),
        OVERLAY("Let ${BrandName.NAME} show over other apps?",
            "This lets me float a small card over the app you're in while I work.",
            "android.settings.action.MANAGE_OVERLAY_PERMISSION"),
        ALL_FILES("Let ${BrandName.NAME} see all your files?",
            "Android hides your Downloads, documents and other apps' folders from me until you turn on All files access. I only read what you ask me to.",
            "android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION"),
        APP_PERMISSION("Allow this for ${BrandName.NAME}?",
            "${BrandName.NAME} needs one more permission for that. Turn it on in ${BrandName.NAME}'s app settings, then ask me again.",
            "android.settings.APPLICATION_DETAILS_SETTINGS"),
    }

    data class Fix(val kind: Kind, val what: String, val perm: String = "", val n: Long = System.nanoTime()) {
        /** "I couldn't read WhatsApp" style headline. */
        val headline: String get() = what.ifBlank { "I couldn't do that yet" }
        /** 0.53.7 (owner: "that permission card is not descriptive"): the app-permission card names the permission. */
        val title: String get() = if (kind == Kind.APP_PERMISSION && perm.isNotBlank()) "Allow ${perm} for ${BrandName.NAME}?" else kind.title
        val body: String get() = if (kind != Kind.APP_PERMISSION) kind.body
            else if (perm.isNotBlank()) "${BrandName.NAME} needs ${perm} access for that. Tap Turn it on, open Permissions, allow ${perm}, then ask me again."
            else "${BrandName.NAME} needs a permission that's off. Tap Turn it on, open Permissions, and allow the one marked Not allowed, then ask me again."
    }

    private val rules = listOf(
        Regex("(?i)notification access (is )?(off|not (on|granted|enabled))|turn on notification access|needs notification access") to Kind.NOTIFICATIONS,
        Regex("(?i)needs usage access|usage access (is )?(off|not granted)") to Kind.USAGE,
        Regex("(?i)(overlay|draw over other apps|display over other apps) (permission )?(is )?(off|not granted|needed)") to Kind.OVERLAY,
        Regex("(?i)all files access is off|needs all files access") to Kind.ALL_FILES,
        Regex("(?i)\\b(permission (denied|not granted|is off|missing))|(needs the [a-z ]{3,30} permission)") to Kind.APP_PERMISSION,
    )

    /**
     * Tools whose "Permission denied" is Linux's (a file mode, a port, a path Android walls off), not an Android
     * permission. 0.53.7: a shell error put up "Allow this for Argus?" and the run gave up mid-task; for these the
     * error goes back to the model as it is (Terminal adds its own hint) and no card shows.
     */
    val linuxTools = setOf("run_shell", "run_code", "linux_install", "terminal_jobs", "terminal_status", "job_input", "job_log",
        "terminal_stop", "preview_server", "servers", "git", "file_read", "file_write", "file_edit", "file_delete", "file_list", "file_search")

    /** The fix a tool result asks for, or null. */
    fun kindOf(result: String, tool: String = ""): Kind? {
        val t = result.take(600)
        val k = rules.firstOrNull { it.first.containsMatchIn(t) }?.second ?: return null
        if (k == Kind.APP_PERMISSION && tool in linuxTools) return null
        return k
    }

    private val permNames = mapOf(
        "CAMERA" to "Camera", "RECORD_AUDIO" to "Microphone", "READ_CONTACTS" to "Contacts", "WRITE_CONTACTS" to "Contacts",
        "ACCESS_FINE_LOCATION" to "Location", "ACCESS_COARSE_LOCATION" to "Location", "ACCESS_BACKGROUND_LOCATION" to "Location",
        "POST_NOTIFICATIONS" to "Notifications", "READ_MEDIA_AUDIO" to "Music and audio", "READ_MEDIA_IMAGES" to "Photos and videos",
        "READ_MEDIA_VIDEO" to "Photos and videos", "READ_EXTERNAL_STORAGE" to "Files and media", "READ_CALENDAR" to "Calendar",
        "WRITE_CALENDAR" to "Calendar", "SEND_SMS" to "SMS", "READ_SMS" to "SMS", "RECEIVE_SMS" to "SMS", "CALL_PHONE" to "Phone",
        "READ_PHONE_STATE" to "Phone", "READ_CALL_LOG" to "Call logs", "BLUETOOTH_CONNECT" to "Nearby devices",
        "BLUETOOTH_SCAN" to "Nearby devices", "NEARBY_WIFI_DEVICES" to "Nearby devices", "BODY_SENSORS" to "Body sensors",
        "ACTIVITY_RECOGNITION" to "Physical activity")

    /** The permission a result names ("android.permission.CAMERA", "needs the camera permission", "permission denied for contacts"), as Settings words it. */
    fun permissionOf(result: String): String {
        val t = result.take(600)
        Regex("android\\.permission\\.([A-Z_]+)").find(t)?.groupValues?.get(1)?.let { id ->
            return permNames[id] ?: id.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() } }
        val word = Regex("(?i)needs the ([a-z ]{3,30}?) permission").find(t)?.groupValues?.get(1)
            ?: Regex("(?i)permission (?:denied|not granted|is off|missing) (?:for|to) (?:the )?([a-z]{3,20}(?: [a-z]{3,20})?)").find(t)?.groupValues?.get(1)
            ?: return ""
        val w = word.trim().lowercase()
        return permNames.values.firstOrNull { it.lowercase() == w || it.lowercase().startsWith(w) } ?: w.replaceFirstChar { it.uppercase() }
    }

    /** What couldn't happen, in plain words, from the tool that hit the wall. */
    fun headline(tool: String, kind: Kind): String = when (kind) {
        Kind.NOTIFICATIONS -> if (tool.contains("whatsapp", true)) "I couldn't read WhatsApp" else "I couldn't read your messages"
        Kind.USAGE -> "I couldn't see which apps you used"
        Kind.OVERLAY -> "I couldn't show over other apps"
        Kind.ALL_FILES -> "I couldn't see all your files"
        Kind.APP_PERMISSION -> "I couldn't do that yet"
    }

    /** The card on screen now (null = none). The UI clears it after a tap. */
    val current = MutableStateFlow<Fix?>(null)

    fun afterTool(tool: String, result: String) {
        val k = kindOf(result, tool) ?: return
        current.value = Fix(k, headline(tool, k), if (k == Kind.APP_PERMISSION) permissionOf(result) else "")
    }

    /** A line for the model so it doesn't also print an error: the card already shows the fix. */
    const val NOTE = "\n(The user now sees a one-button card in the chat that turns this on. Say in one plain line what you couldn't do; no error codes, no settings paths. Carry on with any part of the task that doesn't need it.)"
}
