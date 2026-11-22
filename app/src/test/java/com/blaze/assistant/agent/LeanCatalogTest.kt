package com.blaze.assistant.agent

import com.blaze.assistant.store.JdbcSql
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.55.0 lean harness: the blaze.db catalog (LeanCatalog) on sqlite-jdbc: gateway sync rows, phone rows, FTS search. */
class LeanCatalogTest {
    private val sqls = ArrayList<JdbcSql>()
    @After fun tearDown() { LeanCatalog.use(null); sqls.forEach { try { it.close() } catch (_: Exception) { } } }

    private fun store(): Pair<LeanCatalog.Store, HashMap<String, String>> {
        val meta = HashMap<String, String>()
        val s = JdbcSql().also { sqls += it }
        return LeanCatalog.Store(s, { meta[it].orEmpty() }, { k, v -> meta[k] = v }) to meta
    }

    private val body = JSONObject().put("version", 1).put("plan", "basic").put("hash", "abc123def4567890")
        .put("tools", JSONArray()
            .put(JSONObject().put("name", "device_setting").put("group", "device").put("summary", "Turn a phone setting on or off: Wi-Fi, Bluetooth, flashlight."))
            .put(JSONObject().put("name", "github_create_issue").put("group", "apps").put("summary", "Open an issue in a GitHub repo."))
            .put(JSONObject().put("name", "bad name!").put("summary", "skipped")))
        .put("skills", JSONArray().put(JSONObject().put("name", "study-flashcards").put("summary", "Spaced-repetition flashcards from notes.")))
        .toString()

    @Test fun parsesTheGatewayAnswerAndRejectsOthers() {
        val p = LeanCatalog.parse(body)!!
        assertEquals("basic", p.plan); assertEquals("abc123def4567890", p.hash)
        assertEquals(listOf("device_setting", "github_create_issue"), p.tools.map { it.name })
        assertEquals("study-flashcards", p.skills.single().name)
        assertNull(LeanCatalog.parse("{\"version\":2,\"hash\":\"x\"}"))
        assertNull(LeanCatalog.parse("<html>busy</html>"))
    }

    @Test fun gatewaySyncReplacesOnlyGatewayRowsAndIsIdempotent() {
        val (s, meta) = store()
        s.replacePhone(listOf(LeanCatalog.Entry(LeanCatalog.KIND_TOOL, "mcp__notes__add", "", "Add a note.", LeanCatalog.SRC_PHONE)))
        val p = LeanCatalog.parse(body)!!
        s.replaceGateway(p, "\"cat-abc\"", 1_000L)
        s.replaceGateway(p, "\"cat-abc\"", 2_000L)
        assertEquals(3, s.count(src = LeanCatalog.SRC_GATEWAY))
        assertEquals(1, s.count(src = LeanCatalog.SRC_PHONE))
        assertEquals("abc123def4567890", s.hash()); assertEquals("\"cat-abc\"", meta[LeanCatalog.META_ETAG]); assertEquals(2_000L, s.syncedAt())
        assertEquals(setOf("device_setting", "github_create_issue"), s.gatewayNames(LeanCatalog.KIND_TOOL))
    }

    @Test fun searchFindsToolsByWordsAndSkillsByKind() {
        val (s, _) = store()
        s.replaceGateway(LeanCatalog.parse(body)!!, "", 1L)
        assertEquals("device_setting", s.search("turn on bluetooth", 3).first().name)
        assertEquals("github_create_issue", s.search("open an issue on github", 3, LeanCatalog.KIND_TOOL).first().name)
        assertEquals("study-flashcards", s.search("flashcards", 3, LeanCatalog.KIND_SKILL).single().name)
        assertTrue(s.search("", 3).isEmpty())
    }

