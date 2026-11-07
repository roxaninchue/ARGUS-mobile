package com.blazeinchue.argus.notify


import com.blazeinchue.argus.BrandName
/** Ack-and-background (HQ 22 Oct 20:36): Argus's off-screen messages read like texts from Argus. Pure constants. */
object MessageThread {
    /** How many of Argus's recent lines the text-style notification keeps (ack, then Done ✓). */
    const val KEEP = 5
    /** The title when the task failed: honest, never a "done". */
    const val FAILED_TITLE = "${BrandName.NAME} couldn't finish that"
}
