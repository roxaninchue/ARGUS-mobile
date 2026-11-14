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

    fun label(mode: String): String = com.blaze.assistant.i18n.tr(LABELS[mode] ?: mode.replaceFirstChar { it.uppercase() })   // owner 2026-11-14: app language

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

        /**
         * Owner 2026-11-14: the chat field's hint rotates: "Let's {explore|discover|build|…}". One or two words stay put while
         * the word after them changes; now and then the lead changes too ("Organize my week", "Track my habits"). Pure and
         * reproducible from (step, seed). Only a fade, never a slide (the owner gets motion sick); with animations off the
         * field shows the static [placeholder]. Screen readers always hear the static placeholder.
         */
        /**
         * Owner 2026-11-14: the keyboard lifts the chat column (and the chatbox with it) only when it types into it.
         * Spotlight, chat search, the history drawer and Settings bring their own field, so the chatbox stays put under them.
         */
        fun liftsWithKeyboard(spotlight: Boolean, search: Boolean, drawer: Boolean, settings: Boolean): Boolean =
            !(spotlight || search || drawer || settings)

        object Hints {
            /** One change every this long, and the lead changes every [LEAD_EVERY] changes. */
            const val INTERVAL_MS = 3_200L
            const val LEAD_EVERY = 4
            /** The fade between two words. */
            const val FADE_MS = 600
            /** Each group's word count is never a multiple of [STRIDE], so stepping by it visits every word before repeating. */
            const val STRIDE = 3
            val GROUPS: List<Pair<String, List<String>>> = listOf(
                "Let's" to listOf("explore", "discover", "build", "arrange", "plan", "write", "learn", "fix", "research", "design"),
                "Help me" to listOf("focus", "decide", "unwind", "study", "prep for exams"),
                "Organize" to listOf("my week", "my notes", "my inbox", "my files"),
                "Track" to listOf("my habits", "my budget", "a package", "my goals"),
                "Monitor" to listOf("a price", "the news", "my spending", "a flight"),
            )

            data class Hint(val lead: String, val word: String) { val text: String get() = "$lead $word" }

            private fun mix(a: Int, b: Int): Int {   // splitmix64 of the pair
                var z = (a.toLong() shl 32) xor (b.toLong() and 0xffffffffL)
                z += -0x61c8864680b583ebL
                z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
                z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
                z = z xor (z ushr 31)
                return (z ushr 33).toInt()
            }

            /** The group for lead block [block]: "Let's" about half the time (it may stay on); another lead never twice in a row. */
            fun group(block: Int, seed: Int): Int {
                if (block <= 0) return 0
                val r = mix(block, seed)
                if ((r ushr 9) % 100 < 55) return 0
                val others = GROUPS.size - 1
                var g = 1 + (r ushr 3) % others
                if (g == group(block - 1, seed)) g = 1 + (g % others)
                return g
            }

            fun hint(step: Int, seed: Int): Hint {
                val st = step.coerceAtLeast(0)
                val block = st / LEAD_EVERY
                val (lead, words) = GROUPS[group(block, seed)]
                val i = (mix(seed, block) + (st % LEAD_EVERY) * STRIDE) % words.size
                return Hint(lead, words[i])
            }
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
