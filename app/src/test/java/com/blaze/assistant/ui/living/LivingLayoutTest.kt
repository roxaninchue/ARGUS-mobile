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
        assertEquals("0.2.0", LivingSpec.VERSION)
    }
}
