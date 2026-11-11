package com.blaze.assistant.policy

import com.blaze.assistant.agent.PasteGuard
import com.blaze.assistant.agent.SecretTestKit
import com.blaze.assistant.team.ActionClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P2-SF-05: args (and results) are stored with secrets redacted, reusing SecretGuards' PasteGuard plus secret-named keys. */
class AuditRedactTest {
    private val gl = SecretTestKit.GL
    private val sk = SecretTestKit.SK
    private val ghp = SecretTestKit.GHP
    private val akia = SecretTestKit.AKIA
    private val jwt = SecretTestKit.JWT
    private val card = SecretTestKit.CARD
    private val cf = SecretTestKit.CF

    private fun clean(out: String, vararg secrets: String) = secrets.forEach { assertFalse("leaked $it in: $out", out.contains(it)) }

    @Test fun tokenFormatsGoThroughPasteGuard() {
        for (s in listOf(gl, sk, ghp, akia, jwt)) {
            assertTrue("PasteGuard must see $s", PasteGuard.detect("x $s y").isNotEmpty())
            val out = Audit.redact("""{"cmd":"git push https://oauth2:$s@gitlab.com/a/b.git","note":"deploy $s now"}""")
            clean(out, s)
            assertTrue(out, out.contains("[redacted"))
            assertTrue(out.contains("\"cmd\""))   // structure stays readable
        }
    }

    @Test fun cardNumbersAreRedacted() {
        val out = Audit.redact("pay with 4242 4242 4242 4242 at Shoprite, also $card")
        clean(out, "4242 4242 4242 4242", card)
        assertTrue(out.contains("Shoprite"))
        assertTrue(out.contains("[redacted card]"))
    }

    @Test fun secretNamedKeysAreRedactedWhateverTheValue() {
        val raw = """{"username":"finn","password":"hunter2!","pin":"1234","cvc":123,"cardNumber":"5555","api_key":"short",""" +
            """"X-Api-Key":"abc","client_secret":"s3","otp":"482913","shipping":"Plot 5, Lusaka","max_tokens":400,"amount":2500}"""
        val out = Audit.redact(raw)
        clean(out, "hunter2!", "\"1234\"", "123,", "\"5555\"", "\"short\"", "\"abc\"", "\"s3\"", "482913")
        assertTrue(out.contains("\"username\":\"finn\""))
        assertTrue(out.contains("\"shipping\":\"Plot 5, Lusaka\""))   // "pin" inside "shipping" is not a secret key
        assertTrue(out.contains("\"max_tokens\":400"))
        assertTrue(out.contains("\"amount\":2500"))
        assertEquals(8, Regex.escape(Audit.REDACTED).toRegex().findAll(out).count())
    }

    @Test fun keyValueTextHeadersAndBearer() {
        val out = Audit.redact("curl -H 'Authorization: Bearer $cf' https://api.x.com?token=abc123def&page=2 password=hunter2 pin: 9876")
        clean(out, cf, "abc123def", "hunter2", "9876")
        assertTrue(out.contains("page=2"))
        assertTrue(out.contains("https://api.x.com"))
    }

    @Test fun jsonInsideAJsonString() {
        val out = Audit.redact("""{"arguments":"{\"login\":\"finn\",\"password\":\"p@ss w0rd\"}"}""")
        clean(out, "p@ss w0rd")
        assertTrue(out, out.contains("finn"))
    }

    @Test fun saidSecretsInProse() {
        val out = Audit.redact("the user said my password is Tr0ub4dor&3 and the code is 551234")
        clean(out, "Tr0ub4dor&3")
    }

    @Test fun secureFormHandlesAreKept() {
        val out = Audit.redact("""{"password":"{{secret:zesco_login.password}}","token":"{{secret:gitlab}}"}""")
        assertTrue(out, out.contains("{{secret:zesco_login.password}}"))
        assertTrue(out.contains("{{secret:gitlab}}"))
    }

    @Test fun keyMatching() {
        for (k in listOf("password", "user_password", "X-Api-Key", "apiKey", "accessToken", "refresh_token", "cardNumber", "card_no", "CVV",
            "pin", "otp", "Authorization", "client_secret", "seed_phrase", "set-cookie", "iban"))
            assertTrue(k, Audit.secretKey(k))
        for (k in listOf("shipping", "spinner", "max_tokens", "tokens_used", "amount", "merchant", "author", "passenger", "keyword", "opinion"))
            assertFalse(k, Audit.secretKey(k))
    }

    @Test fun nonSecretArgsUntouched() {
        val raw = """{"to":"mum@example.com","subject":"Dinner","body":"See you at 7","hash":"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"}"""
        assertEquals(raw, Audit.redact(raw))
    }

    @Test fun appendStoresOnlyRedactedTextAndCutsAfterRedacting() {
        val long = "x".repeat(490) + " " + gl + " " + "y".repeat(3000)
        val e = Audit.append(null, AuditEvent(at = 1L, botId = "dev", trigger = "user", tool = "run_shell", actionClass = ActionClass.WRITE_REMOTE,
            args = """{"cmd":"export GITLAB_TOKEN=$gl","password":"hunter2"} $long""", decision = Decision.ALLOWED, decidedBy = "user",
            result = "pushed with $sk", costMinor = null))
        clean(e.argsRedacted, gl, "hunter2", gl.take(12))
        clean(e.result!!, sk)
        assertTrue(e.argsRedacted.length <= Audit.MAX_ARGS)
        assertTrue(e.argsRedacted.endsWith("…"))
        assertTrue(Audit.verify(listOf(e)).ok)
        // exports carry only what was stored
        clean(Audit.toCsv(listOf(e)), gl, sk, "hunter2"); clean(Audit.toJson(listOf(e)), gl, sk, "hunter2")
    }

    @Test fun summaryFoldsWhitespace() {
        assertEquals("a b c", Audit.redactArgs("a\n\n b\t c  "))
        assertEquals(10, Audit.redactArgs("z".repeat(50), 10).length)
    }
}
