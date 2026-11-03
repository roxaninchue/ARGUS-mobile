package com.blaze.assistant.agent.query


import com.blaze.assistant.BrandName
import com.blaze.assistant.composer.ModelChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.51.0 BlazeQuery: source policy, need detection, planner, registries, extraction, judge, evidence pack, verification. */
class SourcePolicyTest {
    @Test fun wikipediaAndWikisAreBlocked() {
        for (u in listOf("https://en.wikipedia.org/wiki/Kotlin_(programming_language)", "https://en.m.wikipedia.org/wiki/Android", "https://www.wikiwand.com/en/Kotlin",
                "https://kotlin.fandom.com/wiki/X", "https://www.wikihow.com/Install-Python", "https://somewiki.org/page", "https://wiki.example.com/x",
                "https://www.investopedia.com/terms/x.asp"))
            assertEquals(u, 0.0, SourcePolicy.trust(u), 0.0)
        assertFalse(SourcePolicy.allowed("https://de.wikipedia.org/wiki/Java"))
    }

    @Test fun contentFarmsMirrorsAndAiAggregatorsAreBlocked() {
        for (u in listOf("https://www.quora.com/How-do-I", "https://www.codegrepper.com/code-examples/x", "https://newbedev.com/x", "https://www.toolify.ai/x",
                "https://www.chegg.com/homework-help", "https://www.pinterest.com/pin/1", "https://twitter.com/x/status/1"))
            assertFalse(u, SourcePolicy.allowed(u))
    }

    @Test fun officialDocsRegistriesAndStandardsRankFirst() {
        for (u in listOf("https://developer.android.com/reference/android/content/Intent", "https://kotlinlang.org/docs/releases.html", "https://pypi.org/project/requests/",
                "https://www.npmjs.com/package/zod", "https://crates.io/crates/serde", "https://man7.org/linux/man-pages/man1/ls.1.html", "https://www.rfc-editor.org/rfc/rfc9110",
                "https://docs.python.org/3/library/asyncio.html", "https://github.com/JetBrains/kotlin/releases/tag/v2.1.0", "https://wiki.archlinux.org/title/Systemd"))
            assertEquals(u, SourcePolicy.OFFICIAL, SourcePolicy.trust(u), 0.0)
        assertEquals(SourcePolicy.HIGH, SourcePolicy.trust("https://github.com/JetBrains/kotlin"), 0.0)
        assertEquals(SourcePolicy.HIGH, SourcePolicy.trust("https://www.reuters.com/technology/x"), 0.0)
        assertEquals(SourcePolicy.HIGH, SourcePolicy.trust("https://www.zra.gov.zm/x"), 0.0)
        assertEquals(SourcePolicy.OK, SourcePolicy.trust("https://stackoverflow.com/questions/1"), 0.0)
        assertTrue(SourcePolicy.trust("https://medium.com/@x/y") < SourcePolicy.trust("https://example.org/y"))
        assertEquals("kotlinlang.org", SourcePolicy.host("https://www.kotlinlang.org/docs"))
    }
}

class NeedDetectTest {
    private fun need(t: String, recent: List<String> = emptyList()) = NeedDetect.decide(t, recent).need

    @Test fun blazeLocalQuestionsNeverSearch() {
        for (t in listOf("what / commands do I have?", "what can you do?", "what did I say earlier?", "how many photos do I have?",
                "what's on my calendar today?", "which settings does ${BrandName.NAME} have?", "/help"))
            assertFalse(t, NeedDetect.decide(t).external)
        assertEquals(NeedDetect.Need.LOCAL, need("what / commands do I have?"))
        assertEquals(NeedDetect.Need.LOCAL, need("what did I say earlier?"))
    }

    @Test fun smallTalkPhoneActionsAndWritingSkipEverything() {
        for (t in listOf("hi", "thanks!", "set an alarm for 6:30", "open WhatsApp", "write me a poem about rain", "turn on bluetooth", "what's the weather tomorrow?"))
            assertEquals(t, NeedDetect.Need.NONE, need(t))
    }

