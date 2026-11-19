package com.blaze.assistant.brand

import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.53.2: brand marks drew wrong (Gemini a sliver, MiMo broken, DeepSeek with holes). Paths spell every number out and keep the SVG's fill rule. */
class BrandPathsTest {

    @Test fun everyNumberSpelledOut() {
        BrandPaths.ICONS.forEach { (b, ps) -> ps.forEach { p ->
            // tokens are single-space separated: commands, numbers, and arc flags each stand alone
            assertTrue("$b has a packed number run", p.d.split(' ').all { t -> t.matches(Regex("""[a-zA-Z]|[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?""")) })
        } }
    }

    @Test fun fillRulesFollowTheSvg() {
        assertTrue(BrandPaths.ICONS.getValue("deepseek").none { it.evenOdd })
        assertTrue(BrandPaths.ICONS.getValue("gemini").all { it.evenOdd })
    }
}
