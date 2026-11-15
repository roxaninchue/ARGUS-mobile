package com.blaze.assistant.ui.living

import com.blaze.assistant.agent.ToolTrail
import com.blaze.assistant.ui.living.LivingLayout.Box
import com.blaze.assistant.ui.living.LivingLayout.Camera
import com.blaze.assistant.ui.living.LivingModel.Kind
import com.blaze.assistant.ui.living.LivingModel.Msg
import com.blaze.assistant.ui.living.LivingModel.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Living UI: positions, connectors, culling and the camera (LivingLayout), the grid and the state (LivingSpec). */
class LivingLayoutTest {
    private val msgs = listOf(
        Msg(1, Role.USER, "Compare Uptime Kuma, Gatus and OpenStatus"),
        Msg(2, Role.STEP, "", "fetch_page(url=https://github.com/louislam/uptime-kuma)"),
        Msg(3, Role.STEP, "", "fetch_page(url=https://github.com/TwiN/gatus)"),
        Msg(4, Role.STEP, "", "fetch_page(url=https://github.com/openstatusHQ/openstatus)"),
        Msg(5, Role.STEP, "", "fetch_page(url=https://openstatus.dev)"),
        Msg(6, Role.STEP, "", "$ docker compose up -d"),
        Msg(7, Role.BLAZE, "Gatus fits best."),
    )
    private val board = LivingModel.board(msgs, working = false)
    private val plan = LivingLayout.plan(board)

    private fun noOverlaps(b: List<LivingLayout.Box>) {
        for (i in b.indices) for (j in i + 1 until b.size) {
            val p = b[i]; val q = b[j]
            assertFalse("${p.key} overlaps ${q.key}", p.x < q.right && q.x < p.right && p.y < q.bottom && q.y < p.bottom)
        }
    }

    @Test fun promptAndPagesStackInTheLeftColumnWithDownArrows() {
        val b = plan.boxes
        assertEquals(7, b.size)
        val prompt = b.single { it.card.kind == Kind.PROMPT }
        val pages = b.filter { it.card.kind == Kind.BROWSER }
        assertEquals(4, pages.size)
        assertEquals(LivingLayout.LEFT_X, prompt.x); assertEquals(0f, prompt.y)
        // every page in the left column, one under the other, in the order they were read
        assertTrue(pages.all { it.x == LivingLayout.LEFT_X })
        assertTrue(pages[0].y > prompt.bottom)
        for (i in 1 until pages.size) assertTrue(pages[i].y > pages[i - 1].bottom)
        assertEquals(listOf("b2", "b3", "b4", "b5"), pages.map { it.key })
        // a down arrow from the prompt into the first page and between pages
        val arrows = plan.links.filter { it.arrow && it.pts.size == 4 }
        assertEquals(4, arrows.size)
        val chain = listOf(prompt) + pages
        for ((i, l) in arrows.withIndex()) {
            assertEquals(chain[i].bottom, l.pts[1], 0.01f); assertEquals(chain[i + 1].y, l.pts[3], 0.01f)
            assertEquals(l.pts[0], l.pts[2], 0.01f)
            assertTrue(l.pts[3] > l.pts[1])
        }
        noOverlaps(b)
    }

    @Test fun answerFilesAndStepsSitInTheRightColumn() {
        val b = plan.boxes
        val right = b.filter { it.x == LivingLayout.RIGHT_X }
        // the answer heads the right column at the prompt's height, then the steps
        assertEquals(listOf(Kind.ANSWER, Kind.LOG), right.map { it.card.kind })
        assertEquals(b.first().y, right[0].y)
        assertTrue(right[1].y > right[0].bottom)
        assertTrue(right.all { it.x >= b.filter { p -> p.x == LivingLayout.LEFT_X }.maxOf { p -> p.right } + LivingLayout.COL_GAP - 0.01f })
        // a file goes to the right column too, in the order it happened
        val withFile = LivingLayout.plan(LivingModel.board(listOf(
            Msg(1, Role.USER, "Write the config"),
            Msg(2, Role.STEP, "", "fetch_page(url=https://gatus.io)"),
            Msg(3, Role.STEP, "", "file_write(path=/home/config.yaml, content=a: 1)"),
            Msg(4, Role.STEP, "", "$ docker compose up -d"),
            Msg(5, Role.BLAZE, "Done. It runs on port 8080."),
        ), working = false))
        assertEquals(listOf(Kind.ANSWER, Kind.FILE, Kind.LOG), withFile.boxes.filter { it.x == LivingLayout.RIGHT_X }.map { it.card.kind })
        noOverlaps(withFile.boxes)
    }

