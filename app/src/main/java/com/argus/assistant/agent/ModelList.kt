package com.blaze.assistant.agent

/**
 * The model picker's list (owner, 13 Nov 2026): one row per model whatever provider serves it, no provider names, a
 * search box, signal bars for speed and health, and the model maker's logo. Pure, so ModelListTest covers it on the JVM.
 */
object ModelList {
    /** One row: [best] is the member a tap picks (the healthiest, then fastest, provider); [keys] are every member's keys. */
    data class Row(val name: String, val best: LiveModels.Member, val keys: Set<String>, val bars: Int, val brand: String?) {
        val tools get() = best.tools
        val vision get() = best.vision
    }

    /** Same model from two providers ("Gemini 3.8 Flash", "gemini-3-8-flash") folds to one key. */
    fun sameKey(name: String): String = name.lowercase().replace(Regex("[^a-z0-9]"), "")

    /**
     * Signal bars, 1 to 4, like a phone's: 4 under 1.5 s, 3 under 3 s, 2 under 5 s, else 1; one bar off under 90 %
     * success and two under 70 %. Unmeasured (0 ms) with no failures reads 3.
     */
    fun bars(ms: Int, success: Double?): Int {
        var b = when { ms <= 0 -> 3; ms <= 1500 -> 4; ms <= 3000 -> 3; ms <= 5000 -> 2; else -> 1 }
        if (success != null) { if (success < 0.7) b -= 2 else if (success < 0.9) b -= 1 }
        return b.coerceIn(1, 4)
    }

    private fun bars(m: LiveModels.Member) = bars(m.ms, m.success)

    /** Model maker for a name or id, matched on the model family, never the serving provider. Null = no logo. */
    fun brand(name: String, key: String = ""): String? {
        val s = (name + " " + key.substringAfterLast('/')).lowercase()
        fun w(vararg r: String) = r.any { Regex("(^|[^a-z])$it").containsMatchIn(s) }
        return when {
            w("claude", "opus", "sonnet", "haiku", "fable") -> "claude"
            w("gemma") -> "gemma"
            w("gemini") -> "gemini"
            w("kimi") -> "kimi"
            w("moonshot") -> "moonshot"
            w("glm", "zhipu", "chatglm") -> "zhipu"
            w("deepseek") -> "deepseek"
            w("qwen", "qwq") -> "qwen"
            w("mimo") -> "xiaomimimo"
            w("nemotron", "nvidia") -> "nvidia"
            w("mistral", "mixtral", "codestral", "devstral", "magistral", "ministral", "pixtral", "voxtral") -> "mistral"
            w("hunyuan", "hy\\d") -> "hunyuan"
            w("grok") -> "grok"
            w("gpt", "o\\d(-|\\s|$)", "chatgpt") -> "openai"
            w("llama", "meta") -> "meta"
            w("minimax", "abab") -> "minimax"
            w("step") -> "stepfun"
            w("command", "cohere", "aya") -> "cohere"
            w("doubao", "seed") -> "doubao"
            w("ernie") -> "baidu"
            else -> null
        }
    }

    /** Folds providers into one row per model (members [hidden] after a miss left out), best first by bars then speed, keeping the gateway's order on ties. */
    fun rows(list: List<LiveModels.Member>, hidden: (String) -> Boolean = { false }): List<Row> {
        val groups = LinkedHashMap<String, MutableList<LiveModels.Member>>()
        for (m in list) if (m.key != LiveModels.AUTO.key && !hidden(m.key)) groups.getOrPut(sameKey(m.name)) { mutableListOf() }.add(m)
        return groups.values.map { g ->
            val best = g.sortedWith(compareByDescending<LiveModels.Member> { bars(it) }.thenBy { if (it.ms > 0) it.ms else Int.MAX_VALUE }).first()
            Row(best.name, best, g.map { it.key }.toSet(), bars(best), brand(best.name, best.key))
        }
    }

    /** Every word of [q] must appear in the name or the maker ("gem fla" finds Gemini 3.8 Flash). Blank = all. */
    fun filter(rows: List<Row>, q: String): List<Row> {
        val words = q.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return rows
        return rows.filter { r -> val hay = (r.name + " " + r.brand.orEmpty()).lowercase(); words.all { it in hay } }
    }

    /** The row holding [key], so a pick made on any provider shows as that model's row. */
    fun rowOf(rows: List<Row>, key: String): Row? = rows.firstOrNull { key in it.keys }

    /** What the row says under the name: no provider, just what the model can do. */
    fun detail(r: Row): String = buildList { if (r.tools) add("tools"); if (r.vision) add("vision") }.joinToString(" · ")
}
