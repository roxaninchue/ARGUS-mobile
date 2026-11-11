package com.blaze.assistant.memory

import com.blaze.assistant.store.BlazeDb
import com.blaze.assistant.store.JdbcSql
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

/** P3-AG-04: MEMORY.md / USER.md render from the DB and parse back (05 §4.5). */
class ProjectionRoundTripTest {
    private lateinit var sql: JdbcSql
    private lateinit var db: BlazeDb
    private lateinit var st: MemScopeStore

    @Before fun setUp() { sql = JdbcSql(); db = BlazeDb(sql); st = MemScopeStore(sql) }
    @After fun tearDown() = sql.close()

    private fun add(t: String, scope: String, stated: Boolean = true) = db.add(t).first.also { st.put(it, scope, stated = stated) }

    private val ops = object : Projections.Ops {
        override fun add(text: String, scope: String, stated: Boolean) = db.add(text).first.also { st.put(it, scope, stated = stated) }
        override fun edit(id: Long, text: String) { db.edit(id, text) }
        override fun setStated(id: Long, stated: Boolean) { st.setStated(id, stated) }
        override fun move(id: Long, scope: String) { st.move(id, scope) }
        override fun delete(id: Long) { db.delete(id); st.delete(id) }
    }

    private fun memLines() = st.inScope("bot:atlas")
    private fun userLines() = st.lines().filter { it.scope.startsWith("user:") }

    @Test fun memoryRendersAndAnUneditedFileChangesNothing() {
        add("Prefers aisle seats", "bot:atlas"); add("Uses Kiwi first for flights", "bot:atlas"); add("team line", "team")
        val md = Projections.renderMemory("atlas", "Atlas", st.lines())
        assertTrue(md.startsWith("# Atlas's notes"))
        assertEquals(2, md.lines().count { it.startsWith("- ") }); assertFalse(md.contains("team line"))
        assertTrue(md.contains("- Prefers aisle seats <!-- #"))
        val p = Projections.plan(md, memLines(), "bot:atlas") as Projections.Plan
        assertTrue(p.empty)
    }

    @Test fun editsDeletesAndAddsRoundTrip() {
        val a = add("Prefers aisle seats", "bot:atlas"); val b = add("Uses Kiwi first for flights", "bot:atlas"); add("Books trains in Europe", "bot:atlas")
        val md = Projections.renderMemory("atlas", "Atlas", st.lines())
            .replace("Prefers aisle seats", "Prefers window seats on night flights")
            .lines().filterNot { it.contains("Kiwi") }.joinToString("\n") + "\n- Carries a Zambian passport\n"
        val p = Projections.plan(md, memLines(), "bot:atlas") as Projections.Plan
        assertEquals(listOf(a), p.edits.map { it.id }); assertEquals(listOf(b), p.deletes); assertEquals(listOf("Carries a Zambian passport"), p.adds.map { it.text })
        Projections.apply(p, ops)
        val again = Projections.renderMemory("atlas", "Atlas", st.lines())
        assertTrue(again.contains("Prefers window seats on night flights <!-- #$a -->"))
        assertFalse(again.contains("Kiwi")); assertTrue(again.contains("Zambian passport"))
        assertTrue((Projections.plan(again, memLines(), "bot:atlas") as Projections.Plan).empty)
    }

    @Test fun hermesSectionSignSeparatedEntriesParse() {
        add("Prefers aisle seats", "bot:atlas")
        val hermes = "Prefers aisle seats\n§\nLikes early check-in\nat hotels\n§\n"
        val p = Projections.plan(hermes, memLines(), "bot:atlas") as Projections.Plan
        assertTrue(p.deletes.isEmpty()); assertEquals(listOf("Likes early check-in at hotels"), p.adds.map { it.text })
    }

