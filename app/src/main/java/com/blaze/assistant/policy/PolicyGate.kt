package com.blaze.assistant.policy

import com.blaze.assistant.agent.ConfirmGate
import com.blaze.assistant.team.ActionClass
import com.blaze.assistant.team.Rule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * P2-SF-01: the policy at dispatch, inside the tool call. [run] decides with [Policy.decide]; a refusal comes back as
 * the tool's result; an allow runs the tool; an ask opens an [Approvals] entry, shows it through the [Presenter] and
 * waits in the call (25-minute expiry, skipped by a new user message), then runs the tool under
 * [ConfirmGate.PolicyOk] so its own confirm cards don't ask a second time, or returns a "don't retry" result.
 *
 * [Request.enforce] false = shadow: the decision is made and reported to [observer] but the call runs exactly as it
 * did before P2-SF-01. P2-SF-02: the presenter is the real card flow ([CardFlow]), run inside the call, so a card
 * closes the moment its approval ends (answered, skipped by a new message, or expired). Pure (no Android);
 * ApprovalsTest / CardFlowTest.
 */
class PolicyGate(
    val approvals: Approvals,
    private val presenter: Presenter? = null,
    private val saveAlways: ((tool: String, botId: String) -> Unit)? = null,
    private val observer: ((Request, Policy.Decision, Approvals.Status?) -> Unit)? = null,
    /**
     * Where the presenter runs. Null = inside the call, cancelled when the approval ends. A scope = detached (with the
     * call's context, minus its Job) and left to close itself: today's confirm card has no finally, so cancelling it
     * would leave the card on screen.
     */
    private val detach: CoroutineScope? = null,
    /**
     * P2-SF-02: a one-time pass that already covers this call (a draft the user edited on its card: that exact text to
     * those recipients). Returns the approval id it came from, or null.
     */
    private val pass: ((Request, Policy.Decision.Ask) -> String?)? = null,
    /** P2-SF-02: the tool result for an approval that didn't run when the card had more to say (an edited draft). */
    private val outcome: ((approvalId: String) -> String?)? = null,
) {
    /** Shows an approval to the user. Returns their answer, or null to leave it waiting (another surface may answer). */
    fun interface Presenter { suspend fun show(p: Approvals.Pending, ask: Policy.Decision.Ask): Approvals.Status? }

    data class Request(val chatId: String, val botId: String, val botName: String, val jobId: String?, val tool: String,
                       val argsRedacted: String, val call: Policy.Call, val ctx: Policy.Ctx, val enforce: Boolean, val callId: String = "",
                       /** P2-SF-02: the whole scrubbed args (a draft card shows every word it approves). */
                       val argsFull: String = argsRedacted)

    sealed class Verdict {
        /** Run the tool; [approvalId] = the card the user answered yes on (null = no card). */
        data class Run(val approvalId: String?) : Verdict()
        data class Stop(val result: String) : Verdict()
    }

    suspend fun check(r: Request): Verdict {
        val d = try { Policy.decide(r.call, r.ctx) } catch (e: Throwable) {
            if (e is CancellationException) throw e
            return if (r.enforce) Verdict.Stop("Error: ${r.tool} wasn't checked (${e.javaClass.simpleName}), so it didn't run.") else Verdict.Run(null)
        }
        if (!r.enforce) { note(r, d, null); return Verdict.Run(null) }
        return when (d) {
            is Policy.Decision.Allow -> { note(r, d, null); Verdict.Run(null) }
            is Policy.Decision.Refuse -> { note(r, d, null); Verdict.Stop(d.message) }
            is Policy.Decision.Ask -> ask(r, d)
        }
    }

    private suspend fun ask(r: Request, d: Policy.Decision.Ask): Verdict {
        pass?.let { f -> runCatching { f(r, d) }.getOrNull()?.let { id -> note(r, d, Approvals.Status.ALLOWED); return Verdict.Run(id) } }
        val p = approvals.open(Approvals.Request(r.chatId, r.botId, r.jobId, r.callId.ifBlank { r.tool }, r.tool, r.call.cls,
            title(r.botName, r.call.cls), detail(r.tool, r.argsRedacted, d.note), r.argsRedacted, r.ctx.tainted != null,
            botName = r.botName, args = r.argsFull))
        val callCtx = currentCoroutineContext().minusKey(Job)
        val st = coroutineScope {
            val shown = presenter?.let { pr -> (detach ?: this).launch(if (detach != null) callCtx else EmptyCoroutineContext) {
                try { pr.show(p, d)?.let { approvals.resolve(p.id, it, "card") } } catch (e: Throwable) { if (e is CancellationException) throw e }
            } }
            val s = approvals.await(p)
            if (detach == null) shown?.cancel()
            s
        }
        note(r, d, st)
        return when (st) {
            Approvals.Status.ALWAYS -> { if (d.always) runCatching { saveAlways?.invoke(r.tool, r.botId) }; Verdict.Run(p.id) }
            Approvals.Status.ALLOWED -> Verdict.Run(p.id)
            Approvals.Status.DENIED -> Verdict.Stop(outcome?.let { f -> runCatching { f(p.id) }.getOrNull() } ?: denied(r.tool))
            Approvals.Status.SKIPPED -> Verdict.Stop(skipped(r.tool))
            Approvals.Status.EXPIRED, Approvals.Status.PENDING -> Verdict.Stop(expired(r.tool))
        }
    }

    /** [block] runs the tool; its result, or the policy's own result when the call doesn't run. */
    suspend fun run(r: Request, block: suspend () -> String): String = when (val v = check(r)) {
        is Verdict.Stop -> v.result
        is Verdict.Run -> if (v.approvalId == null) block() else withContext(ConfirmGate.PolicyOk(r.tool, v.approvalId)) { block() }
    }

    private fun note(r: Request, d: Policy.Decision, s: Approvals.Status?) { runCatching { observer?.invoke(r, d, s) } }

    companion object {
        fun title(bot: String, c: ActionClass): String = "$bot wants to " + when (c) {
            ActionClass.SEND -> "send a message"; ActionClass.SPEND -> "spend money"; ActionClass.DELETE -> "delete something"
            ActionClass.CREDENTIAL -> "use a sign-in or secret"; ActionClass.COMPUTE_PAID -> "start a paid computer"
            ActionClass.SETTINGS -> "change a setting"; ActionClass.WRITE_REMOTE -> "change something in your accounts"
            ActionClass.PHYSICAL -> "use the phone's screen"; ActionClass.WRITE_LOCAL -> "save something"; ActionClass.READ -> "look something up"
        }

        /**
         * 0.53.0: the card's body. The note ("Requested after reading …") is NOT repeated here: the card and the
         * notification draw [Card.note] on their own line (it showed twice). Args read as words (ScreenSend.readable:
         * numbered steps for a screen plan, "key: value" lines otherwise), never raw run-plan JSON.
         */
        fun detail(tool: String, args: String, @Suppress("UNUSED_PARAMETER") note: String?): String {
            val words = ScreenSend.readable(tool, args)
            val name = tool.replace('_', ' ')
            return if (words.isEmpty()) name else if ('\n' in words) "$name\n$words" else "$name: $words"
        }

        fun denied(tool: String) = "The user did NOT allow $tool. Don't retry it; tell them what you would have done so they can do it."
        fun skipped(tool: String) = "The user sent a new message instead of answering, so $tool was skipped. Don't retry it; read their message."
        fun expired(tool: String) = "Nobody answered the approval for $tool within 25 minutes, so it didn't run. Don't retry it now; say what is waiting for the user."

        /** The standing rule "Always allow" saves. */
        val ALWAYS_RULE = Rule.ALLOW
    }
}
