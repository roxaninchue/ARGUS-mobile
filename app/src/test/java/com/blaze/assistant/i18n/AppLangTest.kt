package com.blaze.assistant.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Owner 2026-11-14: the app language (each language by its own name, first in setup) and Auto replies for good. */
class AppLangTest {
    @Test fun everyLanguageIsListedByItsOwnName() {
        assertEquals(23, AppLang.ALL.size)
        assertEquals(AppLang.ALL.size, AppLang.ALL.map { it.code }.toSet().size)
        assertEquals(AppLang.ALL.size, AppLang.ALL.map { it.name }.toSet().size)
        val names = AppLang.ALL.associate { it.code to it.name }
        assertEquals("English", names["en"]); assertEquals("Italiano", names["it"]); assertEquals("Français", names["fr"])
        assertEquals("العربية", names["ar"]); assertEquals("中文", names["zh"]); assertEquals("ChiBemba", names["bem"])
        assertTrue(AppLang.ALL.filter { it.rtl }.map { it.code } == listOf("ar"))
        for (c in listOf("ar", "am", "zh", "ja", "hi", "ru")) assertTrue(c, AppLang.lang(c).systemFont)
    }

    @Test fun firstRunStartsInThePhonesLanguageWhenWeHaveIt() {
        assertEquals("it", AppLang.fromDevice("it-IT")); assertEquals("pt", AppLang.fromDevice("pt_BR"))
        assertEquals("zh", AppLang.fromDevice("zh-Hant-TW")); assertEquals("en", AppLang.fromDevice("nl-NL"))
        assertEquals("en", AppLang.fromDevice("")); assertEquals("en", AppLang.lang("xx").code)
    }

    @Test fun textFallsBackToEnglishAndFillsArguments() {
        AppLang.put("it", mapOf("Settings" to "Impostazioni", "{0} left" to "{0} rimasti"))
        assertEquals("Impostazioni", AppLang.text("it", "Settings"))
        assertEquals("3 rimasti", AppLang.text("it", "{0} left", 3))
        assertEquals("Not translated", AppLang.text("it", "Not translated"))
        assertEquals("2 left", AppLang.text("en", "{0} left", 2))
        assertEquals(mapOf("a" to "b"), AppLang.parse("""{"a":"b","c":""}"""))
        assertTrue(AppLang.parse("not json").isEmpty())
    }

    @Test fun repliesAlwaysFollowTheUsersLanguage() {
        assertEquals("auto", com.blaze.assistant.agent.ReplyLanguage.current())
        assertEquals(null, com.blaze.assistant.agent.ReplyLanguage.header(com.blaze.assistant.agent.ReplyLanguage.current()))
    }

    @Test fun settingsAndSetupPickTheAppLanguage() {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val settings = File(root, "com/blaze/assistant/ui/SettingsSheet.kt").readText()
        assertTrue("AppLang.PICKABLE.forEach" in settings)
        assertFalse("ReplyLanguage.OPTIONS" in settings)
        val onboarding = File(root, "com/blaze/assistant/ui/space/SpaceOnboarding.kt").readText()
        assertTrue("SpaceOnboarding.Step.LANGUAGE -> LanguageStep(onNext)" in onboarding)
        val theme = File(root, "com/blaze/assistant/ui/Theme.kt").readText()
        assertTrue("LocalLayoutDirection provides if (rtl)" in theme)
    }

    /** Every non-English language ships a table; each entry keeps its placeholders and is not blank. */
    @Test fun everyLanguageShipsATableThatKeepsPlaceholders() {
        val dir = listOf(File("src/main/assets/i18n"), File("app/src/main/assets/i18n")).first { it.isDirectory }
        val ph = Regex("""\{\d+\}""")
        for (l in AppLang.PICKABLE.drop(1)) {
            val f = File(dir, "${l.code}.json")
            assertTrue("${l.code} table missing", f.isFile)
            val t = AppLang.parse(f.readText())
            assertTrue("${l.code} table is nearly empty (${t.size})", t.size >= 400)
            for ((k, v) in t) assertEquals("${l.code}: $k", ph.findAll(k).map { it.value }.sorted().toList(), ph.findAll(v).map { it.value }.sorted().toList())
        }
        assertFalse(File(dir, "en.json").exists())
        assertTrue(AppLang.PENDING.none { File(dir, "$it.json").isFile })
    }
}
