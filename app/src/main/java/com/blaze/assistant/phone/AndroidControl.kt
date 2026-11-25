package com.blaze.assistant.phone


import com.blaze.assistant.BrandName
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.blaze.assistant.R
import org.json.JSONArray
import org.json.JSONObject

/**
 * Wider Android control with plain public APIs (no root, no Shizuku): clipboard, Argus's own
 * notifications (with progress and a tap action), the share sheet, jumping straight to any settings page, the app list
 * and the camera. 2026-11-25 permission trim: phone_mode (ringer / DND / brightness / rotation: ACCESS_NOTIFICATION_POLICY,
 * WRITE_SETTINGS), app_usage (PACKAGE_USAGE_STATS) and uninstall_app (REQUEST_DELETE_PACKAGES) left the app.
 */
object AndroidControl {
    val names = setOf("clipboard", "post_notification", "share_text", "open_settings_page", "open_camera", "list_apps", "blaze_camera")

    private fun fn(name: String, desc: String, props: JSONObject, req: List<String>) = JSONObject().put("type", "function").put("function",
        JSONObject().put("name", name).put("description", desc).put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(req))))
    private fun en(vararg v: String) = JSONObject().put("type", "string").put("enum", JSONArray(v.toList()))
    private fun str(d: String = "") = JSONObject().put("type", "string").apply { if (d.isNotBlank()) put("description", d) }

    private val pages = mapOf(
        "wifi" to Settings.ACTION_WIFI_SETTINGS, "bluetooth" to Settings.ACTION_BLUETOOTH_SETTINGS, "hotspot" to "android.settings.TETHER_SETTINGS",
        "location" to Settings.ACTION_LOCATION_SOURCE_SETTINGS, "nfc" to Settings.ACTION_NFC_SETTINGS, "dnd" to "android.settings.ZEN_MODE_SETTINGS",
        "battery" to Intent.ACTION_POWER_USAGE_SUMMARY, "display" to Settings.ACTION_DISPLAY_SETTINGS, "sound" to Settings.ACTION_SOUND_SETTINGS,
        "apps" to Settings.ACTION_APPLICATION_SETTINGS, "storage" to Settings.ACTION_INTERNAL_STORAGE_SETTINGS, "accessibility" to Settings.ACTION_ACCESSIBILITY_SETTINGS,
        "notifications" to "android.settings.ALL_APPS_NOTIFICATION_SETTINGS", "data_usage" to Settings.ACTION_DATA_USAGE_SETTINGS,
        "mobile_network" to Settings.ACTION_NETWORK_OPERATOR_SETTINGS, "airplane" to Settings.ACTION_AIRPLANE_MODE_SETTINGS, "date_time" to Settings.ACTION_DATE_SETTINGS,
        "language" to Settings.ACTION_LOCALE_SETTINGS, "security" to Settings.ACTION_SECURITY_SETTINGS, "developer" to Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
        "cast" to Settings.ACTION_CAST_SETTINGS,
        "vpn" to Settings.ACTION_VPN_SETTINGS, "print" to Settings.ACTION_PRINT_SETTINGS, "default_apps" to Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS,
    )

    fun tools(): List<JSONObject> = listOf(
        fn("clipboard", "The phone's clipboard, even while ${BrandName.NAME} is in the background. get: text on it (and whether there's an image). get_image: look at a copied picture. set: copy text. set_image: copy a file or picture (path) so the user can paste it into any app. history: recent copied text. clear.",
            JSONObject().put("action", en("get", "get_image", "set", "set_image", "history", "clear")).put("text", str("Text to copy (set)")).put("path", str("File to copy (set_image), e.g. a Gallery file or /root/phone/chart.png")), listOf("action")),
        fn("post_notification", "Show a notification from ${BrandName.NAME} (a reminder, a test, a progress bar). Tapping it can ask ${BrandName.NAME} to do something.",
            JSONObject().put("title", str()).put("text", str()).put("progress", JSONObject().put("type", "integer").put("description", "0-100, optional"))
                .put("on_tap", str("Request ${BrandName.NAME} runs when tapped, optional")), listOf("title")),
        fn("share_text", "Open the share sheet with some text or a link.", JSONObject().put("text", str()), listOf("text")),
        fn("open_settings_page", "Open a specific Android settings page for the user.", JSONObject().put("page", en(*pages.keys.toTypedArray())), listOf("page")),
        fn("list_apps", "List the apps installed on this phone (name, package, game or not); the chat shows them as a tappable card. Without a filter: every launchable app. " +
            "The filter matches loosely: words of the name or package, partial words, and categories ('car rally game', 'music', 'maps'). Use it before saying an app isn't installed, and instead of asking the user what they have.",
            JSONObject().put("filter", str("Optional words, e.g. 'rally game', 'bank', 'whatsapp'")), emptyList()),
        fn("open_camera", "Open the phone's own camera app for the user (photo or video, saved to their gallery), or scan a QR code. To SEE something yourself use blaze_camera instead.", JSONObject().put("mode", en("photo", "video", "qr")), emptyList()),
        fn("blaze_camera", "Open ${BrandName.NAME} Camera so the user can show you something. The capture comes back into this chat as their next message. Modes: ask (look at a thing and answer), photo, text (read words, on-device OCR), translate (read words and translate them), scan (QR code / barcode), document (scan a page, crop and clean it).",
            JSONObject().put("mode", en("ask", "photo", "text", "translate", "scan", "document")), listOf("mode")),
    )

    private fun start(ctx: Context, i: Intent): Boolean = try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (_: Exception) { false }

    fun run(ctx: Context, name: String, a: JSONObject): String { return when (name) {
        "clipboard" -> {
            val cm = ctx.getSystemService(ClipboardManager::class.java)
            if (a.optString("action") == "set") { cm.setPrimaryClip(ClipData.newPlainText("${BrandName.NAME}", a.optString("text"))); "Copied to the clipboard." }
            else cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()?.take(4000)?.ifBlank { null }
                ?: "The clipboard is empty (Android only lets ${BrandName.NAME} read it while ${BrandName.NAME} is on screen)."
        }
        "post_notification" -> notify(ctx, a.optString("title"), a.optString("text"), if (a.has("progress")) a.optInt("progress") else -1, a.optString("on_tap"))
        "share_text" -> if (start(ctx, Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, a.optString("text")), "Share"))) "Opened the share sheet." else "Couldn't open sharing."
        "open_settings_page" -> {
            val p = a.optString("page"); val act = pages[p] ?: return "Unknown page."
            if (start(ctx, Intent(act))) "Opened $p settings." else if (start(ctx, Intent(Settings.ACTION_SETTINGS))) "That page isn't on this phone, so I opened Settings." else "Couldn't open settings."
        }
        "list_apps" -> {
            val pm = ctx.packageManager
            val f = a.optString("filter").trim()
            val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }.distinctBy { it.second }
                .filter { f.isBlank() || it.first.contains(f, true) || it.second.contains(f, true) }.sortedBy { it.first.lowercase() }
            if (apps.isEmpty()) "No installed app matches \u201c$f\u201d." else "${apps.size} apps:\n" + apps.take(150).joinToString("\n") { "${it.first} (${it.second})" }
        }
        "blaze_camera" -> com.blaze.assistant.device.BlazeCamera.openFromTool(ctx, a.optString("mode"), a.optString("_chat").ifBlank { null }, a.optString("_asked").ifBlank { null })
        "open_camera" -> openCamera(ctx, a.optString("mode", "photo"))
        else -> "Unknown tool"
    } }

    /**
     * v0.28.x camera-fix: the camera app first (STILL_IMAGE / VIDEO_CAMERA need no permission), then Argus Camera; QR goes
     * straight to Argus Camera's Scan mode (ML Kit, it can act on the code). Every failure is logged with its real
     * exception, and the reply says what really happened instead of a blanket "No camera app found."
     */
    private fun openCamera(ctx: Context, mode: String): String {
        val cs = com.blaze.assistant.device.CameraSupport
        if (!cs.hasCamera(ctx)) return "This phone has no camera."
        fun tryStart(i: Intent, what: String): Throwable? = try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); null }
            catch (e: Exception) { cs.log("open_camera $what failed", e); e }
        val blaze = com.blaze.assistant.device.BlazeCamera
        return when (mode) {
            "qr" -> if (blaze.open(ctx, com.blaze.assistant.device.CameraModes.Mode.SCAN, com.blaze.assistant.device.CameraModes.Origin.STANDALONE)) "Opened ${BrandName.NAME} Camera's scanner: point it at the code." else "Couldn't open the scanner (it's in ${BrandName.NAME}'s log)."
            "video" -> {
                val e1 = tryStart(Intent(android.provider.MediaStore.INTENT_ACTION_VIDEO_CAMERA), "video camera") ?: return "Opened the video camera."
                // ACTION_VIDEO_CAPTURE needs Argus's CAMERA permission (declared by the QR library), so only try it when granted.
                if (cs.granted(ctx) && tryStart(Intent(android.provider.MediaStore.ACTION_VIDEO_CAPTURE), "video capture") == null) return "Opened the video camera."
                if (e1 is SecurityException) "Android blocked the video camera (${e1.message?.take(80)}). ${BrandName.NAME}'s own camera takes photos only."
                else if (!cs.granted(ctx)) "No video camera app answered directly, and ${BrandName.NAME}'s Camera permission is off. Turn it on in Settings → Apps → ${BrandName.NAME} → Permissions."
                else "This phone has no video camera app ${BrandName.NAME} can open."
            }
            else -> {
                tryStart(Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), "still camera") ?: return "Opened the camera."
                if (blaze.open(ctx, com.blaze.assistant.device.CameraModes.Mode.PHOTO, com.blaze.assistant.device.CameraModes.Origin.STANDALONE)) "The phone's camera app didn't open, so I opened ${BrandName.NAME} Camera (photos save to Pictures/Blaze)."
                else "Couldn't open any camera (the reason is in ${BrandName.NAME}'s log)."
            }
        }
    }

    private var nextNote = 500

    private fun notify(ctx: Context, title: String, text0: String, progress: Int, onTap: String): String {
        val text = com.blaze.assistant.ui.Md.plain(text0)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("blaze_custom", "${BrandName.NAME} notes", NotificationManager.IMPORTANCE_DEFAULT))
        val b = NotificationCompat.Builder(ctx, "blaze_custom").setSmallIcon(R.drawable.ic_stat_blaze)
            .setContentTitle(title.ifBlank { "${BrandName.NAME}" }).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text)).setAutoCancel(true)
        if (progress in 0..100) b.setProgress(100, progress, false).setOnlyAlertOnce(true)
        if (onTap.isNotBlank()) {
            val pi = android.app.PendingIntent.getBroadcast(ctx, onTap.hashCode(), Intent(ctx, com.blaze.assistant.notify.ActionReceiver::class.java)
                .setAction(com.blaze.assistant.notify.ActionReceiver.DO).putExtra("prompt", onTap),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
            b.setContentIntent(pi)
        } else {
            ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.let {
                b.setContentIntent(android.app.PendingIntent.getActivity(ctx, 0, it, android.app.PendingIntent.FLAG_IMMUTABLE))
            }
        }
        return try { NotificationManagerCompat.from(ctx).notify(nextNote++, b.build()); "Notification shown." }
        catch (_: SecurityException) { "${BrandName.NAME} isn't allowed to post notifications. Turn them on in Settings → Apps → ${BrandName.NAME} → Notifications." }
    }
}