    @Test fun connectorsRunFromThePromptAndThePageChainIntoTheRightColumn() {
        val b = plan.boxes
        val prompt = b.first()
        val answer = b.single { it.card.kind == Kind.ANSWER }
        val lastPage = b.filter { it.card.kind == Kind.BROWSER }.last()
        fun from(x: Box) = plan.links.filter { it.pts[0] == x.right && it.pts[1] > x.y && it.pts[1] < x.bottom }
        val p = from(prompt).single()
        assertEquals(answer.x, p.pts[p.pts.size - 2], 0.01f)
        assertTrue(p.pts.toList().chunked(2).any { it[0] == LivingLayout.GUTTER_X })
        val c = from(lastPage).single()
        assertEquals(answer.x, c.pts[c.pts.size - 2], 0.01f)
        val enterY = c.pts.last()
        assertTrue(enterY > answer.y && enterY < answer.bottom)
        // the old trunk on the left edge is gone: no link runs left of the cards
        assertTrue(plan.links.all { l -> l.pts.toList().chunked(2).all { it[0] >= LivingLayout.LEFT_X } })
    }

    @Test fun aLiveTurnWithoutAnAnswerKeepsTheStepsOnTheRight() {
        val live = LivingLayout.plan(LivingModel.board(msgs.dropLast(1), working = true))
        val right = live.boxes.filter { it.x == LivingLayout.RIGHT_X }
        assertEquals(listOf(Kind.LOG), right.map { it.card.kind })
        assertEquals(live.boxes.first().y, right[0].y)
        // no chain-to-answer link without an answer: the prompt link only
        val lastPage = live.boxes.filter { it.card.kind == Kind.BROWSER }.last()
        assertTrue(live.links.none { it.pts[0] == lastPage.right && it.pts[1] > lastPage.y && it.pts[1] < lastPage.bottom })
        assertEquals(live.boxes.filter { it.card.kind == Kind.BROWSER }.size + 1, live.links.size)
    }

    @Test fun stepsCardMakesRoomForCommandResults() {
        val res = listOf(ToolTrail.Result("$ docker compose up -d", "Container gatus started\nmore", false, 61))
        val withOut = LivingModel.board(msgs, working = false, results = res).cards.single { it.kind == Kind.LOG }
        val bare = LivingModel.board(msgs, working = false).cards.single { it.kind == Kind.LOG }
        assertEquals("Container gatus started", LivingLayout.result(withOut.rows[0]))
        assertEquals(LivingLayout.height(bare) + LivingLayout.RESULT_H, LivingLayout.height(withOut), 0.01f)
    }

    @Test fun headlineIsTheFirstSentence() {
        assertEquals("Gatus fits best." to "It is light and self-hosted.", LivingLayout.headline("Gatus fits best. It is light and self-hosted."))
        assertEquals("One line" to "", LivingLayout.headline("One line"))
        assertEquals("Title" to "More here", LivingLayout.headline("Title\nMore here"))
        val long = "x".repeat(300)
        assertTrue(LivingLayout.headline(long).first.length <= LivingLayout.HEAD_MAX)
        // a longer answer is a taller card
        val short = LivingModel.Card("a", Kind.ANSWER, LivingModel.State.DONE, text = "Done.")
        val tall = short.copy(text = "Done. " + "More words here. ".repeat(20))
        assertTrue(LivingLayout.height(tall) > LivingLayout.height(short))
    }

    @Test fun turnsStackDownward() {
        val two = LivingModel.board(msgs + listOf(Msg(8, Role.USER, "and deploy it"), Msg(9, Role.BLAZE, "Deployed.")), working = false)
        val p = LivingLayout.plan(two)
        val prompts = p.boxes.filter { it.card.kind == Kind.PROMPT }
        assertEquals(2, prompts.size)
        assertTrue(prompts[1].y > p.boxes.filter { it.card.key.startsWith("a7") }.single().bottom)
        assertTrue(p.height >= p.boxes.maxOf { it.bottom } - 0.5f)
    }

