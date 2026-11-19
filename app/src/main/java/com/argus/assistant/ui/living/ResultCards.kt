package com.blaze.assistant.ui.living

import org.json.JSONObject

/**
 * Living UI 0.2.2, "show, don't tell" (Finn, 15 Nov 2026): when a turn's tool results carry something you can look at,
 * Living draws it as a card of its own above the answer, joined to it by an arrow (the weather itself, not only a
 * sentence about it).
 *
 * A small typed registry: [ResultCard] is the kinds, [PARSERS] maps a tool to its parser, [of] runs them over a turn's
 * calls (and the answer, for a table). Adding a kind = a subclass, a parser here, a height in LivingLayout and a drawing
 * in LivingScreen. Parsers read the exact text each tool hands the model (GatewayWeather.forModel, Location.run…) and,
 * where a tool answers in JSON, its JSON; anything they don't recognise gives no card. Pure: ResultCardsTest.
 */
sealed class ResultCard {
    /** get_weather (GatewayWeather, Open-Meteo / MET Norway): the reading as the tool returned it. */
    data class Weather(
        val temp: Int, val unit: String, val condition: String, val place: String = "", val night: Boolean = false,
        val feels: Int? = null, val humidity: Int? = null, val wind: String? = null, val source: String = "", val time: String = "",
    ) : ResultCard()

    /** get_location: where the phone is (no map tiles: a drawn pin, the locality, the address, the coordinates). */
    data class Place(val locality: String, val address: String, val lat: Double, val lon: Double, val accuracy: String = "") : ResultCard()

    /** set_alarm / set_timer: the time that was set. */
    data class Alarm(val time: String, val label: String = "", val timer: Boolean = false) : ResultCard()

    /** add_calendar_event / gcal_create: the event that was added. */
    data class Event(val title: String, val start: String, val end: String = "", val location: String = "") : ResultCard()

    /** generate_image: the picture that was made (its Gallery file name) and what was asked for. */
    data class Image(val file: String, val prompt: String = "") : ResultCard()

    /** A markdown table in a tool's result or in the answer. */
    data class Table(val head: List<String>, val rows: List<List<String>>, val more: Int = 0) : ResultCard()
}

object ResultCards {
    /** One finished call of a turn: the tool, its arguments (LivingModel.args), what it returned. */
    data class Call(val name: String, val args: Map<String, String>, val output: String, val failed: Boolean = false)

    fun interface Parser { fun parse(call: Call, turn: List<Call>): ResultCard? }

    /** At most this many result cards per turn (the newest win). */
    const val PER_TURN = 3
    const val TABLE_COLS = 4
    const val TABLE_ROWS = 6

    val PARSERS: Map<String, Parser> = mapOf(
        "get_weather" to Parser { c, turn -> weather(c.output, placeOf(turn)) },
        "get_location" to Parser { c, _ -> place(c.output) },
        "set_alarm" to Parser { c, _ -> alarm(c.args) },
        "set_timer" to Parser { c, _ -> timer(c.args) },
        "add_calendar_event" to Parser { c, _ -> event(c.args) },
        "gcal_create" to Parser { c, _ -> event(c.args) },
        "generate_image" to Parser { c, _ -> image(c.output, c.args["prompt"].orEmpty()) },
    )

    /**
     * The result cards for one turn's finished [calls] (oldest first) and its [answer] (null while it works): one per
     * kind (alarms and events: one per call), in the order they happened; a table from a tool's result, else from the
     * answer; failed calls give nothing. When the turn has weather, the place card is folded into it.
     */
    fun of(calls: List<Call>, answer: String? = null): List<ResultCard> {
        val out = ArrayList<ResultCard>()
        for (c in calls) {
            if (c.failed) continue
            val r = PARSERS[c.name.lowercase()]?.parse(c, calls) ?: table(c.output) ?: continue
            if (r !is ResultCard.Alarm && r !is ResultCard.Event) out.removeAll { it::class == r::class }
            out += r
        }
        if (out.none { it is ResultCard.Table }) answer?.let { table(it) }?.let { out += it }
        if (out.any { it is ResultCard.Weather }) out.removeAll { it is ResultCard.Place }
        return out.takeLast(PER_TURN)
    }

