package com.blaze.assistant.connect

import com.blaze.assistant.agent.Settings
import java.security.SecureRandom

/**
 * Account lock: one opaque id per app install, sent with a sign-up (blaze-auth 0.10.0 `device`: at most 3 new
 * accounts a day per install; the server keeps only its hash). Random, never derived from the phone (no Android ID,
 * no hardware id). Kept as a "conn_tok_" pref, so it is Keystore-encrypted, never synced and never backed up: a phone
 * restored from a backup or a reinstall gets a new one.
 */
object DeviceId {
    internal const val PREF = "conn_tok_argus_install_id"
    private val SHAPE = Regex("^ai_[A-Za-z0-9_-]{22,64}$")

    /** True for an id this class made ("ai_" + 22 to 64 base64url characters). */
    fun valid(id: String): Boolean = SHAPE.matches(id)

    /** A new id: "ai_" + 16 random bytes in base64url (22 characters, no padding). */
    fun fresh(rnd: java.util.Random = SecureRandom()): String {
        val b = ByteArray(16).also { rnd.nextBytes(it) }
        return "ai_" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }

    /** The saved id, else [make] a new one and save it. Pure over its two stores (unit-tested). */
    fun getOrMake(read: () -> String, write: (String) -> Unit, make: () -> String = { fresh() }): String {
        val have = runCatching(read).getOrDefault("")
        if (valid(have)) return have
        val n = make()
        runCatching { write(n) }
        return n
    }

    /** This install's id. */
    fun get(): String = getOrMake({ Settings.secret(PREF) }, { Settings.setSecret(PREF, it) })
}
