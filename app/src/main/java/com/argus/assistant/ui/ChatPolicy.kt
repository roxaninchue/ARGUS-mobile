package com.argus.assistant.ui

/**
 * 0.52.0 (owner rule): the chat shows only replies and work pills — the work pill per turn ("Worked · 57 steps ·
 * 3m 35s ›"), one pill per background agent, follow-up chips and choice buttons. No status bubbles or banners: no
 * "Waiting for your task to finish", no task chip ("Task · in progress"), no step line beside the typing dots, no
 * "Sent. Waiting for Blaze…", no model-written wait line, no "Got it, I've passed that…" confirmations.
 * The old surfaces stay in the code behind this switch (off) so their pure tests keep running; nothing turns it on.
 */
object ChatPolicy {
    const val STATUS_LINES = false
}
