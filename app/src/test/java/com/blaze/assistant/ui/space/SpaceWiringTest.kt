package com.blaze.assistant.ui.space

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.52.3 "ARGUS x Hark" wiring, by source scans of the shell (ChatScreen, MainActivity, SettingsSheet, BlazeApp) and the
 * resources. Replaces ArgusHomeWiringTest (the 3D Home and its ArgusHomeBus are gone). What it pins:
 *  - the shell is the space view + Hark's top bar on every tab, Home is the root tab, Projects is the Hark-style list;
 *  - every route home and Back land on the Home tab; what needs the chat (questions, approvals, forms, notifications)
 *    switches to Chat;
 *  - the avatar opens the profile page (the root of Settings), whose rows reach the decluttered pages; the old list,
 *    Advanced, the brain switches and the backup / offline brain sections are gone, and so is the model picker;
 *  - first run is the welcome + one setup screen, the old SetupFlow / dependency gate are gone, and BlazeApp starts what
 *    is left of setup after the first frame;
 *  - the app icon is the ARGUS mark with no duplicate launcher resources; the old Home board stays in the tree.
 * Rendering is SpaceShotsTest's; on a phone, SpaceHomeCheck and SpaceOnboardingCheck.
 */
class SpaceWiringTest {
    private val main = listOf(File("src/main"), File("app/src/main")).first { File(it, "java").isDirectory }
    private val root = File(main, "java")
    private fun src(path: String) = File(root, "com/blaze/assistant/$path").readText()
    private val chat by lazy { src("ui/ChatScreen.kt") }
    private val S = "com.blaze.assistant.ui.space."
    private val allMain by lazy { root.walkTopDown().filter { it.isFile && it.extension == "kt" }.associate { it.name to it.readText() } }

    private fun after(text: String, marker: String, len: Int = 1_600): String {
        val i = text.indexOf(marker)
        assertTrue("'$marker' not found", i >= 0)
        return text.substring(i, minOf(text.length, i + len))
    }

    private fun nowhere(what: String) {
        val hits = allMain.filter { (_, t) -> Regex("(?<![A-Za-z0-9_])" + Regex.escape(what)).containsMatchIn(t) }.keys
        assertTrue("$what is still used in $hits", hits.isEmpty())
    }

    @Test fun theSpaceFilesAreThereAndTheArgusHomeIsGone() {
        for (f in listOf("SpaceLogic", "SpaceTheme", "SpaceTopBar", "SpaceHome", "SpaceSpotlight", "SpaceProfile", "SpaceOnboarding", "SpaceProjects", "SpaceLegal")) {
            val file = File(root, "com/blaze/assistant/ui/space/$f.kt")
            assertTrue(f, file.isFile)
            assertTrue("$f package", file.readText().startsWith("package com.blaze.assistant.ui.space\n"))
        }
        for (f in listOf("SetupPlan", "SetupRunner")) assertTrue(f, File(root, "com/blaze/assistant/setup/$f.kt").isFile)
        assertFalse("ui/argus was removed", File(root, "com/blaze/assistant/ui/argus").exists())
        assertTrue("the ARGUS mark stays", File(root, "com/blaze/assistant/ui/ArgusMark.kt").isFile)
        for (gone in listOf("ArgusHomeBus", "ArgusHomeScreen", "ArgusSceneView", "ArgusSpotlightPanel", "ArgusHomeTokens", "ProjectsPane(")) nowhere(gone)
        // the old Home board is left in the tree (BUILDING.md: don't delete it blind)
        for (f in listOf("ui/HomeIntro.kt", "ui/HomeBoardUi.kt", "ui/HomeGridUi.kt")) assertTrue(f, File(root, "com/blaze/assistant/$f").isFile)
    }

