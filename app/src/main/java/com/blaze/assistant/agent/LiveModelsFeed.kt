package com.blaze.assistant.agent

/**
 * P0-API-01 Android glue for [LiveModels] (behind Settings `live_models`, default on since 0.53.0): fetches GET /v1/models/live in
 * the background at most every 5 min and names the live pick in X-Blaze-Prefer when the turn has no pin yet. Reading
 * back which route answered from X-Blaze-Route ([served]) is always on. Not unit-tested; the
 * decisions it calls are (LiveModelsTest, ModelRouteTest).
 */
object LiveModelsFeed {
    fun on(): Boolean = try { Settings.bool(LiveModels.FLAG, true) } catch (_: Throwable) { false }   // 0.53.0: on by default (owner, 13 Nov)

    /** Starts a background refresh when the list is due. */
    fun touch(base: String, key: String) {
        if (!on() || !LiveModels.shared.due()) return
        Thread { LiveModels.shared.refresh { get(base, key) } }.start()
    }

    /** 0.53.0 model picker: refresh the list now (the picker opened), with the signed-in gateway session. */
    fun touchNow() {
        if (!on()) return
        val key = try { Settings.gatewayToken() } catch (_: Throwable) { "" }
        if (key.isBlank()) return
        Thread { LiveModels.shared.refresh { get(Providers.GATEWAY_BASE, key) } }.start()
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
        // 0.53.0: a manual pick (ModelPick) goes first while it's on the live list and fits the call; else the auto pick.
        ModelPick.route(LiveModels.shared.list(), hasTools, task == "vision")?.let { return it }
        val pick = ModelRoute.task(task, heavy, hasTools)
        return ModelRoute.livePrefer(LiveModels.shared.list(), pick, hasTools, failed)
    }

    /**
     * The served key + label from the response headers: (route token, "Groq · GPT OSS 120b"). The token part is always
     * on (owner, 2026-11-12): gateway 0.8.45+ hides X-Blaze-Provider/Model, so without it TurnModel never pinned and an
     * empty route was never avoided; the gateway takes the token back in X-Blaze-Prefer / blaze_avoid. The label needs
     * the live list, so it only shows with `live_models` on.
     */
    fun served(headers: Map<String?, List<String>>?): Pair<String, String>? {
        val a = LiveModels.answered(headers, if (on()) LiveModels.shared.list() else emptyList())
        if (a.route.isEmpty()) return null
        return a.route to (a.model?.let { "${it.prov} · ${it.name}" + if (a.fallback) " (fallback)" else "" } ?: "")
    }
}
