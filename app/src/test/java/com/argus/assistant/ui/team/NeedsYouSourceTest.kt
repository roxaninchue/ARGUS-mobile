package com.argus.assistant.ui.team

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P2-SF-03: the bell dot, the NeedYouBanner and the Inbox (bell sheet + Desk segment) read ONE needs-you query
 * ([com.argus.assistant.policy.NeedsYou] via NeedsYouFeed). Source scan: no surface counts pending things on its own.
 */
class NeedsYouSourceTest {
    private fun root(): File = listOf(File("src/main/java/com/argus/assistant"), File("app/src/main/java/com/argus/assistant")).first { it.exists() }
    private fun src(rel: String) = File(root(), rel).readText()

    @Test fun bellDotReadsNeedsYou() {
        val chat = src("ui/ChatScreen.kt")
        val badge = chat.lines().single { it.trim().startsWith("bellBadge =") }
        assertTrue(badge.contains("NeedsYou.dot(needsYou)"))
        assertTrue(chat.contains("val needsYou = com.argus.assistant.ui.team.rememberNeedsYou()"))
        // policy cards reach the bell only through NeedsYou, not as a second signal
        assertFalse(badge.contains("pendingConfirmNow") || badge.contains("policyCards"))
    }

    @Test fun everyBannerIsFedByNeedsYou() {
        val callers = root().walkTopDown().filter { it.extension == "kt" }
            .filter { f -> f.readText().let { it.contains("NeedYouBanner(") && !it.contains("fun NeedYouBanner(") } }.toList()
        assertTrue("the banner is shown somewhere", callers.isNotEmpty())
        for (f in callers) assertTrue(f.path, f.readText().contains("NeedsYou.banner("))
    }

    @Test fun deskInboxUsesTheOneList() {
        val desk = src("ui/team/DeskMode.kt")
        assertTrue(desk.contains("val needs = rememberNeedsYou()"))
        assertTrue(desk.contains("DeskModel.chips(com.argus.assistant.policy.NeedsYou.count(needs)"))
        assertTrue(desk.contains("InboxList(needs"))
        assertFalse(desk.contains("DeskModel.inboxBody("))
        val sheet = src("ui/InboxSheet.kt")
        assertTrue(sheet.contains("rememberNeedsYou()") && sheet.contains("InboxList(needs"))
    }

    @Test fun oneFeedOneDecisionPath() {
        // only the feed reads the policy cards to build the needs-you list; the Inbox answers through PolicyRuntime.answer
        val inbox = src("ui/team/Inbox.kt")
        assertEquals(1, Regex("""NeedsYou\.build\(""").findAll(inbox).count())
        assertTrue(inbox.contains("PolicyRuntime.answer(item.id, a)"))
        val builders = root().walkTopDown().filter { it.extension == "kt" && it.readText().contains("NeedsYou.build(") }.map { it.name }.toSet()
        assertEquals(setOf("Inbox.kt"), builders)
        // nobody else counts open approvals for a badge
        val counted = root().walkTopDown().filter { it.extension == "kt" && !it.path.contains("/policy/") }
            .filter { it.readText().let { t -> t.contains("approvals.pending().size") || t.contains("pendingFor(") } }.map { it.name }.toList()
        assertEquals(emptyList<String>(), counted)
    }

    @Test fun inboxNeverOpensOnStart() {
        // 6f5a958: no Tempo/Inbox on start. Desk opens on Jobs; the bell sheet opens only from the bell.
        assertFalse(src("ui/team/DeskModel.kt").contains("val DEFAULT = Segment.INBOX"))
        val chat = src("ui/ChatScreen.kt")
        assertTrue(chat.contains("var inboxOpen by remember { mutableStateOf(false) }"))
    }
}