    // ------------------------------------------------------------------ weather
    private val WEATHER = Regex(
        "^\\s*(-?\\d+)\\s*(°\\s*[CF])\\s*,\\s*([^,(.]+?)\\s*(\\(it's night\\))?" +
            "(?:\\s*,\\s*feels\\s+(-?\\d+)\\s*°\\s*[CF])?(?:\\s*,\\s*humidity\\s+(\\d+)\\s*%)?(?:\\s*,\\s*wind\\s+(\\d+(?:\\.\\d+)?)\\s*([^.,]+))?" +
            "\\s*\\.\\s*Source:\\s*([^·.]+?)\\s*(?:·\\s*(\\d{1,2}:\\d{2}))?\\s*\\.", RegexOption.IGNORE_CASE)

    /** get_weather's text (GatewayWeather.forModel: "29°C, Cloudy, feels 32°C, humidity 43%, wind 3 km/h. Source: Open-Meteo · 10:15. …") or the gateway's JSON. */
    fun weather(out: String, place: String = ""): ResultCard.Weather? {
        val t = out.trim()
        if (t.startsWith("{")) return weatherJson(t, place)
        val m = WEATHER.find(t) ?: return null
        val g = m.groupValues
        return ResultCard.Weather(
            temp = g[1].toInt(), unit = g[2].replace(" ", ""), condition = cap(g[3].trim()), place = place, night = g[4].isNotEmpty(),
            feels = g[5].toIntOrNull(), humidity = g[6].toIntOrNull(),
            wind = g[7].takeIf { it.isNotEmpty() }?.let { w -> "${Math.round(w.toDouble())} ${g[8].trim()}" },
            source = g[9].trim(), time = g[10],
        )
    }

    private fun weatherJson(t: String, place: String): ResultCard.Weather? = try {
        val o = JSONObject(t)
        if (!o.has("temperature")) null else {
            val unit = o.optString("temperature_unit", "°C").ifBlank { "°C" }
            val time = o.optString("observed_at").let { Regex("T(\\d{2}:\\d{2})").find(it)?.groupValues?.get(1).orEmpty() }
            ResultCard.Weather(
                temp = Math.round(o.getDouble("temperature")).toInt(), unit = unit, condition = cap(o.optString("condition")),
                place = o.optString("place").ifBlank { place }, night = !o.optBoolean("is_day", true),
                feels = o.optDouble("feels_like", Double.NaN).takeIf { !it.isNaN() }?.let { Math.round(it).toInt() },
                humidity = o.optInt("humidity_pct", -1).takeIf { it >= 0 },
                wind = o.optDouble("wind_speed", Double.NaN).takeIf { !it.isNaN() }?.let { "${Math.round(it)} ${o.optString("wind_unit", "km/h")}" },
                source = o.optString("source"), time = time,
            )
        }
    } catch (_: Exception) { null }

    /** The condition's family, for the drawn icon. */
    enum class Sky { CLEAR, PARTLY, CLOUDY, FOG, RAIN, STORM, SNOW }

    fun sky(condition: String): Sky {
        val c = condition.lowercase()
        return when {
            "thunder" in c || "storm" in c -> Sky.STORM
            "snow" in c || "sleet" in c || "hail" in c -> Sky.SNOW
            "rain" in c || "drizzle" in c || "shower" in c -> Sky.RAIN
            "fog" in c || "mist" in c || "haze" in c -> Sky.FOG
            "partly" in c || "mainly clear" in c || "few clouds" in c || "scattered" in c -> Sky.PARTLY
            "cloud" in c || "overcast" in c -> Sky.CLOUDY
            else -> Sky.CLEAR
        }
    }

    // ------------------------------------------------------------------ place
    private val LOC = Regex("Location:\\s*(-?\\d+(?:\\.\\d+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?)(?:\\s*\\((±\\s*\\d+\\s*m)[^)]*\\))?")

