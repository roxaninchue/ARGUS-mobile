package com.blaze.assistant.browser.phone.instances

import com.blaze.assistant.browser.phone.PhonePolicy

/**
 * Argus Phone instances, step 1 + 1a of docs/ARGUS-PHONE.md (section 5a, 5g, 6). An instance is one Argus Phone: its own
 * open apps (for now only the browser: url, title, site, scroll), its own recent steps, its own lock and its own
 * checkpoint on disk. Pure Kotlin (no Android): the registry, leases, checkpoints and the deep-state snapshot are all
 * JVM-tested (PhoneInstancesTest). The Android side ([ArgusPhone], [ArgusPhoneService]) only binds these to the one
 * live WebView (PhoneBrowser) and the notification.
 */

/**
 * COLD: only its checkpoint on disk (no RAM beyond a one-line header). WARM: its state model in memory, nothing drawing,
 * no WebView. LIVE: attached to Argus Phone's one WebView (frames are made only while someone watches, as today).
 */
enum class Tier { COLD, WARM, LIVE }

/** Who holds a lock. The id comes from the runtime (a chat id, a background agent id), never from the model's words. */
data class Owner(val kind: Kind, val id: String, val name: String = "") {
    enum class Kind { ARGUS, AGENT, MEMBER, ROUTINE, USER }

    fun label(): String = when (kind) {
        Kind.ARGUS -> "Argus (this chat)"
        Kind.AGENT -> "background agent " + name.ifBlank { id }.take(40)
        Kind.MEMBER -> "team member " + name.ifBlank { id }.take(40)
        Kind.ROUTINE -> "routine " + name.ifBlank { id }.take(40)
        Kind.USER -> "you"
    }

    fun same(o: Owner?): Boolean = o != null && o.kind == kind && o.id == id

    companion object { val USER = Owner(Kind.USER, "user", "you") }
}

/**
 * A lock is a lease: it names its owner and task, runs until [until] unless renewed, and counts as *working* while the
 * owner renewed or acted in the last [Leases.WORKING_MS]. A lapsed lease frees the instance (after a checkpoint).
 */
data class Lease(val owner: Owner, val task: String, val since: Long, val until: Long, val renewed: Long)

/** The browser app's state: enough to put the page back where it was. */
data class BrowserState(val url: String, val title: String = "", val site: String = "", val scrollY: Int = 0) {
    val empty: Boolean get() = url.isBlank()
}

/** Something waiting on the user in this instance (step 2 adds credentials / choice / approval cards; today: a handoff). */
data class PendingCard(val id: String, val kind: String, val text: String, val at: Long)

/** One recent step (what Argus or the user did here). */
data class Step(val at: Long, val text: String)

/**
 * One instance. Immutable: the registry swaps whole values, so a reader never sees half an update. A COLD instance keeps
 * only its header in memory ([body] false): [browser] and [steps] are on disk until it is restored.
 */
data class Instance(
    val id: String,
    val name: String,
    val task: String = "",
    val createdAt: Long,
    val lastActiveAt: Long,
    val tier: Tier = Tier.WARM,
    val mode: PhonePolicy.Mode = PhonePolicy.Mode.OFF,
    val browser: BrowserState? = null,
    val unsaved: Boolean = false,
    val cards: List<PendingCard> = emptyList(),
    val lastCheckpointAt: Long = 0L,
    val lastError: String? = null,
    /** The last error left it broken: Restore (from its checkpoint) or Reset brings it back; nothing else is affected. */
    val restorable: Boolean = false,
    val steps: List<Step> = emptyList(),
    val lease: Lease? = null,
    /** False for a COLD header whose [browser] / [steps] are only on disk. */
    val body: Boolean = true,
) {
    /** The open apps. Step 3 (the app contract) turns this into a list of PhoneApp states. */
    fun apps(): List<String> = if (browser != null && !browser.empty) listOf("browser") else emptyList()

    fun withStep(text: String, at: Long, max: Int = STEPS): Instance = copy(steps = (steps + Step(at, text.take(120))).takeLast(max))

    /** Header only (what a COLD instance keeps in memory). */
    fun header(): Instance = copy(browser = null, steps = emptyList(), body = false, tier = Tier.COLD)

    companion object {
        /** Recent steps kept per instance (a ring: the oldest drops off). */
        const val STEPS = 12
        private val ID_RE = Regex("^i[a-z0-9]{4,24}$")
        /** Ids are ours (letters and digits after "i"); anything else on disk is ignored, never used as a path. */
        fun validId(id: String): Boolean = ID_RE.matches(id)
    }
}

/** How many instances may be LIVE and WARM at once (docs/ARGUS-PHONE.md section 6). Cold ones cost disk only. */
data class Budget(val live: Int, val warm: Int) {
    companion object {
        fun of(lowRam: Boolean): Budget = if (lowRam) Budget(1, 2) else Budget(2, 4)
        /** Instances kept at all (cold included), so a runaway loop can't fill the disk with checkpoints. */
        const val MAX_INSTANCES = 16
    }
}
