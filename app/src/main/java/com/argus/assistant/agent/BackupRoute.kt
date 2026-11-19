package com.blaze.assistant.agent

/**
 * Pure (BackupRouteTest): the brains Argus asks for an answer, and when it moves on. Two, both on the gateway:
 *  - Blaze Cloud (the gateway's own lineup);
 *  - the Nous reserve (`X-Blaze-Reserve: only`, free models, slower but consistent).
 * Owner 16 Nov 2026: the on-device backup brain (OpenCode under Alpine) was removed; nothing runs a model on the phone.
 * The person never sees which one answered beyond the route chip.
 */
object BackupRoute {
    enum class Step { CLOUD, RESERVE }

    /** The `X-Blaze-Reserve` header value for a gateway call at [step]: "only" for the reserve, "skip" for Cloud (the reserve is asked on its own). */
    fun reserveHeader(step: Step): String = if (step == Step.RESERVE) "only" else "skip"

    /**
     * Should the next step run after a brain failed with [code] / [message]? Not for what no other
     * brain can fix: a sign-in problem, the waiting list, today's allowance used up, a request the user cancelled.
     */
    fun moveOn(code: Int, message: String): Boolean {
        val t = message.lowercase()
        if (code == 401 || code == 403) return false
        if (Cloud.access(code, message) != null) return false
        if (t == Cloud.SIGNED_OUT.lowercase() || t == Cloud.EXPIRED.lowercase()) return false
        if (code == 429 && ("allowance" in t || "quota_exceeded" in t || "today's free messages" in t)) return false
        return true
    }

    /**
     * v0.45.28: what runs after Blaze Cloud's own call failed for good. The Nous reserve on the gateway
     * (`X-Blaze-Reserve: only`) ALWAYS gets a turn for a failure another brain can fix (busy, 5xx, 429 that isn't the
     * allowance, too slow, no answer, a stream that died mid-reply). [shapeError] (the request itself was refused) and
     * what [moveOn] rules out (sign-in, waiting list, allowance) get nothing.
     */
    fun fallbacks(code: Int, message: String, shapeError: Boolean, afterHeal: Boolean = false): List<Step> = when {
        // A1: after the flattened retry a 400 is a model / provider quirk, not the request's shape: the fallbacks still run
        (shapeError && !afterHeal) || !moveOn(code, message) -> emptyList()
        else -> listOf(Step.RESERVE)
    }

    /** A stream that broke after some words arrived (the fallback's reply replaces the partial one in the same bubble). */
    fun brokeMidReply(message: String): Boolean {
        val t = message.lowercase()
        return "stopped sending" in t || "mid-reply" in t || "stream ended" in t || "unexpected end of stream" in t ||
            "connection reset" in t || "software caused connection abort" in t || "stream was reset" in t
    }

    /** Has the attempt that started at [startedAt] shown a sign of life ([lastChunkAt] = latest chunk of any kind)? */
    fun alive(startedAt: Long, lastChunkAt: Long, gotFirst: Boolean, callStart: Long): Boolean =
        lastChunkAt >= startedAt || (gotFirst && callStart >= startedAt)

    /** Too slow: no sign of life [ttft] ms after [startedAt] (0 = never). */
    fun tooSlow(startedAt: Long, now: Long, alive: Boolean, ttft: Long): Boolean = ttft > 0 && !alive && now - startedAt >= ttft
}
