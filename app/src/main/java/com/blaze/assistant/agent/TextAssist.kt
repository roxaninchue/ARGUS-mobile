package com.blaze.assistant.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Text assist (owner, 18 Nov 2026): a chip on the composer's top row, on the right, with five levels of help writing the
 * prompt: Off, Low, Med, High, Full. It improves what the user typed while keeping what they meant, so the model gets a
 * clearer ask, not a different one.
 *
 * Model-first (docs/MODEL-FIRST.md): the user's words are never rewritten behind their back. With assist on, the first tap
 * on send puts the improved text in the chat box, where the user reads it, edits it or taps Undo; the second tap sends what
 * the box holds, and that is exactly what the model reads. With assist Off, send works as before. The rewrite is one
 * plain call (no tools, the recent chat as context) on the picked model, or Auto's background lane; it never answers.
 *
 * Pure Kotlin + Settings (str / setStr); TextAssistTest.
 */
object TextAssist {
    const val KEY = "text_assist"

    enum class Level(val id: String, val label: String, val sub: String) {
        OFF("off", "Off", "Send what you type"),
        LOW("low", "Low", "Fix spelling and grammar"),
        MED("med", "Med", "Also make vague references clear"),
        HIGH("high", "High", "Also spell out implied details and format"),
        FULL("full", "Full", "Turn it into a structured brief");

        companion object { fun of(id: String?): Level = entries.firstOrNull { it.id == id?.trim()?.lowercase() } ?: OFF }
    }

    private val _level = MutableStateFlow(load())
    val level: StateFlow<Level> = _level

    /** True while a rewrite is on its way (the chip says so). */
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private fun load(): Level = try { Level.of(Settings.str(KEY, "off")) } catch (_: Throwable) { Level.OFF }

    fun set(l: Level) {
        _level.value = l
        try { Settings.setStr(KEY, l.id) } catch (_: Throwable) { }
    }

    /**
     * Whether this tap on send should rewrite first: assist is on, there is text, and this draft hasn't been through assist
     * yet ([assistedFrom] is the original once it has, so the next tap sends whatever the box holds, edits and Undo included).
     */
    fun shouldRewrite(level: Level, text: String, assistedFrom: String?): Boolean =
        level != Level.OFF && text.isNotBlank() && assistedFrom == null

    private const val CORE = "You improve a message the user is about to send to an AI assistant, so the assistant understands it well. " +
        "Keep the user's intent, voice, language and every request in it, and add nothing they did not mean. " +
        "Reply with the improved message only, written as the user, with no preface or quotes."

    /** The system prompt for [level]: one shared line, then one line for the level. Null for Off. */
    fun prompt(level: Level): String? = when (level) {
        Level.OFF -> null
        Level.LOW -> "$CORE\nOnly fix spelling, grammar and punctuation; keep the wording otherwise."
        Level.MED -> "$CORE\nFix spelling and grammar, and make vague references specific using the conversation (name the file, item or person \"it\" or \"that\" means)."
        Level.HIGH -> "$CORE\nFix spelling and grammar, make vague references specific, and state the constraints, preferences and output format the user implied in the message or the conversation."
        Level.FULL -> "$CORE\nRewrite it as a clear brief in short labelled parts: the goal, relevant context from the conversation, constraints, the deliverable, and what done looks like."
    }

    /** The request for [text] at [level] with the recent chat [lines] ((isUser, text), oldest first). Null for Off or no text. */
    fun request(level: Level, text: String, lines: List<Pair<Boolean, String>>): JSONArray? {
        val sys = prompt(level) ?: return null
        if (text.isBlank()) return null
        val picked = ArrayList<String>(); var size = 0
        for ((isUser, raw) in lines.asReversed()) {
            if (picked.size >= 8) break
            var t = raw.split(Regex("\\s+")).joinToString(" ").trim(); if (t.isEmpty()) continue
            if (t.length > 500) t = t.take(500) + "…"
            val l = (if (isUser) "User: " else "Assistant: ") + t
            if (size + l.length > 3000 && picked.isNotEmpty()) break
            size += l.length; picked.add(0, l)
        }
        val user = (if (picked.isEmpty()) "" else "Conversation so far (latest last):\n" + picked.joinToString("\n") + "\n\n") +
            "Message to improve:\n" + text
        return JSONArray().put(JSONObject().put("role", "system").put("content", sys)).put(JSONObject().put("role", "user").put("content", user))
    }

    /** The model's reply → the text for the chat box, or null when nothing usable came back (empty, or the same text). */
    fun clean(raw: String, original: String): String? {
        var s = raw.substringAfterLast("</think>").trim()
        if (s.startsWith("```") && s.endsWith("```") && s.length > 6) s = s.removePrefix("```").removeSuffix("```").substringAfter('\n', s).trim()
        if (s.length >= 2 && ((s.first() == '"' && s.last() == '"') || (s.first() == '\u201c' && s.last() == '\u201d'))) s = s.substring(1, s.length - 1).trim()
        if (s.isEmpty() || s == original.trim()) return null
        return s
    }

    /** One rewrite of [text] at the current level; null when assist is Off or the call fails (the box keeps the user's text). */
    suspend fun rewrite(text: String, lines: List<Pair<Boolean, String>>, level: Level = this.level.value): String? {
        val msgs = request(level, text, lines) ?: return null
        _busy.value = true
        return try {
            clean(LlmClient.complete(msgs, null, null, null, LlmClient.Lane.BACKGROUND).optString("content"), text)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
        finally { _busy.value = false }
    }
}