    @Test fun codeAndCommandWorkIsThePriorityTrigger() {
        for (t in listOf("what's the latest version of Kotlin?", "how do I use ffmpeg -ss to trim a video?", "java.lang.IllegalStateException: Fragment not attached to a context",
                "pip install requests fails with error: externally-managed-environment", "which androidx compose BOM should I use?", "npm ERR! code ERESOLVE"))
            assertEquals(t, NeedDetect.Need.CODE, need(t))
        assertTrue(Intent.ERROR in NeedDetect.decide("npm ERR! code ERESOLVE unable to resolve dependency tree").intents)
        assertTrue(Intent.VERSION in NeedDetect.decide("what's the latest version of Kotlin?").intents)
    }

    @Test fun worldQuestionsAreExternal() {
        assertEquals(NeedDetect.Need.EXTERNAL, need("who won the Champions League final this year?"))
        assertEquals(NeedDetect.Need.EXTERNAL, need("what is the exchange rate of the kwacha to the dollar?"))
        assertTrue(Intent.NEWS in NeedDetect.decide("latest news about the Zambian election").intents)
    }
}

class QueryPlannerTest {
    @Test fun plansFromTheConversationNotTheRawSentence() {
        val recent = listOf("I'm building an app with Jetpack Compose", "Compose is Android's UI toolkit.")
        val d = NeedDetect.decide("what's the latest version of it?", recent)
        val plan = QueryPlanner.plan("what's the latest version of it?", recent, d.copy(need = NeedDetect.Need.CODE, intents = setOf(Intent.VERSION)))
        assertTrue(plan.isNotEmpty() && plan.size <= QueryPlanner.MAX)
        assertTrue(plan.first().text, plan.first().text.contains("Compose", true))
        assertTrue(plan.any { it.text.contains("release notes") })
        assertTrue(plan.all { it.intent == Intent.VERSION })
    }

    @Test fun errorsBecomeTheirOwnQueryWithPathsAndNumbersStripped() {
        val t = "my build fails:\nE/AndroidRuntime: java.lang.NullPointerException: Attempt to invoke virtual method at /data/app/com.x/base.apk:1234"
        val q = QueryPlanner.errorQuery(t)
        assertNotNull(q)
        assertFalse(q!!, q.contains("/data/app"))
        val plan = QueryPlanner.plan(t, emptyList(), NeedDetect.decide(t))
        assertEquals(Intent.ERROR, plan.first().intent)
    }

    @Test fun topicKeysMatchRewordingsAndParseModelPlans() {
        assertEquals(QueryPlanner.topicKey("latest Kotlin version"), QueryPlanner.topicKey("Kotlin latest version"))
        assertTrue(QueryPlanner.similar("kotlin latest version", "kotlin version") >= 0.6)
        val p = QueryPlanner.parseModel("```json\n[{\"q\":\"kotlin 2.1 release notes\",\"intent\":\"version\"},{\"q\":\"kotlin k2 compiler docs\",\"intent\":\"docs\"}]\n```")
        assertEquals(2, p.size); assertEquals(Intent.VERSION, p[0].intent); assertEquals(Intent.DOCS, p[1].intent)
        assertEquals(listOf("one query", "two query"), QueryPlanner.parseModel("1. one query\n2. two query").map { it.text })
        assertEquals(4, QueryPlanner.parseModel((1..9).joinToString("\n") { "query number $it" }).size)
    }
}

