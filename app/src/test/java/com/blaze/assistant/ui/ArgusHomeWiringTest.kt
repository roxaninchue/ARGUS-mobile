package com.blaze.assistant.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.52.2 the ARGUS Home is the app's root, wired through the chat shell (source scans of ChatScreen, MainActivity,
 * BackToChat and SimplePages, plus BackRule's pure rule). What it pins:
 *  - ChatScreen draws ArgusHomeLayer -> ArgusHomeScreen while ArgusHomeBus is visible, with the chat's send / mic / profile;
 *  - every route home (/home, open_view home, the Home tab, Back out of Music / Video / Browser via EXTRA_HOME) shows it;
 *  - Back from the chat (or anything over it) lands on it; on the Home itself Back is the Home's own;
 *  - questions, approvals, fix cards, forms, secure fields, shares and notification taps bring the chat up instead;
 *  - the old Home board (HomeSurface / HomePane / HomeRedesignContent) is left in the tree but unreachable;
 *  - Settings > Look & feel carries the tilt parallax switch the Home reads.
 * Rendering and touch are ArgusHomeShotsTest's and the device checks' (ArgusHomeCheck, ArgusSpotlightCheck).
 */
class ArgusHomeWiringTest {
    private val root = listOf(java.io.File("src/main/java"), java.io.File("app/src/main/java")).first { it.isDirectory }
    private fun src(path: String) = java.io.File(root, "com/blaze/assistant/$path").readText()
    private val chat by lazy { src("ui/ChatScreen.kt") }
    private val bus = "com.blaze.assistant.ui.argus.ArgusHomeBus"

    /** The body of the first `LaunchedEffect(<key>)` / `fun <name>(` block after [marker], up to [len] characters. */
    private fun after(text: String, marker: String, len: Int = 1_600): String {
        val i = text.indexOf(marker)
        assertTrue("'$marker' not found", i >= 0)
        return text.substring(i, minOf(text.length, i + len))
    }

    @Test fun theArgusFilesAreThere() {
        for (f in listOf("ArgusHome.kt", "ArgusHomeLogic.kt", "ArgusGl.kt", "ArgusSceneView.kt", "ArgusSpotlight.kt"))
            assertTrue("ui/argus/$f", java.io.File(root, "com/blaze/assistant/ui/argus/$f").isFile)
        assertTrue(java.io.File(root, "com/blaze/assistant/composer/SpotlightGroups.kt").isFile)
        // every Argus file is in the argus package
        for (f in java.io.File(root, "com/blaze/assistant/ui/argus").listFiles()!!.filter { it.extension == "kt" })
            assertTrue("${f.name} package", f.readText().startsWith("package com.blaze.assistant.ui.argus\n"))
    }

    @Test fun theShellDrawsTheArgusHomeWhileTheBusIsUp() {
        assertTrue("val argusUp by $bus.visible" in chat)
        val layer = after(chat, "if (argusUp && !newLook)")
        assertTrue("ArgusHomeLayer(composer" in layer)
        assertTrue("the chat's own send path", "onSend = { t -> send(t) }" in layer)
        assertTrue("the chat's mic", "onMic = { onMic() }" in layer)
        assertTrue("the avatar opens the account / settings menu", "onProfile = { avatarMenu = true }" in layer)
        // covered by pages, the gallery, search, setup, the gate and a meeting room: the scene stops drawing
        for (c in listOf("settingsPage != null", "galleryOpen", "showSearch", "onboard", "gate", "roomNow >= 0")) assertTrue("covered by $c", c in layer)
        val fn = after(chat, "private fun ArgusHomeLayer(", 3_000)
        assertTrue("com.blaze.assistant.ui.argus.ArgusHomeScreen(state, level, name, composer" in fn)
        assertTrue("'Open in chat' and the up chevron put the Home away", "onOpenChat = { $bus.hide() }" in fn)
        assertTrue("the orb follows voice and work", "ArgusScene.stateOf(listening = voiceState == Voice.State.LISTENING" in fn)
        assertTrue("the answer card reads the chat", "ArgusAnswer.card(from" in fn)
        assertTrue("Spotlight gets the phone's apps", "InstalledApps.all(ctx)" in fn)
        // the chat underneath keeps its state but isn't drawn
        assertTrue("alpha = if ($bus.visible.value) 0f else 1f" in chat)
    }