    @Test fun theShellIsTheSpaceViewWithHarksTopBar() {
        assertTrue("testTag(${S}SpaceTags.ROOT)" in chat)
        assertTrue("${S}SpaceBackdrop()" in chat)
        val bar = after(chat, "${S}SpaceTopBar(tab = shellMode,", 2_200)
        assertTrue("the pill switches tabs", "go(navNow().switchTab(m))" in bar)
        assertTrue("the menu button opens the projects list", "${S}SpaceTabs.Left.MENU -> go(navNow().projectsList())" in bar)
        assertTrue("+ and search open Spotlight", "composer.openSpotlight()" in bar)
        assertTrue("the avatar opens the profile page", "onAvatar = { settingsPage = \"\" }" in bar)
        assertTrue("long-press = light / dark", "LightDark.toggle(c.dark)" in bar)
        assertTrue("private chats show the Private pill", "privateChat = isPrivate" in bar)
        // Spotlight sits over everything, fed the phone's apps and what was said
        val spot = after(chat, "${S}SpaceSpotlightLayer(composer", 700)
        assertTrue("InstalledApps.all(ctx)" in spot && "SpotlightGroups.Said(" in spot)
    }

    @Test fun homeIsTheRootTab() {
        val home = after(chat, "if (paneNow == \"home\") {", 900)
        assertTrue("SpaceHomeLayer(" in home)
        assertTrue("asking from Home goes to the chat", "onAsk = { t -> send(t); go(navNow().switchTab(ChatNav.CHAT)) }" in home)
        assertTrue("onOpenProject = { go(navNow().openProject(it)) }" in home)
        assertTrue("the setup card opens the setup screen", "onSetup = { setupSheet = true }" in home)
        val layer = after(chat, "private fun SpaceHomeLayer(", 2_500)
        assertTrue("${S}SpaceHomeScreen(state" in layer)
        assertTrue("the answer card reads the chat", "${S}SpaceAnswer.card(" in layer)
        assertTrue("the greeting is re-read on the hour", "${S}SpaceGreeting.msToNextHour(System.currentTimeMillis())" in layer)
        assertTrue("projects on the Home are capped", "SpaceHomeCards.MAX_PROJECTS" in layer)
    }

    @Test fun projectsIsTheHarkStyleList() {
        assertEquals("both Projects panes use it", 2, Regex(Regex.escape("${S}SpaceProjectsScreen(projects, allChats, busyChats, needs, nav.project,")).findAll(chat).count())
        val p = src("ui/space/SpaceProjects.kt")
        for (k in listOf("onCreate(", "onNewChat(open.id)", "onInstructions(open.id, it)", "onBrief(open.id, fit)", "onRenameChat(ch.id, t)", "onDeleteChat(ch.id)", "onDelete(open.id)"))
            assertTrue("the projects screen keeps $k", k in p)
        assertTrue("the brief keeps its token cap", "P.cutTokens(t, P.BRIEF_TOKENS)" in p)
        assertTrue("testTag(SpaceTags.PROJECTS)" in p)
    }

    @Test fun everyRouteHomeAndBackLandOnTheHomeTab() {
        val homeReq = after(chat, "LaunchedEffect(homeReq)", 1_200)
        assertTrue("go(navNow().switchTab(ChatNav.HOME))" in homeReq)
        assertTrue("views and pages close on the way home", "settingsPage = null" in homeReq && "Views.showing.value = \"\"" in homeReq && "composer.closeSpotlight()" in homeReq)
        assertTrue("open_view home / /home", "\"home\" -> homeRequests.value++" in chat)
        val m = src("MainActivity.kt")
        val extra = after(m, "getBooleanExtra(com.blaze.assistant.ui.HomeRoute.EXTRA_HOME, false)", 500)
        assertTrue("EXTRA_HOME lands on the Home", "homeRequests.value = com.blaze.assistant.ui.homeRequests.value + 1" in extra)
        val back = chat.substringAfter("fun backToChat() {").substringBefore("\n    }\n")
        assertTrue("with nothing over the tab, Back goes Home", "else if (!Agent.privateChat.value) go(n.switchTab(ChatNav.HOME))" in back)
        assertTrue("projects step back inside Projects first", "up.tab == ChatNav.PROJECTS) go(up)" in back)
        val shell = after(chat, "BackRule.shellNow = {\n", 1_200)
        assertTrue("homeRoot = !Agent.privateChat.value" in shell)
        assertTrue("tab = n.tab" in shell)
    }

