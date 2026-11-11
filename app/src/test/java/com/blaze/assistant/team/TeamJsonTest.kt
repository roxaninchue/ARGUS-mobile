package com.blaze.assistant.team

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * P0-TM-01: every team model survives JSON and back unchanged (full values, defaults, awkward text), the team.db JSON
 * columns round-trip, and reading is forgiving only where it is safe (unknown enum → the safest value, missing
 * optional → the default) while missing ids / bad JSON / an unknown approval class are errors. Pure JVM.
 */
class TeamJsonTest {

    private val nasty = "Quote \" back\\slash /slash\nnew\rret\ttab \u0001ctl \u2028ls \u2029ps é 中文 \uD83D\uDE80 {\"json\":[1]}"

    private fun fullBot() = Bot(
        id = "ledger", kind = BotKind.DOMAIN, name = "Ledger", label = "Money", domain = "money",
        avatar = Avatar(shape = "orb", color = "#3D5AFE", face = 3, image = "file:///x.png"),
        voice = Voice(id = "nova", speed = 1.25f, language = "en-ZM"), mode = "teammate",
        soulPath = "/data/files/bots/ledger/SOUL.md", soulVersion = 7, skills = listOf("budget", "receipts"),
        toolGroups = setOf("money", "memory"), toolGrants = mapOf("bank_read" to Rule.ALLOW, "pay" to Rule.NEVER),
        engine = EngineSpec(EngineKind.HERMES, ModelPolicy(lane = "long", tier = "flare", pinned = "x/y", effort = "high", zeroRetention = true),
            hermesUrl = "https://hermes.example", hermesSecretRef = "vault:hermes"),
        computer = ComputerSpec(ComputerKind.CLOUD, CloudProvider.CODESPACES, askEachSession = false, idleStopMin = 25),
        apps = listOf(BotAppGrant("gmail", "argus-connect", mapOf("send" to Rule.ASK)), BotAppGrant("fs", "mcp")),
        permissions = Permissions(Autonomy.AUTOPILOT, DEFAULT_RULES + (ActionClass.SPEND to Rule.NEVER), 50_000, "USD",
            SendScope.KNOWN_CONTACTS, setOf("user:core", "team", "money"), 12, 5, 2),
        reportsTo = "manager", parentId = "parent", members = listOf("a", "b"), hidden = true, pinned = true, enabled = false,
        routingHints = listOf("budget", nasty), createdAt = 1_731_300_000_000, u = 1_731_300_000_123)

    private fun plainBot() = Bot(id = "tempo", kind = BotKind.DOMAIN, name = "Tempo", label = "", avatar = Avatar("orb", "#fff"),
        soulPath = "bots/tempo/SOUL.md", toolGroups = emptySet(), createdAt = 1, u = 2)

    @Test fun botsRoundTrip() {
        for (b in listOf(fullBot(), plainBot())) assertEquals(b, TeamJson.decodeBot(TeamJson.encode(b)))
        // the same bot always writes the same text
        assertEquals(TeamJson.encode(fullBot()), TeamJson.encode(TeamJson.decodeBot(TeamJson.encode(fullBot()))))
    }

    @Test fun theManagerKeepsItsNullReportsTo() {
        val m = plainBot().copy(id = "manager", kind = BotKind.MANAGER, reportsTo = null)
        val s = TeamJson.encode(m)
        assertTrue(s, s.contains("\"reportsTo\":null"))
        assertNull(TeamJson.decodeBot(s).reportsTo)
        // a bot written without the field gets the model default
        assertEquals("manager", TeamJson.decodeBot(s.replace("\"reportsTo\":null,", "")).reportsTo)
    }

    @Test fun jobsAndStepsRoundTrip() {
        val full = Job("j1", "Pay rent", "ledger", "2026-12-01", "high", "doing", JobRun.WAITING, listOf("j0"), "parent", "routine",
            nasty, "done ok", "note", confirmed = false, retryAt = 99, createdAt = 10, doneAt = 20, u = 30)
        val plain = Job(id = "j2", title = "t", botId = null, due = null, source = "user", brief = "b", confirmed = true, createdAt = 1, u = 1)
        for (j in listOf(full, plain)) assertEquals(j, TeamJson.decodeJob(TeamJson.encode(j)))
        val st = JobStep("j1", 3, nasty, "done", 42)
        assertEquals(st, TeamJson.decodeJobStep(TeamJson.encode(st)))
    }

    @Test fun rowsKeepTheirJsonBodyVerbatim() {
        val body = "{\"text\":\"hi \\\"there\\\"\",\"n\":[1,2.5,null,true]}"
        val r = Row("ledger", 2, 17, 19, "tool-call", body, 1_731_300_000_000)
        val back = TeamJson.decodeRow(TeamJson.encode(r))
        assertEquals(r, back)
        assertEquals(body, back.body)
    }