class RegistriesTest {
    @Test fun detectsPackagesInMessages() {
        val d = Registries.detect("pip install requests then npm i zod and cargo add serde; also androidx.core:core-ktx:1.12.0 and github.com/JetBrains/kotlin")
        val names = d.map { it.eco to it.name }
        assertTrue(names.toString(), Registries.Eco.PYPI to "requests" in names)
        assertTrue(names.toString(), Registries.Eco.NPM to "zod" in names)
        assertTrue(names.toString(), Registries.Eco.CRATES to "serde" in names)
        assertTrue(Registries.detect("androidx.core:core-ktx:1.12.0").any { it.eco == Registries.Eco.MAVEN && it.name == "androidx.core:core-ktx" })
        assertTrue(Registries.detect("see github.com/JetBrains/kotlin").any { it.eco == Registries.Eco.GITHUB && it.name == "JetBrains/kotlin" })
        assertTrue(Registries.detect("connect to example.com:8080 please").none { it.eco == Registries.Eco.MAVEN })
    }

    @Test fun parsesRegistryBodiesEvenWhenCut() {
        val pypi = "{\"info\":{\"author\":\"x\",\"description\":\"long\",\"requires_python\":\">=3.8\",\"version\":\"2.32.3\"},\"urls\":[{\"upload_time_iso_8601\":\"2024-05-29T15:37:47Z\""
        val (line, date) = Registries.parse(Registries.Pkg(Registries.Eco.PYPI, "requests"), pypi)!!
        assertTrue(line, line.contains("2.32.3") && line.contains("pip install requests==2.32.3"))
        assertEquals("2024-05-29", date)
        assertTrue(Registries.parse(Registries.Pkg(Registries.Eco.NPM, "zod"), "{\"name\":\"zod\",\"version\":\"3.23.8\"}")!!.first.contains("3.23.8"))
        assertTrue(Registries.parse(Registries.Pkg(Registries.Eco.GITHUB, "JetBrains/kotlin"), "{\"tag_name\":\"v2.1.0\",\"name\":\"Kotlin 2.1.0\",\"published_at\":\"2024-11-27T10:00:00Z\"}")!!.first.contains("v2.1.0"))
        assertNull(Registries.parse(Registries.Pkg(Registries.Eco.MAVEN, "a.b:c"), "{\"response\":{\"numFound\":0,\"docs\":[]}}"))
        assertNull(Registries.parse(Registries.Pkg(Registries.Eco.NPM, "x"), "Error: HTTP 404"))
        assertTrue(Registries.url(Registries.Pkg(Registries.Eco.NPM, "@types/node")).contains("@types%2Fnode"))
    }
}

class ExtractTest {
    private val page = "# Trim a video with ffmpeg\nAccept all cookies\nSign in\nUse -ss before -i to seek quickly. The -t option sets the duration of the output clip.\n" +
        "ffmpeg -ss 00:01:00 -i input.mp4 -t 30 -c copy out.mp4\nUnrelated paragraph about our company history and values and team.\n© 2025 Example Inc. All rights reserved"

    @Test fun keepsCommandLinesWholeAndDropsBoilerplate() {
        val p = Extract.pieces(page, listOf("ffmpeg", "-ss", "trim", "duration"), 3)
        assertTrue(p.any { it.code && it.text == "ffmpeg -ss 00:01:00 -i input.mp4 -t 30 -c copy out.mp4" })
        assertTrue(p.none { it.text.contains("cookies") || it.text.contains("All rights reserved") })
        assertTrue(p.none { it.text.contains("company history") })
    }

    @Test fun findsTheNewestPastDate() {
        assertEquals("2026-03-04", Extract.date("Updated March 4, 2026. First published 2024-01-02.", 2026))
        assertEquals("2025-10-01", Extract.date("Released 1 October 2025; next release 2031-01-01", 2026))
        assertEquals("", Extract.date("no dates here"))
    }

    @Test fun clipsProseAtSentencesAndCodeAtLines() {
        val long = "Sentence one is here. ".repeat(40)
        assertTrue(Extract.clip(long, false).length <= Extract.PROSE_MAX)
        val code = (1..80).joinToString("\n") { "line $it of code();" }
        val c = Extract.clip(code, true)
        assertTrue(c.length <= Extract.CODE_MAX && c.lines().all { it.startsWith("line ") && it.endsWith("code();") })
    }
}

