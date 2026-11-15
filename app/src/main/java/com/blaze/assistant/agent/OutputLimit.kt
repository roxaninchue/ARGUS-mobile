package com.blaze.assistant.agent

import java.util.concurrent.ConcurrentHashMap

/**
 * 0.54.0 (harness musts #1): the output limit a call asks for is the model's own, not one number for every model.
 * Like opencode's outputLimit (packages/core/src/session/model-request.ts): the model's catalog output limit (32K when
 * unknown, never over 256K), fitted to the room the prompt leaves in the context window, never under 1K.
 * The old flat 8,192 on every tool turn let a thinking model (DeepSeek, Gemini: reasoning counts toward max_tokens)
 * run out before it reached its tool call.
 * A route that rejects the number names its own limit in the 400; [learn] keeps that limit for the model, so only
 * the first call to that route ever hears the 400.
 */
object OutputLimit {
    const val FALLBACK = 32_000
    const val MAX = 256_000
    const val MIN = 1_024
    /** Prompt text is estimated at ~4 chars a token, which runs low on code; leave this share spare. */
    private const val ESTIMATE_ERROR = 0.15
    private const val HEADROOM = 0.01

    /** Limits a route told us in a 400 ("max_tokens must be <= 8192"), by model id. */
    private val learned = ConcurrentHashMap<String, Int>()

    /** The catalog output limit for a model id (0 = unknown). Figures from models.dev as opencode ships them. */
    fun catalog(model: String): Int {
        val m = model.lowercase()
        return when {
            m.contains("deepseek-v4") || m.contains("deepseek-v3.2") -> 384_000
            m.contains("deepseek-reasoner") || m.contains("deepseek-r1") -> 64_000
            m.contains("deepseek-chat") || m.contains("deepseek-v3") -> 8_192
            m.contains("gemini-3") || m.contains("gemini-2.5") -> 65_536
            m.contains("gemini") -> 8_192
            m.contains("claude") && m.contains("opus") -> 32_000
            m.contains("claude") -> 64_000
            m.contains("gpt-5") -> 128_000
            m.contains("gpt-oss") -> 32_768
            m.contains("gpt-4.1") -> 32_768
            m.contains("gpt-4o") -> 16_384
            m.contains("kimi-k2") -> 32_768
            m.contains("glm-4.6") || m.contains("glm-4.7") || m.contains("glm-5") -> 128_000
            m.contains("minimax-m2") -> 128_000
            m.contains("qwen3") -> 32_768
            else -> 0
        }
    }

    /**
     * The max_tokens to send. [override] is Settings "tool_max_tokens" (0 = not set), [inputTokens] the prompt's
     * estimated size, [contextWindow] the model's window (0 = unknown).
     */
    fun forCall(model: String, inputTokens: Int, contextWindow: Int, override: Int = 0): Int {
        if (override > 0) return override.coerceIn(MIN, MAX)
        val known = learned[key(model)] ?: catalog(model).takeIf { it > 0 } ?: FALLBACK
        val requested = minOf(known, MAX)
        if (contextWindow <= 0 || inputTokens <= 0) return requested
        val room = (contextWindow * (1 - HEADROOM)).toInt() - Math.ceil(inputTokens * (1 + ESTIMATE_ERROR)).toInt()
        return minOf(requested, maxOf(MIN, room))
    }

    /**
     * A 400 about the output limit: keep the limit the route named for [model] and return it (null when the
     * message isn't about max_tokens or names no usable number). The caller retries the call once.
     */
    fun learn(model: String, error: String, sent: Int): Int? {
        val e = error.lowercase()
        if (!(e.contains("max_tokens") || e.contains("max_completion_tokens") || e.contains("max_output_tokens") ||
                e.contains("maximum output") || e.contains("output tokens"))) return null
        val n = Regex("""\d[\d,]*""").findAll(error).mapNotNull { it.value.replace(",", "").toIntOrNull() }
            .filter { it in MIN until sent }.maxOrNull() ?: return null
        learned[key(model)] = n
        return n
    }

    fun forget() = learned.clear()

    private fun key(model: String) = model.trim().lowercase()
}