    @Test fun messagesRoutinesSuggestionsApprovalsScopesRoundTrip() {
        val m = BotMessage("t1", "ledger", "scout", nasty, 2, true, 17)
        assertEquals(m, TeamJson.decodeBotMessage(TeamJson.encode(m)))

        val r1 = Routine("r1", "tempo", "Morning brief", nasty, "CRON", "0 7 * * *", "Africa/Lusaka", "cloud", false, false, 4, 123, 456)
        val r2 = Routine(id = "r2", botId = "tempo", name = "n", prompt = "p", kind = "CHECKIN", schedule = "daily", timezone = "UTC", enabled = true, u = 1)
        for (r in listOf(r1, r2)) assertEquals(r, TeamJson.decodeRoutine(TeamJson.encode(r)))

        val s1 = Suggestion("s1", "pulse", "Walk", "You sat 6 h", "walk 10 min", "health", 2, setOf(ActionClass.SEND, ActionClass.SPEND), 5, "new")
        val s2 = s1.copy(id = "s2", risk = emptySet(), state = "not_now")
        for (s in listOf(s1, s2)) assertEquals(s, TeamJson.decodeSuggestion(TeamJson.encode(s)))

        val a1 = Approval("a1", "inbox", "j1", "call-9", "gmail_send", ActionClass.SEND, "Send reply", nasty, "{\"to\":\"[redacted]\"}",
            true, "always", true, 100, 200, "phone")
        val a2 = Approval("a2", "inbox", null, "call-1", "pay", ActionClass.SPEND, "Pay", "d", "{}", false, "pending", false, 1, null, null)
        for (a in listOf(a1, a2)) assertEquals(a, TeamJson.decodeApproval(TeamJson.encode(a)))

        val ms1 = MemScope(42, "bot:ledger", "ledger", "money", false, 1, setOf("pulse", "team"), "import", 9_999)
        val ms2 = MemScope(43, "user:core", null, null, true, 0, emptySet(), "chat", null)
        for (s in listOf(ms1, ms2)) assertEquals(s, TeamJson.decodeMemScope(TeamJson.encode(s)))
    }

    @Test fun dbColumnsRoundTrip() {
        val b = fullBot()
        assertEquals(b.avatar, TeamJson.avatar(TeamJson.avatar(b.avatar)))
        assertEquals(b.voice, TeamJson.voice(TeamJson.voice(b.voice)))
        assertEquals(Voice(), TeamJson.voice(null as String?))
        assertEquals(b.engine, TeamJson.engine(TeamJson.engine(b.engine)))
        assertEquals(b.computer, TeamJson.computer(TeamJson.computer(b.computer)))
        assertEquals(b.permissions, TeamJson.permissions(TeamJson.permissions(b.permissions)))
        assertEquals(Permissions(), TeamJson.permissions(TeamJson.permissions(Permissions())))
        assertEquals(b.routingHints, TeamJson.strings(TeamJson.strings(b.routingHints)))
        assertEquals("[]", TeamJson.strings(emptyList()))
        assertEquals(emptyList<String>(), TeamJson.strings("[]"))
        assertEquals(emptyList<String>(), TeamJson.strings(null as String?))
        assertEquals(b.toolGrants, TeamJson.grants(TeamJson.grants(b.toolGrants)))
        assertEquals("{}", TeamJson.grants(emptyMap()))
        assertEquals(setOf(ActionClass.DELETE, ActionClass.READ), TeamJson.classes(TeamJson.classes(setOf(ActionClass.DELETE, ActionClass.READ))))
        // action classes are written in enum order, so the text is stable
        assertEquals("[\"READ\",\"DELETE\"]", TeamJson.classes(linkedSetOf(ActionClass.DELETE, ActionClass.READ)))
    }

    @Test fun defaultRulesFollow05Section71() {
        assertEquals(ActionClass.values().toSet(), DEFAULT_RULES.keys)
        val allow = setOf(ActionClass.READ, ActionClass.WRITE_LOCAL, ActionClass.PHYSICAL)
        for (c in ActionClass.values()) assertEquals(c.name, if (c in allow) Rule.ALLOW else Rule.ASK, DEFAULT_RULES[c])
        assertFalse(DEFAULT_RULES.containsValue(Rule.NEVER))
        assertEquals(DEFAULT_RULES, Permissions().actions)
        assertEquals(0L, Permissions().spendCapMonthlyMinor)
    }