    @Test fun everyRouteHomeShowsTheArgusHome() {
        val homeReq = after(chat, "LaunchedEffect(homeReq)")
        assertTrue("$bus.show()" in homeReq)
        assertTrue("views close on the way home", "Views.showing.value = \"\"" in homeReq && "settingsPage = null" in homeReq)
        assertTrue("open_view home / /home", "\"home\" -> homeRequests.value++" in chat)
        // the Home tab only brings the Home up
        assertTrue("if (paneNow == \"home\")" in chat)
        assertTrue(after(chat, "if (paneNow == \"home\")", 400).contains("$bus.show()"))
        // Back out of Music / Video / Browser / Terminal: MainActivity with EXTRA_HOME
        assertTrue("HomeRoute.EXTRA_HOME, true" in src("mediaapps/BackToChat.kt"))
        val main = src("MainActivity.kt")
        val extra = after(main, "getBooleanExtra(com.blaze.assistant.ui.HomeRoute.EXTRA_HOME, false)", 500)
        assertTrue("EXTRA_HOME always lands on the Home", "homeRequests.value = com.blaze.assistant.ui.homeRequests.value + 1" in extra)
    }

    @Test fun backFromTheChatLandsOnTheHome() {
        val back = after(chat, "fun backToChat()", 1_500)
        assertTrue("if (!Agent.privateChat.value) $bus.show()" in back)
        val shell = after(chat, "BackRule.shellNow = {\n", 1_200)   // the shell's (not the call screen's)
        assertTrue("swipeHome = (swipe && sheetP < 0.5f) || $bus.visible.value" in shell)
        assertTrue("homeRoot = !Agent.privateChat.value" in shell)
        // BackRule: on the Home, Back is the Home's own (closes Spotlight, then leaves); the plain chat goes to the Home
        assertEquals(BackRule.Action.OWN, BackRule.action(BackRule.Shell(swipeHome = true, homeRoot = true)))
        assertEquals("home", BackRule.front(BackRule.Shell(swipeHome = true, homeRoot = true)))
        assertEquals(BackRule.Action.TO_CHAT, BackRule.action(BackRule.Shell(homeRoot = true)))
        assertEquals(BackRule.Action.TO_CHAT, BackRule.action(BackRule.Shell(homeRoot = true, view = "music")))
        assertEquals(BackRule.Action.TO_CHAT, BackRule.action(BackRule.Shell(homeRoot = true, settingsPage = "look")))
        // a page over the Home closes first
        assertEquals("page:look", BackRule.front(BackRule.Shell(swipeHome = true, homeRoot = true, settingsPage = "look")))
        // Spotlight's own Back on the Home
        assertTrue("BackHandler(enabled = composer.spotlightOpen) { composer.closeSpotlight() }" in src("ui/argus/ArgusHome.kt"))
    }

    @Test fun whatNeedsTheChatBringsItUp() {
        val waits = after(chat, "LaunchedEffect(question, pendingConfirmNow, fixNow, listenReq, formNow, secretNow)", 1_000)
        assertTrue("question != null || pendingConfirmNow != null || fixNow != null || formNow != null || secretNow != null) $bus.hide()" in waits)
        // the mic alone keeps the Home (the orb listens)
        assertFalse("listenReq > 0) $bus.hide()" in waits)
        assertTrue("a share waits in the chat's message box", "$bus.hide()   // 0.52.2: it waits in the chat's message box" in chat)
        assertTrue("projects" in chat && "\"projects\" -> { $bus.hide(); go(navNow().projectsList()) }" in chat)
        assertTrue("LaunchedEffect(isPrivate) { if (isPrivate) $bus.hide() }" in chat)
        val main = src("MainActivity.kt")
        assertTrue("the secure form notification", "if (ok.openForm) { Agent.showFormChat(); Notifier.clearAction(); $bus.hide() }" in main)
        assertTrue("a reply notification", "if (ok.openChat && chatId != null) { Agent.openFromLink(chatId); $bus.hide() }" in main)
    }

