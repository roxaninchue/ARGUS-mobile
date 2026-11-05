package com.argus.assistant.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 0.50.0: the expand arrows at the top right of the chat box. On: the chat fills the screen (the top bar, banners and
 * Home step aside, the message box grows); off: back to the normal layout. Back restores first. Not saved across launches.
 */
object ChatExpand {
    var on by mutableStateOf(false)
    fun toggle() { on = !on }
    fun restore() { on = false }
}