    @Test fun userMdGroupsByHeadingAndHeadingsDecideScope() {
        add("Call me Finn", "user:core"); add("Gloria is my sister", "user:people"); add("Likes jazz", "user:preferences", stated = false)
        val md = Projections.renderUser("Finn", st.lines())
        assertTrue(md.indexOf("## About you") < md.indexOf("## People"))
        assertTrue(md.contains("- Likes jazz (inferred) <!-- #"))
        assertTrue((Projections.plan(md, userLines(), "user:core", Projections::userScope) as Projections.Plan).empty)
        // mark stated, move a line, add one under People
        val edited = md.replace("Likes jazz (inferred)", "Likes jazz")
            .replace("## People\n", "## People\n- Mutale is my cousin\n")
            .let { it.replace(Regex("- Call me Finn <!-- #(\\d+) -->\n"), "").replace("## Preferences\n", "## Preferences\n" + Regex("- Call me Finn <!-- #\\d+ -->").find(md)!!.value + "\n") }
        val p = Projections.plan(edited, userLines(), "user:core", Projections::userScope) as Projections.Plan
        assertEquals(1, p.stated.size); assertTrue(p.stated[0].second)
        assertEquals(listOf("user:preferences"), p.moves.map { it.second })
        assertEquals(listOf("Mutale is my cousin" to "user:people"), p.adds.map { it.text to it.scope })
        Projections.apply(p, ops)
        assertTrue(st.lines().first { it.text == "Likes jazz" }.stated)
        assertEquals("user:preferences", st.lines().first { it.text == "Call me Finn" }.scope)
        val back = Projections.renderUser("Finn", st.lines())
        assertTrue((Projections.plan(back, userLines(), "user:core", Projections::userScope) as Projections.Plan).empty)
    }

    @Test fun badEditsFailWithADiffAndChangeNothing() {
        val a = add("Prefers aisle seats", "bot:atlas"); add("Uses Kiwi", "bot:atlas"); add("Books trains", "bot:atlas"); add("Hates layovers", "bot:atlas")
        val md = Projections.renderMemory("atlas", "Atlas", st.lines())
        val twice = md + "- copy <!-- #$a -->\n"
        assertTrue((Projections.plan(twice, memLines(), "bot:atlas") as Projections.Failed).why.contains("twice"))
        val foreign = md + "- stolen <!-- #999 -->\n"
        val f = Projections.plan(foreign, memLines(), "bot:atlas") as Projections.Failed
        assertTrue(f.why.contains("#999")); assertTrue(f.diff.any { it.startsWith("+ - stolen") })
        assertTrue((Projections.plan("# Atlas's notes\n", memLines(), "bot:atlas") as Projections.Failed).why.contains("Forget everything"))
        assertTrue((Projections.plan("## Weird Heading!\n- x y z\n", userLines(), "user:core", Projections::userScope) as Projections.Failed).why.contains("unknown heading"))
        assertEquals(4, memLines().size)
    }

    @Test fun secretsInAnEditAreDropped() {
        val p = Projections.plan("- password: hunter22x\n- Likes Lusaka sunsets\n", emptyList(), "bot:atlas") as Projections.Plan
        assertEquals(1, p.secretsDropped); assertEquals(listOf("Likes Lusaka sunsets"), p.adds.map { it.text })
    }

    @Test fun filesAreWrittenWherePlanned() {
        add("Call me Finn", "user:core"); add("Prefers aisle seats", "bot:atlas")
        val dir = Files.createTempDirectory("proj").toFile()
        try {
            val fs = ProjectionFiles(dir).write(st.lines(), "Finn", mapOf("manager" to "ARGUS"))
            assertTrue(java.io.File(dir, "USER.md").readText().contains("Call me Finn"))
            assertTrue(java.io.File(dir, "bots/atlas/MEMORY.md").readText().contains("Prefers aisle seats"))
            assertTrue(java.io.File(dir, "bots/manager/MEMORY.md").isFile)
            assertEquals(3, fs.size)
        } finally { dir.deleteRecursively() }
    }
}