    @Test fun cullingAndHitTesting() {
        val cam = Camera(0f, 0f, 1f)
        val keys = LivingLayout.visibleKeys(plan, cam, 1f, 360f, 200f)
        assertTrue(plan.boxes.first().key in keys)
        assertFalse(plan.boxes.maxBy { it.bottom }.key in keys)
        val first = plan.boxes.first()
        assertEquals(first.key, LivingLayout.hit(plan, cam, 1f, first.x + 5f, first.y + 5f)?.key)
        assertEquals(null, LivingLayout.hit(plan, cam, 1f, -50f, -50f))
    }

    @Test fun zoomIsClampedAndKeepsTheFocalPoint() {
        val cam = Camera(10f, 20f, 1f)
        val z = LivingLayout.zoom(cam, 100f, 50f, 60f)
        assertEquals(LivingSpec.MAX_ZOOM, z.scale, 0.0001f)
        // the world point under (50, 60) stays there
        assertEquals(cam.wx(50f, 2f), z.wx(50f, 2f), 0.001f)
        assertEquals(LivingSpec.MIN_ZOOM, LivingLayout.zoom(cam, 0.001f, 0f, 0f).scale, 0.0001f)
    }

    @Test fun fitShowsEverythingAndFocusFillsTheWidth() {
        val d = 2.5f; val w = 1080f; val h = 2200f
        val fit = LivingLayout.fit(plan, d, w, h, 40f, 160f)
        for (b in plan.boxes) assertTrue(LivingLayout.visible(b, fit, d, w, h))
        val target = plan.boxes[2]
        val f = LivingLayout.focus(target, d, w, h, 40f, 160f)
        assertEquals(40f, f.sx(target.x, d), 1f)
        assertEquals(w - 40f, f.sx(target.right, d), 1f)
        for (b in listOf(plan.boxes.last(), plan.boxes.maxBy { it.bottom })) {
            val fol = LivingLayout.follow(b, d, w, h, 40f, 160f, 30f)
            assertTrue(LivingLayout.visible(b, fol, d, w, h))
            // the followed card is fully across the screen
            assertTrue(fol.sx(b.x, d) >= 0f && fol.sx(b.right, d) <= w + 0.5f)
        }
        // following reads one column at 1x on a phone (the other column peeks in from the side)
        val answer = plan.boxes.single { it.card.kind == Kind.ANSWER }
        val onAnswer = LivingLayout.follow(answer, d, w, h, 40f, 160f, 30f)
        assertEquals(1f, onAnswer.scale, 0.0001f)
        assertTrue(onAnswer.sx(plan.boxes.first().right, d) > 0f)
    }

    @Test fun gridThinsOutWhenZoomedOut() {
        val near = LivingSpec.gridStepPx(1080f, 2200f, 2.5f, 1f)
        val far = LivingSpec.gridStepPx(1080f, 2200f, 2.5f, 0.25f)
        assertTrue((1080f / far + 1) * (2200f / far + 1) <= LivingSpec.MAX_DOTS)
        assertTrue((1080f / near + 1) * (2200f / near + 1) <= LivingSpec.MAX_DOTS)
    }

    @Test fun chatIsTheDefaultAndBackReturnsToChat() {
        LivingSpec.show(LivingSpec.View.CHAT)
        assertEquals(LivingSpec.View.CHAT, LivingSpec.view.value)
        assertFalse(LivingSpec.back())
        LivingSpec.show(LivingSpec.View.LIVING)
        assertTrue(LivingSpec.back())
        assertEquals(LivingSpec.View.CHAT, LivingSpec.view.value)
        assertEquals("0.2.3", LivingSpec.VERSION)
    }

    // ------------------------------------------------------------------ 0.2.1 landscape

    private val wide = LivingLayout.plan(board, wide = true)