class JudgeTest {
    private val now = java.util.Calendar.getInstance().apply { set(2026, 10, 2) }.timeInMillis

    @Test fun heuristicDropsBlockedAndIrrelevantAndRanksAuthority() {
        val items = listOf(
            Evidence("Kotlin 2.1.0 is the latest release of Kotlin", "https://en.wikipedia.org/wiki/Kotlin", intent = Intent.VERSION),
            Evidence("Kotlin 2.1.0 released: what's new in Kotlin", "https://kotlinlang.org/docs/whatsnew21.html", date = "2026-10-01", intent = Intent.VERSION),
            Evidence("Kotlin 2.1.0 is out, here is what changed in Kotlin", "https://medium.com/@x/kotlin", intent = Intent.VERSION),
            Evidence("Banana bread recipe with walnuts", "https://example.org/bread"))
        val out = Judge.heuristic(items, listOf("kotlin", "latest", "version"), now)
        assertTrue(out.none { it.host.contains("wikipedia") })
        assertTrue(out.none { it.text.contains("Banana") })
        assertEquals("kotlinlang.org", out.first().host)
        assertTrue(out.first().agreement >= 0.75)
    }

    @Test fun freshnessDependsOnIntent() {
        assertTrue(Judge.freshness("2026-10-20", Intent.NEWS, now) > Judge.freshness("2025-01-01", Intent.NEWS, now))
        assertTrue(Judge.freshness("2023-01-01", Intent.DOCS, now) > Judge.freshness("2023-01-01", Intent.NEWS, now))
    }

    @Test fun flagsVersionConflictsAndDedupes() {
        val a = Evidence("latest is 2.1.0", "https://kotlinlang.org/a", intent = Intent.VERSION, authority = 1.0)
        val b = Evidence("latest is 2.0.21", "https://github.com/JetBrains/kotlin", intent = Intent.VERSION, authority = 0.85)
        assertEquals(1, Judge.conflicts(listOf(a, b)).size)
        assertTrue(Judge.conflicts(listOf(a, a.copy(url = "https://github.com/x/y", authority = 0.85))).isEmpty())
        assertEquals(1, Judge.dedupe(listOf(a, a.copy(url = "https://medium.com/x"))).size)
        assertEquals("kotlinlang.org", Judge.dedupe(listOf(a.copy(url = "https://medium.com/x"), a)).first().host)
    }

    @Test fun modelScoresParseLenientlyAndBlend() {
        val raw = "Sure:\n```json\n[{\"i\":1,\"rel\":9,\"auth\":10,\"fresh\":8,\"cons\":9},{\"i\":2,\"rel\":1,\"auth\":5,\"fresh\":5,\"cons\":5,\"conflict\":\"says 1.9\"}]\n```"
        val sc = Judge.parse(raw, 2)
        assertEquals(0.9, sc[0]!!.rel, 1e-9)
        assertEquals("says 1.9", sc[1]!!.conflict)
        val items = listOf(Evidence("a", "https://kotlinlang.org/a", score = 0.6), Evidence("b", "https://example.org/b", score = 0.6))
        val blended = Judge.blend(items, sc)
        assertEquals(1, blended.size)          // rel 0.1 → dropped
        assertTrue(blended[0].judged && blended[0].score > 0.6)
        assertTrue(Judge.parse("no json at all", 2).isEmpty())
        assertEquals(0.5, Judge.parse("[{\"rel\":0.5}]", 1)[0]!!.rel, 1e-9)   // 0..1 scale accepted
    }

