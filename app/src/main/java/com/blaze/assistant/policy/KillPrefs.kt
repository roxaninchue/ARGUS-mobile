package com.blaze.assistant.policy

import android.content.Context
import android.content.SharedPreferences

/**
 * P2-SF-06: the persisted Pause flags behind [Kill] (own prefs file "argus_kill", so the shared `blaze` prefs and the
 * `memories` schema are untouched). Install once at start: `Kill.install(KillPrefs(this))` in `BlazeApp.onCreate`
 * (left to the P2 lead / P1 shell owner so this ticket does not touch BlazeApp.kt).
 *
 * Android glue, checked by hand; the rules live in Kill.kt (PauseAllTest uses Kill.Store.Memory).
 */
class KillPrefs(ctx: Context) : Kill.Store {
    private val p: SharedPreferences = ctx.applicationContext.getSharedPreferences("argus_kill", Context.MODE_PRIVATE)
    override fun allPaused(): Boolean = p.getBoolean("all_paused", false)
    override fun setAllPaused(on: Boolean) { p.edit().putBoolean("all_paused", on).apply() }
    override fun pausedBots(): Set<String> = p.getStringSet("paused_bots", emptySet())?.toSet() ?: emptySet()
    override fun setPausedBots(ids: Set<String>) { p.edit().putStringSet("paused_bots", ids.toSet()).apply() }
}
