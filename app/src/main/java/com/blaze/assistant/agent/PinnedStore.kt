package com.blaze.assistant.agent

import com.blaze.assistant.BrandName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Unreleased round after 0.52.3: where the user's pinned facts live (Settings `pinned_facts_v1`, on this phone), apart
 * from the memory table and from every chat's history. The rules are in [PinnedFacts] (pure); this keeps the list, the
 * flow the "What Argus is holding" page reads, and the same no-secrets rule as Memory.add.
 */
object PinnedStore {
    const val KEY = "pinned_facts_v1"

    private val _facts = MutableStateFlow<List<PinnedFacts.Fact>>(emptyList())
    val facts: StateFlow<List<PinnedFacts.Fact>> = _facts
    @Volatile private var loaded = false

    /** Every pinned fact, oldest first (read from Settings once). */
    fun all(): List<PinnedFacts.Fact> {
        if (!loaded) synchronized(this) {
            if (!loaded) {
                _facts.value = try { PinnedFacts.decode(Settings.str(KEY, "")) } catch (_: Throwable) { emptyList() }
                loaded = true
            }
        }
        return _facts.value
    }

    private fun save(list: List<PinnedFacts.Fact>) {
        _facts.value = list
        try { Settings.setStr(KEY, PinnedFacts.encode(list)) } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("PinnedStore: save", e) }
    }

    /** Pins [text]; returns the line for the user (pinned, already pinned, too long, full, or why it wasn't kept). */
    fun pin(text: String): String = synchronized(this) {
        val f = SecretVault.scrub(text)
        // Same rule as Memory.add: keys, passwords, codes and card / account numbers are never kept, even when asked.
        if (SecretVault.hasHandle(f) || Learn.secret(f))
            return "Not pinned: that looks like a password, code or account number, which ${BrandName.NAME} never keeps."
        val now = System.currentTimeMillis()
        val r = PinnedFacts.add(all(), f, now, now)
        if (r.outcome == PinnedFacts.Outcome.ADDED) save(r.list)
        r.message
    }

    fun unpin(id: Long) = synchronized(this) { save(PinnedFacts.remove(all(), id)) }

    /** "unpin coffee": removes the facts that contain every word; returns the line for the user. */
    fun unpinMatching(query: String): String = synchronized(this) {
        val hit = PinnedFacts.matching(all(), query)
        if (hit.isEmpty()) return "Nothing pinned matches \u201c${query.trim()}\u201d."
        save(all().filterNot { f -> hit.any { it.id == f.id } })
        if (hit.size == 1) "Unpinned: ${hit[0].text}." else "Unpinned ${hit.size} facts: " + hit.joinToString("; ") { it.text } + "."
    }

    /** The system-prompt block ("" when nothing is pinned or Settings can't be read). */
    fun block(): String = try { PinnedFacts.block(all()) } catch (_: Throwable) { "" }
}
