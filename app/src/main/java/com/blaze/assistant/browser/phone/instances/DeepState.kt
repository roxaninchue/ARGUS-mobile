package com.blaze.assistant.browser.phone.instances

import com.blaze.assistant.browser.phone.PhonePolicy
import org.json.JSONArray
import org.json.JSONObject

/**
 * The deep-state snapshot of docs/ARGUS-PHONE.md 5g: for every instance, cheaply, what Argus needs to choose "reuse or
 * new" without guessing: owner and lock, cold / warm / live, open apps with a state summary, the page each is on,
 * unsaved changes, pending cards, memory, last checkpoint, last error and the last few steps. Compact JSON (short keys,
 * nothing empty), one object per instance; `phone_instances list` returns them as one array.
 *
 * Secrets never reach the model (hard rule 6): URL query values whose names look secret (token, code, password, key,
 * session, auth, sig...) are replaced by `***`; card / password field values are never part of an instance's state.
 * Running terminal commands join when the Terminal app does (step 4).
 */
object DeepState {
    /** Rough RAM per tier (estimates until PhonePerf measures them per app on a low-RAM phone, section 6). */
    const val LIVE_BYTES = 90L * 1024 * 1024      // a WebView renderer + Argus Phone's frame buffers
    const val WARM_BASE_BYTES = 8L * 1024          // the state model in memory
    const val STEPS_SHOWN = 5

    fun memoryEstimate(i: Instance): Long = when (i.tier) {
        Tier.LIVE -> LIVE_BYTES
        Tier.WARM -> WARM_BASE_BYTES + (i.browser?.let { it.url.length + it.title.length } ?: 0) * 2L + i.steps.sumOf { it.text.length } * 2L
        Tier.COLD -> 0L
    }

    private val secretKey = Regex("""(?i)^(.*(token|secret|passw|pwd|pass|auth|session|sid|sig|signature|key|code|otp|nonce|ticket|jwt|bearer|cred).*)$""")

    /** [url] with secret-looking query and fragment values masked. */
    fun safeUrl(url: String): String {
        val q = url.indexOf('?').takeIf { it >= 0 } ?: url.indexOf('#').takeIf { it >= 0 } ?: return url.take(200)
        val base = url.substring(0, q)
        val rest = url.substring(q + 1)
        val sep = url[q]
        val masked = rest.split('&', '#').joinToString("&") { kv ->
            val k = kv.substringBefore('=')
            if (kv.contains('=') && secretKey.matches(k)) "$k=***" else kv
        }
        return (base + sep + masked).take(200)
    }

    /** One instance's snapshot. [active]: attached to the WebView now. [now] for ages. */
    fun of(i: Instance, now: Long, active: Boolean, pinnedWhy: String? = null): JSONObject {
        val o = JSONObject().put("id", i.id).put("name", i.name)
        if (i.task.isNotBlank()) o.put("task", i.task.take(80))
        o.put("tier", i.tier.name.lowercase())
        if (active) o.put("active", true)
        o.put("mode", when (i.mode) { PhonePolicy.Mode.AGENT -> "argus"
            PhonePolicy.Mode.WAITING -> "waiting_on_user"
            PhonePolicy.Mode.USER -> "user_drives"
            else -> "idle" })
        Leases.current(i, now)?.let { l ->
            o.put("lock", JSONObject().put("by", l.owner.label()).put("kind", l.owner.kind.name.lowercase())
                .apply { if (l.task.isNotBlank()) put("task", l.task.take(60)) }
                .put("ends_s", ((l.until - now) / 1000).coerceAtLeast(0)).put("working", Leases.working(l, now)))
        }
        val apps = JSONArray()
        i.browser?.takeIf { !it.empty }?.let { b ->
            apps.put(JSONObject().put("app", "browser").put("url", safeUrl(b.url)).apply {
                if (b.title.isNotBlank()) put("title", b.title.take(80)); if (b.site.isNotBlank()) put("site", b.site); if (b.scrollY > 0) put("scroll", b.scrollY) })
        }
        if (apps.length() > 0) o.put("apps", apps)
        if (i.unsaved) o.put("unsaved", true)
        if (i.cards.isNotEmpty()) o.put("cards", JSONArray().apply { i.cards.take(4).forEach { put(JSONObject().put("kind", it.kind).put("text", it.text.take(80))) } })
        o.put("mem_kb", memoryEstimate(i) / 1024)
        if (i.lastCheckpointAt > 0) o.put("checkpoint_s_ago", ((now - i.lastCheckpointAt) / 1000).coerceAtLeast(0))
        i.lastError?.let { o.put("error", it.take(160)) }
        if (i.restorable) o.put("restorable", true)
        if (i.steps.isNotEmpty()) o.put("steps", JSONArray().apply { i.steps.takeLast(STEPS_SHOWN).forEach { put(it.text.take(80)) } })
        pinnedWhy?.let { o.put("kept_because", it) }
        o.put("idle_s", ((now - i.lastActiveAt) / 1000).coerceAtLeast(0))
        return o
    }
}
