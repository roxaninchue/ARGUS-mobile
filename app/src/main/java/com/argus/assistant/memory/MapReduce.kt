package com.argus.assistant.memory

/**
 * Whole-document questions over the cache tier (0.49.0): summarise sections in batches that fit a small model
 * (map), then combine the partial summaries (reduce), repeating the reduce until one answer is left.
 * The model call is passed in, so this is pure and testable (MapReduceTest); CacheTier supplies the quick route.
 */
object MapReduce {
    /** Tokens of section text per map call (fits a 6–8k-token route with the prompt and the answer). */
    const val BATCH_TOKENS = 3_000
    /** Most sections one summary reads (≈ 240k tokens); the answer says when it stopped short. */
    const val MAX_SECTIONS = 120
    /** One section is clipped to this many characters inside a batch. */
    const val SECTION_CLIP = BATCH_TOKENS * 4

    /** Groups section sizes (chars) into batches of at most [maxTok] tokens; a section bigger than that is its own batch. */
    fun batches(sizes: List<Int>, maxTok: Int = BATCH_TOKENS): List<List<Int>> {
        val out = ArrayList<List<Int>>(); var cur = ArrayList<Int>(); var tok = 0
        sizes.forEachIndexed { i, chars ->
            val t = (minOf(chars, SECTION_CLIP) + 3) / 4
            if (cur.isNotEmpty() && tok + t > maxTok) { out += cur; cur = ArrayList(); tok = 0 }
            cur += i; tok += t
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    fun mapPrompt(focus: String, docName: String, part: String): String =
        "You are reading part of the document \"$docName\". " +
            (if (focus.isBlank()) "Summarise this part in at most 120 words: key points, names, numbers and decisions exactly as written."
            else "From this part, note everything relevant to: $focus. At most 120 words; quote names and numbers exactly. If nothing is relevant, answer \"(nothing relevant)\".") +
            " Answer with the notes only.\n\n$part"

    fun reducePrompt(focus: String, docName: String, notes: String): String =
        "Below are notes from consecutive parts of the document \"$docName\". " +
            (if (focus.isBlank()) "Combine them into one summary of the whole document (at most 300 words), in order, keeping names and numbers exact."
            else "Combine them into one answer to: $focus (at most 300 words), keeping names and numbers exact.") +
            " Answer with the result only.\n\n$notes"

    /**
     * Runs map then reduce. [texts] = the sections' text in order (read lazily), [ask] = one model call.
     * Returns the final text and how many sections were read.
     */
    suspend fun run(docName: String, focus: String, sizes: List<Int>, texts: suspend (List<Int>) -> List<String>,
                    ask: suspend (String) -> String, maxTok: Int = BATCH_TOKENS): Pair<String, Int> {
        val n = minOf(sizes.size, MAX_SECTIONS)
        val notes = ArrayList<String>()
        for (b in batches(sizes.take(n), maxTok)) {
            val part = texts(b).joinToString("\n\n---\n\n") { it.take(SECTION_CLIP) }
            val r = ask(mapPrompt(focus, docName, part)).trim()
            if (r.isNotBlank() && !r.startsWith("(nothing relevant)")) notes += "[§${b.first()}–§${b.last()}] $r"
        }
        if (notes.isEmpty()) return "(nothing relevant found)" to n
        var level: List<String> = notes
        var guard = 0
        while (level.size > 1 && guard++ < 6) {
            val next = ArrayList<String>()
            for (g in batches(level.map { it.length }, maxTok)) {
                val joined = g.joinToString("\n\n") { level[it] }
                next += if (g.size == 1) level[g[0]] else ask(reducePrompt(focus, docName, joined)).trim()
            }
            if (next.size == level.size) { level = listOf(ask(reducePrompt(focus, docName, level.joinToString("\n\n").take(maxTok * 4))).trim()); break }
            level = next
        }
        return level.first() to n
    }
}