    @Test fun theOldHomeBoardIsUnreachable() {
        // it is still in the tree (follow-up), but the shell never shows it: swipe mode only with the RC2 redesign, which is off
        assertTrue("val swipeMode = HomeRoute.swipe(false, HomeRedesign.enabled)" in chat)
        assertTrue("@Volatile var enabled = false" in after(src("ui/HomeIntro.kt"), "object HomeRedesign {", 200))
        assertFalse(HomeRoute.swipe(setting = false, redesign = false))
        assertTrue("HomeSurface only in swipe mode", "if (swipeMode && p < 1f) {" in chat)
        for (f in listOf("ui/HomeBoardUi.kt", "ui/HomeIntro.kt")) assertTrue("$f kept (deleting it is a follow-up)", java.io.File(root, "com/blaze/assistant/$f").isFile)
        // nothing outside the old Home's own files calls its entry points
        val entry = listOf("HomePane(", "HomeRedesignContent(", "HomeSurface(")
        val callers = root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { it.name !in setOf("HomeBoardUi.kt", "HomeIntro.kt", "SimpleUi.kt", "HomeGridUi.kt", "ChatScreen.kt") }
            .flatMap { f -> val t = f.readText(); entry.filter { e -> Regex("(?<![A-Za-z])" + Regex.escape(e)).containsMatchIn(t) }.map { "${f.name}: $it" } }.toList()
        assertTrue("old Home entry points called from: $callers", callers.isEmpty())
    }

    @Test fun lookAndFeelHasTheTiltSwitchTheHomeReads() {
        val pages = src("ui/SimplePages.kt")
        assertTrue("Settings.bool(\"argus_parallax\", false)" in pages)
        assertTrue("Settings.setBool(\"argus_parallax\", tilt)" in pages)
        assertTrue("readBool(\"argus_parallax\", false)" in src("ui/argus/ArgusHome.kt"))
        // the avatar menu says Look & feel, not Themes
        assertTrue("MenuItem(\"look\", \"Look & feel\"" in chat)
        assertFalse("MenuItem(\"themes\"" in chat)
    }

    @Test fun theHomeIsTestableWithoutTheClockOrGl() {
        val home = src("ui/argus/ArgusHome.kt")
        assertTrue("the hour can be pinned", "hour: Int? = null" in home)
        assertTrue("and is re-read on the hour", "ArgusGreeting.msToNextHour(System.currentTimeMillis())" in home)
        assertFalse("no direct Calendar read in the composable", "java.util.Calendar.getInstance()" in home)
        assertTrue("the renderer can be pinned (shots use Canvas)", "forceMode: ArgusFps.Mode? = null" in home)
        // every tag is used on screen
        val all = home + src("ui/argus/ArgusSpotlight.kt")
        for (t in listOf("ROOT", "SCENE", "MARK", "AVATAR", "NOTCH", "GREETING", "ANSWER", "ANSWER_DISMISS", "PROMPT", "PROMPT_FIELD", "MIC", "SEND", "SCRIM", "SPOTLIGHT", "SPOTLIGHT_FIELD", "SPOTLIGHT_HANDLE"))
            assertTrue("ArgusHomeTags.$t is on screen", Regex("testTag\\(ArgusHomeTags\\.$t\\)").containsMatchIn(all))
    }
}
