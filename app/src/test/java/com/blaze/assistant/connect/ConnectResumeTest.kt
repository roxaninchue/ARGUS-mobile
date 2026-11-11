package com.blaze.assistant.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.blaze.assistant.connect.ConnectReturn.Result as R

/** P4-CN-01/04: the argus-connect OAuth return on the loopback. */
class ConnectResumeTest {
    @Test fun okAndError() {
        assertEquals(R.Ok, ConnectReturn.parse("GET /connected?app=github&status=ok HTTP/1.1", "github"))
        assertEquals(R.Failed, ConnectReturn.parse("GET /connected?app=github&status=error HTTP/1.1", "github"))
        assertEquals(R.Ok, ConnectReturn.parse("GET /connected?status=ok HTTP/1.1", "github"))
    }

    @Test fun wrongAppOrGarbage() {
        assertEquals(R.WrongApp, ConnectReturn.parse("GET /connected?app=slack&status=ok HTTP/1.1", "github"))
        assertEquals(R.Garbled, ConnectReturn.parse("GET /favicon.ico HTTP/1.1", "github"))
        assertEquals(R.Garbled, ConnectReturn.parse("", "github"))
        assertEquals(R.Garbled, ConnectReturn.parse("GET /connected?app=github HTTP/1.1", "github"))
    }

    @Test fun encodedQuery() {
        assertEquals(mapOf("app" to "google calendar", "status" to "ok"), ConnectReturn.query("app=google%20calendar&status=ok&junk"))
    }

    @Test fun wordsAndPage() {
        assertEquals("Connected", ConnectReturn.message(R.Ok))
        R.values().forEach { assertTrue(ConnectReturn.message(it).isNotBlank()) }
        assertTrue(ConnectReturn.page(R.Ok, "ARGUS").contains("Connected"))
        assertTrue(ConnectReturn.page(R.Failed, "<b>").contains("&lt;b&gt;"))
    }
}
