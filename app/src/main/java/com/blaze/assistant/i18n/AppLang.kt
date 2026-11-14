package com.blaze.assistant.i18n

import androidx.compose.runtime.mutableStateOf
import org.json.JSONObject
import java.util.Locale

/**
 * Owner 2026-11-14: the app's language. ARGUS always replies in the language you write in (ReplyLanguage is Auto for
 * good); this setting is what the app itself is shown in. Every language is listed by its own name (an Italian speaker
 * switching to English sees "English", never "Inglese"). First run asks for it before anything else.
 *
 * Text: [tr] takes the English source string and returns it in the app language from `assets/i18n/<code>.json`
 * (`{ "English": "Translation" }`), or the English when a table lacks it. `{0}`, `{1}` … are filled from the arguments.
 * Reading [tr] inside a composable redraws it when the language changes (the code is Compose state).
 */
object AppLang {
    /** [name] in its own language and script; [english] for the prompt and logs; [rtl] lays the app out right to left;
     *  [systemFont] = letters the bundled Inter subset lacks, so the whole UI uses the system font. [locale] = Android's
     *  tag for dates and numbers (Bemba, Lozi and Tonga have none; English formats stand in). */
    data class Lang(val code: String, val name: String, val english: String, val rtl: Boolean = false,
                    val systemFont: Boolean = false, val locale: String = code)

    val ALL = listOf(
        Lang("en", "English", "English"),
        Lang("sw", "Kiswahili", "Swahili"),
        Lang("bem", "ChiBemba", "Bemba", locale = "en"),
        Lang("ny", "ChiNyanja", "Nyanja", systemFont = true),
        Lang("toi", "Chitonga", "Tonga (Zambia)", locale = "en"),
        Lang("loz", "Silozi", "Lozi", locale = "en"),
        Lang("sn", "chiShona", "Shona"),
        Lang("zu", "isiZulu", "Zulu"),
        Lang("ha", "Hausa", "Hausa", systemFont = true),
        Lang("yo", "Yorùbá", "Yoruba", systemFont = true),
        Lang("am", "አማርኛ", "Amharic", systemFont = true),
        Lang("fr", "Français", "French"),
        Lang("pt", "Português", "Portuguese"),
        Lang("es", "Español", "Spanish"),
        Lang("it", "Italiano", "Italian"),
        Lang("de", "Deutsch", "German"),
        Lang("tr", "Türkçe", "Turkish", systemFont = true),
        Lang("ru", "Русский", "Russian", systemFont = true),
        Lang("ar", "العربية", "Arabic", rtl = true, systemFont = true),
        Lang("hi", "हिन्दी", "Hindi", systemFont = true),
        Lang("zh", "中文", "Chinese (Simplified)", systemFont = true, locale = "zh-CN"),
        Lang("ja", "日本語", "Japanese", systemFont = true),
        Lang("id", "Bahasa Indonesia", "Indonesian"),
    )

    const val PREF = "app_language"
    const val DEFAULT = "en"

    fun lang(code: String?): Lang = ALL.firstOrNull { it.code == code } ?: ALL[0]

    /** The phone's language when ARGUS has it (pt-BR → pt, zh-TW → zh), else English. */
    fun fromDevice(tag: String?): String {
        val l = (tag ?: "").lowercase().replace('_', '-')
        if (l.isBlank()) return DEFAULT
        ALL.firstOrNull { it.code == l }?.let { return it.code }
        val base = l.substringBefore('-')
        return ALL.firstOrNull { it.code == base }?.code ?: DEFAULT
    }

    /** The language on screen. Compose state: a composable that reads [tr] redraws when it changes. */
    val code = mutableStateOf(DEFAULT)
    private val tables = HashMap<String, Map<String, String>>()
    @Volatile private var source: ((String) -> String?)? = null

    /** Android: where tables come from (assets) and the saved choice. Called once at start. */
    fun init(loadTable: (String) -> String?, saved: String?, deviceTag: String?) {
        source = loadTable
        apply(if (saved.isNullOrBlank()) fromDevice(deviceTag) else lang(saved).code)
    }

    /** Switches the app to [c] (the caller saves it). Dates and numbers follow too. */
    fun apply(c: String) {
        val l = lang(c)
        table(l.code)
        try { Locale.setDefault(Locale.forLanguageTag(l.locale)) } catch (_: Throwable) {}
        code.value = l.code
    }

    val current: Lang get() = lang(code.value)

    /** Parses a table's JSON ({ "English": "Translation" }); blank values are left out (they fall back to English). */
    fun parse(json: String?): Map<String, String> = try {
        val o = JSONObject(json ?: return emptyMap()); val m = HashMap<String, String>()
        for (k in o.keys()) o.optString(k).takeIf { it.isNotBlank() }?.let { m[k] = it }
        m
    } catch (_: Exception) { emptyMap() }

    @Synchronized fun table(c: String): Map<String, String> =
        if (c == DEFAULT) emptyMap() else tables.getOrPut(c) { parse(try { source?.invoke(c) } catch (_: Throwable) { null }) }

    /** Tests and previews: put a table in place. */
    @Synchronized fun put(c: String, t: Map<String, String>) { tables[c] = t }

    /** [en] in [c], `{0}`… filled from [args]. */
    fun text(c: String, en: String, vararg args: Any?): String {
        val s = table(c)[en] ?: en
        return if (args.isEmpty()) s else fill(s, args)
    }

    fun fill(s: String, args: Array<out Any?>): String {
        var out = s
        args.forEachIndexed { i, a -> out = out.replace("{$i}", a?.toString() ?: "") }
        return out
    }
}

/** [en] in the app language (see [AppLang]). */
fun tr(en: String, vararg args: Any?): String = AppLang.text(AppLang.code.value, en, *args)