    @Test fun whatNeedsTheChatSwitchesToChat() {
        val waits = after(chat, "LaunchedEffect(question, pendingConfirmNow, fixNow, listenReq, formNow, secretNow)", 1_200)
        assertTrue("(question != null || pendingConfirmNow != null || fixNow != null || formNow != null || secretNow != null) && !navNow().inProjectChat && navNow().tab != ChatNav.CHAT)" in waits)
        assertTrue("go(navNow().switchTab(ChatNav.CHAT))" in waits)
        assertTrue("a shared text waits in the chat's message box", "${S}SpaceNav.showChat()   // 0.52.3: it waits in the chat's message box" in chat.substringAfter("LaunchedEffect(shared) {").take(400))
        assertTrue("LaunchedEffect(chatAsk) { if (chatAsk > 0 && !navNow().inProjectChat) go(navNow().switchTab(ChatNav.CHAT)) }" in chat)
        val m = src("MainActivity.kt")
        assertTrue("if (ok.openForm) { Agent.showFormChat(); Notifier.clearAction(); ${S}SpaceNav.showChat() }" in m)
        assertTrue("if (ok.openChat && chatId != null) { Agent.openFromLink(chatId); ${S}SpaceNav.showChat() }" in m)
    }

    @Test fun settingsIsTheProfileAndTheDeclutteredTree() {
        val call = after(chat, "SettingsScreen(start, a11yOn,", 700)
        assertTrue("the profile's pill switches tabs", "onTab = { t -> settingsPage = null; exitView(); go(navNow().switchTab(t)) }" in call)
        assertTrue("the profile's Search opens Spotlight", "onSearch = { settingsPage = null; composer.query = \"\"; composer.openSpotlight() }" in call)
        val s = src("ui/SettingsSheet.kt")
        assertTrue("fun SettingsScreen(start: String?, a11yOn: Boolean, onOpenAccessibility: () -> Unit, onDismiss: () -> Unit,\n                   onTab: (String) -> Unit = {}, onSearch: () -> Unit = {})" in s)
        assertTrue("the root is the profile page", "${S}SpaceProfileScreen(" in s)
        assertTrue("Back follows the tree", "page = ${S}SpaceSettings.parentOf(p)" in s)
        assertTrue("${S}SpaceProfile.SYSTEM_PAGE -> ${S}SpaceSystemPage { page = it }" in s)
        assertTrue("${S}SpaceProfile.FEEDBACK_PAGE -> ${S}SpaceFeedbackPage(version)" in s)
        assertTrue("the brain page is only its status", "\"ai\" -> BrainCard { page = \"account\" }" in s)
        assertTrue("Security carries Bank mode", "\"privacy\" -> { SecuritySection(); Spacer(Modifier.height(18.dp)); BankModePage() }" in s)
        // every page the profile can reach has a branch
        for (p in SpaceSettings.TREE - setOf(SpaceProfile.SYSTEM_PAGE, SpaceProfile.FEEDBACK_PAGE)) assertTrue("no branch for $p", "\"$p\" ->" in s)
        // old ids land somewhere real
        assertTrue("\"backup_brain\" to \"ai\"" in s && "\"advanced\" to \"\"" in s)
        for (gone in listOf("SettingsList(", "AdvancedList(", "AiPage(", "BackupBrainSection(", "OfflineBrainSection(", "SimpleAdvancedSwitch()")) assertFalse("$gone in Settings", gone in s)
        for (f in listOf("OfflineBrainSection.kt", "BackupBrainSection.kt")) assertFalse(f, File(root, "com/blaze/assistant/ui/$f").exists())
        nowhere("BackupBrainSection("); nowhere("OfflineBrainSection(")
    }

    @Test fun thereIsNoModelPicker() {
        for (gone in listOf("ModelPicker(", "ModelPill(", "Act.PickModel", "Kind.MODEL", "Icon.MODEL", "SelfMap.MODELS")) nowhere(gone)
        val mc = src("composer/ModelChoice.kt")
        assertTrue("a saved tier goes back to Auto", "return Family.AUTO" in mc)
        // Appearance has no tilt switch and no "Home stays dark" line (the Home follows light / dark)
        val look = src("ui/SimplePages.kt").substringAfter("fun LookPage()")
        assertFalse("argus_parallax" in look); assertFalse("stays dark" in look)
    }

