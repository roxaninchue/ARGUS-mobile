package com.blaze.assistant.ui.living

import com.blaze.assistant.ui.living.LivingLayout.Camera
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

    @Test fun pageReadsBranchSideBySideUpToThree() {
        val b = plan.boxes
        assertEquals(7, b.size)
        val reads = b.subList(1, 4)
        assertTrue(reads.all { it.y == reads[0].y })
        assertTrue(reads[1].x > reads[0].right && reads[2].x > reads[1].right)
        // the fourth read starts a new row in the main column
        assertEquals(LivingLayout.INDENT, b[4].x); assertTrue(b[4].y > reads[0].bottom)
        // nothing overlaps
        for (i in b.indices) for (j in i + 1 until b.size) {
            val p = b[i]; val q = b[j]
            assertFalse("${p.key} overlaps ${q.key}", p.x < q.right && q.x < p.right && p.y < q.bottom && q.y < p.bottom)
        }
        // trunk elbows into every main-column card, branch links across
        assertEquals(b.size - 1, plan.links.size)
        assertEquals(LivingLayout.TRUNK_X, plan.links[0].pts[0])
    }

    @Test fun turnsStackDownward() {
        val two = LivingModel.board(msgs + listOf(Msg(8, Role.USER, "and deploy it"), Msg(9, Role.BLAZE, "Deployed.")), working = false)
        val p = LivingLayout.plan(two)
        val prompts = p.boxes.filter { it.card.kind == LivingModel.Kind.PROMPT }
        assertEquals(2, prompts.size)
        assertTrue(prompts[1].y > p.boxes.filter { it.card.key.startsWith("a7") }.single().bottom)
        assertTrue(p.height >= p.boxes.maxOf { it.bottom } - 0.5f)
    }

    @Test fun cullingAndHitTesting() {
        val cam = Camera(0f, 0f, 1f)
        val keys = LivingLayout.visibleKeys(plan, cam, 1f, 360f, 200f)
        assertTrue(plan.boxes.first().key in keys)
        assertFalse(plan.boxes.last().key in keys)
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
        val fol = LivingLayout.follow(plan.boxes.last(), d, w, h, 40f, 160f, 30f)
        assertTrue(LivingLayout.visible(plan.boxes.last(), fol, d, w, h))
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
        assertEquals("0.1.0", LivingSpec.VERSION)
    }
}
