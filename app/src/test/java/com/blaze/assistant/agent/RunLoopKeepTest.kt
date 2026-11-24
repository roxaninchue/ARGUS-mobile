package com.blaze.assistant.agent

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.55.16: Finn's crash report (24 Nov 11:03, Android 12) was a VerifyError on Agent.Session.runLoop after R8 inlined its
 * local functions into it. The keep rule that stops that inlining must stay in proguard-rules.pro.
 */
class RunLoopKeepTest {
    @Test fun runLoopLocalFunctionsStayOutOfRunLoop() {
        val rules = listOf(File("proguard-rules.pro"), File("app/proguard-rules.pro")).first { it.isFile }.readText()
        assertTrue(rules.contains("class com.blaze.assistant.agent.Agent\$Session"))
        assertTrue(rules.contains("*** runLoop\$*(...);"))
    }
}
