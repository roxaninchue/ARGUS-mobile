package com.blaze.assistant.link

import org.json.JSONObject

/**
 * Marketer pack (v0.17.0): WhatsApp replies drafted on Argus Desktop and sent from the phone over
 * Argus Link. The PC puts a structured request in the phone's inbox:
 *
 *   {"id": "...", "kind": "message", "to": "Chanda (HomeGlow customer)", "text": "Hi Chanda, ...", "app": "whatsapp"}
 *
 * 2026-11-25 permission trim: the phone no longer sends messages (SMS, WhatsApp, Telegram), so such a request gets
 * [NOT_SENT] back and nothing is sent. Plain-text requests keep working as before.
 */
object PcMessages {
    data class Send(val to: String, val text: String, val app: String)

    /** The send in an inbox request, or null when it's an ordinary text request. */
    fun parse(r: JSONObject): Send? {
        val kind = r.optString("kind").lowercase()
        if (kind != "message" && kind != "whatsapp_reply" && kind != "send_message") return null
        val app = r.optString("app", "whatsapp").lowercase().ifBlank { "whatsapp" }
        return Send(r.optString("to").trim(), (r.optString("message").ifBlank { r.optString("text") }).trim(), app)
    }

    const val NOT_SENT = "Not sent: Argus on this phone no longer sends messages. Send it from the chat app yourself."
}
