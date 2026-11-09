package com.blaze.assistant.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.52.4 icons everywhere: a menu row's label picks its leading icon ([MenuIcons.forLabel]), settings rows pick theirs
 * from the title ([MenuIcons.forSetting]), and the shared menu card and the bubble menu draw them. Pure: no Compose here.
 */
class MenuIconsTest {
    private fun root(): File = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
    private fun src(path: String) = File(root(), "com/blaze/assistant/$path").readText()

    @Test fun theMenusInTheAppGetTheIconsTheirLabelsSay() {
        val cases = mapOf(
            // chat and projects: the screenshot's overflow menu
            "New chat in this project" to MenuIcon.NEW_CHAT, "Rename" to MenuIcon.RENAME, "Delete chat" to MenuIcon.DELETE,
            "Copy" to MenuIcon.COPY, "Quote" to MenuIcon.QUOTE, "Retry" to MenuIcon.RETRY, "React" to MenuIcon.REACT,
            "What I did" to MenuIcon.RECEIPT, "What happened" to MenuIcon.INFO,
            // music
            "Play next" to MenuIcon.PLAY_NEXT, "Add to queue" to MenuIcon.QUEUE_ADD, "Add to playlist\u2026" to MenuIcon.PLAYLIST_ADD,
            "Remove from this playlist" to MenuIcon.PLAYLIST_REMOVE, "Like" to MenuIcon.LIKE, "Remove from Liked" to MenuIcon.UNLIKE,
            "Lyrics" to MenuIcon.LYRICS, "Karaoke" to MenuIcon.KARAOKE, "ARGUS DJ: more like this" to MenuIcon.DJ,
            "Repeat: off" to MenuIcon.REPEAT, "Speed: 1.5x" to MenuIcon.SPEED, "Equaliser: Flat" to MenuIcon.EQUALISER,
            "Fade between songs: off" to MenuIcon.FADE, "15 minutes" to MenuIcon.TIMER, "End of this song" to MenuIcon.TIMER,
            "Turn off" to MenuIcon.TIMER_OFF, "Move up" to MenuIcon.MOVE_UP, "Move down" to MenuIcon.MOVE_DOWN,
            "Custom order" to MenuIcon.REORDER, "Title" to MenuIcon.SORT_ALPHA, "Artist" to MenuIcon.SORT_ARTIST, "Recently added" to MenuIcon.SORT_RECENT,
            "Export to Music" to MenuIcon.EXPORT, "Export to Downloads" to MenuIcon.EXPORT, "Share\u2026" to MenuIcon.SHARE,
            "Delete from phone" to MenuIcon.DELETE,
            // video and movies
            "Recap so far" to MenuIcon.RECAP, "Translate subtitles to French (AI)" to MenuIcon.TRANSLATE, "Subtitles" to MenuIcon.SUBTITLES,
            "Mark the end of the intro here" to MenuIcon.MARK, "Autoplay next episode: on" to MenuIcon.AUTOPLAY,
            "Smart downloads: off" to MenuIcon.DOWNLOAD, "Off" to MenuIcon.SUBTITLES_OFF, "English" to MenuIcon.LANGUAGE,
            // gallery
            "Newest first" to MenuIcon.SORT, "Name A\u2013Z" to MenuIcon.SORT_ALPHA, "Largest first" to MenuIcon.SORT,
            "Trash" to MenuIcon.TRASH, "Backup plan" to MenuIcon.BACKUP, "Remove location when sharing: on" to MenuIcon.LOCATION_OFF,
            "Rename album" to MenuIcon.RENAME, "Delete album" to MenuIcon.DELETE,
            // browser
            "Picture grid" to MenuIcon.GRID, "Close all tabs" to MenuIcon.CLOSE, "New private tab" to MenuIcon.NEW_TAB,
            // the generic ones
            "Open" to MenuIcon.OPEN, "Edit" to MenuIcon.EDIT, "Pin" to MenuIcon.PIN, "Download" to MenuIcon.DOWNLOAD,
            "Info" to MenuIcon.INFO, "Settings" to MenuIcon.SETTINGS, "Sort" to MenuIcon.SORT,
        )
        for ((label, icon) in cases) assertEquals(label, icon, MenuIcons.forLabel(label))
    }