    /** get_location's text (phone/Location.run): "Location: -12.96, 28.63 (±12 m, just now)\nNear: 12 Broadway, Ndola, Zambia". */
    fun place(out: String): ResultCard.Place? {
        val m = LOC.find(out) ?: return null
        val addr = Regex("(?m)^Near:\\s*(.+)$").find(out)?.groupValues?.get(1)?.trim().orEmpty()
        return ResultCard.Place(locality(addr), addr, m.groupValues[1].toDouble(), m.groupValues[2].toDouble(), m.groupValues[3].replace(" ", ""))
    }

    /** "12 Broadway, Ndola, Zambia" → "Ndola"; "1600 Amphitheatre Pkwy, Mountain View, CA 94043, USA" → "Mountain View". */
    fun locality(address: String): String {
        val parts = address.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return ""
        val body = if (parts.size > 1) parts.dropLast(1) else parts
        return body.lastOrNull { p -> p.none { it.isDigit() } } ?: body.last().replace(Regex("\\d+"), "").trim()
    }

    /** The place a turn knows: its get_location's locality. */
    fun placeOf(turn: List<Call>): String =
        turn.lastOrNull { it.name.equals("get_location", true) && !it.failed }?.let { place(it.output)?.locality }.orEmpty()

    // ------------------------------------------------------------------ alarm, timer, event, image
    fun alarm(a: Map<String, String>): ResultCard.Alarm? {
        val h = a["hour"]?.toIntOrNull(); val m = a["minute"]?.toIntOrNull() ?: 0
        val time = when {
            h != null -> "%02d:%02d".format(h, m)
            !a["time"].isNullOrBlank() -> a["time"]!!.trim()
            else -> return null
        }
        return ResultCard.Alarm(time, a["label"].orEmpty().trim())
    }

    fun timer(a: Map<String, String>): ResultCard.Alarm? {
        val s = (a["minutes"]?.toIntOrNull() ?: 0) * 60 + (a["seconds"]?.toIntOrNull() ?: 0) + (a["hours"]?.toIntOrNull() ?: 0) * 3600
        if (s <= 0) return null
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        val time = when { h > 0 -> "%d:%02d:%02d".format(h, m, sec); else -> "%d:%02d".format(m, sec) }
        return ResultCard.Alarm(time, a["label"].orEmpty().trim(), timer = true)
    }

    fun event(a: Map<String, String>): ResultCard.Event? {
        val title = (a["title"] ?: a["summary"] ?: a["name"]).orEmpty().trim()
        val start = (a["start"] ?: a["when"] ?: a["start_time"]).orEmpty().trim()
        if (title.isEmpty() || start.isEmpty()) return null
        return ResultCard.Event(title, start, (a["end"] ?: a["end_time"]).orEmpty().trim(), a["location"].orEmpty().trim())
    }

    private val GALLERY = Regex("Gallery as ([^)\\s]+\\.(?:png|jpe?g|webp))", RegexOption.IGNORE_CASE)

    fun image(out: String, prompt: String): ResultCard.Image? = GALLERY.find(out)?.let { ResultCard.Image(it.groupValues[1], prompt.trim()) }

    // ------------------------------------------------------------------ table
    /** The first markdown table in [text] (a header row, a `|---|` row, data rows), capped to [TABLE_COLS] × [TABLE_ROWS]. */
    fun table(text: String): ResultCard.Table? {
        val lines = text.lines().map { it.trim() }
        for (i in 0 until lines.size - 1) {
            if (!lines[i].startsWith("|") || !Regex("^\\|?\\s*:?-{2,}").containsMatchIn(lines[i + 1])) continue
            fun cells(l: String) = l.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim().replace("**", "").replace("`", "") }
            val head = cells(lines[i])
            if (head.size < 2) continue
            val rows = lines.drop(i + 2).takeWhile { it.startsWith("|") }.map(::cells).filter { r -> r.any { it.isNotEmpty() } }
            if (rows.isEmpty()) continue
            val cols = minOf(TABLE_COLS, head.size)
            return ResultCard.Table(head.take(cols), rows.take(TABLE_ROWS).map { r -> List(cols) { r.getOrElse(it) { "" } } }, maxOf(0, rows.size - TABLE_ROWS))
        }
        return null
    }

    private fun cap(s: String) = s.replaceFirstChar { it.uppercase() }
}
