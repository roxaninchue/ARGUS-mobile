package com.argus.assistant.agent.query

import com.argus.assistant.composer.ModelChoice
import com.argus.assistant.memory.CacheStore
import com.argus.assistant.store.JdbcSql
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** 0.51.0 BlazeQuery engine with a fake network / model and a real cache.db on sqlite-jdbc: latency budget, non-blocking, cache. */
class BlazeQueryEngineTest {
    private var clock = 1_000_000L
    private fun store() = CacheStore(JdbcSql(), Files.createTempDirectory("bq").toFile()) { clock }

    private class FakeNet(val searchMs: Long = 0, val fetchMs: Long = 0, val up: Boolean = true) : BlazeQuery.Net {
        val searches = AtomicInteger(); val fetches = CopyOnWriteArrayList<String>()
        override suspend fun search(q: String): List<BlazeQuery.Hit> {
            searches.incrementAndGet(); delay(searchMs)
            return listOf(
                BlazeQuery.Hit("Kotlin - Wikipedia", "https://en.wikipedia.org/wiki/Kotlin", "Kotlin 2.1.0 is the latest version of Kotlin, per Wikipedia."),
                BlazeQuery.Hit("What's new in Kotlin 2.1.0", "https://kotlinlang.org/docs/whatsnew21.html", "Kotlin 2.1.0 is the latest version: new K2 compiler features, released 2024-11-27."),
                BlazeQuery.Hit("Kotlin releases", "https://github.com/JetBrains/kotlin/releases", "Kotlin 2.1.0 latest version release of Kotlin on GitHub."))
        }
        override suspend fun fetch(url: String, max: Int): String? {
            fetches += url; delay(fetchMs)
            return "# Kotlin releases\nKotlin 2.1.0 is the latest stable version of Kotlin, released on November 27, 2024.\n" +
                "Update the Kotlin version in build.gradle.kts:\nplugins { kotlin(\"jvm\") version \"2.1.0\" }\nOther text about the company and careers and offices."
        }
        override fun online() = up
    }

    private fun env(net: BlazeQuery.Net, s: CacheStore?, ask: (suspend (String, ModelChoice.Family) -> String)? = null) =
        BlazeQuery.Env(net, { s }, ask, CoroutineScope(SupervisorJob() + Dispatchers.Default), { clock })

    private val req = BlazeQuery.Request("chat1", "what's the latest version of Kotlin?", emptyList(), ModelChoice.Family.SPARK, 380)

    @Test fun slowSearchNeverDelaysTheReplyPastTheBudget() = runBlocking {
        val s = store(); val net = FakeNet(searchMs = 3_000, fetchMs = 100)
        val late = CopyOnWriteArrayList<BlazeQuery.Pack>()
        val t0 = System.nanoTime()
        val p = BlazeQuery.hot(req, env(net, s), hotMs = 400) { late += it }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("hot path took $ms ms", ms < 900)
        assertEquals(BlazeQuery.Status.LATE, p.status)
        assertEquals("", p.block)
        // the background pass keeps going and finishes on its own
        withTimeout(10_000) { while (late.isEmpty()) delay(50) }
        assertTrue(late.first().block.contains("BLAZEQUERY"))
        assertNotNull(late.first().doc)
    }

    @Test fun fastSearchInjectsAJudgedCompactPackWithoutWikipedia() = runBlocking {
        val s = store(); val net = FakeNet()
        val late = CopyOnWriteArrayList<BlazeQuery.Pack>()
        val p = BlazeQuery.hot(req, env(net, s), hotMs = 1_200) { late += it }
        assertEquals(BlazeQuery.Status.FRESH, p.status)
        assertTrue(p.block.contains("[q1]"))
        assertFalse(p.block.contains("wikipedia"))
        assertTrue(EvidencePack.tokens(p.block) <= req.budgetTok)
        withTimeout(10_000) { while (late.isEmpty()) delay(50) }
        // the blocked host is never fetched; the stored document is in the chat's cache and searchable
        assertTrue(net.fetches.none { it.contains("wikipedia") })
        val doc = late.first().doc!!
        assertEquals(BlazeQuery.KIND, s.doc(doc)!!.kind)
        assertTrue(s.search("kotlin version", chat = "chat1").isNotEmpty())
        assertTrue(late.first().evidence.any { it.code && it.text.contains("version \"2.1.0\"") })
    }

