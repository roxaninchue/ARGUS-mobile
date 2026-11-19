package com.blaze.assistant.team

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blaze.assistant.agent.Settings
import com.blaze.assistant.agent.Specialists
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * P1-TM-01 acceptance, moved out of TeamStoreTest in the 0.53.0 build round: [Specialists.tool] and [Specialists.byId]
 * read the user's custom specialists from [Settings], which needs a Context, so on a plain JVM the test died with
 * "lateinit property prefs has not been initialized" (CI run #44). Same assertions, on Robolectric with Settings set up.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = android.app.Application::class)
class SpecialistsDelegateTest {
    @Before fun initSettings() { Settings.init(ApplicationProvider.getApplicationContext()) }

    /** Acceptance "delegate to specialists still works": the delegate tool still names every built-in by its plain id, byId finds each, filter still scopes tools by group. */
    @Test fun delegateToSpecialistsStillWorks() {
        val ids = listOf("researcher", "operator", "writer", "planner", "coder", "inbox", "shopper", "booker", "tutor", "designer")
        assertEquals(ids, Specialists.builtIn.map { it.id })
        val fn = Specialists.tool().getJSONObject("function")
        assertEquals("delegate", fn.getString("name"))
        for (s in Specialists.builtIn) {
            assertTrue(fn.getString("description").contains("${s.id} (${s.name})"))
            assertEquals(s, Specialists.byId(s.id))
        }
        val all = org.json.JSONArray(listOf("web_search", "delegate", "set_alarm").map { n ->
            org.json.JSONObject().put("type", "function").put("function", org.json.JSONObject().put("name", n)) })
        val coder = Specialists.filter(all, Specialists.byId("coder")!!)
        assertEquals(listOf("web_search"), (0 until coder.length()).map { coder.getJSONObject(it).getJSONObject("function").getString("name") })   // web yes, phone no, never delegate
        val op = Specialists.filter(all, Specialists.byId("operator")!!)
        assertEquals(listOf("set_alarm"), (0 until op.length()).map { op.getJSONObject(it).getJSONObject("function").getString("name") })
    }
}
