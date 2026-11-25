package com.blaze.assistant.agent

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** v0.19.0 journal entries on Robolectric's SQLite: the kind column, dedupe, chat-app logs, life logs and their review. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = android.app.Application::class)
class MindLogsTest {
    private lateinit var db: Mind.Db

    @Before fun open() {
        NativeSqlite.assume()
        db = Mind.Db(ApplicationProvider.getApplicationContext(), null)
        db.writableDatabase
        Mind.use(db)
    }

    @After fun close() { Mind.use(null); if (::db.isInitialized) db.close() }

    @Test fun appendDedupesAndKeepsOldRowsAsChat() {
        db.journal("main", JSONArray().put(JSONObject().put("role", "user").put("content", "hello there")))
        MindLogs.ensureKind(db); MindLogs.ensureKind(db)   // idempotent
        assertTrue(MindLogs.append(db, "life_log:2026-10-12", "heard", "Heard nearby: we need milk", 100, MindLogs.LIFE) > 0)
        assertEquals(0L, MindLogs.append(db, "life_log:2026-10-12", "heard", "Heard nearby: we need milk", 100, MindLogs.LIFE))
        assertTrue(MindLogs.append(db, "life_log:2026-10-12", "heard", "Heard nearby: and bread", 130, MindLogs.LIFE) > 0)
        assertEquals(2, MindLogs.count(db, MindLogs.LIFE))
        assertEquals(1, MindLogs.count(db, "chat"))
        assertEquals(listOf(0, 1), MindLogs.recent(db, "life_log:2026-10-12", 10).map { it.turn })
        // search_chat finds it, then delete removes logs only.
        assertTrue(Mind.searchChat("milk", 5).contains("life_log:2026-10-12"))
        assertEquals(2, MindLogs.deleteKind(db, MindLogs.LIFE))
        assertEquals(1, db.count())
        assertTrue(Mind.searchChat("milk", 5).startsWith("Nothing"))
    }

    @Test fun reviewKeepsClassesApartAndMarksLifeLogsInferred() = runBlocking {
        db.journal("main", JSONArray().put(JSONObject().put("role", "user").put("content", "I study at Northrise")))
        MindLogs.append(db, "life_log:2026-10-12", "heard", "Heard nearby 10:00\u201310:01: Mutale is moving to Kitwe in December", 200, MindLogs.LIFE)
        val seen = ArrayList<String>()
        val n1 = MindReview.review(1) { req -> seen += req.getJSONObject(1).getString("content")
            "[{\"kind\":\"profile\",\"subject\":\"You\",\"text\":\"Studies at Northrise University\"}]" }
        assertEquals(1, n1)
        assertTrue(!seen[0].contains("LIFE LOG"))
        val n2 = MindReview.review(1) { req -> seen += req.getJSONObject(1).getString("content")
            "[{\"kind\":\"person\",\"subject\":\"Mutale\",\"text\":\"Mutale is moving to Kitwe in December\"}]" }
        assertEquals(1, n2)
        assertTrue(seen[1].contains("LIFE LOG"))
        val m = db.all().first { it.subject == "Mutale" }
        assertTrue(m.text, m.text.endsWith(Mind.INFERRED))
        assertEquals(0, db.unreviewedCount())
    }
}