    @Test fun phoneRowsWorkBeforeTheFirstSyncAndAGatewayRowWinsAfter() {
        val (s, _) = store()
        val phone = LeanCatalog.phoneRows(JSONArray()
            .put(JSONObject().put("function", JSONObject().put("name", "device_setting").put("description", "Phone switches. Long text after.")))
            .put(JSONObject().put("function", JSONObject().put("name", "music_ops").put("description", "Play music in Argus Music."))), { if (it == "music_ops") "media" else null })
        assertEquals("Phone switches. Long text after.", phone[0].summary)
        assertEquals("media", phone[1].group)
        s.replacePhone(phone)
        assertEquals("music_ops", s.search("play some music", 2).first().name)
        s.replaceGateway(LeanCatalog.parse(body)!!, "", 1L)
        assertEquals(LeanCatalog.SRC_GATEWAY, s.get(LeanCatalog.KIND_TOOL, "device_setting")!!.src)
        assertEquals(1, s.search("bluetooth setting", 5).count { it.name == "device_setting" })
    }

    @Test fun syncIsDueEverySixHoursAndRetriesAfterFifteenMinutes() {
        val h = 60 * 60_000L
        assertTrue(LeanCatalog.due(0L, 10L))
        assertFalse(LeanCatalog.due(1_000L, 1_000L + 5 * h))
        assertTrue(LeanCatalog.due(1_000L, 1_000L + 6 * h))
        assertFalse(LeanCatalog.due(0L, 20 * 60_000L, lastFailedAt = 10 * 60_000L))
        assertTrue(LeanCatalog.due(0L, 30 * 60_000L, lastFailedAt = 10 * 60_000L))
    }

    @Test fun namesAreSearchableByTheirWords() {
        assertEquals("github create issue github_create_issue", LeanCatalog.words("github_create_issue"))
        assertEquals("tutor", LeanCatalog.words("tutor"))
        assertEquals("Turn a phone setting on or off.", LeanCatalog.firstSentence("Turn a phone setting on or off. Then more words here."))
        assertEquals("Short. Kept whole", LeanCatalog.firstSentence("Short.   Kept whole"))
    }

    @Test fun parseDropsDuplicateNamesAndCountsByKind() {
        val dup = JSONObject(body).apply { getJSONArray("tools").put(JSONObject().put("name", "device_setting").put("summary", "again")) }.toString()
        val p = LeanCatalog.parse(dup)!!
        assertEquals(2, p.tools.size)
        assertEquals("Turn a phone setting on or off: Wi-Fi, Bluetooth, flashlight.", p.tools.first().summary)
        val (s, _) = store()
        s.replaceGateway(p, "", 1L)
        assertEquals(2, s.count(LeanCatalog.KIND_TOOL)); assertEquals(1, s.count(LeanCatalog.KIND_SKILL))
        assertEquals(setOf("study-flashcards"), s.names(LeanCatalog.KIND_SKILL))
    }

    @Test fun aSkillIsReadBackByName() {
        val (s, _) = store()
        s.replaceGateway(LeanCatalog.parse(body)!!, "", 1L)
        assertEquals("Spaced-repetition flashcards from notes.", s.get(LeanCatalog.KIND_SKILL, "study-flashcards")!!.summary)
        assertNull(s.get(LeanCatalog.KIND_SKILL, "nope"))
        assertTrue(s.touch(5L).let { s.syncedAt() == 5L })
    }

    @Test fun aCatalogTicketCoversFoundToolsOnlyWhenThePhoneHoldsThatSameCatalog() {
        val have = "abc123def4567890" to setOf("device_setting", "github_create_issue")
        assertTrue(LeanCatalog.covers(setOf("set_alarm"), "abc123def4567890", listOf("set_alarm", "device_setting"), have))
        assertFalse("another plan catalog", LeanCatalog.covers(setOf("set_alarm"), "ffff000011112222", listOf("device_setting"), have))
        assertFalse("an older ticket (no cat)", LeanCatalog.covers(setOf("set_alarm"), "", listOf("device_setting"), have))
        assertFalse("not in the catalog either", LeanCatalog.covers(setOf("set_alarm"), "abc123def4567890", listOf("mcp__x__y"), have))
        assertFalse("before the first sync", LeanCatalog.covers(emptySet(), "abc123def4567890", listOf("device_setting"), "" to emptySet()))
    }
}
