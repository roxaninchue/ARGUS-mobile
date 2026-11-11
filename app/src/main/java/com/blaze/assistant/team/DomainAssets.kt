package com.blaze.assistant.team

import android.content.Context

/**
 * P5-AG-01 Android glue: points [Domains] at `assets/domains/` and at the user's own domains in
 * `filesDir/domains/`. Called once from the team runtime start (TeamHooks / TeamAndroid when P0-AG-01 lands; until then
 * the first screen that needs the catalog, e.g. Setup, calls it). Idempotent.
 */
object DomainAssets {
    @Volatile private var done = false

    fun install(context: Context) {
        if (done) return
        val app = context.applicationContext
        Domains.loader = { path -> runCatching { app.assets.open("domains/$path").bufferedReader().use { it.readText() } }.getOrNull() }
        UserDomains(app.filesDir).install()
        com.blaze.assistant.agent.Playbooks.resetDomains()
        done = true
    }
}
