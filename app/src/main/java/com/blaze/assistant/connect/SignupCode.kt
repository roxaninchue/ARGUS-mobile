package com.blaze.assistant.connect

import com.blaze.assistant.BrandName
import org.json.JSONObject

/**
 * Account lock: the emailed code that confirms an address (blaze-auth 0.10.0, cloud/src/verify.ts).
 *   POST /v1/auth/signup            -> 202 {verify_required:true, verify_token, email_hint}   (no session yet)
 *   POST /v1/auth/login             -> 403 {error:"email_unverified", verify_token, email_hint} (an unverified account;
 *                                      a code is mailed and the same step finishes it)
 *   POST /v1/auth/signup/verify     {verify_token, code, client} -> 200 {token, user} | 401 mfa_required |
 *                                      400 {error:"invalid_code", tries_left} | 400 verify_expired | 403 disposable_email
 *   POST /v1/auth/signup/resend     {verify_token} -> 200 {ok:true} | 429 rate_limited (the token's 3 mails are used,
 *                                      or the per-IP limit) | 400 verify_expired
 * The token lives 1 hour and allows 5 wrong codes; each code works 10 minutes; 3 mails per token, the first included.
 * Pure: the HTTP call is passed in, so the flow is unit-tested without a network.
 */
object SignupCode {
    const val TTL_MS = 3_600_000L
    const val CODE_MS = 600_000L
    const val TRIES = 5
    const val SENDS = 3
    /** Client-side wait between mails (the server only caps the count). */
    const val RESEND_MS = 30_000L
    const val VERIFY_PATH = "/v1/auth/signup/verify"
    const val RESEND_PATH = "/v1/auth/signup/resend"

    /** An address waiting for its code. [sends] counts mails sent for this token (the first one included). */
    data class Pending(val token: String, val emailHint: String, val startedAt: Long, val triesLeft: Int = TRIES,
                       val sends: Int = 1, val lastSentAt: Long = startedAt, val email: String = "") {
        val sendsLeft get() = (SENDS - sends).coerceAtLeast(0)
        fun expired(now: Long): Boolean = now >= startedAt + TTL_MS || triesLeft <= 0
        fun codeLeftMs(now: Long): Long = (lastSentAt + CODE_MS - now).coerceAtLeast(0L)
        fun resendInMs(now: Long): Long = (lastSentAt + RESEND_MS - now).coerceAtLeast(0L)
        fun canResend(now: Long): Boolean = sendsLeft > 0 && resendInMs(now) == 0L && !expired(now)
        val sentTo get() = emailHint.ifBlank { email }.ifBlank { "your email" }
    }

    /** The code step a sign-up (202) or a password sign-in on an unverified account (403 email_unverified) opens, else null. */
    fun pendingOf(status: Int, body: JSONObject?, now: Long, email: String = ""): Pending? {
        if (body == null) return null
        val opens = (status == 202 && body.optBoolean("verify_required")) || (status == 403 && body.optString("error") == "email_unverified")
        val tok = body.optString("verify_token").trim()
        if (!opens || tok.isEmpty()) return null
        return Pending(tok, body.optString("email_hint"), now, email = email.trim())
    }

    sealed class Outcome {
        /** Confirmed and signed in: the {token, user} body. */
        data class Signed(val body: JSONObject) : Outcome()
        /** Confirmed; the account has two-step verification, so the 401 mfa_required body goes to that step. */
        data class TwoStep(val body: JSONObject) : Outcome()
        /** The step stays with a new state (wrong code, new code mailed, no mails left): show [message]. */
        data class Retry(val pending: Pending, val message: String) : Outcome()
        /** The step is over (expired, too many tries, unknown token, throwaway address): back to sign-in with [message]. */
        data class Restart(val message: String) : Outcome()
        /** Nothing was spent (bad format, offline, rate limit): show [message], the step stays as it was. */
        data class Error(val message: String) : Outcome()
    }

    const val EXPIRED = "This code step ran out (1 hour or 5 tries). Sign in again to get a new code."
    const val NO_MORE = "No more codes can be sent for this sign-up. Use the newest one, or sign in again in an hour."
    private const val OFFLINE = "Couldn't reach ${BrandName.NAME}'s sign-in service. Check your internet connection."

    private fun mmss(ms: Long): String { val s = (ms + 999) / 1000; return "%d:%02d".format(s / 60, s % 60) }

    /** The 6 digits as the server wants them, or null. */
    fun clean(raw: String): String? = raw.replace(Regex("\\s|-"), "").takeIf { it.length == 6 && it.all { c -> c in '0'..'9' } }

