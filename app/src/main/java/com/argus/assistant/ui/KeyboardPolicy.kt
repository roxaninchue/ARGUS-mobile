package com.blaze.assistant.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner

/**
 * 0.52.1 (owner: "when I leave the app and come back the keyboard pops up"): the chat box never takes focus by itself.
 * Android restores the focused text field (and so the keyboard) when Blaze comes back, so every text field that can hold
 * the cursor across a leave drops its focus when the screen stops; the keyboard only shows when you tap the box.
 * MainActivity also has windowSoftInputMode stateAlwaysHidden. No LaunchedEffect requests focus on the chat box.
 */
object KeyboardPolicy {
    /** Events after which the chat box gives up its focus (pure; KeyboardPolicyTest). */
    fun dropsFocus(e: Lifecycle.Event): Boolean = e == Lifecycle.Event.ON_STOP

    fun clear(fm: FocusManager?, kb: SoftwareKeyboardController?) {
        try { kb?.hide() } catch (_: Throwable) { }
        try { fm?.clearFocus(force = true) } catch (_: Throwable) { }
    }
}

/** Put once inside a screen that holds a text field: when Blaze goes to the background the field loses focus. */
@Composable
fun ClearFocusOnLeave() {
    val fm = LocalFocusManager.current
    val kb = LocalSoftwareKeyboardController.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, fm, kb) {
        val obs = LifecycleEventObserver { _, e -> if (KeyboardPolicy.dropsFocus(e)) KeyboardPolicy.clear(fm, kb) }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
}