    @Test fun readingIsForgivingOnlyTowardsSafety() {
        val s = TeamJson.encode(fullBot())
            .replace("\"kind\":\"DOMAIN\"", "\"kind\":\"ALIEN\"")
            .replace("\"autonomy\":\"AUTOPILOT\"", "\"autonomy\":\"YOLO\"")
            .replace("\"sendScope\":\"KNOWN_CONTACTS\"", "\"sendScope\":\"EVERYONE\"")
            .replace("\"bank_read\":\"ALLOW\"", "\"bank_read\":\"MAYBE\"")
            .replace("\"SPEND\":\"NEVER\"", "\"SPEND\":\"NEVER\",\"TELEPORT\":\"ALLOW\"")
            .replace("\"provider\":\"CODESPACES\"", "\"provider\":\"MAINFRAME\"")
        val b = TeamJson.decodeBot(s)
        assertEquals(BotKind.CUSTOM, b.kind)
        assertEquals(Autonomy.ASSIST, b.permissions.autonomy)
        assertEquals(SendScope.NONE, b.permissions.sendScope)
        assertEquals(Rule.ASK, b.toolGrants["bank_read"])
        assertEquals(Rule.NEVER, b.permissions.actions[ActionClass.SPEND])
        assertEquals(ActionClass.values().toSet(), b.permissions.actions.keys)
        assertNull(b.computer.provider)

        // an older permissions object missing classes falls back to the defaults for them; missing fields take defaults
        val p = TeamJson.permissions("{\"actions\":{\"SEND\":\"NEVER\"}}")
        assertEquals(Rule.NEVER, p.actions[ActionClass.SEND])
        assertEquals(Rule.ALLOW, p.actions[ActionClass.READ])
        assertEquals(Permissions().copy(actions = DEFAULT_RULES + (ActionClass.SEND to Rule.NEVER)), p)

        // a minimal bot: only the required fields
        val min = TeamJson.decodeBot("{\"id\":\"x\",\"name\":\"X\",\"avatar\":{\"shape\":\"orb\",\"color\":\"#000\"},\"soulPath\":\"s\",\"createdAt\":1,\"u\":2}")
        assertEquals(Bot(id = "x", kind = BotKind.CUSTOM, name = "X", label = "", avatar = Avatar("orb", "#000"), soulPath = "s",
            toolGroups = emptySet(), createdAt = 1, u = 2), min)

        // a job whose provenance is lost is not confirmed; an unknown run state is QUEUED
        val j = TeamJson.decodeJob("{\"id\":\"j\",\"title\":\"t\",\"source\":\"mcp\",\"brief\":\"b\",\"run\":\"ZOMBIE\",\"createdAt\":1,\"u\":1}")
        assertFalse(j.confirmed); assertEquals(JobRun.QUEUED, j.run); assertNull(j.botId)

        // unknown risk classes are dropped, known ones kept
        assertEquals(setOf(ActionClass.SPEND), TeamJson.classes("[\"SPEND\",\"TELEPORT\"]"))
    }

    @Test fun badInputIsAnError() {
        fun bad(what: String, f: () -> Unit) { try { f(); fail("$what should throw") } catch (_: TeamJson.BadJson) { } }
        bad("no id") { TeamJson.decodeBot("{\"name\":\"X\",\"avatar\":{\"shape\":\"o\",\"color\":\"c\"},\"soulPath\":\"s\",\"createdAt\":1,\"u\":2}") }
        bad("no avatar") { TeamJson.decodeBot("{\"id\":\"x\",\"name\":\"X\",\"soulPath\":\"s\",\"createdAt\":1,\"u\":2}") }
        bad("id not a string") { TeamJson.decodeRow("{\"botId\":5,\"generation\":0,\"seq\":1,\"updatedSeq\":1,\"kind\":\"k\",\"body\":\"{}\",\"createdAt\":1}") }
        bad("not an object") { TeamJson.decodeJob("[1,2]") }
        bad("trailing text") { TeamJson.decodeJobStep(TeamJson.encode(JobStep("j", 1, "l", "s", 1)) + "x") }
        bad("truncated") { TeamJson.decodeRoutine("{\"id\":\"r\"") }
        bad("bad escape") { TeamJson.read("\"\\q\"") }
        bad("raw newline") { TeamJson.read("\"a\nb\"") }
        bad("garbage") { TeamJson.read("nope") }
        bad("deep") { TeamJson.read("[".repeat(100) + "]".repeat(100)) }
        bad("unknown approval class") {
            TeamJson.decodeApproval(TeamJson.encode(Approval("a", "b", null, "c", "t", ActionClass.READ, "t", "d", "{}", false, "pending", false, 1, null, null))
                .replace("\"READ\"", "\"TELEPORT\""))
        }
    }

    @Test fun readerHandlesTheJsonGrammar() {
        @Suppress("UNCHECKED_CAST")
        val v = TeamJson.read(" { \"a\" : [ 1 , -2.5e3 , true , false , null , \"\\u00e9\\ud83d\\ude80\\/\" ] , \"b\" : { } , \"c\" : [ ] , \"big\": 9007199254740993 } ") as Map<String, Any?>
        assertEquals(listOf(1L, -2500.0, true, false, null, "é\uD83D\uDE80/"), v["a"])
        assertEquals(emptyMap<String, Any?>(), v["b"])
        assertEquals(emptyList<Any?>(), v["c"])
        assertEquals(9007199254740993L, v["big"])   // longs stay exact (u stamps, ids)
        assertEquals(nasty, TeamJson.read(TeamJson.write(nasty)))
        // written text never carries a raw control or line-separator character
        assertTrue(TeamJson.write(nasty).none { it < ' ' || it == '\u2028' || it == '\u2029' })
    }
}
