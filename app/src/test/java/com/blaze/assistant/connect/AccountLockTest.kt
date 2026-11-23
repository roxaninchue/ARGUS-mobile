package com.blaze.assistant.connect

import com.blaze.assistant.connect.AccountLock.Reason
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Account lock: shapes from blaze-auth 0.10.0 (Blaze-dekstop cloud/src/verify.ts, accounts.ts, index.ts signup / login)
 * and the gateway 0.9.22 (Blaze-agent gateway/src/auth.ts requireUser + util.ts apiError).
 */
class AccountLockTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 1_800_000_000_000L
    private fun st(signedIn: Boolean = true, verified: Boolean = true, email: String = "finn@gmail.com", lastOk: Long = now - day,
                   rejected: Reason? = null, verifyOn: Boolean = true) = AccountLock.State(signedIn, verified, email, lastOk, rejected, verifyOn)

    // ---------------------------------------------------------------- the rule

    @Test fun openWhenSignedInConfirmedAndCheckedLately() {
        assertNull(AccountLock.decide(st(), now))
        assertNull("6 days 23 h offline still works", AccountLock.decide(st(lastOk = now - 7 * day + 60_000), now))
    }

    @Test fun signedOutIsLocked() {
        assertEquals(Reason.SIGNED_OUT, AccountLock.decide(st(signedIn = false), now))
        // a refusal of the account itself stays the reason after the local sign-out
        assertEquals(Reason.EMAIL_UNVERIFIED, AccountLock.decide(st(signedIn = false, rejected = Reason.EMAIL_UNVERIFIED), now))
        assertEquals(Reason.SIGNED_OUT, AccountLock.decide(st(signedIn = false, rejected = Reason.SESSION_REJECTED), now))
    }

    @Test fun sevenDayOfflineGrace() {
        assertEquals(Reason.OFFLINE_TOO_LONG, AccountLock.decide(st(lastOk = now - 7 * day - 1), now))
        assertEquals("never checked", Reason.OFFLINE_TOO_LONG, AccountLock.decide(st(lastOk = 0L), now))
        assertEquals("a check from the future (clock moved) counts as none", Reason.OFFLINE_TOO_LONG, AccountLock.decide(st(lastOk = now + 3 * day), now))
        assertEquals(day * 6, AccountLock.graceLeftMs(now - day, now))
        assertEquals(0L, AccountLock.graceLeftMs(now - 8 * day, now))
    }

    @Test fun upgradingConfirmedUsersGetSevenDaysFromTheUpdate() {
        assertEquals(now, AccountLock.upgradeLastOk(0L, signedIn = true, verified = true, now = now))
        assertEquals("unconfirmed: no grace given", 0L, AccountLock.upgradeLastOk(0L, signedIn = true, verified = false, now = now))
        assertEquals("signed out: nothing", 0L, AccountLock.upgradeLastOk(0L, signedIn = false, verified = true, now = now))
        assertEquals("a stored check is kept", now - day, AccountLock.upgradeLastOk(now - day, true, true, now))
        assertNull(AccountLock.decide(st(lastOk = AccountLock.upgradeLastOk(0L, true, true, now)), now + 6 * day))
        assertEquals(Reason.OFFLINE_TOO_LONG, AccountLock.decide(st(lastOk = AccountLock.upgradeLastOk(0L, true, true, now)), now + 8 * day))
    }

    @Test fun unconfirmedAndThrowawayEmailsLockOnlyOnceTheServerAsks() {
        assertEquals(Reason.EMAIL_UNVERIFIED, AccountLock.decide(st(verified = false), now))
        assertEquals(Reason.DISPOSABLE_EMAIL, AccountLock.decide(st(email = "x@mailinator.com"), now))
        // an app update that lands before blaze-auth 0.10.0 locks nobody over email
        assertNull(AccountLock.decide(st(verified = false, verifyOn = false), now))
        assertNull(AccountLock.decide(st(email = "x@mailinator.com", verifyOn = false), now))
    }

    @Test fun aRefusalLocksUntilCleared() {
        assertEquals(Reason.SESSION_REJECTED, AccountLock.decide(st(rejected = Reason.SESSION_REJECTED), now))
        assertEquals(Reason.DISPOSABLE_EMAIL, AccountLock.decide(st(rejected = Reason.DISPOSABLE_EMAIL), now))
    }

    // ---------------------------------------------------------------- server answers

    @Test fun anyUnauthorizedLocks() {
        assertEquals(Reason.SESSION_REJECTED, AccountLock.fromResponse(401, """{"error":"unauthorized"}"""))
        assertEquals(Reason.SESSION_REJECTED, AccountLock.fromResponse(401, """{"error":{"message":"Your ARGUS session has expired. Sign in again.","type":"invalid_api_key","code":"invalid_api_key"}}"""))
        assertEquals(Reason.SESSION_REJECTED, AccountLock.fromResponse(401, null))
        assertNull(AccountLock.fromResponse(200, "{}"))
        assertNull(AccountLock.fromResponse(500, """{"error":"account_locked"}"""))
    }

    @Test fun blazeAuthFlatAccountLocked() {
        assertEquals(Reason.EMAIL_UNVERIFIED, AccountLock.fromResponse(403, """{"error":"account_locked","reason":"email_unverified"}"""))
        assertEquals(Reason.DISPOSABLE_EMAIL, AccountLock.fromResponse(403, """{"error":"account_locked","reason":"disposable_email"}"""))
        assertEquals(Reason.DISPOSABLE_EMAIL, AccountLock.fromResponse(403, """{"error":"disposable_email"}"""))
    }

    @Test fun gatewayNestedAccountLocked() {
        val b = """{"error":{"message":"Confirm your email address to use ARGUS: sign in again and enter the code we email you.","type":"account_locked","code":"account_locked","reason":"email_unverified"}}"""
        assertEquals(Reason.EMAIL_UNVERIFIED, AccountLock.fromResponse(403, b))
        assertEquals(Reason.DISPOSABLE_EMAIL, AccountLock.fromResponse(403, b.replace("\"reason\":\"email_unverified\"", "\"reason\":\"disposable_email\"")))
    }

    @Test fun otherForbiddenAnswersDoNotLock() {
        assertNull(AccountLock.fromResponse(403, """{"error":{"code":"not_approved","message":"waitlist"}}"""))
        assertNull(AccountLock.fromResponse(403, """{"error":"forbidden"}"""))
        assertNull(AccountLock.fromResponse(403, "<html>nope</html>"))
        assertNull(AccountLock.fromResponse(403, ""))
    }

    // ---------------------------------------------------------------- side doors (pure parts)

    @Test fun onlyStopAndDontWorkWhileLocked() {
        for (a in listOf("com.blaze.assistant.STOP", "com.blaze.assistant.DENY")) assertTrue(a, AccountLock.actionAllowed(true, a))
        for (a in listOf("com.blaze.assistant.REPLY", "com.blaze.assistant.ALLOW", "com.blaze.assistant.DO", null)) assertFalse("$a", AccountLock.actionAllowed(true, a))
        assertTrue(AccountLock.actionAllowed(false, "com.blaze.assistant.DO"))
        assertTrue(AccountLock.mediaAccepts(false, "com.google.android.projection.gearhead", "com.blaze.assistant"))
        assertFalse("Android Auto is turned away", AccountLock.mediaAccepts(true, "com.google.android.projection.gearhead", "com.blaze.assistant"))
        assertTrue(AccountLock.mediaAccepts(true, "com.blaze.assistant", "com.blaze.assistant"))
    }

    // ---------------------------------------------------------------- the email code

    /** A stand-in for /v1/auth/signup/verify and /resend with blaze-auth 0.10.0's rules. */
    private class FakeVerify(val good: String = "482913") {
        var tries = 0; var sends = 1; var dead = false; val seen = mutableListOf<Pair<String, JSONObject>>()
        fun post(path: String, b: JSONObject): Pair<Int, JSONObject> {
            seen += path to JSONObject(b.toString())
            if (dead || !b.optString("verify_token").startsWith("vt_")) return 400 to JSONObject().put("error", "verify_expired")
            if (path == SignupCode.RESEND_PATH) {
                if (sends >= SignupCode.SENDS) return 429 to JSONObject().put("error", "rate_limited").put("retry_after", 3000)
                sends++; return 200 to JSONObject().put("ok", true)
            }
            if (b.optString("code") != good) {
                tries++; if (tries >= SignupCode.TRIES) dead = true
                return 400 to JSONObject().put("error", "invalid_code").put("tries_left", SignupCode.TRIES - tries)
            }
            dead = true
            return 200 to JSONObject().put("token", "bz_x").put("user", JSONObject().put("id", "u1").put("email", "finn@gmail.com").put("email_verified", true))
        }
    }

    private val tok = "vt_" + "a".repeat(43)

    @Test fun signupAndUnverifiedLoginOpenTheCodeStep() {
        val p = SignupCode.pendingOf(202, JSONObject().put("verify_required", true).put("verify_token", tok).put("email_hint", "f•••@gmail.com"), now, "finn@gmail.com")
        assertNotNull(p); assertEquals(tok, p!!.token); assertEquals("f•••@gmail.com", p.sentTo); assertEquals(2, p.sendsLeft)
        val q = SignupCode.pendingOf(403, JSONObject().put("error", "email_unverified").put("verify_token", tok).put("email_hint", "f•••@gmail.com"), now)
        assertNotNull("login email_unverified opens the same step", q)
        assertNull(SignupCode.pendingOf(403, JSONObject().put("error", "disposable_email"), now))
        assertNull(SignupCode.pendingOf(200, JSONObject().put("token", "bz_x"), now))
        assertNull("no token, no step", SignupCode.pendingOf(202, JSONObject().put("verify_required", true), now))
    }

    @Test fun verifyRightCodeSignsIn() {
        val f = FakeVerify(); val p = SignupCode.Pending(tok, "f•••@gmail.com", now)
        val r = SignupCode.verify(p, " 482 913 ", now + 1000, "Blaze Android", f::post)
        assertTrue(r is SignupCode.Outcome.Signed)
        assertEquals(SignupCode.VERIFY_PATH, f.seen.single().first)
        assertEquals("482913", f.seen.single().second.optString("code")); assertEquals("Blaze Android", f.seen.single().second.optString("client"))
    }

    @Test fun verifyWrongCodeCountsDownThenRestarts() {
        val f = FakeVerify(); var p = SignupCode.Pending(tok, "", now)
        repeat(SignupCode.TRIES - 1) {
            val r = SignupCode.verify(p, "000000", now, "c", f::post) as SignupCode.Outcome.Retry
            p = r.pending; assertTrue(r.message.startsWith("That code didn't work."))
        }
        assertEquals(1, p.triesLeft)
        assertTrue(SignupCode.verify(p, "000000", now, "c", f::post) is SignupCode.Outcome.Restart)
        val calls = f.seen.size
        assertTrue("bad format never reaches the server", SignupCode.verify(SignupCode.Pending(tok, "", now), "12ab", now, "c", f::post) is SignupCode.Outcome.Error)
        assertEquals(calls, f.seen.size)
    }

    @Test fun verifyOtherAnswers() {
        val p = SignupCode.Pending(tok, "", now)
        val mfa = SignupCode.verify(p, "123456", now, "c") { _, _ -> 401 to JSONObject().put("error", "mfa_required").put("mfa_token", "mfa_1") }
        assertTrue("two-step accounts go on to the 2FA step", mfa is SignupCode.Outcome.TwoStep)
        val throwaway = SignupCode.verify(p, "123456", now, "c") { _, _ -> 403 to JSONObject().put("error", "disposable_email") }
        assertEquals(SignupCode.Outcome.Restart(ThrowawayEmail.MESSAGE), throwaway)
        // a code past its 10 minutes with mails left: Resend fixes it; a dead token: start again
        val late = SignupCode.verify(p, "123456", now + SignupCode.CODE_MS + 1) { _, _ -> 400 to JSONObject().put("error", "verify_expired") }
        assertTrue(late is SignupCode.Outcome.Retry)
        val dead = SignupCode.verify(p, "123456", now + 1000, "c") { _, _ -> 400 to JSONObject().put("error", "verify_expired") }
        assertEquals(SignupCode.Outcome.Restart(SignupCode.EXPIRED), dead)
        assertTrue("an hour later the step is over without a call", SignupCode.verify(p, "123456", now + SignupCode.TTL_MS, "c") { _, _ -> error("no call") } is SignupCode.Outcome.Restart)
        assertTrue(SignupCode.verify(p, "123456", now, "c") { _, _ -> throw java.io.IOException("offline") } is SignupCode.Outcome.Error)
    }

    private fun SignupCode.verify(p: SignupCode.Pending, raw: String, at: Long, post: (String, JSONObject) -> Pair<Int, JSONObject>) = verify(p, raw, at, "c", post)

    @Test fun resendThreeMailsPerTokenThenTheServerSaysNo() {
        val f = FakeVerify(); var p = SignupCode.Pending(tok, "f•••@gmail.com", now)
        var t = now
        assertTrue("30 s between mails", SignupCode.resend(p, t + 1_000, f::post) is SignupCode.Outcome.Error)
        repeat(2) {
            t += SignupCode.RESEND_MS
            val r = SignupCode.resend(p, t, f::post) as SignupCode.Outcome.Retry
            p = r.pending; assertTrue(r.message.contains("f•••@gmail.com"))
        }
        assertEquals(3, p.sends); assertEquals(0, p.sendsLeft)
        val calls = f.seen.size
        assertEquals(SignupCode.Outcome.Error(SignupCode.NO_MORE), SignupCode.resend(p, t + SignupCode.RESEND_MS, f::post))
        assertEquals("the limit is kept on the phone too", calls, f.seen.size)
        assertEquals("No more codes can be sent for this sign-up.", SignupCode.resendLine(p, t))
    }

    @Test fun resend429IsHandled() {
        val p = SignupCode.Pending(tok, "", now, sends = 2)
        // the server counted a mail the phone didn't (another device / a retry): 429 ends the sending
        val r = SignupCode.resend(p, now + SignupCode.RESEND_MS) { _, _ -> 429 to JSONObject().put("error", "rate_limited").put("retry_after", 3000) }
        assertEquals(SignupCode.Outcome.Retry(p.copy(sends = SignupCode.SENDS), SignupCode.NO_MORE), r)
        // a short per-IP limit with mails left: wait, nothing spent
        val q = SignupCode.Pending(tok, "", now, sends = 1)
        val w = SignupCode.resend(q, now + SignupCode.RESEND_MS) { _, _ -> 429 to JSONObject().put("error", "rate_limited").put("retry_after", 60) }
        assertTrue(w is SignupCode.Outcome.Error)
        assertTrue(SignupCode.resend(q, now + SignupCode.RESEND_MS) { _, _ -> 400 to JSONObject().put("error", "verify_expired") } is SignupCode.Outcome.Restart)
    }

    @Test fun plainWordsForEveryLockAndCode() {
        for (r in Reason.values()) assertTrue(r.name, AccountLock.message(r).contains("ARGUS"))
        assertEquals("Sign in to ARGUS", AccountLock.TITLE)
        assertEquals("123456", SignupCode.clean("123 456")); assertEquals("123456", SignupCode.clean("123-456"))
        assertNull(SignupCode.clean("12345")); assertNull(SignupCode.clean("abcdef"))
        val p = SignupCode.Pending(tok, "", now)
        assertEquals("Code works 10:00 more · 5 tries left", SignupCode.stateLine(p, now))
        assertEquals("New code in 0:30", SignupCode.resendLine(p, now))
        assertEquals("2 more codes can be sent", SignupCode.resendLine(p, now + SignupCode.RESEND_MS))
    }

    // ---------------------------------------------------------------- throwaway emails, install id

    @Test fun throwawayEmailsAreRejected() {
        assertTrue(ThrowawayEmail.isThrowaway("a@mailinator.com"))
        assertTrue("subdomains too", ThrowawayEmail.isThrowaway("a@x.mailinator.com"))
        assertTrue("case and trailing dot", ThrowawayEmail.isThrowaway("A@YopMail.com."))
        assertTrue(ThrowawayEmail.isThrowaway("a@guerrillamail.com") && ThrowawayEmail.isThrowaway("a@10minutemail.com"))
        for (ok in listOf("finn@gmail.com", "a@outlook.com", "a@northrise.ac.zm", "a@mail.com", "nobody", "")) assertFalse(ok, ThrowawayEmail.isThrowaway(ok))
        assertEquals("", ThrowawayEmail.check("finn@gmail.com")); assertEquals(ThrowawayEmail.MESSAGE, ThrowawayEmail.check("a@yopmail.com"))
    }

    @Test fun throwawayListIsASubsetOfTheServers() {
        // every built-in domain is on blaze-auth's list (same file as gateway/src/disposable-list.ts); here: shape only
        assertTrue(ThrowawayEmail.DOMAINS.size >= 100)
        assertTrue(ThrowawayEmail.DOMAINS.all { it == it.lowercase() && '.' in it && ' ' !in it })
    }

    @Test fun installIdIsRandomAndKept() {
        val a = DeviceId.fresh(); val b = DeviceId.fresh()
        assertTrue(DeviceId.valid(a)); assertTrue(a != b); assertEquals(25, a.length)
        var stored = ""
        val first = DeviceId.getOrMake({ stored }, { stored = it })
        assertEquals(first, stored)
        assertEquals("kept", first, DeviceId.getOrMake({ stored }, { error("no write") }))
        stored = "android-id-123"
        assertTrue("a wrong shape is replaced", DeviceId.getOrMake({ stored }, { stored = it }).startsWith("ai_"))
        assertTrue("an unreadable store still gives an id", DeviceId.valid(DeviceId.getOrMake({ throw IllegalStateException() }, { })))
    }

    // ---------------------------------------------------------------- wiring (source checks)

    private fun root(): File = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
    private fun src(p: String) = File(root(), "com/blaze/assistant/$p").readText()

    @Test fun lockScreenComesFirstAndEveryDoorAsksTheGate() {
        val main = src("MainActivity.kt")
        val route = main.indexOf("AccountLockRoute(acct)")
        assertTrue("the lock screen is drawn before the app (and its first-run language step)", route > 0 && route < main.indexOf("ChatScreen()"))
        for (a in listOf("browser/phone/PhoneBrowserActivity.kt", "terminal/BlazeTerminalActivity.kt", "mediaapps/MusicActivity.kt", "movies/AddSourceActivity.kt",
                "movies/MovieOpenActivity.kt", "mediaapps/VideoActivity.kt", "siteapps/SiteAppActivity.kt", "browser/BrowserActivity.kt", "ui/InAppCamera.kt"))
            assertTrue(a, src(a).contains("super.onCreate(savedInstanceState)\n        if (com.blaze.assistant.connect.AccountGate.bounce(this)) return"))
        assertTrue("VideoActivity survives a bounced onCreate", src("mediaapps/VideoActivity.kt").contains("if (!::player.isInitialized) { super.onDestroy(); return }"))
        assertTrue(src("notify/Notifier.kt").contains("private fun allowed() = !com.blaze.assistant.connect.AccountGate.locked() && ("))
        assertTrue(src("notify/Notifier.kt").contains("AccountLock.actionAllowed(gate.locked(), intent.action)"))
        assertTrue(src("assist/BlazeAssist.kt").contains("AccountLockCard(locked"))
        assertTrue(src("voice/WakeWordService.kt").contains("AccountGate.locked()) return   // account lock: the wake word stays off"))
        assertTrue(src("mediaapps/MusicService.kt").contains("MediaSession.ConnectionResult.reject()"))
        assertTrue(src("routine/RoutineReceiver.kt").contains("AccountGate.locked()) { Routines.ran(id)"))
        assertTrue(src("proactive/Triggers.kt").contains("AccountGate.locked()) { com.blaze.assistant.connect.AccountGate.notice(); return }"))
        for (f in listOf("agent/LlmClient.kt", "agent/CloudRuns.kt", "connect/Entitlements.kt")) assertTrue(f, src(f).contains("AccountGate.report(code"))
        assertFalse("no Skip for now", src("ui/space/SpaceLogic.kt").contains("Skip for now"))
        val acct = src("connect/BlazeAccount.kt")
        assertTrue(acct.contains("put(\"device\", DeviceId.get())") && acct.contains("SignupCode.pendingOf(status, o"))
    }
}
