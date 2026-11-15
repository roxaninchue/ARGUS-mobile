package com.blaze.assistant.ui.living

import com.blaze.assistant.agent.ToolTrail
import com.blaze.assistant.ui.living.LivingModel.Kind
import com.blaze.assistant.ui.living.LivingModel.Msg
import com.blaze.assistant.ui.living.LivingModel.Role
import com.blaze.assistant.ui.living.ResultCards.Call
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Living UI 0.2.2 result cards: the parsers (fixtures as each tool returns them), the registry, the board and the steps' words. */
class ResultCardsTest {
    // GatewayWeather.forModel's text, as the model (and ToolTrail) gets it
    private val weatherOut = "29°C, Cloudy, feels 32°C, humidity 43%, wind 3 km/h. Source: Open-Meteo · 10:15. Quote the source and time."
    // phone/Location.run's text
    private val locationOut = "Location: -12.96880, 28.63660 (±12 m, just now)\nNear: Plot 12 Broadway, Ndola, Zambia"
    // the gateway's /v1/weather JSON
    private val weatherJson = """{"temperature":28.6,"feels_like":31.7,"temperature_unit":"°C","condition":"partly cloudy","is_day":false,
        "humidity_pct":43,"wind_speed":3.4,"wind_unit":"km/h","observed_at":"2026-11-15T08:15:00Z","source":"Open-Meteo","summary":"x"}"""

    @Test fun weatherFromTheToolsText() {
        val w = ResultCards.weather(weatherOut, "Ndola")!!
        assertEquals(29, w.temp); assertEquals("°C", w.unit); assertEquals("Cloudy", w.condition); assertEquals("Ndola", w.place)
        assertEquals(32, w.feels); assertEquals(43, w.humidity); assertEquals("3 km/h", w.wind)
        assertEquals("Open-Meteo", w.source); assertEquals("10:15", w.time); assertFalse(w.night)
        // night, imperial, no feels / humidity, no time
        val n = ResultCards.weather("-3°F, clear sky (it's night), wind 12 mph. Source: MET Norway. Quote the source and time.")!!
        assertEquals(-3, n.temp); assertEquals("°F", n.unit); assertEquals("Clear sky", n.condition); assertTrue(n.night)
        assertNull(n.feels); assertNull(n.humidity); assertEquals("12 mph", n.wind); assertEquals("MET Norway", n.source); assertEquals("", n.time)
        // the refusals and errors give no card
        assertNull(ResultCards.weather("Weather is unavailable right now (both weather sources failed). Tell the user that plainly; don't guess."))
        assertNull(ResultCards.weather("No location: call get_location (or ask which place), then call get_weather with lat and lon."))
    }

    @Test fun weatherFromTheGatewaysJson() {
        val w = ResultCards.weather(weatherJson, "Ndola")!!
        assertEquals(29, w.temp); assertEquals(32, w.feels); assertEquals("Partly cloudy", w.condition); assertTrue(w.night)
        assertEquals("3 km/h", w.wind); assertEquals("08:15", w.time); assertEquals("Ndola", w.place)
        assertNull(ResultCards.weather("""{"error":"weather_unavailable"}"""))
        assertEquals(ResultCards.Sky.PARTLY, ResultCards.sky(w.condition))
        assertEquals(ResultCards.Sky.RAIN, ResultCards.sky("Light drizzle")); assertEquals(ResultCards.Sky.STORM, ResultCards.sky("Thunderstorm"))
        assertEquals(ResultCards.Sky.CLOUDY, ResultCards.sky("Overcast")); assertEquals(ResultCards.Sky.CLEAR, ResultCards.sky("Clear sky"))
    }

    @Test fun theWeatherToolStillSpeaksTheFormatWeRead() {
        val root = listOf(File("src/main/java"), File("app/src/main/java"), File("main/java")).firstOrNull { File(it, "com/blaze/assistant/agent/GatewayWeather.kt").isFile } ?: return
        val src = File(root, "com/blaze/assistant/agent/GatewayWeather.kt").readText()
        for (needle in listOf("\", feels \${", "\", humidity \${", "\", wind \${", "\". Source: \${", "\" (it's night)\""))
            assertTrue("GatewayWeather.forModel changed: $needle", src.contains(needle))
    }