    @Test fun tierChoosesBatchAndDepth() {
        assertEquals(ModelChoice.Family.FLARE, Judge.family(ModelChoice.Family.AUTO))
        assertEquals(ModelChoice.Family.SPARK, Judge.family(ModelChoice.Family.INFERNO, hot = true))
        assertTrue(Judge.batchSize(ModelChoice.Family.SPARK) > Judge.batchSize(ModelChoice.Family.INFERNO))
        val items = listOf(Evidence("x", "https://kotlinlang.org/x"))
        assertTrue(Judge.prompt("q", items, ModelChoice.Family.INFERNO).contains("conflict"))
        assertFalse(Judge.prompt("q", items, ModelChoice.Family.SPARK).contains("\"conflict\""))
    }
}

class EvidencePackTest {
    private fun ev(i: Int, code: Boolean = false) = Evidence(if (code) "./gradlew assembleRelease --stacktrace" else "Fact number $i: " + "detail ".repeat(60), "https://kotlinlang.org/p$i", date = "2026-01-0${i % 9 + 1}", code = code, score = 1.0 - i * 0.01)

    @Test fun staysInsideItsTokenBudget() {
        for (budget in listOf(220, 380, 900)) {
            val block = EvidencePack.build(EvidencePack.cite((1..40).map { ev(it) }), budget, "dabc")
            assertTrue("$budget → ${EvidencePack.tokens(block)}", EvidencePack.tokens(block) <= budget)
            assertTrue(block.contains("[q1]"))
            assertTrue(block.contains("doc:dabc"))
        }
        assertEquals("", EvidencePack.build(emptyList(), 380))
    }

    @Test fun budgetsScaleByRoute() {
        assertTrue(EvidencePack.budgetTokens(null, lite = true) < EvidencePack.budgetTokens(null, lite = false))
        assertEquals(300, EvidencePack.budgetTokens(8_192, false))
        assertEquals(900, EvidencePack.budgetTokens(1_000_000, false))
        assertTrue(EvidencePack.budgetTokens(16_000, false, code = true) > EvidencePack.budgetTokens(16_000, false))
    }

    @Test fun keepsCodeAsCodeAndLinesShort() {
        val block = EvidencePack.build(EvidencePack.cite(listOf(ev(1, code = true), ev(2))), 600)
        assertTrue(block.contains("```\n./gradlew assembleRelease --stacktrace\n```"))
        block.lines().filter { it.startsWith("[q") && !it.contains("```") }.forEach { assertTrue(it.length <= EvidencePack.LINE_MAX + 60) }
    }
}

class VerifyTest {
    private val ev = listOf(Evidence("Use ffmpeg -ss before -i to seek; -t sets duration; -c copy avoids re-encoding.", "https://ffmpeg.org/ffmpeg.html", score = 0.9, authority = 1.0))

    @Test fun verifiesFlagsAgainstTheEvidence() {
        val ok = Verify.check("ffmpeg -ss 10 -i a.mp4 -t 5 -c copy b.mp4", ev)
        assertTrue(ok.verified)
        assertTrue(Verify.note(ok).contains("verified against ffmpeg.org"))
        val bad = Verify.check("ffmpeg --turbo -i a.mp4 b.mp4", ev)
        assertFalse(bad.verified)
        assertEquals(listOf("--turbo"), bad.missing)
        assertTrue(Verify.note(bad).contains("unverified: --turbo"))
        val other = Verify.check("sudo apt-get install -y vim", ev)
        assertFalse(other.verified)
        assertTrue(Verify.note(other).contains("unverified"))
        assertEquals("", Verify.note(Verify.check("ls -la", emptyList())).let { if (it.contains("unverified")) "" else it })
    }

    @Test fun findsProgramsFlagsAndApis() {
        assertEquals("git", Verify.program("FOO=1 sudo git push --force origin main | tee log"))
        assertEquals(listOf("--force"), Verify.flags("git push --force origin main | grep -v x"))
        assertTrue(Verify.apis("val x = ContextCompat.getColor(ctx, id); Foo::bar").containsAll(listOf("ContextCompat.getColor", "Foo::bar")))
    }
}
