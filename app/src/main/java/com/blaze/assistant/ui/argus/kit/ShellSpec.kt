package com.blaze.assistant.ui.argus.kit

/**
 * P1-SH-01: the shell's rules (04 §2.8 Chrome 1–4, §3.2), pure so ShellSpecTest checks them on the JVM. The composables
 * that draw them are in KitShell.kt.
 */
object ShellSpec {
    /** 04 §2.8 #1: the Home top bar is 62 dp under the status bar. */
    const val TOP_BAR_DP = 62

    /** The four modes, in notch and pager order (ChatNav.MODES). */
    val MODES = listOf("team", "chat", "life", "desk")
    val LABELS = mapOf("team" to "Team", "chat" to "Chat", "life" to "Life", "desk" to "Desk")

    fun label(mode: String): String = LABELS[mode] ?: mode.replaceFirstChar { it.uppercase() }

    // ---------------------------------------------------------------- Composer (04 §2.8 #3)
    object Composer {
        const val HEIGHT = 58
        const val SIDE = 12
        const val PLUS = 42
        const val MAX_LINES = 5
        /** Corner radius: half of [HEIGHT], so one line is a pill and more lines are a rounded box with the buttons inside it. */
        const val CORNER = HEIGHT / 2

        enum class Trailing { MIC, SEND, STOP }

        /**
         * Mic while empty, send ↑ once there is text or an attachment, stop ■ while a reply streams and nothing is typed.
         * Typing while it streams shows send again: a message sent then joins the running turn (AM v0.30.6).
         */
        fun trailing(text: String, attachments: Int, streaming: Boolean): Trailing = when {
            text.isNotBlank() || attachments > 0 -> Trailing.SEND
            streaming -> Trailing.STOP
            else -> Trailing.MIC
        }

        /** The field's placeholder per mode (04 §3.2 "Composer target"). */
        fun placeholder(mode: String): String = when (mode) {
            "team" -> "Tell the team\u2026"
            "life" -> "Ask about your life\u2026"
            "desk" -> "Add a job, routine or file\u2026"
            else -> "Ask anything"
        }

        fun trailingLabel(t: Trailing): String = when (t) { Trailing.MIC -> "Talk"; Trailing.SEND -> "Send"; Trailing.STOP -> "Stop" }
        fun trailingIcon(t: Trailing): String = when (t) { Trailing.MIC -> "mic"; Trailing.SEND -> "arrow-up"; Trailing.STOP -> "square" }
    }

    // ---------------------------------------------------------------- Notch (04 §2.8 #2)
    object Notch {
        const val HANDLE_W = 46
        const val HANDLE_H = 22
        /** A pull this far down (dp) opens the pill. */
        const val OPEN_AT = 24f
        /** Pulling on past this (dp) is Voice ("pull-further = Voice", 06 P1-SH-01). */
        const val VOICE_AT = 140f

        enum class Pull { NONE, OPEN, VOICE }

        fun pull(downDp: Float): Pull = when {
            downDp >= VOICE_AT -> Pull.VOICE
            downDp >= OPEN_AT -> Pull.OPEN
            else -> Pull.NONE
        }
    }

    // ---------------------------------------------------------------- ChipsRow (04 §2.8 #4)
    data class ShellChip(val key: String, val label: String, val icon: String)

    object Chips {
        /** The chips above the composer per mode (04 §4.1 Team variant); the other modes get theirs with their tickets. */
        fun of(mode: String): List<ShellChip> = when (mode) {
            "team" -> listOf(ShellChip("job", "Add job", "plus"), ShellChip("memory", "Memory", "brain"), ShellChip("routines", "Routines", "repeat"))
            else -> emptyList()
        }

        /** Hidden while attachments are pending (the attachment bar takes the slot) or a sheet is open. */
        fun visible(attachments: Int, sheetOpen: Boolean): Boolean = attachments == 0 && !sheetOpen
    }

    // ---------------------------------------------------------------- Toast
    object Toast {
        const val SHORT_MS = 2_500L
        const val LONG_MS = 4_000L
        /** Long text stays longer: about a reading speed of 15 characters a second, between the two. */
        fun durationMs(text: String): Long = (1_000L + text.length * 66L).coerceIn(SHORT_MS, LONG_MS)
    }
}
