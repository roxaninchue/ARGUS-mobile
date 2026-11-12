package com.blaze.assistant.agent

/**
 * P0-API-01 Android glue for [LiveModels] (behind Settings `live_models`, default off): fetches GET /v1/models/live in
 * the background at most every 5 min, names the live pick in X-Blaze-Prefer when the turn has no pin yet, and reads
 * back which route answered from X-Blaze-Route (gateway 0.8.45+ sends no model ids by default). Not unit-tested; the
 * decisions it calls are (LiveModelsTest, ModelRouteTest).
 */
object LiveModelsFeed {
    fun on(): Boolean = try { Settings.bool(LiveModels.FLAG, false) } catch (_: Throwable) { false }

    /** Starts a background refresh when the list is due. */
    fun touch(base: String, key: String) {
        if (!on() || !LiveModels.shared.due()) return
        Thread { LiveModels.shared.refresh { get(base, key) } }.start()
    }

    private fun get(base: String, key: String): String? {
        val c = com.blaze.assistant.connect.UrlSafety.open(LiveModels.liveUrl(base))
        return try {
            c.requestMethod = "GET"; c.connectTimeout = 8_000; c.readTimeout = 8_000
            LlmClient.authHeaders(key, Providers.GATEWAY).forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (c.responseCode == 200) c.inputStream.use { it.readBytes().decodeToString() } else { c.errorStream?.use { it.readBytes() }; null }
        } finally { c.disconnect() }
    }

    /** The route to prefer for one request ([task] = Cloud.task's hint), or null. */
    fun prefer(task: String, heavy: Boolean, hasTools: Boolean, failed: List<String>): String? {
        if (!on()) return null
        val pick = ModelRoute.task(task, heavy, hasTools)
        return ModelRoute.livePrefer(LiveModels.shared.list(), pick, hasTools, failed)
    }

    /** "provider/model"-free served key + label from the response headers: (route token, "Groq · GPT OSS 120b"). */
    fun served(headers: Map<String?, List<String>>?): Pair<String, String>? {
        if (!on()) return null
        val a = LiveModels.answered(headers, LiveModels.shared.list())
        if (a.route.isEmpty()) return null
        return a.route to (a.model?.let { "${it.prov} · ${it.name}" + if (a.fallback) " (fallback)" else "" } ?: "")
    }
}
