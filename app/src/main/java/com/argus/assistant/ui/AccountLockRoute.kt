package com.blaze.assistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.connect.AccountGate
import com.blaze.assistant.connect.AccountLock
import com.blaze.assistant.connect.BlazeAccount
import com.blaze.assistant.i18n.tr
import com.blaze.assistant.ui.common.cutoutSafe
import com.blaze.assistant.ui.common.readableWidth
import com.blaze.assistant.ui.space.ArgusLogo
import com.blaze.assistant.ui.space.SpaceBackdrop
import com.blaze.assistant.ui.space.SpaceCard
import com.blaze.assistant.ui.space.SpaceHeadline
import com.blaze.assistant.ui.space.SpaceLook
import com.blaze.assistant.ui.space.SpacePillButton
import com.blaze.assistant.ui.space.SpaceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Account lock: the one screen ARGUS shows while it is locked ([AccountGate]). It comes before everything, the
 * first-run language step included. Signed out: the account card (sign in, create an account, the emailed code).
 * Signed in but locked (no check for 7 days, or a refused session): Try again (a /v1/me) and Sign out. While it shows,
 * a signed-in session is checked again every 30 seconds, so it opens by itself once the phone is back online.
 */
@Composable
fun AccountLockRoute(reason: AccountLock.Reason, modifier: Modifier = Modifier) {
    val look = SpaceLook.Dark
    val v by BlazeAccount.view.collectAsState()
    LaunchedEffect(v.user != null) {
        withContext(Dispatchers.IO) { BlazeAccount.refreshProviders(true) }
        while (v.user != null) {
            withContext(Dispatchers.IO) { BlazeAccount.checkSession() }
            AccountGate.recompute()
            delay(30_000)
        }
    }
    CompositionLocalProvider(LocalBlaze provides DarkColors) {
        DarkSystemBars()   // always dark: keep the status bar icons light over it
        SpaceBackdrop(modifier.testTag(ACCOUNT_LOCK_TAG), forceDark = true) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().cutoutSafe().padding(horizontal = 18.dp, vertical = 18.dp).readableWidth()) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    ArgusLogo(if (com.blaze.assistant.ui.common.WindowUi.short()) 72.dp else 120.dp, Modifier.align(Alignment.CenterHorizontally))
                    Spacer(Modifier.height(18.dp))
                    SpaceCard {
                        SpaceHeadline(tr(AccountLock.TITLE), size = 30.sp)
                        Spacer(Modifier.height(8.dp))
                        Text(tr(AccountLock.message(reason)), style = SpaceType.body(16.sp, look.sub))
                        Spacer(Modifier.height(8.dp))
                        if (v.user != null) SignedInLocked() else AccountPage()
                    }
                }
            }
        }
    }
}

const val ACCOUNT_LOCK_TAG = "account_lock"

@Composable
private fun SignedInLocked() {
    val look = SpaceLook.Dark
    val scope = rememberCoroutineScope()
    val v by BlazeAccount.view.collectAsState()
    var busy by remember { mutableStateOf(false) }
    Text(tr("Signed in as {0}", v.user?.email.orEmpty()), style = SpaceType.body(14.sp, look.sub), modifier = Modifier.padding(vertical = 8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.weight(1f)) {
            SpacePillButton(tr(if (busy) "Checking…" else "Try again"), {
                if (!busy) { busy = true; scope.launch { withContext(Dispatchers.IO) { BlazeAccount.checkSession() }; AccountGate.recompute(); busy = false } }
            }, primary = true, modifier = Modifier.fillMaxWidth())
        }
        Box(Modifier.weight(1f)) {
            SpacePillButton(tr("Sign out"), { scope.launch { BlazeAccount.signOut() } }, modifier = Modifier.fillMaxWidth())
        }
    }
}

/**
 * Account lock: what the assist long-press shows while ARGUS is locked, and nothing else: why, and one Sign in button
 * that opens the lock screen. No listening, no screen reading.
 */
@Composable
fun AccountLockCard(reason: AccountLock.Reason, onSignIn: () -> Unit, onClose: () -> Unit) {
    val look = SpaceLook.Dark
    CompositionLocalProvider(LocalBlaze provides DarkColors) {
        Box(Modifier.fillMaxSize().navigationBarsPadding().padding(16.dp), contentAlignment = Alignment.BottomCenter) {
            SpaceCard(Modifier.widthIn(max = 520.dp).fillMaxWidth(), tag = ACCOUNT_LOCK_CARD_TAG) {
                SpaceHeadline(tr(AccountLock.TITLE), size = 24.sp)
                Spacer(Modifier.height(6.dp))
                Text(tr(AccountLock.message(reason)), style = SpaceType.body(15.sp, look.sub))
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Box(Modifier.weight(1f)) { SpacePillButton(tr("Not now"), onClose, modifier = Modifier.fillMaxWidth()) }
                    Box(Modifier.weight(1f)) { SpacePillButton(tr("Sign in"), onSignIn, primary = true, modifier = Modifier.fillMaxWidth()) }
                }
            }
        }
    }
}

const val ACCOUNT_LOCK_CARD_TAG = "account_lock_card"
