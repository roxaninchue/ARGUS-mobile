package com.blaze.assistant.phone


import com.blaze.assistant.BrandName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import com.blaze.assistant.voice.Voice
import org.json.JSONArray
import org.json.JSONObject

/**
 * Call controls for a call with Argus itself (the call screen, [Voice]): speaker, mute, end, call volume, status, and
 * start_blaze_call. 2026-11-25 permission trim: real phone calls (answer / end / hold / keypad through Android's telecom
 * API, READ_PHONE_STATE and ANSWER_PHONE_CALLS) left with SMS & calls; this tool no longer touches them.
 */
object CallControl {
    val names = setOf("call_control")

    fun tool(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject()
        .put("name", "call_control")
        .put("description", "Control the user's voice call with you (${BrandName.NAME}'s call screen): speaker on/off (loudspeaker), mute on/off (their microphone), end, " +
            "louder/quieter/max call volume, status. Also start_blaze_call to start a voice call with you. It does not control the phone's own calls.")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("action", JSONObject().put("type", "string").put("enum", JSONArray(listOf("status", "speaker", "mute", "end", "louder", "quieter", "max_volume", "start_blaze_call"))))
            .put("on", JSONObject().put("type", "boolean").put("description", "speaker / mute: true to turn on, false to turn off (default true)")))
            .put("required", JSONArray(listOf("action")))))

    private fun am(ctx: Context) = ctx.getSystemService(AudioManager::class.java)

    fun run(ctx: Context, a: JSONObject): String {
        val action = a.optString("action")
        val on = if (a.has("on")) a.optBoolean("on") else true
        if (action == "start_blaze_call") {
            if (Voice.inCall) return "You're already on a call with the user."
            ctx.startActivity(com.blaze.assistant.IntentKey.sign(ctx, Intent(ctx, com.blaze.assistant.MainActivity::class.java).setAction(com.blaze.assistant.MainActivity.ACTION_CALL)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)))
            return "Started a voice call with the user. Say one short line to greet them."
        }
        if (!Voice.inCall) return if (action == "status") "No call with ${BrandName.NAME} right now." else
            "There's no call with ${BrandName.NAME} right now. ${BrandName.NAME} doesn't control the phone's own calls."
        return when (action) {
            "speaker" -> { Voice.callSpeaker.value = on; "Loudspeaker ${if (on) "on" else "off (earpiece)"}." }
            "mute" -> { Voice.callMute.value = on; if (on) "Muted the mic. Tell the user to tap Unmute to talk again." else "Mic unmuted." }
            "end" -> { Voice.callEnded.value = Voice.callEnded.value + 1; "Ended the call." }
            "louder", "quieter", "max_volume" -> volume(ctx, action, if (Voice.callSpeaker.value) AudioManager.STREAM_MUSIC else AudioManager.STREAM_VOICE_CALL)
            "status" -> "On a call with ${BrandName.NAME}. Loudspeaker ${if (Voice.callSpeaker.value) "on" else "off"}, mic ${if (Voice.callMute.value) "muted" else "on"}."
            else -> "That doesn't apply to a call with ${BrandName.NAME}."
        }
    }

    private fun volume(ctx: Context, action: String, stream: Int): String {
        val am = am(ctx)
        when (action) {
            "louder" -> repeat(2) { am.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI) }
            "quieter" -> repeat(2) { am.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI) }
            else -> am.setStreamVolume(stream, am.getStreamMaxVolume(stream), AudioManager.FLAG_SHOW_UI)
        }
        return "Call volume ${am.getStreamVolume(stream)} of ${am.getStreamMaxVolume(stream)}."
    }
}
