package com.blaze.assistant.team

import com.blaze.assistant.agent.ChatIndex
import com.blaze.assistant.agent.engines.LocalEngine
import com.blaze.assistant.agent.engines.LocalEngineTest.FakeLlm
import com.blaze.assistant.agent.engines.LocalEngineTest.FakeLoop
import com.blaze.assistant.store.JdbcSql
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * P1-TM-02 upgrade test (06 §6.2 "AM chat history intact"): a phone coming from 0.52.4 has its chats in filesDir/chats
 * (ChatJson files + the `.chat_index`) and no team.db. After the upgrade the ARGUS thread shows those chats as they are,
 * nothing is moved or rewritten (bytes and timestamps unchanged), the user's chat list is the same, and the bot
 * threads' own chats (kept beside them by the agent loop) never appear in it. team.db stays schema v2 (no migration).
 */
class ArgusUpgradeTest {
    private val root: File = Files.createTempDirectory("upgrade").toFile()
    private val chatsDir = File(root, "chats").apply { mkdirs() }
    private val dbFile = File(root, TeamSchema.NAME)
    private val opened = mutableListOf<JdbcSql>()

    @After fun tearDown() { opened.forEach { runCatching { it.close() } }; root.deleteRecursively() }

    private fun message(id: String, role: String, text: String, at: Long) =
        JSONObject().put("id", id).put("role", role).put("text", text).put("files", JSONArray()).put("at", at)

    /** A chat as ChatJson.file writes it: header fields, then messages, then the model history. */
    private fun amChat(id: String, title: String, updated: Long, vararg msgs: JSONObject) = JSONObject()
        .put("id", id).put("title", title).put("updated", updated).put("pinned", false).put("preview", msgs.last().getString("text").take(80))
        .put("summary", "").put("messages", JSONArray(msgs.toList()))
        .put("history", JSONArray().put(JSONObject().put("role", "user").put("content", msgs.first().getString("text"))))

    private fun snapshot() = chatsDir.listFiles()!!.filter { it.isFile }.associate { it.name to (it.readBytes() to it.lastModified()) }

    private fun open(): Pair<TeamStore, JdbcSql> {
        val fresh = !dbFile.exists()
        val sql = JdbcSql("jdbc:sqlite:${dbFile.path}").also { opened += it }
        sql.exec("PRAGMA foreign_keys = ON")
        if (fresh) sql.tx { for (st in TeamSchema.steps(0)) sql.exec(st) }
        val team = TeamStore(sql) { 1_731_500_000_000L }
        team.ensure()
        return team to sql
    }

    @Test fun amChatHistoryIntactAfterUpgrade() {
        // --- 0.52.4: three chats, one from before the rename (role "Blaze"), indexed by ChatIndex
        val old = ChatIndex(chatsDir)
        old.save(amChat("a1b2c3d4e5f6", "Trip to Lusaka", 1_731_000_000_000L,
            message("m1", "USER", "Find buses to Lusaka on Friday", 1_731_000_000_000L),
            message("m2", "BLAZE", "Three buses leave before noon.", 1_731_000_001_000L),
            message("m3", "STEP", "Searched the web", 1_731_000_000_500L)))
        old.save(amChat("0f9e8d7c6b5a", "Old chat", 1_700_000_000_000L,
            message("m1", "USER", "hello", 1_700_000_000_000L), message("m2", "Blaze", "Hi!", 1_700_000_000_100L)))
        old.save(amChat("ffeeddccbbaa", "Errors", 1_731_100_000_000L,
            message("m1", "USER", "what's on today", 1_731_100_000_000L), message("m2", "ERROR", "No network", 1_731_100_000_100L)))
        val userList = old.list().map { it.id }
        val before = snapshot()
        Thread.sleep(20)   // so a rewrite would show in lastModified

        // --- 0.53.0: team.db created next to the chats, ARGUS bridged, a bot thread used
        val (team, sql) = open()
        assertEquals("no team.db migration in P1-TM-02", 2, TeamSchema.VERSION)
        val idx = ChatIndex(chatsDir)
        var current: String? = "a1b2c3d4e5f6"
        val argus = ArgusChats.of({ idx.list() }, { File(chatsDir, "$it.json").readText() }, { current })
        val loop = FakeLoop(FakeLlm(FakeLlm.Reply.Say("Your Friday is clear.")), emptyList())
        val tempo = LocalEngine(object : LocalEngine.Driver by loop {
            override suspend fun run(threadId: String, text: String, byVoice: Boolean, untrusted: String?) {
                loop.run(threadId, text, byVoice, untrusted)
                idx.save(JSONObject().put("id", threadId).put("title", "Tempo").put("updated", 1_731_500_000_000L).put("preview", "x")
                    .put("messages", JSONArray()))   // the agent loop keeps the bot's chat like any other
            }
        }, soul = { null }, clock = { 1_731_500_000_000L })
        val threads = BotThreads(sql, team, argus, { if (it.id == "tempo") tempo else null }) { 1_731_500_000_000L }

        val rows = threads.rows("manager")
        assertEquals(listOf("Find buses to Lusaka on Friday", "Three buses leave before noon.", "Searched the web"), rows.map { RowBodies.read(it.body)["text"] })
        assertEquals(listOf("message", "message", "event"), rows.map { it.kind })
        assertEquals(listOf("user", "manager"), rows.take(2).map { RowBodies.read(it.body)["from"] })
        current = "0f9e8d7c6b5a"
        assertEquals("the pre-rename speaker is ARGUS", "manager", RowBodies.read(threads.rows("manager")[1].body)["from"])
        current = "ffeeddccbbaa"
        assertEquals("notice", threads.rows("manager")[1].kind)

        runBlocking { threads.send("tempo", Trigger.User("Is Friday free?")).toList() }
        assertTrue(File(chatsDir, "team-tempo-g0.json").isFile)

        // --- the user's chats: same list, same bytes, same timestamps; the bot's chat is kept but never listed
        assertEquals(userList, argus.list().map { it.id })
        assertEquals(userList, BotThreads.userChats(idx.list()).map { it.id })
        assertTrue(idx.list().any { it.id == "team-tempo-g0" })
        val after = snapshot()
        for ((name, v) in before) if (name.endsWith(".json")) {
            assertArrayEquals("$name rewritten", v.first, after.getValue(name).first)
            assertEquals("$name touched", v.second, after.getValue(name).second)
        }
        assertEquals("nothing copied into team.db for ARGUS", 0L,
            sql.query("SELECT count(*) FROM rows WHERE bot_id='manager'") { it.long(0) }.first())

        // --- restart: the bot thread and its counters survive, ARGUS still reads the chat
        opened.forEach { it.close() }; opened.clear()
        val (team2, sql2) = open()
        val again = BotThreads(sql2, team2, argus, { null }) { 1_731_500_000_000L }
        assertEquals(listOf("Is Friday free?", "Your Friday is clear."), again.rows("tempo").map { RowBodies.read(it.body)["text"] })
        assertEquals(1, again.thread("tempo")!!.unread)
        assertEquals(2, again.rows("manager").size)
    }
}
