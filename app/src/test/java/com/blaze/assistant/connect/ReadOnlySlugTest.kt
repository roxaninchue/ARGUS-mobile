package com.blaze.assistant.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.blaze.assistant.connect.ConnectRisk.Action as A
import com.blaze.assistant.connect.ConnectRisk.Rule as R

/** P4-CN-03 (BH-24): Bot HQ composio.ts isReadOnlySlug cases, mapped to READ, and the ActionClass of everything else. */
class ReadOnlySlugTest {
    @Test fun botHqReadSlugs() {
        listOf("GMAIL_FETCH_EMAILS", "GITHUB_LIST_REPOSITORIES", "GOOGLECALENDAR_FIND_EVENT", "NOTION_SEARCH_NOTION_PAGE",
            "SLACK_LIST_ALL_CHANNELS", "GOOGLEDRIVE_GET_FILE_METADATA", "HUBSPOT_RETRIEVE_CONTACT", "LINEAR_QUERY_ISSUES",
            "STRIPE_LOOKUP_CUSTOMER", "GITHUB_CHECK_RUN", "AWS_DESCRIBE_INSTANCES", "TRELLO_VIEW_BOARD", "JIRA_COUNT_ISSUES",
            "gmail_read_message", "github_list_repos").forEach { assertTrue(it, ConnectRisk.looksReadOnly(it)) }
    }

    @Test fun botHqWriteSlugs() {
        listOf("GMAIL_SEND_EMAIL", "GITHUB_CREATE_AN_ISSUE", "SLACK_SENDS_A_MESSAGE_TO_A_SLACK_CHANNEL", "NOTION_ADD_PAGE_CONTENT",
            "GOOGLECALENDAR_CREATE_EVENT", "GMAIL_DELETE_MESSAGE", "COMPOSIO_EXECUTE", "github_create_issue", "GETAWAY_BOOK")
            .forEach { assertFalse(it, ConnectRisk.looksReadOnly(it)) }
    }

    @Test fun readVerbWithAWriteVerbIsNotRead() {
        assertFalse(ConnectRisk.looksReadOnly("GMAIL_LIST_AND_DELETE_SPAM"))
        assertFalse(ConnectRisk.looksReadOnly("SLACK_FIND_AND_SEND_MESSAGE"))
        assertFalse(ConnectRisk.looksReadOnly("STRIPE_LIST_THEN_PAY_INVOICE"))
    }

    @Test fun slugNormalises() {
        assertEquals("_GMAIL_SEND_EMAIL_", ConnectRisk.slug("gmail_send_email"))
        assertEquals("_GMAIL_SEND_EMAIL_", ConnectRisk.slug("gmailSendEmail"))
        assertEquals("_NOTION_GET_PAGE_", ConnectRisk.slug("notion.get-page"))
    }

    @Test fun actionClasses() {
        assertEquals(ConnectRisk.Action.READ, ConnectRisk.classify("github_list_repos"))
        assertEquals(ConnectRisk.Action.READ, ConnectRisk.classify("whatever_tool", readOnly = true))
        assertEquals(ConnectRisk.Action.WRITE_REMOTE, ConnectRisk.classify("github_list_repos", readOnly = false))   // the catalog wins
        assertEquals(ConnectRisk.Action.SEND, ConnectRisk.classify("gmail_send_email"))
        assertEquals(ConnectRisk.Action.SEND, ConnectRisk.classify("slack_post_message"))
        assertEquals(ConnectRisk.Action.DELETE, ConnectRisk.classify("gmail_delete_message"))
        assertEquals(ConnectRisk.Action.DELETE, ConnectRisk.classify("files_upload", destructive = true))
        assertEquals(ConnectRisk.Action.SPEND, ConnectRisk.classify("stripe_create_payment"))
        assertEquals(ConnectRisk.Action.CREDENTIAL, ConnectRisk.classify("github_create_token"))
        assertEquals(ConnectRisk.Action.SETTINGS, ConnectRisk.classify("drive_update_sharing"))
        assertEquals(ConnectRisk.Action.WRITE_REMOTE, ConnectRisk.classify("notion_create_page"))
    }

    @Test fun cards() {
        assertFalse(ConnectRisk.needsCard(A.READ, null))
        assertFalse(ConnectRisk.needsCard(A.READ, null, tainted = true))
        assertTrue(ConnectRisk.needsCard(A.WRITE_REMOTE, null))
        assertTrue(ConnectRisk.needsCard(A.WRITE_REMOTE, R.ASK))
        assertFalse(ConnectRisk.needsCard(A.WRITE_REMOTE, R.ALLOW))
        assertTrue(ConnectRisk.needsCard(A.WRITE_REMOTE, R.ALLOW, tainted = true))   // K6: after reading mail/web, ask
        assertFalse(ConnectRisk.needsCard(A.SEND, R.ALLOW))
        listOf(A.SPEND, A.DELETE, A.CREDENTIAL).forEach { assertTrue(it.name, ConnectRisk.needsCard(it, R.ALLOW)) }
        assertEquals(R.ALLOW, R.of("always")); assertEquals(R.NEVER, R.of("off")); assertEquals(R.ASK, R.of(" Ask ")); assertEquals(null, R.of("maybe"))
        assertEquals("always", R.ALLOW.wire())
    }

    @Test fun actionNamesMatchThePolicyEnum() {
        // 05 §9.1 enum class ActionClass, in order: P2-SF-01 maps by name.
        assertEquals(listOf("READ", "WRITE_LOCAL", "WRITE_REMOTE", "SEND", "SPEND", "DELETE", "SETTINGS", "CREDENTIAL", "COMPUTE_PAID", "PHYSICAL"),
            ConnectRisk.Action.values().map { it.name })
    }
}
