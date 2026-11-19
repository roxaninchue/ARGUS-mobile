package com.argus.assistant.agent

import com.argus.assistant.BrandName

/**
 * Owner 16 Nov 2026 (whole-chat review): one chat as a compact digest the curator reads in a single call: every user
 * prompt, every reply (trimmed), each tool with its arguments and outcome (from the reply's "What happened" trace,
 * [TurnTrace]), step lines and errors. When it is too long the first prompt (what the chat set out to do) stays and the
 * newest lines fill the rest. Pure Kotlin (ChatDigestTest).
 */
object ChatDigest {
    const val MAX_CHARS = 24_000
    const val USER_CHARS = 1_200
    const val REPLY_CHARS = 800

    data class Stats(val prompts: Int, val replies: Int, val tools: Int, val failed: Int, val errors: Int)

    private fun one(s: String, n: Int) = TurnTrace.short(s, n)

    /** The digest's lines, oldest first. */
    fun lines(msgs: List<ChatMessage>): List<String> {
        val out = ArrayList<String>()
        for (m in msgs) when (m.role) {
            Role.USER -> if (m.text.isNotBlank() || m.files.isNotEmpty())
                out += "[user] " + one(m.text, USER_CHARS) + (if (m.files.isNotEmpty()) " (files: ${m.files.joinToString(", ")})" else "")
            Role.BLAZE -> {
                val t = TurnTrace.fromJson(m.trace)
                t?.tools?.forEach { x -> out += "  tool ${x.name}(${one(x.args, 160)}) -> " + (if (x.ok) "ok" else "FAILED") + (if (x.result.isNotBlank()) ": " + one(x.result, 200) else "") }
                t?.errors?.forEach { out += "  error: " + one(it, 200) }
                if (m.text.isNotBlank()) out += "[${BrandName.NAME.lowercase()}] " + one(m.text, REPLY_CHARS)
            }
            Role.STEP -> if (m.text.isNotBlank()) out += "  step: " + one(m.text, 120)
            Role.ERROR -> if (m.text.isNotBlank()) out += "[error] " + one(m.text, 300)
        }
        // the same step line many times over ("Searching the web" x6) says nothing new
        return out.filterIndexed { i, l -> i == 0 || !(l.startsWith("  step: ") && l == out[i - 1]) }
    }

    /** The digest, at most [max] characters: the first prompt, then as many of the newest lines as fit. */
    fun build(msgs: List<ChatMessage>, max: Int = MAX_CHARS): String {
        val all = lines(msgs)
        val text = all.joinToString("\n")
        if (text.length <= max) return text
        val first = all.firstOrNull { it.startsWith("[user] ") }.orEmpty()
        val tail = ArrayList<String>()
        var used = first.length + 60
        for (l in all.asReversed()) {
            if (used + l.length + 1 > max) break
            tail += l; used += l.length + 1
        }
        tail.reverse()
        val left = all.size - tail.size - (if (first.isNotEmpty() && first !in tail) 1 else 0)
        return listOfNotNull(first.takeIf { it.isNotEmpty() && it !in tail }, "… ($left earlier lines left out)").joinToString("\n") +
            "\n" + tail.joinToString("\n")
    }

    fun stats(msgs: List<ChatMessage>): Stats {
        val traces = msgs.filter { it.role == Role.BLAZE }.mapNotNull { TurnTrace.fromJson(it.trace) }
        return Stats(msgs.count { it.role == Role.USER }, msgs.count { it.role == Role.BLAZE && it.text.isNotBlank() },
            traces.sumOf { it.tools.size }, traces.sumOf { t -> t.tools.count { !it.ok } },
            traces.sumOf { it.errors.size } + msgs.count { it.role == Role.ERROR })
    }

    /** Worth a review: a real conversation, not a greeting (at least [minPrompts] prompts, or tools were used). */
    fun worthReview(msgs: List<ChatMessage>, minPrompts: Int = 3): Boolean {
        val s = stats(msgs)
        return s.prompts >= minPrompts || (s.prompts >= 1 && s.tools >= 2)
    }
}
