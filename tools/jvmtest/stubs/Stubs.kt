// JVM test stubs for app classes the pure files touch (not shipped; P1-TM-04 local test run only).
package com.blaze.assistant.agent
object Settings {
    val m = HashMap<String, Any?>()
    fun bool(k: String, d: Boolean): Boolean = (m[k] as? Boolean) ?: d
    fun str(k: String, d: String): String = (m[k] as? String) ?: d
    fun setStr(k: String, v: String) { m[k] = v }
    fun setBool(k: String, v: Boolean) { m[k] = v }
}
object Playbooks {
    const val MAX_TRIES = 3; const val MIN_CHECKS = 5
    data class Sec(val title: String)
    val SECTIONS = listOf(Sec("Role"))
    fun secrets(s: String): List<String> = emptyList()
    class Check(val ok: Boolean) { fun feedback() = "" }
    fun validate(p: String, field: String, others: List<String>) = Check(true)
}
object Web { val names = setOf("web_search", "fetch_page") }
object Designs { const val TOOL = "design" }
