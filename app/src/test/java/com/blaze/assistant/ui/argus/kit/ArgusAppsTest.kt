package com.blaze.assistant.ui.argus.kit

import com.blaze.assistant.ui.argus.icons.ArgusGlyphs
import com.blaze.assistant.ui.argus.icons.LucideSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2026-11-17: Home's Apps opens ARGUS's own apps, media first, each with a real icon. */
class ArgusAppsTest {
    @Test fun mediaComeFirstAndEveryAppIsAView() {
        val ids = ArgusApps.all.map { it.view }
        assertEquals(listOf("music", "video"), ids.take(2))
        assertTrue(ids.containsAll(listOf("browser", "phonebrowser", "gallery", "design", "terminal", "apps")))
        assertEquals(12, ids.size)
    }

    @Test fun everyIconIsDrawnNotTheFallbackSquare() {
        for (a in ArgusApps.all) assertTrue(a.icon, a.icon in ArgusGlyphs.PATHS || LucideSpec.resolve(a.icon) != "square")
    }

    @Test fun fourToARow() {
        val rows = ArgusApps.rows()
        assertTrue(rows.all { it.size <= ArgusApps.COLUMNS })
        assertEquals(ArgusApps.all.size, rows.sumOf { it.size })
    }
}