    @Test fun placeAndLocality() {
        val p = ResultCards.place(locationOut)!!
        assertEquals("Ndola", p.locality); assertEquals(-12.9688, p.lat, 1e-6); assertEquals(28.6366, p.lon, 1e-6); assertEquals("±12m", p.accuracy)
        assertEquals("Mountain View", ResultCards.locality("1600 Amphitheatre Pkwy, Mountain View, CA 94043, USA"))
        assertEquals("Lusaka", ResultCards.locality("Lusaka"))
        assertNull(ResultCards.place("Location is switched off on the phone (Quick Settings → Location)."))
    }

    @Test fun alarmTimerEventImageTable() {
        assertEquals(ResultCard.Alarm("07:30", "Gym"), ResultCards.alarm(mapOf("hour" to "7", "minute" to "30", "label" to "Gym")))
        assertEquals("7:00", ResultCards.alarm(mapOf("time" to "7:00"))!!.time)
        assertEquals(ResultCard.Alarm("10:00", timer = true), ResultCards.timer(mapOf("minutes" to "10")))
        assertNull(ResultCards.timer(emptyMap()))
        assertEquals(ResultCard.Event("Dentist", "2026-11-16 09:00", "", "Ndola Clinic"),
            ResultCards.event(mapOf("title" to "Dentist", "start" to "2026-11-16 09:00", "location" to "Ndola Clinic")))
        assertNull(ResultCards.event(mapOf("title" to "No time")))
        assertEquals("img_42.png", ResultCards.image("Image made and shown to the user in the chat (saved in their Gallery as img_42.png). Reply with one short line", "a fox")!!.file)
        val t = ResultCards.table("Here:\n\n| Tool | Stars | Lang |\n|---|---:|---|\n| **Gatus** | 7k | Go |\n| Kuma | 60k | JS |\n\nDone.")!!
        assertEquals(listOf("Tool", "Stars", "Lang"), t.head)
        assertEquals(listOf(listOf("Gatus", "7k", "Go"), listOf("Kuma", "60k", "JS")), t.rows)
        assertNull(ResultCards.table("a | b without a separator"))
    }

    @Test fun theRegistryPicksOnePerKindAndFoldsThePlaceIntoTheWeather() {
        val calls = listOf(Call("get_location", emptyMap(), locationOut), Call("get_weather", mapOf("lat" to "-12.97", "lon" to "28.64"), weatherOut))
        val cards = ResultCards.of(calls)
        assertEquals(1, cards.size)
        assertEquals("Ndola", (cards[0] as ResultCard.Weather).place)
        // location alone is a place card; a failed call is nothing; two alarms are two cards; an answer's table counts
        assertTrue(ResultCards.of(calls.take(1)).single() is ResultCard.Place)
        assertTrue(ResultCards.of(listOf(Call("get_weather", emptyMap(), weatherOut, failed = true))).isEmpty())
        assertEquals(2, ResultCards.of(listOf(Call("set_alarm", mapOf("hour" to "6"), ""), Call("set_alarm", mapOf("hour" to "7"), ""))).size)
        assertTrue(ResultCards.of(emptyList(), "| a | b |\n|---|---|\n| 1 | 2 |").single() is ResultCard.Table)
        assertTrue(ResultCards.of(listOf(Call("web_search", mapOf("query" to "x"), "plain text"))).isEmpty())
        assertTrue(ResultCards.PARSERS.keys.containsAll(listOf("get_weather", "get_location", "set_alarm", "set_timer", "add_calendar_event", "generate_image")))
    }

    // the owner's turn (15 Nov 2026 10:19): "Weather right now" → get_location, get_weather → the answer
    private val weatherTurn = listOf(
        Msg(1, Role.USER, "Weather right now", at = 1),
        Msg(2, Role.STEP, "", "get_location()", at = 2),
        Msg(3, Role.STEP, "", "get_weather(lat=-12.97, lon=28.64)", at = 3),
        Msg(4, Role.BLAZE, "29°C and cloudy in Ndola — feels like 32°C. Humidity 43%, light wind at 3 km/h. Source: Open-Meteo, 10:15.", at = 4),
    )
    private val trail = listOf(ToolTrail.Result("get_location()", locationOut, false, 2), ToolTrail.Result("get_weather(lat=-12.97, lon=28.64)", weatherOut, false, 3))
    private val generic = { _: String, running: Boolean -> if (running) "Looking things up" else "Looked things up" }