    @Test fun landscapeHasFourColumnsLeftToRight() {
        val xs = LivingLayout.wideColumns(board)
        assertEquals(listOf(LivingLayout.Col.PROMPT, LivingLayout.Col.PAGES, LivingLayout.Col.MAIN, LivingLayout.Col.CHECK), xs.keys.toList())
        val b = wide.boxes
        assertEquals(7, b.size)
        val prompt = b.single { it.card.kind == Kind.PROMPT }
        val pages = b.filter { it.card.kind == Kind.BROWSER }
        val answer = b.single { it.card.kind == Kind.ANSWER }
        val log = b.single { it.card.kind == Kind.LOG }
        // prompt | pages | answer | checklist, each column right of the one before, all starting at the turn's top
        assertTrue(pages.all { it.x >= prompt.right + LivingLayout.WIDE_GAP - 0.01f })
        assertTrue(answer.x >= pages.maxOf { it.right } + LivingLayout.WIDE_GAP - 0.01f)
        assertTrue(log.x >= answer.right + LivingLayout.WIDE_GAP - 0.01f)
        for (x in listOf(prompt, pages[0], answer, log)) assertEquals(0f, x.y, 0.01f)
        for (i in 1 until pages.size) assertTrue(pages[i].y > pages[i - 1].bottom)
        assertEquals(listOf("b2", "b3", "b4", "b5"), pages.map { it.key })
        noOverlaps(b)
        // wider and shorter than the portrait plan
        assertTrue(wide.width > plan.width)
        assertTrue(wide.height < plan.height)
        assertTrue(wide.height >= b.maxOf { it.bottom } - 0.5f)
        // portrait is untouched by the wide flag
        assertEquals(plan.boxes, LivingLayout.plan(board, wide = false).boxes)
    }

    @Test fun landscapeConnectorsElbowFromThePromptIntoEachPageAndOn() {
        val prompt = wide.boxes.first()
        val pages = wide.boxes.filter { it.card.kind == Kind.BROWSER }
        val answer = wide.boxes.single { it.card.kind == Kind.ANSWER }
        val log = wide.boxes.single { it.card.kind == Kind.LOG }
        fun ends(l: LivingLayout.Link) = l.pts[l.pts.size - 2] to l.pts.last()
        // a link from the prompt's right edge into each page's left edge, with an arrow
        for (p in pages) {
            val l = wide.links.single { it.pts[0] == prompt.right && ends(it).first == p.x && ends(it).second > p.y && ends(it).second < p.bottom }
            assertTrue(l.arrow)
            assertEquals(8, l.pts.size)   // out, down the gutter, in: two elbows
        }
        // every page joins the answer; one arrowhead
        val into = wide.links.filter { ends(it).first == answer.x && pages.any { p -> it.pts[0] == p.right } }
        assertEquals(pages.size, into.size)
        assertEquals(1, into.count { it.arrow })
        assertTrue(into.all { ends(it).second > answer.y && ends(it).second < answer.bottom })
        // the answer column into the checklist
        assertTrue(wide.links.any { it.pts[0] == answer.right && ends(it).first == log.x })
        // a turn without pages: no pages column, the prompt links straight to the answer
        val chat = LivingModel.board(listOf(Msg(1, Role.USER, "Hey hey"), Msg(2, Role.BLAZE, "Hi! What can I do?")), working = false)
        val cp = LivingLayout.plan(chat, wide = true)
        assertEquals(listOf(LivingLayout.Col.PROMPT, LivingLayout.Col.MAIN), LivingLayout.wideColumns(chat).keys.toList())
        assertEquals(1, cp.links.size)
        assertEquals(cp.boxes[1].x, cp.links[0].pts[cp.links[0].pts.size - 2], 0.01f)
    }

    @Test fun landscapeLiveTurnLinksPagesToTheChecklist() {
        val live = LivingLayout.plan(LivingModel.board(msgs.dropLast(1), working = true), wide = true)
        val log = live.boxes.single { it.card.kind == Kind.LOG }
        val pages = live.boxes.filter { it.card.kind == Kind.BROWSER }
        assertTrue(live.links.any { l -> l.pts[0] == pages.last().right && l.pts[l.pts.size - 2] == log.x && l.arrow })
        noOverlaps(live.boxes)
        // turns stack downward in landscape too
        val two = LivingLayout.plan(LivingModel.board(msgs + listOf(Msg(8, Role.USER, "and deploy it"), Msg(9, Role.BLAZE, "Deployed.")), working = false), wide = true)
        val prompts = two.boxes.filter { it.card.kind == Kind.PROMPT }
        assertTrue(prompts[1].y > two.boxes.filter { it.y == 0f }.maxOf { it.bottom })
        noOverlaps(two.boxes)
    }

