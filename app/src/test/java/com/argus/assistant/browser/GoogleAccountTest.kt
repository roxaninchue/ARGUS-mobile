package com.blaze.assistant.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleAccountTest {
    @Test fun knowsGoogleSites() {
        assertTrue(GoogleAccount.isGoogle("https://classroom.google.com/u/1/c/abc"))
        assertTrue(GoogleAccount.isGoogle("https://docs.google.com/document/d/x/edit"))
        assertTrue(GoogleAccount.isGoogle("https://google.com/"))
        assertFalse(GoogleAccount.isGoogle("https://accounts.google.com/ServiceLogin"))
        assertFalse(GoogleAccount.isGoogle("https://evilgoogle.com/u/1/"))
        assertFalse(GoogleAccount.isGoogle("https://google.com.evil.io/"))
        assertFalse(GoogleAccount.isGoogle("http://classroom.google.com/"))
        assertFalse(GoogleAccount.isGoogle("https://example.com/?next=https://classroom.google.com/u/1/"))
    }

    @Test fun readsTheAccount() {
        assertEquals(1, GoogleAccount.accountOf("https://classroom.google.com/u/1/c/abc"))
        assertEquals(2, GoogleAccount.accountOf("https://mail.google.com/mail/u/2/#inbox"))
        assertEquals(3, GoogleAccount.accountOf("https://docs.google.com/document/d/x/edit?usp=sharing&authuser=3"))
        assertEquals(0, GoogleAccount.accountOf("https://drive.google.com/drive/u/0/my-drive"))
        assertNull(GoogleAccount.accountOf("https://docs.google.com/document/d/x/edit"))
        assertNull(GoogleAccount.accountOf("https://example.com/u/1/"))
        // /u/ in the query or fragment is not the account.
        assertNull(GoogleAccount.accountOf("https://docs.google.com/x?next=/u/4/"))
    }

    @Test fun addsOrSwitchesTheAccount() {
        assertEquals("https://docs.google.com/document/d/x/edit?authuser=1", GoogleAccount.withAccount("https://docs.google.com/document/d/x/edit", 1))
        assertEquals("https://docs.google.com/d/x?usp=sharing&authuser=1#h", GoogleAccount.withAccount("https://docs.google.com/d/x?usp=sharing#h", 1))
        assertEquals("https://docs.google.com/d/x?authuser=2&usp=s", GoogleAccount.withAccount("https://docs.google.com/d/x?authuser=0&usp=s", 2))
        assertEquals("https://classroom.google.com/u/2/c/abc", GoogleAccount.withAccount("https://classroom.google.com/u/0/c/abc", 2))
        assertEquals("https://mail.google.com/mail/u/1/#inbox", GoogleAccount.withAccount("https://mail.google.com/mail/u/0/#inbox", 1))
        assertEquals("https://example.com/a", GoogleAccount.withAccount("https://example.com/a", 1))
    }

    @Test fun carriesTheAccountOnLinkTaps() {
        val from = "https://classroom.google.com/u/1/c/NzA/m/1"
        assertEquals("https://docs.google.com/document/d/x/edit?authuser=1", GoogleAccount.carry(from, "https://docs.google.com/document/d/x/edit"))
        // Already names an account, account 0, off Google, or from a non-Google page: unchanged.
        assertNull(GoogleAccount.carry(from, "https://docs.google.com/d/x?authuser=0"))
        assertNull(GoogleAccount.carry("https://classroom.google.com/u/0/c/x", "https://docs.google.com/d/x"))
        assertNull(GoogleAccount.carry(from, "https://example.com/"))
        assertNull(GoogleAccount.carry("https://example.com/u/1/", "https://docs.google.com/d/x"))
        assertNull(GoogleAccount.carry(null, "https://docs.google.com/d/x"))
        // Loading the carried URL never carries again (no loop).
        val once = GoogleAccount.carry(from, "https://drive.google.com/file/d/y/view")!!
        assertNull(GoogleAccount.carry(from, once))
        assertEquals("Account 2", GoogleAccount.label(1))
    }

    @Test fun agentLinksUseTheLastAccount() {
        val keep = GoogleAccount.last
        try {
            GoogleAccount.last = 0
            assertEquals("https://docs.google.com/d/x", GoogleAccount.forAgent("https://docs.google.com/d/x"))
            assertTrue(GoogleAccount.seen("https://classroom.google.com/u/2/c/abc"))
            assertFalse(GoogleAccount.seen("https://classroom.google.com/u/2/h"))
            assertFalse(GoogleAccount.seen("https://example.com/u/5/"))
            assertEquals(2, GoogleAccount.last)
            assertEquals("https://docs.google.com/d/x?authuser=2", GoogleAccount.forAgent("https://docs.google.com/d/x"))
            assertEquals("https://docs.google.com/d/x?authuser=0", GoogleAccount.forAgent("https://docs.google.com/d/x?authuser=0"))
            assertEquals("https://example.com/", GoogleAccount.forAgent("https://example.com/"))
            // Back in account 0: agent links stop carrying.
            assertTrue(GoogleAccount.seen("https://mail.google.com/mail/u/0/#inbox"))
            assertEquals("https://docs.google.com/d/x", GoogleAccount.forAgent("https://docs.google.com/d/x"))
        } finally { GoogleAccount.last = keep }
    }
}
