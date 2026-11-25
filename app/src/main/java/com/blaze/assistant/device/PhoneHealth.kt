package com.blaze.assistant.device


import com.blaze.assistant.BrandName
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * v0.33.0 Phone Health Check ("why is my phone slow / hot / dying fast?"): one report from the battery, temperature,
 * storage, memory, uptime and app usage. Lists apps you haven't opened in 30+ days with their size (uninstall candidates),
 * the apps you used most this week, and fixes in order of payoff. Read-only: uninstalling stays a separate, confirmed step.
 * Pure grading in [grade] (PhoneHealthTest).
 */
object PhoneHealth {
    const val TOOL = "phone_health"

    data class Input(val battery: Int, val charging: Boolean, val tempC: Float, val freeGb: Float, val totalGb: Float,
                     val freeRamMb: Long, val totalRamMb: Long, val uptimeH: Float, val powerSave: Boolean, val unusedApps: Int, val unusedMb: Long)

    data class Report(val score: Int, val grade: String, val fixes: List<String>)

    fun grade(i: Input): Report {
        var s = 100; val fix = ArrayList<Pair<Int, String>>()
        if (i.totalGb > 0) {
            val pct = i.freeGb / i.totalGb
            when { pct < 0.05f -> { s -= 30; fix += 30 to "Storage is almost full (%.1f GB free): apps slow down and updates fail. Clear WhatsApp media and old videos first.".format(i.freeGb) }
                   pct < 0.12f -> { s -= 15; fix += 15 to "Storage is getting tight (%.1f GB free). Aim for 15%% free.".format(i.freeGb) } }
        }
        if (i.tempC >= 42f) { s -= 20; fix += 20 to "The battery is hot (${i.tempC.toInt()}°C). Close games/camera, take the case off, don't charge in the sun." }
        else if (i.tempC >= 38f) { s -= 8; fix += 8 to "The battery is warm (${i.tempC.toInt()}°C)." }
        if (i.totalRamMb > 0 && i.freeRamMb * 100 / i.totalRamMb < 12) { s -= 12; fix += 12 to "Memory is nearly used up: restart the phone, then remove apps that run in the background." }
        if (i.uptimeH > 24 * 7) { s -= 10; fix += 10 to "The phone hasn't restarted in ${(i.uptimeH / 24).toInt()} days. A restart clears memory leaks." }
        if (i.unusedApps >= 10) { s -= 8; fix += 8 to "${i.unusedApps} apps unused for 30+ days take ${if (i.unusedMb > 1024) "%.1f GB".format(i.unusedMb / 1024f) else "${i.unusedMb} MB"}. Want me to list them for removal?" }
        if (!i.charging && i.battery in 0..15) { s -= 5; fix += 5 to "Battery is at ${i.battery}%." + if (!i.powerSave) " Turn on power saving." else "" }
        s = s.coerceIn(0, 100)
        val g = when { s >= 85 -> "healthy"; s >= 65 -> "okay"; s >= 45 -> "needs attention"; else -> "struggling" }
        return Report(s, g, fix.sortedByDescending { it.first }.map { it.second })
    }

    fun tool(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject().put("name", TOOL)
        .put("description", "Phone Health Check: score + fixes from battery, heat, storage, memory and uptime. " +
            "Use for 'why is my phone slow/hot/dying', 'clean up my phone'. Read-only.")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("detail", JSONObject().put("type", "string").put("enum", JSONArray(listOf("report"))))).put("required", JSONArray())))

    /** 2026-11-25 permission trim: unused_apps / top_apps needed Usage access (PACKAGE_USAGE_STATS), which left the app. */
    @Suppress("UNUSED_PARAMETER")
    fun run(ctx: Context, a: JSONObject): String {
        val s = DeviceStatus.snap(ctx)
        val r = grade(Input(s.battery, s.charging, s.tempC, s.freeStorageGb, s.totalStorageGb, s.freeRamMb, s.totalRamMb, s.uptimeH, s.powerSave, 0, 0))
        return "Phone health ${r.score}/100 (${r.grade}). Battery ${s.battery}%${if (s.charging) " charging" else ""}, ${s.tempC}°C, ${s.health}; storage " +
            "%.1f of %.0f GB free; memory ${s.freeRamMb} of ${s.totalRamMb} MB free; up ${(s.uptimeH).toInt()} h.".format(s.freeStorageGb, s.totalStorageGb) +
            (if (r.fixes.isEmpty()) "\nNothing to fix." else "\nFixes, biggest payoff first:\n" + r.fixes.mapIndexed { i, f -> "${i + 1}. $f" }.joinToString("\n"))
    }
}