    @Test fun landscapeFollowKeepsTheActiveCardClearOfHeaderAndComposer() {
        // a landscape phone: 2340 × 1080 px at 2.75 (≈ 851 × 393 dp), the rail already taken off the canvas width
        val d = 2.75f; val w = 2340f - 64f * d; val h = 1080f
        val pad = 16f * d; val top = LivingSpec.WIDE_TOP_DP * d; val bottom = LivingSpec.WIDE_BOTTOM_DP * d
        for (b in wide.boxes) {
            val c = LivingLayout.followWide(b, wide, d, w, h, pad, top, bottom)
            assertTrue(c.scale >= LivingSpec.MIN_ZOOM && c.scale <= 1f)
            // whole card across the screen and in the band between the header line and the composer
            assertTrue("${b.key} left", c.sx(b.x, d) >= pad - 0.5f)
            assertTrue("${b.key} right", c.sx(b.right, d) <= w - pad + 0.5f)
            assertTrue("${b.key} top", c.sy(b.y, d) >= top + pad - 0.5f)
            assertTrue("${b.key} bottom", c.sy(b.bottom, d) <= h - bottom - pad + 0.5f)
        }
        // on a tablet-wide window every column is in view at once while following the answer
        val big = LivingLayout.followWide(wide.boxes.single { it.card.kind == Kind.ANSWER }, wide, 2f, 2560f, 1600f, 32f, 120f, 168f)
        assertTrue(big.sx(0f, 2f) >= 31.5f && big.sx(wide.width, 2f) <= 2560f - 31.5f)
    }

    @Test fun turningThePhoneKeepsTheCardInTheMiddle() {
        val d = 2.75f
        val pw = 1080f; val ph = 2200f; val lw = 2164f; val lh = 1080f
        val target = plan.boxes.single { it.card.kind == Kind.ANSWER }
        // the answer's middle at the portrait screen's centre, at 0.8x
        val s = 0.8f
        val cam = Camera(pw / 2f - target.midX * d * s, ph / 2f - (target.y + target.h / 2f) * d * s, s)
        val turned = LivingLayout.reanchor(plan, cam, pw, ph, wide, lw, lh, d)!!
        val nb = wide.box(target.key)!!
        assertEquals(s, turned.scale, 0.0001f)
        assertEquals(lw / 2f, turned.sx(nb.midX, d), 1f)
        assertEquals(lh / 2f, turned.sy(nb.y + nb.h / 2f, d), 1f)
        // and back
        val back = LivingLayout.reanchor(wide, turned, lw, lh, plan, pw, ph, d)!!
        assertEquals(cam.ox, back.ox, 1f); assertEquals(cam.oy, back.oy, 1f)
        // nothing to anchor on
        assertEquals(null, LivingLayout.reanchor(LivingLayout.Plan(emptyList(), emptyList(), 0f, 0f), cam, pw, ph, wide, lw, lh, d))
    }

    @Test fun railOnlyForLandscapeLivingOnTheMainChat() {
        assertTrue(LivingSpec.railOn(landscape = true, argus = true, living = true, mainChat = true, hasMessages = true, viewOpen = false))
        assertFalse(LivingSpec.railOn(false, true, true, true, true, false))   // portrait never changes
        assertFalse(LivingSpec.railOn(true, true, false, true, true, false))   // Chat view keeps its chrome
        assertFalse(LivingSpec.railOn(true, true, true, false, true, false))   // project chats
        assertFalse(LivingSpec.railOn(true, false, true, true, true, false))   // the old shell
        assertFalse(LivingSpec.railOn(true, true, true, true, false, false))   // nothing to show yet
        assertFalse(LivingSpec.railOn(true, true, true, true, true, true))     // a view is open
        // 0.2.3: the shell rail in every mode on its side, unless a full-screen view, a maximised chat or the Stage hides the bar
        assertTrue(LivingSpec.shellRail(landscape = true, argus = true, designFull = false, maxed = false, topBar = true))
        assertFalse(LivingSpec.shellRail(false, true, false, false, true))
        assertFalse(LivingSpec.shellRail(true, false, false, false, true))
        assertFalse(LivingSpec.shellRail(true, true, true, false, true))
        assertFalse(LivingSpec.shellRail(true, true, false, true, true))
        assertFalse(LivingSpec.shellRail(true, true, false, false, false))
        assertEquals(listOf("team", "chat", "life", "desk"), LivingSpec.SHELL_MODES.map { it.first })   // ChatNav.MODES order
        // landscape = wider than tall and at least 560 dp wide
        assertTrue(com.blaze.assistant.ui.common.Orientation.landscape(851, 393))
        assertFalse(com.blaze.assistant.ui.common.Orientation.landscape(393, 851))
        assertFalse(com.blaze.assistant.ui.common.Orientation.landscape(520, 400))   // a small split-screen window
        assertFalse(com.blaze.assistant.ui.common.Orientation.landscape(800, 1280))  // a tablet held upright
    }
}