    @Test fun aWeatherTurnShowsTheWeatherBeforeTheAnswer() {
        val b = LivingModel.board(weatherTurn, working = false, results = trail, label = generic)
        val kinds = b.cards.map { it.kind }
        assertEquals(listOf(Kind.PROMPT, Kind.LOG, Kind.RESULT, Kind.ANSWER), kinds)
        val w = b.cards[2].result as ResultCard.Weather
        assertEquals(29, w.temp); assertEquals("Ndola", w.place)
        // steps say what was done, not "Looked things up" twice
        assertEquals(listOf("Found your location \u00b7 Ndola", "Checked the weather \u00b7 Ndola"), b.cards[1].rows.map { it.text })
        // while it works: the card shows as soon as the weather is in, before any answer
        val live = LivingModel.board(weatherTurn.dropLast(1), working = true, results = trail, label = generic)
        assertEquals(Kind.RESULT, live.cards.last().kind)
        // no trail (an app restart): no weather card, words still say what was done
        val bare = LivingModel.board(weatherTurn, working = false, label = generic)
        assertTrue(bare.cards.none { it.kind == Kind.RESULT })
        assertEquals("Checked the weather", bare.cards[1].rows[1].text)
    }

    @Test fun theResultArrowsIntoTheAnswerInBothLayouts() {
        val b = LivingModel.board(weatherTurn, working = false, results = trail, label = generic)
        for (wide in listOf(false, true)) {
            val plan = LivingLayout.plan(b, wide)
            val res = plan.boxes.single { it.card.kind == Kind.RESULT }
            val ans = plan.boxes.single { it.card.kind == Kind.ANSWER }
            assertEquals("wide=$wide same column", res.x, ans.x, 0.01f)
            assertTrue("wide=$wide result above the answer", res.bottom < ans.y)
            val l = plan.links.single { it.pts[1] == res.bottom && it.pts[3] == ans.y }
            assertTrue("wide=$wide arrow", l.arrow)
            assertEquals(LivingLayout.resultHeight(res.card.result), res.h, 0.01f)
            for (i in plan.boxes.indices) for (j in i + 1 until plan.boxes.size) {
                val p = plan.boxes[i]; val q = plan.boxes[j]
                assertFalse("${p.key} overlaps ${q.key}", p.x < q.right && q.x < p.right && p.y < q.bottom && q.y < p.bottom)
            }
        }
    }

    @Test fun stepsWordsAndDuplicates() {
        assertEquals("Searched the web \u00b7 best camera phone", LivingSteps.label("web_search", mapOf("query" to "best camera phone"), false, "Searched the web"))
        assertEquals("Set an alarm \u00b7 07:00", LivingSteps.label("set_alarm", mapOf("hour" to "7", "minute" to "0"), false, "Set an alarm"))
        assertEquals("Checked your calendar \u00b7 today", LivingSteps.label("calendar_events", mapOf("days" to "1"), false, "Looked things up"))
        assertEquals("Opened a page \u00b7 github.com", LivingSteps.label("open_thing", mapOf("url" to "https://github.com/TwiN/gatus"), false, "Opened a page"))
        // numbers alone (lat / lon) are never the detail
        assertEquals("Worked", LivingSteps.label("mystery", mapOf("lat" to "1.5"), false, "Worked"))
        val r = LivingModel.Row("Checked memory", false, LivingModel.State.DONE)
        val merged = LivingSteps.merge(listOf(r, r, r.copy(text = "Saved to memory"), r))
        assertEquals(listOf("Checked memory \u00d72", "Saved to memory", "Checked memory"), merged.map { it.text })
        // commands never merge
        val c = LivingModel.Row("$ ls", true, LivingModel.State.DONE)
        assertEquals(2, LivingSteps.merge(listOf(c, c)).size)
    }
}
