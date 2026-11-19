package com.blaze.assistant.agent

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-SF-01: every tool the agent really builds on the JVM (ToolPickSizeTest's list: the agent's own tool sources minus
 * the few that need an Android Context) has a declared ActionClass. The source scan in policy/ToolRegistryTest covers
 * the rest, including the tools that need a Context.
 */
class ToolRegistryCatalogTest {
    @Test fun everyBuiltToolHasAnActionClass() {
        val (tools, _) = ToolPickSizeTest().all()
        assertTrue("tools built (${tools.length()})", tools.length() > 50)
        val missing = ToolRouter.undeclared(tools)
        assertTrue("tools without an ActionClass (policy/ActionClass.kt ToolRegistry): $missing", missing.isEmpty())
    }
}