    @Test fun firstRunIsTheWelcomeAndOneSetupScreen() {
        val ob = after(chat, "if (onboard) {", 1_600)
        assertTrue("${S}SpaceOnboardingScreen(step" in ob)
        assertTrue("setup starts on the setup step", "LaunchedEffect(step) { if (step == ${S}SpaceOnboarding.Step.SETUP) com.blaze.assistant.setup.SetupRunner.start(ctx) }" in ob)
        assertTrue("finishing marks onboarded and lands on Home", "Settings.setBool(${S}SpaceOnboarding.PREF_ONBOARDED, true); onboard = false; go(navNow().switchTab(ChatNav.HOME))" in ob)
        assertTrue("only for people who never used ARGUS", "${S}SpaceOnboarding.show(Settings.bool(\"onboarded\", false), Settings.ready, hasMessages)" in chat)
        for (gone in listOf("SetupFlow(", "SetupGateScreen(", "SetupRow(")) nowhere(gone)
        val o = src("ui/space/SpaceOnboarding.kt")
        assertTrue("SpaceOnboarding.TAGLINE" in o && "R.drawable.argus_logo" in o)
        assertTrue("one overall bar", "SetupPlan.overall(" in o || "SetupPlan.percent(" in o)
        // BlazeApp: what is left starts by itself after the first frame, for people past onboarding
        val app = src("BlazeApp.kt").substringAfter("override fun onCreate()").substringBefore("override fun onTrimMemory")
        assertTrue("if (!com.blaze.assistant.device.StartGuard.safeStart) com.blaze.assistant.device.StartLater.add(\"setup.SetupRunner.later\") { com.blaze.assistant.setup.SetupRunner.later(this) }" in app)
        val later = src("setup/SetupRunner.kt").substringAfter("fun later(ctx: Context) {").substringBefore("\n    }\n")
        assertTrue("delay(SetupPlan.LATER_MS)" in later && "SetupPlan.startLater(onboarded, started(), _state.value)" in later)
    }

    @Test fun theAppIconIsTheArgusMarkWithoutDuplicates() {
        val res = File(main, "res")
        for (d in listOf("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi")) for (n in listOf("ic_launcher", "ic_launcher_round")) {
            val dir = File(res, "mipmap-$d")
            assertTrue("$d/$n.webp", File(dir, "$n.webp").isFile)
            assertFalse("$d/$n.png duplicates the webp", File(dir, "$n.png").exists())
        }
        for (n in listOf("ic_launcher_foreground", "ic_launcher_monochrome")) {
            assertTrue(File(res, "drawable-nodpi/$n.png").isFile)
            assertFalse("drawable/$n.xml duplicates the png", File(res, "drawable/$n.xml").exists())
        }
        for (x in listOf("ic_launcher.xml", "ic_launcher_round.xml")) {
            val a = File(res, "mipmap-anydpi-v26/$x").readText()
            assertTrue(x, "@drawable/ic_launcher_foreground" in a && "@drawable/ic_launcher_monochrome" in a && "@drawable/ic_launcher_background" in a)
        }
        assertTrue(File(main, "ic_launcher-playstore.png").isFile)
        for (n in listOf("argus_space", "argus_logo")) assertTrue(n, File(res, "drawable-nodpi/$n.webp").isFile)
        // every resource name is defined once per qualifier-less type (aapt rejects duplicates across extensions)
        for (dir in res.listFiles()!!.filter { it.isDirectory }) {
            val names = dir.listFiles()!!.map { it.nameWithoutExtension.removeSuffix(".9") }
            assertEquals("duplicate resources in ${dir.name}", names.size, names.toSet().size)
        }
    }

    @Test fun theVersionIs0523() {
        val g = listOf(File("build.gradle.kts"), File("app/build.gradle.kts")).first { it.isFile && it.readText().contains("versionCode") }.readText()
        assertTrue("versionCode = 190" in g)
        assertTrue("versionName = \"0.52.3\"" in g)
    }
}
