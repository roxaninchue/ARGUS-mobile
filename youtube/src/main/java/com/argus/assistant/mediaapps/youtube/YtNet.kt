package com.argus.assistant.mediaapps.youtube

import com.argus.assistant.mediaapps.store.StoreError
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** One InnerTube app client: the name / id / version YouTube expects, its user-agent, and extra context fields. */
data class YtClient(val name: String, val id: Int, val version: String, val ua: String, val extra: Map<String, Any> = emptyMap())

/** The InnerTube clients this plug-in uses. */
object YtClients {
    /**
     * Stream clients, tried in order. VISIONOS first: in 2026 it is the app client whose plain https stream urls still
     * download without a PO token or signature JavaScript; the others answer the first bytes and then refuse later
     * ranges on many networks, which the stream probe ([YtParse.probeAt]) catches.
     */
    val streams = listOf(
        YtClient("VISIONOS", 101, "1.02", "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15",
            mapOf("deviceMake" to "Apple", "deviceModel" to "RealityDevice17,1", "osName" to "visionOS", "osVersion" to "26.5.23O471")),
        YtClient("ANDROID_VR", 28, "1.65.10", "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            mapOf("deviceMake" to "Oculus", "deviceModel" to "Quest 3", "androidSdkVersion" to 32, "osName" to "Android", "osVersion" to "12L")),
        YtClient("ANDROID", 3, "21.26.364", "com.google.android.youtube/21.26.364 (Linux; U; Android 14) gzip",
            mapOf("androidSdkVersion" to 34, "osName" to "Android", "osVersion" to "14")),
        YtClient("IOS", 5, "21.26.4", "com.google.ios.youtube/21.26.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
            mapOf("deviceMake" to "Apple", "deviceModel" to "iPhone16,2", "osName" to "iPhone", "osVersion" to "18.3.2.22D82")),
    )

    /** The WEB client, for search only (streams always come from [streams]). */
    val web = YtClient("WEB", 1, "2.20260114.08.00", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36")

    /** The InnerTube "context" object for [c]. */
    fun context(c: YtClient): JSONObject {
        val client = JSONObject().put("clientName", c.name).put("clientVersion", c.version).put("hl", "en").put("gl", "US")
        c.extra.forEach { (k, v) -> client.put(k, v) }
        return JSONObject().put("client", client)
    }
}

data class YtResponse(val code: Int, val body: String)

/** The network under the plug-in. Swapped for a fake in the unit tests (no network there). */
interface YtTransport {
    fun post(url: String, body: String, headers: Map<String, String>): YtResponse
    fun get(url: String, headers: Map<String, String>): YtResponse
    /** True when a ranged read of [len] bytes at [at] returns data (the stream really downloads). */
    fun probe(url: String, ua: String, at: Long, len: Int = 1024): Boolean
}

/** [YtTransport] over HttpURLConnection (no extra HTTP library). */
object HttpTransport : YtTransport {
    override fun post(url: String, body: String, headers: Map<String, String>): YtResponse = call(url, "POST", body, headers)
    override fun get(url: String, headers: Map<String, String>): YtResponse = call(url, "GET", null, headers)

    private fun call(url: String, method: String, body: String?, headers: Map<String, String>): YtResponse {
        val h = URL(url).openConnection() as HttpURLConnection
        try {
            h.requestMethod = method; h.connectTimeout = 15_000; h.readTimeout = 20_000; h.instanceFollowRedirects = true
            headers.forEach { (k, v) -> h.setRequestProperty(k, v) }
            if (body != null) { h.doOutput = true; h.outputStream.use { it.write(body.toByteArray()) } }
            val code = h.responseCode
            val text = (if (code in 200..299) h.inputStream else h.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            return YtResponse(code, text)
        } finally { h.disconnect() }
    }

    override fun probe(url: String, ua: String, at: Long, len: Int): Boolean = try {
        val h = URL(url).openConnection() as HttpURLConnection
        try {
            h.connectTimeout = 10_000; h.readTimeout = 10_000
            h.setRequestProperty("User-Agent", ua); h.setRequestProperty("Range", "bytes=$at-${at + len - 1}")
            h.responseCode in 200..299 && (h.inputStream.use { it.read(ByteArray(len)) } > 0)
        } finally { h.disconnect() }
    } catch (_: Exception) { false }
}

/** The InnerTube calls: visitor id, search, player, oEmbed. Each returns the raw JSON text. */
class YtApi(private val net: YtTransport) {
    private fun headers(c: YtClient, visitor: String) = buildMap {
        put("Content-Type", "application/json"); put("User-Agent", c.ua)
        put("X-YouTube-Client-Name", c.id.toString()); put("X-YouTube-Client-Version", c.version)
        put("Origin", "https://www.youtube.com")
        if (visitor.isNotBlank()) put("X-Goog-Visitor-Id", visitor)
    }

    private fun post(endpoint: String, body: JSONObject, c: YtClient, visitor: String = ""): String {
        val r = net.post("https://www.youtube.com/youtubei/v1/$endpoint?prettyPrint=false", body.toString(), headers(c, visitor))
        if (r.code !in 200..299) throw StoreError("${c.name}: YouTube answered ${r.code}" + if (r.body.contains("Sorry", true)) " (bot check)" else "", r.code)
        return r.body
    }

    fun visitorId(): String {
        val c = YtClients.streams.first()
        return YtParse.visitorData(post("visitor_id", JSONObject().put("context", YtClients.context(c)), c))
    }

    /** One page of video results ("EgIQAQ==" = type: video), or the page after [continuation]. */
    fun search(query: String, continuation: String = ""): String {
        val body = JSONObject().put("context", YtClients.context(YtClients.web))
        if (continuation.isNotBlank()) body.put("continuation", continuation) else body.put("query", query).put("params", "EgIQAQ==")
        return post("search", body, YtClients.web)
    }

    fun player(c: YtClient, videoId: String, visitor: String): String = post("player",
        JSONObject().put("context", YtClients.context(c)).put("videoId", videoId).put("contentCheckOk", true).put("racyCheckOk", true), c, visitor)

    /** Title and channel from the public oEmbed endpoint (no client needed). */
    fun oembed(videoId: String): Pair<String, String> {
        val u = "https://www.youtube.com/oembed?format=json&url=" + URLEncoder.encode("https://www.youtube.com/watch?v=$videoId", "UTF-8")
        val r = net.get(u, mapOf("User-Agent" to YtClients.web.ua, "Accept" to "application/json"))
        if (r.code !in 200..299) throw StoreError("oEmbed answered ${r.code}", r.code)
        val o = JSONObject(r.body)
        return o.optString("title") to o.optString("author_name")
    }

    fun probe(url: String, ua: String, length: Long): Boolean = net.probe(url, ua, YtParse.probeAt(length))
}
