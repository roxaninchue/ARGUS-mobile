package com.blazeinchue.argus.device

import android.app.Activity
import android.content.Context
import android.content.Intent

/**
 * 0.49.0 (RC5 a): starts a screen (system settings, another app) from a tool. The open Argus Activity is used when there is
 * one: an Activity context needs no FLAG_ACTIVITY_NEW_TASK and is never caught by Android 10+'s background-start limits, so
 * "open accessibility settings" from the chat over Home really opens it. Without a visible Argus screen it falls back to
 * [ctx] with NEW_TASK (the old path). Throws what startActivity throws, so callers can try the next intent. ActivityStartTest.
 */
object ActivityStart {
    /** The Activity to start from, or null (gone, finishing or destroyed). */
    fun host(): Activity? = com.blazeinchue.argus.agent.SelfControl.activity?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }

    /** Starts [intent]; returns "activity" or "context" (which path ran). */
    fun start(ctx: Context, intent: Intent): String {
        val a = host()
        if (a != null) {
            a.startActivity(Intent(intent).apply { flags = flags and Intent.FLAG_ACTIVITY_NEW_TASK.inv() })
            return "activity"
        }
        ctx.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "context"
    }
}
