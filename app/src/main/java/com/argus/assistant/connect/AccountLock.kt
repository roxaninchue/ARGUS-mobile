package com.blaze.assistant.connect

import com.blaze.assistant.BrandName
import org.json.JSONObject

/**
 * Account lock (blaze-auth 0.10.0, gateway 0.9.22): nobody uses ARGUS without a signed-in account with a confirmed,
 * real email address. The servers enforce it (a locked account gets no session and every Bearer call answers 403
 * account_locked); the phone mirrors it so nothing of ARGUS runs, on screen or in the background, for an account the
 * servers refused. Pure rules; [AccountGate] holds the state on the phone and closes the side doors.
 */
object AccountLock {
    /** How long the phone keeps working without reaching blaze-auth (/v1/me) before it asks to connect. */
    const val GRACE_MS = 7L * 24 * 60 * 60 * 1000
    /** A last check this far in the future means the clock was moved: it counts as no check. */
    private const val CLOCK_SLACK_MS = 24L * 60 * 60 * 1000

    enum class Reason { SIGNED_OUT, EMAIL_UNVERIFIED, DISPOSABLE_EMAIL, SESSION_REJECTED, OFFLINE_TOO_LONG }

    /**
     * What the phone knows. [lastOkAt]: the last time blaze-auth said yes (a sign-in or a /v1/me), 0 = never.
     * [rejected]: a server refusal not cleared since (any 401, or 403 account_locked). [verifyOn]: the account server
     * asks for confirmed emails (/v1/providers signup_verify, blaze-auth 0.10.0+); before that the email rules stay off,
     * so an app update that lands before the server can't lock anyone out over a step the server can't do yet.
     */
    data class State(val signedIn: Boolean, val emailVerified: Boolean, val email: String, val lastOkAt: Long,
                     val rejected: Reason? = null, val verifyOn: Boolean = true)

    /** Why ARGUS is locked now, or null when it is open. */
    fun decide(s: State, now: Long): Reason? {
        val emailLock = s.rejected == Reason.EMAIL_UNVERIFIED || s.rejected == Reason.DISPOSABLE_EMAIL
        if (!s.signedIn) return if (emailLock) s.rejected else Reason.SIGNED_OUT
        if (s.rejected != null) return s.rejected
        if (s.verifyOn && ThrowawayEmail.isThrowaway(s.email)) return Reason.DISPOSABLE_EMAIL
        if (s.verifyOn && !s.emailVerified) return Reason.EMAIL_UNVERIFIED
        if (s.lastOkAt <= 0L || s.lastOkAt > now + CLOCK_SLACK_MS || now - s.lastOkAt > GRACE_MS) return Reason.OFFLINE_TOO_LONG
        return null
    }

    /** Time left in the offline grace (0 when it is over). */
    fun graceLeftMs(lastOkAt: Long, now: Long): Long = if (lastOkAt <= 0L) 0L else (lastOkAt + GRACE_MS - now).coerceIn(0L, GRACE_MS)

    /**
     * The first start after the update that brought the lock: an account that is signed in and already confirmed gets
     * the full grace counted from now (the update), not from a check it never had. Otherwise the stored value stays.
     */
    fun upgradeLastOk(stored: Long, signedIn: Boolean, verified: Boolean, now: Long): Long =
        if (stored > 0L) stored else if (signedIn && verified) now else 0L

    /**
     * The lock a server answer to a signed-in call means, or null. Any 401 (blaze-auth "unauthorized", the gateway's
     * "invalid_api_key") locks until a /v1/me says yes. A 403 locks only when it is account_locked, in either shape:
     * blaze-auth's flat {error:"account_locked", reason} or the gateway's {error:{code:"account_locked", reason, message}}.
     */
    fun fromResponse(status: Int, body: String?): Reason? {
        if (status == 401) return Reason.SESSION_REJECTED
        if (status != 403 || body.isNullOrBlank()) return null
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return null
        return lockedReason(o)
    }

    /** account_locked (flat or nested) or a bare disposable_email, as a [Reason]; null for any other body. */
    fun lockedReason(o: JSONObject): Reason? {
        val code: String; val reason: String
        when (val e = o.opt("error")) {
            is JSONObject -> { code = e.optString("code").ifBlank { e.optString("type") }; reason = e.optString("reason").ifBlank { o.optString("reason") } }
            is String -> { code = e; reason = o.optString("reason") }
            else -> return null
        }
        return when (code) {
            "account_locked" -> if (reason == "disposable_email") Reason.DISPOSABLE_EMAIL else Reason.EMAIL_UNVERIFIED
            "disposable_email" -> Reason.DISPOSABLE_EMAIL
            else -> null
        }
    }

    const val TITLE = "Sign in to ${BrandName.UPPER}"

    /** The lock screen's line for [r]. */
    fun message(r: Reason): String = when (r) {
        Reason.SIGNED_OUT -> "Sign in or create a free account to use ${BrandName.UPPER}."
        Reason.EMAIL_UNVERIFIED -> "Confirm your email to use ${BrandName.UPPER}: sign in and enter the code we email you."
        Reason.DISPOSABLE_EMAIL -> "${BrandName.UPPER} needs a real email address. Throwaway addresses can't be used: create an account with your own email."
        Reason.SESSION_REJECTED -> "${BrandName.UPPER} couldn't confirm your sign-in. Connect to the internet, or sign in again."
        Reason.OFFLINE_TOO_LONG -> "${BrandName.UPPER} hasn't been able to check your account for 7 days. Connect to the internet to keep going."
    }

    /** Notification buttons that still work while locked: they only stop things (Stop, Stop Magic Hand, Don't). */
    val ACTIONS_WHILE_LOCKED = setOf("com.blaze.assistant.STOP", "com.blaze.assistant.STOP_HAND", "com.blaze.assistant.DENY")
    fun actionAllowed(locked: Boolean, action: String?): Boolean = !locked || action in ACTIONS_WHILE_LOCKED

    /** Music: while locked only ARGUS itself may hold a controller (Android Auto and other apps are turned away). */
    fun mediaAccepts(locked: Boolean, controllerPkg: String, ownPkg: String): Boolean = !locked || controllerPkg == ownPkg
}