    /** "Code works 9:58 more · 5 tries left", for the code step. */
    fun stateLine(p: Pending, now: Long): String =
        "Code works " + mmss(p.codeLeftMs(now)) + " more · " + (if (p.triesLeft == 1) "1 try left" else "${p.triesLeft} tries left")

    /** The Resend button's line: "", "New code in 0:25", "2 more codes can be sent", or the no-more sentence. */
    fun resendLine(p: Pending, now: Long): String = when {
        p.sendsLeft <= 0 -> "No more codes can be sent for this sign-up."
        p.resendInMs(now) > 0 -> "New code in " + mmss(p.resendInMs(now))
        else -> (if (p.sendsLeft == 1) "1 more code" else "${p.sendsLeft} more codes") + " can be sent"
    }

    /** One verify try. An expired step never calls the server. */
    fun verify(p: Pending, raw: String, now: Long, client: String,
               post: (path: String, body: JSONObject) -> Pair<Int, JSONObject>): Outcome {
        if (p.expired(now)) return Outcome.Restart(EXPIRED)
        val code = clean(raw) ?: return Outcome.Error("Enter the 6-digit code from the email.")
        val (status, body) = try {
            post(VERIFY_PATH, JSONObject().put("verify_token", p.token).put("code", code).put("client", client))
        } catch (_: Exception) { return Outcome.Error(OFFLINE) }
        if (status in 200..299) {
            return if (body.optString("token").isNotBlank() && body.optJSONObject("user") != null) Outcome.Signed(body)
            else Outcome.Error("Sign-in failed (no_session).")
        }
        Mfa.tokenOf(status, body)?.let { return Outcome.TwoStep(body) }
        return when (body.optString("error")) {
            "invalid_code" -> {
                val left = if (body.has("tries_left")) body.optInt("tries_left", p.triesLeft - 1) else p.triesLeft - 1
                if (left <= 0) Outcome.Restart(EXPIRED)
                else Outcome.Retry(p.copy(triesLeft = left), "That code didn't work. " + (if (left == 1) "1 try left." else "$left tries left."))
            }
            // The server says verify_expired for a dead token and for a code past its 10 minutes: a new mail fixes the second.
            "verify_expired" -> if (p.codeLeftMs(now) == 0L && p.sendsLeft > 0 && now < p.startedAt + TTL_MS)
                Outcome.Retry(p, "That code ran out after 10 minutes. Tap Resend for a new one.") else Outcome.Restart(EXPIRED)
            "disposable_email" -> Outcome.Restart(ThrowawayEmail.MESSAGE)
            "rate_limited" -> Outcome.Error("Too many tries. Wait a few minutes and try again.")
            else -> Outcome.Error("Couldn't check the code (" + body.optString("error").ifBlank { "http_$status" } + ").")
        }
    }

    /** Mails a new code. Never sends while the wait or the 3-mail limit says no; a server 429 is taken as the limit. */
    fun resend(p: Pending, now: Long, post: (path: String, body: JSONObject) -> Pair<Int, JSONObject>): Outcome {
        if (p.expired(now)) return Outcome.Restart(EXPIRED)
        if (p.sendsLeft <= 0) return Outcome.Error(NO_MORE)
        if (p.resendInMs(now) > 0) return Outcome.Error("Wait " + mmss(p.resendInMs(now)) + " before asking for a new code.")
        val (status, body) = try { post(RESEND_PATH, JSONObject().put("verify_token", p.token)) } catch (_: Exception) { return Outcome.Error(OFFLINE) }
        if (status in 200..299) {
            val q = p.copy(sends = p.sends + 1, lastSentAt = now)
            return Outcome.Retry(q, "We sent a new code to " + q.sentTo + ". It works for 10 minutes.")
        }
        return when {
            // 3 mails per token: the server answers 429 rate_limited with retry_after = the token's remaining life.
            // A short retry_after with mails left is the per-IP limit instead: wait, nothing is spent.
            status == 429 && (p.sendsLeft <= 1 || body.optLong("retry_after", 0L) > 600L) -> Outcome.Retry(p.copy(sends = SENDS), NO_MORE)
            status == 429 -> Outcome.Error("Too many tries. Wait a few minutes and try again.")
            body.optString("error") == "verify_expired" -> Outcome.Restart(EXPIRED)
            status == 503 -> Outcome.Error("Codes by email aren't switched on yet.")
            else -> Outcome.Error("Couldn't send a new code (" + body.optString("error").ifBlank { "http_$status" } + ").")
        }
    }
}