    @Test fun repeatTopicIsReusedInstantlyAndExpiresAfterTtl() = runBlocking {
        val s = store(); val net = FakeNet()
        val late = CopyOnWriteArrayList<BlazeQuery.Pack>()
        BlazeQuery.hot(req, env(net, s)) { late += it }
        withTimeout(10_000) { while (late.isEmpty()) delay(50) }
        val searchesBefore = net.searches.get()
        val again = BlazeQuery.hot(req.copy(text = "Kotlin latest version"), env(net, s), hotMs = 50)
        assertEquals(BlazeQuery.Status.REUSED, again.status)
        assertTrue(again.block.contains("[q1]"))
        assertEquals(searchesBefore, net.searches.get())
        // TTL: the topic (and its document) go once it expired
        clock += BlazeQuery.VERSION_TTL_MS + 1
        assertNull(s.bqGet(QueryPlanner.topicKey(req.text)))
        assertEquals(1, s.bqExpire())
        assertNull(s.doc(late.first().doc!!))
    }

    @Test fun storingTheSameTopicTwiceKeepsOneDocument() {
        val s = store()
        val d1 = s.ingest("c", BlazeQuery.KIND, "BlazeQuery: a", "first text about kotlin")
        s.bqPut("kotlin version", d1.id, "c", 60_000, BlazeQuery.encode(listOf(Evidence("x", "https://kotlinlang.org/x"))), false)
        val d2 = s.ingest("c", BlazeQuery.KIND, "BlazeQuery: b", "second text about kotlin")
        s.bqPut("kotlin version", d2.id, "c", 60_000, BlazeQuery.encode(listOf(Evidence("y", "https://kotlinlang.org/y"))), true)
        assertNull(s.doc(d1.id))
        assertEquals(d2.id, s.bqGet("kotlin version")!!.doc)
        assertEquals(1, s.bqAlive().size)
        val (items, _) = BlazeQuery.decode(s.bqGet("kotlin version")!!.pack)
        assertEquals("y", items.single().text)
    }

    @Test fun localAndSmallTalkNeverTouchTheNetwork() = runBlocking {
        val s = store(); val net = FakeNet()
        s.ingest("chat1", "paste", "Notes", "My project notes: the slash commands list lives in Commands.kt and SlashCommands.kt.")
        val local = BlazeQuery.hot(req.copy(text = "what / commands do I have?"), env(net, s))
        assertEquals(BlazeQuery.Status.LOCAL, local.status)
        val chat = BlazeQuery.hot(req.copy(text = "thanks!"), env(net, s))
        assertEquals(BlazeQuery.Status.SKIPPED, chat.status)
        val priv = BlazeQuery.hot(req.copy(private = true), env(net, s))
        assertEquals(BlazeQuery.Status.SKIPPED, priv.status)
        assertEquals(0, net.searches.get())
    }

    @Test fun offlineDegradesToNothingButStillReusesTheCache() = runBlocking {
        val s = store()
        val p = BlazeQuery.hot(req, env(FakeNet(up = false), s))
        assertEquals(BlazeQuery.Status.OFFLINE, p.status)
        assertEquals("", p.block)
        val d = s.ingest("chat1", BlazeQuery.KIND, "BlazeQuery: k", "Kotlin 2.1.0")
        s.bqPut(QueryPlanner.topicKey(req.text), d.id, "chat1", 60_000, BlazeQuery.encode(listOf(Evidence("Kotlin 2.1.0 is the latest", "https://kotlinlang.org/x", score = 0.9))), true)
        assertEquals(BlazeQuery.Status.REUSED, BlazeQuery.hot(req, env(FakeNet(up = false), s)).status)
        // no store at all (cache unavailable) still answers safely
        assertEquals(BlazeQuery.Status.OFFLINE, BlazeQuery.hot(req, env(FakeNet(up = false), null)).status)
    }