    @Test fun aTickOrAnEllipsisDoesNotChangeTheIconAndUnknownLabelsGetNone() {
        assertEquals(MenuIcon.FADE, MenuIcons.forLabel("Fade 3 s between songs  \u2713"))
        assertEquals(MenuIcon.SHARE, MenuIcons.forLabel("Share..."))
        assertEquals("repeat", MenuIcons.normalise("Repeat: OFF"))
        // whole words only: "autoplay next" is not "play next", "more like this" is the DJ, not Like
        assertEquals(MenuIcon.AUTOPLAY, MenuIcons.forLabel("Autoplay next episode: off"))
        assertEquals(MenuIcon.DJ, MenuIcons.forLabel("More like this"))
        for (none in listOf("", "  ", "1.5x  \u2713", "Normal", "1080p")) assertNull(none, MenuIcons.forLabel(none))
    }

    @Test fun settingsRowsAlwaysGetAnIcon() {
        assertEquals(MenuIcon.VOICE, MenuIcons.forSetting("Speak replies"))
        assertEquals(MenuIcon.MIC, MenuIcons.forSetting("Keep recordings"))          // a word's start is enough here
        assertEquals(MenuIcon.SHIELD, MenuIcons.forSetting("Scam Shield"))
        assertEquals(MenuIcon.LOCK, MenuIcons.forSetting("Lock ARGUS"))
        assertEquals(MenuIcon.VIBRATE, MenuIcons.forSetting("Haptic ticks"))
        assertEquals(MenuIcon.CALL, MenuIcons.forSetting("\u201cBye\u201d hangs up"))
        assertEquals(MenuIcon.WIFI, MenuIcons.forSetting("Share on Wi-Fi"))
        assertEquals(MenuIcon.TUNE, MenuIcons.forSetting("Something new"))
    }

    @Test fun answersAreCachedAndStayTheSame() {
        // 0.52.4 CI fix: a label or title is matched once; asking again gives the same answer, also for "no icon"
        for (l in listOf("Rename", "Normal", "Repeat: off", "Share\u2026")) assertEquals(l, MenuIcons.forLabel(l), MenuIcons.forLabel(l))
        assertNull(MenuIcons.forLabel("1080p")); assertNull(MenuIcons.forLabel("1080p"))
        for (t in listOf("Speak replies", "Something new")) assertEquals(t, MenuIcons.forSetting(t), MenuIcons.forSetting(t))
    }

    @Test fun nothingIsBuiltAtStartOnTheMainThread() {
        // 0.52.4 CI fix (low-RAM smoke): the rules are lazy, the regexes compiled once, and the warm-up runs off the main thread
        val m = src("ui/MenuIcons.kt")
        assertTrue("private val RULES: List<Pair<MenuIcon, List<String>>> by lazy {" in m)
        assertTrue("private val SETTING_RULES: List<Pair<MenuIcon, List<String>>> by lazy {" in m)
        val norm = m.substringAfter("internal fun normalise(").substringBefore("\n\n")
        assertTrue("no regex compiled per label", "Regex(" !in norm)
        val app = src("BlazeApp.kt").substringAfter("StartLater.add(\"ui.MenuIcons.warm\")").take(300)
        assertTrue("Thread(" in app && "MenuIcons.warm()" in app && "THREAD_PRIORITY_BACKGROUND" in app)
        // the first-run welcome is drawn alone in the first frame; ChatScreen composes from the second
        assertTrue("FirstRunGate(coldStart) { ChatScreen() }" in src("MainActivity.kt"))
        assertTrue("fun FirstRunGate(coldStart: Boolean, content: @Composable () -> Unit)" in src("ui/space/SpaceOnboarding.kt"))
    }

    @Test fun thePlusMenuShowsTheModeItIsIn() {
        assertEquals(MenuIcon.CAMERA, plusIcon("camera"))
        assertEquals(MenuIcon.AUTOPLAY_MODE, plusIcon("approval", on = true))
        assertEquals(MenuIcon.SHIELD, plusIcon("approval", on = false))
        // every row plusItems can show has its own icon; none falls back to +
        for (it in plusItems("plan", "autopilot", "", hasClipImage = true, inProject = true)) assertTrue(it.id, plusIcon(it.id, it.on) != MenuIcon.ADD)
    }

    @Test fun theMenuCardAndTheBubbleMenuDrawThem() {
        val kit = src("ui/Kit.kt").substringAfter("fun BlazeMenuCard(").substringBefore("\n}\n")
        assertTrue("MenuIcons.forLabel(" in kit && "LeadIcon(icon.vector()" in kit)
        val bubble = src("ui/BubbleMenu.kt")
        assertTrue("MenuIcons.forLabel(label)" in bubble && "leadingIcon =" in bubble)
        // one style for every icon tile and row icon
        val theme = src("ui/Theme.kt")
        assertTrue("object BlazeIconStyle" in theme)
        assertTrue("BlazeIconStyle.tile" in src("ui/IconKit.kt"))
    }
}
