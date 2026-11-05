package com.argus.assistant.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v0.44.0: tool schema size per turn, before (v0.42.0's ToolRouter diet) and after (ToolPick), over the tool
 * definitions that can be built on the JVM (the agent's own list, minus the few that need an Android Context).
 * The numbers go to app/build/toolpick-sizes.txt.
 */
class ToolPickSizeTest {
    internal fun all(): Pair<JSONArray, List<String>> {
        val t = JSONArray()
        val missed = ArrayList<String>()
        fun add(what: String, f: () -> Any) {
            try { when (val v = f()) { is JSONObject -> t.put(v); is List<*> -> v.forEach { t.put(it) } } } catch (e: Throwable) { missed += "$what (${e.javaClass.simpleName})" }
        }
        add("Memory") { Memory.tools() }
        add("DeviceStatus") { com.argus.assistant.device.DeviceStatus.tool() }
        add("Jamendo") { com.argus.assistant.mediaapps.store.JamendoSource.tool() }
        add("SelfControl") { SelfControl.tool() }
        add("Web") { Web.tools() }
        add("Media") { com.argus.assistant.media.Media.tools() }
        add("Processes") { Processes.tools() }
        add("Watchers") { com.argus.assistant.phone.Watchers.tool() }
        add("SkillPacks") { SkillPacks.tools() }
        add("AndroidControl") { com.argus.assistant.phone.AndroidControl.tools() }
        add("Autonomy") { listOf(Autonomy.findToolDef(), Autonomy.goalTool(), Autonomy.permissionTool()) }
        add("Triggers") { com.argus.assistant.proactive.Triggers.tools() }
        add("Ussd") { com.argus.assistant.phone.Ussd.tool() }
        add("Messaging") { com.argus.assistant.phone.Messaging.tool() }
        add("Browser") { com.argus.assistant.browser.BrowserTools.definitions() }
        add("PhoneBrowser") { com.argus.assistant.browser.phone.PhoneBrowserTools.definitions() }   // 0.45.19
        add("DevTools") { com.argus.assistant.terminal.DevTools.tools() }
        add("Git") { com.argus.assistant.terminal.Git.tool() }
        add("PimEdit") { com.argus.assistant.phone.PimEdit.tools() }
        add("SelfCheck") { com.argus.assistant.device.SelfCheck.tool() }
        add("Location") { com.argus.assistant.phone.Location.tool() }
        add("Specialists") { listOf(Specialists.tool(), Specialists.createTool()) }
        add("Learning") { Learning.tools() }
        add("Updater") { com.argus.assistant.update.Updater.tool() }
        add("Views") { com.argus.assistant.ui.Views.tool() }
        add("SelfKnowledge") { SelfKnowledge.tool() }
        add("Awareness") { Awareness.tool() }
        add("Jarvis") { com.argus.assistant.proactive.Jarvis.tool() }
        add("FollowUps") { com.argus.assistant.proactive.FollowUps.tool() }
        add("BlazeDocs") { BlazeDocs.tools() }
        add("ProjectDocs") { ProjectDocs.tools() }
        add("HomeTools") { com.argus.assistant.home.HomeTools.tools() }
        add("BlazeRouter") { BlazeRouter.tool() }
        add("CommandRegistry") { CommandRegistry.tool() }
        add("LongChat") { LongChat.tools() }
        add("Flashcards") { Flashcards.tool() }
        add("Designs") { Designs.tool() }
        add("Seller") { Seller.tool() }
        add("LifeLogs") { com.argus.assistant.life.LifeLogs.tool() }
        add("MediaFetch") { com.argus.assistant.mediaapps.MediaFetch.tool() }
        add("VideoTools") { com.argus.assistant.mediaapps.VideoTools.tool() }
        add("MusicTools") { com.argus.assistant.mediaapps.MusicTools.tool() }
        add("GalleryOps") { com.argus.assistant.media.GalleryOps.tool() }
        add("load_tools") { ToolPick.loadTool() }
        return t to missed
    }

    @Test fun measure() {
        val (tools, missed) = all()
        val prompts = listOf(
            "open whatsapp",
            "set an alarm for 6:30",
            "turn on wifi",
            "install python in the linux terminal",
            "text mum I'm running late",
            "search the web for the latest zesco load shedding schedule",
            "write a python script that renames my photos by date",
            "what did you say earlier about my rent?",
            "cancel my 7am alarm",
            "make me a poster for my shop",
        )
        val sb = StringBuilder("ToolPick sizes over ${tools.length()} JVM-buildable tools (${ToolPick.chars(tools)} chars of schemas); not built here: ${missed.joinToString().ifBlank { "none" }}\n")
        sb.append("prompt | v0.42 diet: tools / chars | v0.44 pick: tools / chars | saved\n")
        var b = 0; var a = 0
        for (p in prompts) {
            val g = ToolRouter.guess(p)
            val before = ToolRouter.selectDefault(tools, g)
            val after = ToolPick.pick(before, p, emptySet(), g)
            val cb = ToolPick.chars(before); val ca = ToolPick.chars(after)
            b += cb; a += ca
            sb.append("$p | ${before.length()} / $cb | ${after.length()} / $ca | ${(100 - ca * 100 / maxOf(1, cb))}%\n")
            assertTrue(after.length() <= before.length())
            assertTrue(after.length() <= ToolPick.MAX_TOOLS)
        }
        sb.append("total | $b | $a | ${100 - a * 100 / maxOf(1, b)}% (≈ ${(b - a) / 4} tokens less over ${prompts.size} first calls)\n")
        File("build").mkdirs()
        File("build/toolpick-sizes.txt").writeText(sb.toString())
        println(sb)
    }
}
