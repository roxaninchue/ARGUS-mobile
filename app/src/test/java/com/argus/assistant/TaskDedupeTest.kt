package com.argus.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaskDedupeTest {
    private val main = "com.argus.assistant.MainActivity"

    @Test fun keepsTheOlderChatTask() {
        val rows = listOf(TaskDedupe.Row(12, main), TaskDedupe.Row(7, main))
        assertEquals(7, TaskDedupe.keep(12, rows, main))
    }

    @Test fun aloneOrOtherActivitiesMeansKeepThisOne() {
        assertNull(TaskDedupe.keep(12, listOf(TaskDedupe.Row(12, main)), main))
        assertNull(TaskDedupe.keep(12, listOf(TaskDedupe.Row(12, main), TaskDedupe.Row(9, "com.argus.assistant.browser.BrowserActivity")), main))
        assertNull(TaskDedupe.keep(12, listOf(TaskDedupe.Row(-1, main)), main))
        assertNull(TaskDedupe.keep(12, emptyList(), main))
    }
}
