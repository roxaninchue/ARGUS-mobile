package com.argus.assistant.composer


import com.argus.assistant.BrandName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 0.47 chat composer: the model family the user picked on the composer's model chip (or in Spotlight).
 * Auto (default) / Spark (fast, < 6 s) / Flare (balanced, 6–12 s) / Inferno (smartest, > 12 s).
 *
 * The choice never changes the brain ORDER (on-phone brain → Nous → Blaze cloud gateway; never Jev, System One or
 * NVIDIA models). Routing only READS it: [wire] is sent to the gateway as the `blaze_family` hint (every blaze_* field is
 * stripped by the gateway before a provider sees it), Auto sends nothing. Persisted in Settings ("composer_model").
 */
object ModelChoice {
    enum class Family(val id: String, val label: String, val sub: String) {
        AUTO("auto", "Auto", "Picks for you"),
        // 0.52.1 (Argus rename): user-facing labels Insight / Oracle / Panoptes (fast → balanced → deepest). The ids stay
        // spark / flare / inferno: they are what is saved (Settings "composer_model") and what the gateway's tiers.ts reads.
        SPARK("spark", "Insight", "Fast"),
        FLARE("flare", "Oracle", "Balanced"),
        INFERNO("inferno", "Panoptes", "Deepest");

        /** Old labels (and ids) that still find this tier in Spotlight search. */
        val aliases: List<String> get() = when (this) { AUTO -> emptyList(); SPARK -> listOf("spark", "fast"); FLARE -> listOf("flare"); INFERNO -> listOf("inferno", "smartest") }

        /** "Insight (Fast)", the picker row. */
        val pickerLabel: String get() = if (this == AUTO) label else "$label ($sub)"
        /** "Blaze Inferno", the Spotlight / self-map name. */
        val blazeName: String get() = "${BrandName.NAME} $label"
    }

    const val PREF = "composer_model"

    private val _current = MutableStateFlow(load())
    val current: StateFlow<Family> = _current

    /** A saved or typed pick: an id ("spark"), a current label ("Insight") or an old label ("Spark"). Anything else is Auto. */
    fun of(id: String?): Family {
        val k = id?.trim()?.lowercase().orEmpty()
        return Family.values().firstOrNull { it.id == k || it.label.lowercase() == k } ?: Family.AUTO
    }

    /**
     * 0.52.1 migration: a pick saved as a label (an old build, a restored backup, a hand-edited setting: "Spark",
     * "Insight", "Panoptes"…) is rewritten as its id once, so it keeps meaning the same tier.
     */
    fun migrate(saved: String?): String? {
        val k = saved?.trim() ?: return null
        val f = of(k)
        return if (k == f.id) null else f.id
    }

    private fun load(): Family {
        val saved = runCatching { com.argus.assistant.agent.Settings.str(PREF, "auto") }.getOrDefault("auto")
        migrate(saved)?.let { id -> runCatching { com.argus.assistant.agent.Settings.setStr(PREF, id) } }
        return of(saved)
    }

    fun set(f: Family) {
        _current.value = f
        runCatching { com.argus.assistant.agent.Settings.setStr(PREF, f.id) }
    }

    /** The gateway hint for [f]: null for Auto (the gateway picks as before). */
    fun wire(f: Family = _current.value): String? = if (f == Family.AUTO) null else f.id

    /**
     * 0.52.0: the gateway routes each tier leads with (gateway 0.8.61 src/tiers.ts, branch feat/tiers-0.52; the gateway
     * resolves the tier, the phone only sends [wire]). Real route ids ("provider/model") from the live 0.8.60 routes.
     * Never shown in the UI (model names stay hidden); kept here for the docs, the self-map and TiersTest.
     */
    fun routes(f: Family): List<String> = when (f) {
        Family.AUTO -> emptyList()
        Family.SPARK -> listOf("groq/qwen/qwen3.8-27b", "cerebras/gpt-oss-120b", "gemini/gemini-3.5-flash-lite")
        Family.FLARE -> listOf("yeta/deepseek-v4.1-flash", "tokenharbor/deepseek-v4.1-flash:free", "gemini/gemini-3.5-flash")
        Family.INFERNO -> listOf("yeta/glm-5.3-flash", "deepseek/deepseek-flash", "nous/reflection/beam-1.0-501b-a23b:free", "gemini/gemini-3.5-flash")
    }

    /**
     * 0.52.0: the tier a background / blueprint agent runs on when the user left the chip on Auto: planners, reviewers
     * and verifiers think hardest (Inferno), writers and coders are balanced (Flare), searches and rankers are fast
     * (Spark). A tier the user picked always wins.
     */
    fun forRole(role: String, picked: Family = _current.value): Family = if (picked != Family.AUTO) picked else when (role.lowercase()) {
        "planner", "reviewer", "verifier", "integrator", "sorter" -> Family.INFERNO
        "researcher", "curator", "search", "ranker" -> Family.SPARK
        "" -> Family.AUTO
        else -> Family.FLARE
    }

    /** Chip text: "Auto", "Insight", … */
    fun chip(f: Family = _current.value): String = f.label
}
