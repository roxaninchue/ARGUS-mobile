package com.blaze.assistant.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import com.blaze.assistant.agent.ChatNav
import com.blaze.assistant.ui.argus.kit.EmptyState

/**
 * P1-SH-01: the ARGUS shell's four modes as one HorizontalPager (04 §3.2: the notch, a swipe and /team /chat /life /desk
 * all land on the same page). [tab] is the source of truth (ChatNav); a settled swipe reports the new mode through
 * [onTab], and a tab set elsewhere scrolls the pager. Only the page on screen is composed (no pages kept beyond it),
 * so a mode costs nothing until it is shown (low-RAM start).
 */
@Composable
internal fun ArgusModesPager(
    tab: String,
    onTab: (String) -> Unit,
    team: @Composable () -> Unit,
    chat: @Composable () -> Unit,
    life: @Composable () -> Unit,
    desk: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    swipe: Boolean = true,
) {
    val start = ChatNav.modeIndex(tab).coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = start) { ChatNav.MODES.size }
    val report = rememberUpdatedState(onTab)
    val cur = rememberUpdatedState(tab)
    val want = ChatNav.modeIndex(tab)
    LaunchedEffect(want) { if (want >= 0 && pager.currentPage != want) pager.animateScrollToPage(want) }
    LaunchedEffect(pager) {
        androidx.compose.runtime.snapshotFlow { pager.settledPage }.collect { p ->
            val m = ChatNav.MODES[p]
            if (m != cur.value) report.value(m)
        }
    }
    HorizontalPager(pager, modifier.fillMaxSize(), userScrollEnabled = swipe, beyondViewportPageCount = 0, key = { ChatNav.MODES[it] }) { p ->
        Box(Modifier.fillMaxSize()) {
            when (ChatNav.MODES[p]) {
                ChatNav.TEAM -> team()
                ChatNav.CHAT -> chat()
                ChatNav.LIFE -> life()
                else -> desk()
            }
        }
    }
}

/** P1-SH-01: Desk until P1-TM-07 (Desk v1) fills it: what will live here, and Projects, which moves to Desk. */
@Composable
internal fun DeskPlaceholder(onProjects: () -> Unit) {
    EmptyState(title = "Desk", icon = "layers",
        body = "Your inbox, jobs, routines and library will live here. Projects are here already.",
        primary = "Projects" to onProjects)
}
