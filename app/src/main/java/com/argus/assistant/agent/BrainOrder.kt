package com.argus.assistant.agent


import com.argus.assistant.BrandName
import org.json.JSONArray

/**
 * Brain order and the per-brain ledger. Owner 16 Nov 2026: the on-device backup brain was removed, so the order is the
 * gateway's two brains: plain chat asks the Nous reserve then Argus Cloud; tool turns Argus Cloud then the reserve
 * ([BrainRace.order]). [coding] still marks coding jobs for the race's limits. Pure (BrainOrderTest).
 */
object BrainOrder {
    fun plan(signedIn: Boolean = true): List<BackupRoute.Step> =
        if (signedIn) listOf(BackupRoute.Step.RESERVE, BackupRoute.Step.CLOUD) else emptyList()

    /** Display names for Settings › Brains. */
    fun label(s: BackupRoute.Step): String = when (s) {
        BackupRoute.Step.RESERVE -> "Nous reserve"
        BackupRoute.Step.CLOUD -> "${BrandName.NAME} Cloud"
    }

    private val codeWords = Regex("""(?i)\b(code|coding|bug|debug|function|class|method|compile|compiler|stack ?trace|traceback|exception|refactor|repo|repository|git|github|gitlab|commit|pull request|merge request|terminal|shell|bash|script|python|kotlin|java|javascript|typescript|rust|golang|c\+\+|sql|regex|api|json|yaml|dockerfile|npm|pip|gradle|apk|decompile|reverse engineer|rea|exam coach)\b|```""")
    private val codeTools = Regex("""^(terminal|run_command|shell|git|github_|gitlab_|code_|repo_|linux_|exam_coach|rea)""")

    private fun lastUser(messages: JSONArray): String {
        for (i in messages.length() - 1 downTo 0) {
            val m = messages.optJSONObject(i) ?: continue
            if (m.optString("role") == "user") return (m.opt("content") as? String) ?: m.optJSONArray("content")?.toString().orEmpty()
        }
        return ""
    }

    /** A coding job: the request talks code / repos / terminal, or the turn already used code tools. */
    fun coding(messages: JSONArray): Boolean {
        if (codeWords.containsMatchIn(lastUser(messages).take(4_000))) return true
        for (i in 0 until messages.length()) {
            val calls = messages.optJSONObject(i)?.optJSONArray("tool_calls") ?: continue
            for (j in 0 until calls.length()) if (codeTools.containsMatchIn(calls.optJSONObject(j)?.optJSONObject("function")?.optString("name").orEmpty())) return true
        }
        return false
    }
}

/** 0.46.3: usage and health per brain, recorded on the phone (the daily effort meter will read it). Pure core. */
class BrainLedger(private val now: () -> Long = { System.currentTimeMillis() }) {
    data class Row(var calls: Int = 0, var fails: Int = 0, var ms: Long = 0, var lastOk: Long = 0, var lastFail: Long = 0, var lastError: String = "", var ttft: Long = 0,
                   var rel: Double = -1.0)
    val rows = linkedMapOf<BackupRoute.Step, Row>()
    @Synchronized fun ok(s: BackupRoute.Step, ms: Long) { val r = rows.getOrPut(s) { Row() }; r.calls++; r.ms += ms; r.lastOk = now(); r.rel = blend(r.rel, 1.0) }
    /** A real failure (error from the brain). 0.46.5: a brain cut by OUR deadline is not a failure: don't call this for it. */
    @Synchronized fun fail(s: BackupRoute.Step, why: String) { val r = rows.getOrPut(s) { Row() }; r.fails++; r.lastFail = now(); r.lastError = why.take(120); r.rel = blend(r.rel, 0.0) }
    /** 0.46.5: success rate, smoothed (new = 0.7 × old + 0.3 × this outcome; first outcome counts from 0.75); null = never measured. */
    @Synchronized fun reliability(s: BackupRoute.Step): Double? = rows[s]?.rel?.takeIf { it >= 0.0 }
    private fun blend(old: Double, x: Double) = if (old < 0) 0.75 * 0.7 + x * 0.3 else old * 0.7 + x * 0.3
    /** 0.46.4: first-token time (ms), smoothed (new = 0.6 × old + 0.4 × this call); the chat router puts the fastest first. */
    @Synchronized fun ttft(s: BackupRoute.Step, ms: Long) { val r = rows.getOrPut(s) { Row() }; r.ttft = if (r.ttft <= 0) ms.coerceAtLeast(1) else ((r.ttft * 6 + ms.coerceAtLeast(1) * 4) / 10) }
    @Synchronized fun ttft(s: BackupRoute.Step): Long? = rows[s]?.ttft?.takeIf { it > 0 }
    /** "answering", "failing", or "not used yet", from the newest outcome. */
    @Synchronized fun health(s: BackupRoute.Step): String { val r = rows[s] ?: return "not used yet"; return if (r.lastOk >= r.lastFail) "answering" else "failing" }
    @Synchronized fun toJson(): String = org.json.JSONObject().also { o -> rows.forEach { (k, r) -> o.put(k.name, org.json.JSONObject().put("calls", r.calls).put("fails", r.fails).put("ms", r.ms)
        .put("lastOk", r.lastOk).put("lastFail", r.lastFail).put("err", r.lastError).put("ttft", r.ttft).put("rel", r.rel)) } }.toString()
    @Synchronized fun load(json: String) { try { val o = org.json.JSONObject(json); o.keys().forEach { k -> val r = o.getJSONObject(k)
        // 16 Nov 2026: rows of removed brains (the on-phone "BACKUP") are skipped, the rest still load
        val step = BackupRoute.Step.values().firstOrNull { it.name == k } ?: return@forEach
        rows[step] = Row(r.optInt("calls"), r.optInt("fails"), r.optLong("ms"), r.optLong("lastOk"), r.optLong("lastFail"), r.optString("err"), r.optLong("ttft"), r.optDouble("rel", -1.0)) } } catch (_: Exception) { } }

    companion object {
        /** The app's ledger (saved in Settings "brain_usage_v1"). */
        val app: BrainLedger by lazy { BrainLedger().also { l -> try { l.load(Settings.str("brain_usage_v1", "")) } catch (_: Throwable) { } } }
        fun save() { try { Settings.setStr("brain_usage_v1", app.toJson()) } catch (_: Throwable) { } }
    }
}
