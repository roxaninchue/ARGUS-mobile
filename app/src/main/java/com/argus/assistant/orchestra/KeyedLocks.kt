package com.blaze.assistant.orchestra

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock

/**
 * 0.52.0: process-wide reader/writer locks by resource key ("file:/root/app/Main.kt", "screen", "browser", "term:3").
 *
 * Used by [ToolScheduler] (several tool calls of one reply) and the [Orchestrator] (several agents of one blueprint),
 * and shared between them, so a background agent filling a file and the chat agent editing it never write at once, and
 * only one action drives the phone screen at a time, whichever agent asks.
 *
 * A key is a [Semaphore] of [PERMITS]: a reader takes one permit, a writer takes them all (under the key's writer
 * mutex, so two writers never deadlock half-way). Several keys are always taken in sorted order, so two holders of
 * overlapping sets can't deadlock either. Pure (kotlinx.coroutines only); KeyedLocksTest.
 */
class KeyedLocks {
    private class Entry { val sem = Semaphore(PERMITS); val writer = Mutex() }
    private val map = HashMap<String, Entry>()

    private fun entry(key: String): Entry = synchronized(map) { map.getOrPut(key) { Entry() } }

    data class Claim(val key: String, val write: Boolean)

    /** Runs [block] holding every claim (writers exclusive, readers shared). An empty set runs at once. */
    suspend fun <T> with(claims: Collection<Claim>, block: suspend () -> T): T {
        if (claims.isEmpty()) return block()
        // One claim per key, write wins; sorted so overlapping sets are always taken in the same order.
        val merged = claims.groupBy { it.key }.map { (k, cs) -> Claim(k, cs.any { it.write }) }.sortedBy { it.key }
        val taken = ArrayList<Pair<Entry, Int>>()
        try {
            for (c in merged) {
                val e = entry(c.key)
                if (c.write) {
                    e.writer.withLock { repeat(PERMITS) { e.sem.acquire(); taken += e to 1 } }
                } else {
                    // A waiting writer goes first: a reader passes the writer mutex before taking its permit.
                    e.writer.withLock { }
                    e.sem.acquire(); taken += e to 1
                }
            }
            return block()
        } finally {
            for ((e, n) in taken.asReversed()) repeat(n) { e.sem.release() }
        }
    }

    /** True when nobody holds or waits on [key] (tests, diagnostics). */
    fun idle(key: String): Boolean = synchronized(map) { map[key] }?.let { it.sem.availablePermits == PERMITS && !it.writer.isLocked } ?: true

    companion object {
        const val PERMITS = 64
        /** The one instance the app shares (tool calls of every agent, every blueprint). */
        val shared = KeyedLocks()
    }
}
