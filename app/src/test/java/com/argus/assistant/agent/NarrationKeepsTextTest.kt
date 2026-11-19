package com.argus.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NarrationKeepsTextTest {
    @Test fun keepsPathsAndEverySentence() {
        val t = "I found your music files in `/storage/emulated/0/Music` — mostly m4a, opus, and a few mp3. Next I'll build the player."
        assertEquals("I found your music files in /storage/emulated/0/Music — mostly m4a, opus, and a few mp3. Next I'll build the player.", Friendly.narration(t))
    }

    @Test fun dropsCodeBlocks() {
        val out = Friendly.narration("Writing the server.\n```python\nprint(1)\n```\nDone.")
        assertTrue(!out.contains("print(1)") && out.contains("Writing the server.") && out.contains("Done."))
    }
}
