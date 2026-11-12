package com.blaze.assistant.ui.team

import com.blaze.assistant.agent.ChatNav
import com.blaze.assistant.ui.argus.kit.ChipModel

/**
 * P1-TM-07: Desk v1 (04 §3.1 / §4.7, A-team jobs page + Bot HQ tasks / routines / library), the pure part DeskModelTest
 * runs on the JVM. Desk is the ARGUS shell's fourth mode: a scrolling row of segments (Inbox · Jobs · Routines · Library ·
 * Projects · Computers) over one page each. Compose ([DeskPage]) only draws what this says.
 *
 *  - Inbox is a placeholder until P2 (approvals, questions, connects and failed jobs collect there); it carries a count
 *    of what waits on the user today (a question or a confirm in the chat) and a button to Chat, where those are answered.
 *  - Jobs is [JobsPageModel] (filters, search, FAB, the job sheet).
 *  - Routines opens AM's SchedulePage (the "schedule" view); Routines of their own come with P6.
 *  - Library links to AM's Gallery and the phone's Downloads (Download/Blaze is where save_file puts files) until the
 *    Bot HQ library (P4).
 *  - Projects is AM's Projects list, moved here from over the pager ([DeskRoute]); a project page and a project chat
 *    open as before.
 *  - Computers: Terminal (Alpine / phone shell) and Browser entries until the computer viewer (P7).
 */
object DeskModel {
    enum class Segment(val key: String, val label: String, val icon: String) {
        INBOX("inbox", "Inbox", "bell"),
        JOBS("jobs", "Jobs", "list-checks"),
        ROUTINES("routines", "Routines", "repeat"),
        LIBRARY("library", "Library", "library"),
        PROJECTS("projects", "Projects", "layers"),
        COMPUTERS("computers", "Computers", "monitor");

        companion object {
            fun of(key: String?): Segment? = entries.firstOrNull { it.key == key?.trim()?.lowercase() }
        }
    }

    /** Desk opens on Jobs (the inbox is empty until P2). */
    val DEFAULT = Segment.JOBS

    /**
     * The segment chips with their counts: Inbox = what waits on the user, Jobs = jobs that need them ([JobsFilter.NEEDS_YOU]),
     * Projects = how many there are. Zero shows no count.
     */
    fun chips(inboxWaiting: Int, jobsNeedYou: Int, projects: Int): List<ChipModel> = Segment.entries.map { s ->
        val n = when (s) { Segment.INBOX -> inboxWaiting; Segment.JOBS -> jobsNeedYou; Segment.PROJECTS -> projects; else -> 0 }
        ChipModel(s.key, s.label, n.takeIf { it > 0 })
    }

    /** One link row on a Desk page (Library, Computers, Routines). [target] is what the shell opens. */
    data class Link(val target: String, val icon: String, val title: String, val sub: String)

    object Targets {
        const val SCHEDULE = "schedule"
        const val GALLERY = "gallery"
        const val DOWNLOADS = "downloads"
        const val TERMINAL = "terminal"
        const val BROWSER = "browser"
        const val CHAT = "chat"
    }

    val ROUTINES: List<Link> = listOf(
        Link(Targets.SCHEDULE, "calendar-clock", "Schedule", "Routines, reminders, alarms and timed commands"),
    )

    val LIBRARY: List<Link> = listOf(
        Link(Targets.GALLERY, "palette", "Gallery", "Images, music, videos and files your team made"),
        Link(Targets.DOWNLOADS, "download", "Downloads", "Files saved to the phone (Download/Blaze)"),
    )

    val COMPUTERS: List<Link> = listOf(
        Link(Targets.TERMINAL, "square-terminal", "Terminal", "Alpine Linux, or the phone shell"),
        Link(Targets.BROWSER, "globe", "Browser", "The team's own browser and its tabs"),
    )

    /** A page's links, empty for the pages that draw their own content. */
    fun links(s: Segment): List<Link> = when (s) {
        Segment.ROUTINES -> ROUTINES
        Segment.LIBRARY -> LIBRARY
        Segment.COMPUTERS -> COMPUTERS
        else -> emptyList()
    }

    /** The line under a link page's title. */
    fun intro(s: Segment): String = when (s) {
        Segment.INBOX -> "Approvals, questions and jobs that failed will collect here."
        Segment.ROUTINES -> "What runs on a schedule. Routines per bot come later; for now they live in Schedule."
        Segment.LIBRARY -> "Everything your team saved. A library per bot comes later."
        Segment.COMPUTERS -> "Where your team works: a Linux terminal and a browser."
        else -> ""
    }

    /** The Inbox placeholder: its body, and whether Chat has something to answer now. */
    fun inboxBody(waiting: Int): String =
        if (waiting > 0) (if (waiting == 1) "Something waits for your answer in Chat." else "$waiting things wait for your answer in Chat.")
        else "Nothing waits on you. " + intro(Segment.INBOX)
}

/**
 * P1-TM-07: Projects moved from over the pager into Desk (ARGUS shell only). [ChatNav] is unchanged, so every route that
 * opened the Projects list before (a /projects or open_view, Life's Projects, Back out of a project page, a deleted
 * project) still makes the list, and the shell shows Desk on its Projects segment instead. A project page and a
 * project chat are not the list and still show over the pager, as before. The 0.52 shell (argus off) is untouched.
 */
object DeskRoute {
    /** True when [nav] is the bare Projects list in the ARGUS shell, which Desk now shows. */
    fun isProjectsList(nav: ChatNav, argus: Boolean): Boolean = argus && nav.tab == ChatNav.PROJECTS && nav.project.isBlank()

    /** Where the shell really is: the Projects list is Desk; everything else as it is. */
    fun shown(nav: ChatNav, argus: Boolean): ChatNav = if (isProjectsList(nav, argus)) ChatNav(ChatNav.DESK) else nav

    /** The Desk segment to show for [nav] (Projects when it came from the Projects list), or null to keep the current one. */
    fun segment(nav: ChatNav, argus: Boolean): DeskModel.Segment? = if (isProjectsList(nav, argus)) DeskModel.Segment.PROJECTS else null
}