    @Test fun backgroundJudgeUsesTheSelectedTier() = runBlocking {
        val s = store(); val tiers = CopyOnWriteArrayList<ModelChoice.Family>()
        val late = CopyOnWriteArrayList<BlazeQuery.Pack>()
        val ask: suspend (String, ModelChoice.Family) -> String = { prompt, f -> tiers += f; "[" + (1..12).joinToString(",") { "{\"i\":$it,\"rel\":9,\"auth\":9,\"fresh\":9,\"cons\":9}" } + "]" }
        BlazeQuery.hot(req.copy(family = ModelChoice.Family.INFERNO), env(FakeNet(), s, ask)) { late += it }
        withTimeout(10_000) { while (late.isEmpty()) delay(50) }
        assertTrue(tiers.isNotEmpty() && tiers.all { it == ModelChoice.Family.INFERNO })
        assertTrue(late.first().evidence.any { it.judged })
    }
}

/** 0.51.0 research mode: bounded rounds, gap-driven follow-ups, a cited brief, cached evidence. */
class ResearchTest {
    private var clock = 5_000_000L

    private class Net : BlazeQuery.Net {
        val queries = CopyOnWriteArrayList<String>()
        override suspend fun search(q: String): List<BlazeQuery.Hit> { queries += q
            return listOf(BlazeQuery.Hit("Docs for $q", "https://developer.android.com/${q.hashCode()}", "Official docs about $q with details for the question on android permissions.")) }
        override suspend fun fetch(url: String, max: Int): String? = null
    }

    private fun env(net: Net, ask: (suspend (String, ModelChoice.Family) -> String)?) =
        BlazeQuery.Env(net, { CacheStore(JdbcSql(), Files.createTempDirectory("r").toFile()) { clock } }, ask, CoroutineScope(SupervisorJob() + Dispatchers.Default), { clock })

    @Test fun roundsAreBoundedEvenWhenTheModelNeverSaysDone() = runBlocking {
        val net = Net(); var n = 0
        val ask: suspend (String, ModelChoice.Family) -> String = { p, _ ->
            when {
                p.startsWith("Write 1 to") -> "[{\"q\":\"android runtime permissions\",\"intent\":\"docs\"}]"
                p.startsWith("Research question") -> { n++; "{\"done\":false,\"gaps\":[\"gap $n\"],\"followups\":[{\"q\":\"android permission follow-up $n\",\"intent\":\"docs\"}]}" }
                p.startsWith("Write a short") -> "- Ask at runtime [q1]"
                else -> "[]"
            }
        }
        val b = Research.run("how do android runtime permissions work?", emptyList(), "c", ModelChoice.Family.FLARE, env(net, ask), Research.Limits(rounds = 3))
        assertEquals(3, b.rounds)
        assertEquals(3, net.queries.size)
        assertTrue(b.text.contains("[q1]"))
        assertNotNull(b.doc)
        assertTrue(Research.render(b).contains("Sources:\n[q1]"))
    }

    @Test fun stopsWhenCoveredAndWorksWithoutAModel() = runBlocking {
        val net = Net()
        val ask: suspend (String, ModelChoice.Family) -> String = { p, _ -> if (p.startsWith("Research question")) "{\"done\":true,\"gaps\":[]}" else if (p.startsWith("Write 1 to")) "[{\"q\":\"android permissions\"}]" else "brief [q1]" }
        val b = Research.run("android permissions?", emptyList(), "c", ModelChoice.Family.SPARK, env(net, ask))
        assertEquals(1, b.rounds); assertEquals("covered", b.stopped)
        val offline = Research.run("how do android runtime permissions work?", emptyList(), "c", ModelChoice.Family.AUTO, env(Net(), null), Research.DEEP)
        assertEquals(1, offline.rounds)
        assertTrue(offline.text.startsWith("- "))
        val t = Research.parseGaps("garbage")
        assertTrue(t.done)
    }
}
