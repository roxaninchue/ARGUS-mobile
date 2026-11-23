package com.blaze.assistant.agent

/**
 * 0.55.10 (Finn, 23 Nov: the app crashed when he hit Stop while a reply was being written): Stop and the end of a
 * stopped turn run on the main thread, partly outside any guarded scope. Each clean-up step goes through [run], so a
 * failure in one (an exception, or an OutOfMemoryError on a low-RAM phone) is logged and the rest still runs; it never
 * closes the app. A coroutine's own cancellation is passed on, never swallowed.
 */
object StopSafe {
    /** Runs [block]; anything it throws except cancellation is logged under [what] and dropped. Returns false when it threw. */
    inline fun run(what: String, block: () -> Unit): Boolean = try { block(); true }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Throwable) { note(what, e); false }

    /** Logs a dropped failure; never throws (the log itself may be out of memory). */
    fun note(what: String, e: Throwable) {
        if (e is OutOfMemoryError) try { com.blaze.assistant.device.MemoryTrim.dropCaches() } catch (_: Throwable) { }
        try { com.blaze.assistant.device.CrashLog.quiet("Stop: $what", e) } catch (_: Throwable) { }
    }
}
