package com.blaze.assistant.agent

import com.blaze.assistant.agent.StepTool.Kind
import com.blaze.assistant.ui.argus.icons.LucidePaths
import com.blaze.assistant.ui.argus.icons.LucideSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Milestone 4: the label table uses the app's real tool names (orchestra/ToolScheduler.Resources), sends and posts are
 * Messages, alarms / timers / calendar changes are Schedule, and only real file and content edits are Edits.
 */
class StepToolLabelsTest {
    /** name → (kind, present tense, past tense). */
    private val table = listOf(
        Triple("file_edit", Kind.EDIT, "Editing a file" to "Edited a file"),
        Triple("file_write", Kind.EDIT, "Writing a file" to "Wrote a file"),
        Triple("file_delete", Kind.EDIT, "Deleting a file" to "Deleted a file"),
        Triple("save_file", Kind.EDIT, "Saving a file" to "Saved a file"),
        Triple("file_read", Kind.READ, "Reading a file" to "Read a file"),
        Triple("file_list", Kind.READ, "Listing files" to "Listed files"),
        Triple("file_search", Kind.READ, "Searching files" to "Searched files"),
        Triple("run_shell", Kind.COMMAND, "Running a command" to "Ran a command"),
        Triple("termux_run", Kind.COMMAND, "Running a command" to "Ran a command"),
        Triple("run_code", Kind.COMMAND, "Running code" to "Ran code"),
        Triple("send_sms", Kind.MESSAGES, "Sending a text" to "Sent a text"),
        Triple("send_text", Kind.MESSAGES, "Sending a text" to "Sent a text"),
        Triple("gmail_send", Kind.MESSAGES, "Sending an email" to "Sent an email"),
        Triple("slack_post", Kind.MESSAGES, "Posting a message" to "Posted a message"),
        Triple("set_alarm", Kind.SCHEDULE, "Setting an alarm" to "Set an alarm"),
        Triple("set_timer", Kind.SCHEDULE, "Setting a timer" to "Set a timer"),
        Triple("add_calendar_event", Kind.SCHEDULE, "Adding to your calendar" to "Added to your calendar"),
        Triple("gcal_events", Kind.READ, "Checking your calendar" to "Checked your calendar"),
        Triple("web_search", Kind.BROWSER, "Searching the web" to "Searched the web"),
        Triple("memory_save", Kind.MEMORY, "Saving to memory" to "Saved to memory"),
    )

    @Test fun realToolNamesHaveTheirOwnLabels() {
        for ((n, kind, words) in table) {
            val l = StepTool.label(n)
            assertEquals(n, kind, l.kind); assertEquals(n, words.first, l.now); assertEquals(n, words.second, l.done)
        }
    }

    @Test fun theOldMadeUpNamesAreGone() {
        for (n in listOf("edit_file", "write_file", "read_file", "terminal", "run_command")) assertFalse(n, n in StepTool.knownNames)
        for (n in listOf("file_edit", "file_write", "file_read", "file_list", "file_search", "file_delete", "run_shell", "run_code", "termux_run"))
            assertTrue(n, n in StepTool.knownNames)
    }

    @Test fun sendsAndSchedulesAreNeverEdits() {
        // word fallbacks for names outside the table (connectors, MCP tools)
        val messages = listOf("gmail_send_email", "outlook_send_mail", "whatsapp_send_message", "slack_post_message", "telegram_reply", "share_link", "post_tweet", "compose_email")
        for (n in messages) assertEquals(n, Kind.MESSAGES, StepTool.label(n).kind)
        val schedule = listOf("googlecalendar_create_event", "add_event", "set_reminder", "create_alarm", "cancel_timer", "update_event", "delete_routine")
        for (n in schedule) assertEquals(n, Kind.SCHEDULE, StepTool.label(n).kind)
        // a read of mail or the calendar stays a read
        for (n in listOf("GOOGLECALENDAR_LIST_EVENTS", "gmail_read", "read_messages", "gmail_search", "outlook_get_mail", "list_routines"))
            assertEquals(n, Kind.READ, StepTool.label(n).kind)
        // settings are not edits either
        for (n in listOf("set_volume", "device_setting", "toggle_wifi")) assertNotEquals(n, Kind.EDIT, StepTool.label(n).kind)
        // real content edits stay edits
        for (n in listOf("notion_add_page", "add_contact", "edit_contact", "googledocs_update_doc", "drive_create_file", "update_brief"))
            assertEquals(n, Kind.EDIT, StepTool.label(n).kind)
    }

    @Test fun everyLabelIsShownSafeWithARealIcon() {
        val names = StepTool.knownNames + table.map { it.first } + listOf("outlook_send_mail", "add_event", "set_volume", "drive_create_file", "zz_q", "terminal_run", "composio_execute")
        for (n in names) {
            val l = StepTool.label(n)
            for (t in listOf(l.now, l.done)) {
                assertEquals("$n: '$t' passes the pill's check", t, StepPill.clean(t))
                assertFalse("$n leaks into '$t'", n.contains('_') && t.contains(n, ignoreCase = true))
            }
            assertTrue("$n icon ${l.icon}", LucideSpec.resolve(l.icon) == l.icon || LucidePaths.ALIASES.containsKey(l.icon))
            assertNotEquals("$n icon falls back", LucideSpec.FALLBACK, LucideSpec.resolve(l.icon))
        }
    }

    @Test fun kindCountsReadRightInOneAndMany() {
        for (k in Kind.entries) {
            assertFalse(k.name, k.one.isBlank() || k.many.isBlank() || k.group.isBlank())
            assertNotEquals("${k.name}: '1 ${k.one}' and '2 ${k.many}' differ", k.one, k.many)
        }
        assertEquals("edit" to "edits", Kind.EDIT.one to Kind.EDIT.many)
        assertEquals("Edits", Kind.EDIT.group); assertEquals("Messages", Kind.MESSAGES.group); assertEquals("Schedule", Kind.SCHEDULE.group)
    }
}
