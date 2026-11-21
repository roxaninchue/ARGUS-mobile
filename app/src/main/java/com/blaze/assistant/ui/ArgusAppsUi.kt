package com.blaze.assistant.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.BrandName
import com.blaze.assistant.connect.ArgusApps
import com.blaze.assistant.connect.ArgusAppsModel
import com.blaze.assistant.connect.ArgusAppsModel.App
import com.blaze.assistant.connect.BlazeAccount
import com.blaze.assistant.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Brand icons for gateway apps: bundled PNGs first (assets/connect-icons), else the gateway's icon URL (cached), else a letter tile. */
internal object AppIconCache {
    private val mem = LruCache<String, Bitmap>(96)
    private val missing = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    fun peek(id: String): Bitmap? = mem.get(id)

    fun load(ctx: android.content.Context, app: App): Bitmap? {
        mem.get(app.id)?.let { return it }
        if (app.id in missing) return null
        val bmp = runCatching {
            val asset = ArgusAppsModel.iconAsset(app.id)
            if (asset.isNotEmpty()) ctx.assets.open(asset).use { BitmapFactory.decodeStream(it) }
            else if (app.iconUrl.isNotEmpty()) {
                val f = File(ctx.cacheDir, "connect-icons/${app.id}.png")
                if (!f.exists() || f.length() == 0L) {
                    f.parentFile?.mkdirs()
                    val c = java.net.URL(app.iconUrl).openConnection() as java.net.HttpURLConnection
                    c.connectTimeout = 10_000; c.readTimeout = 10_000
                    try { if (c.responseCode in 200..299) c.inputStream.use { inp -> f.outputStream().use { inp.copyTo(it) } } } finally { c.disconnect() }
                }
                if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
            } else null
        }.getOrNull()
        if (bmp != null) mem.put(app.id, bmp) else missing.add(app.id)
        return bmp
    }
}

/** An app's real icon on a soft rounded tile; a letter tile when it has none. */
@Composable
fun AppIcon(app: App, size: Dp = 28.dp) {
    val c = LocalBlaze.current.mono
    val ctx = LocalContext.current
    val bmp by produceState(AppIconCache.peek(app.id), app.id, app.iconUrl) {
        if (value == null) value = withContext(Dispatchers.IO) { AppIconCache.load(ctx.applicationContext, app) }
    }
    val shape = RoundedCornerShape(size * 0.28f)
    Box(Modifier.size(size).clip(shape).background(androidx.compose.ui.graphics.Color.White, shape), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(size * 0.78f))
        else Box(Modifier.size(size).background(c.accentSoft, shape), contentAlignment = Alignment.Center) {
            Text(ArgusAppsModel.initial(app.name), style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.SemiBold, fontSize = (size.value * 0.45f).sp, color = c.accent))
        }
    }
}

/**
 * Settings › Apps & connectors: every app the ARGUS gateway offers (argus-connect), with its real icon, grouped by
 * category, searchable from the top bar's magnifier. Tap a row for Connect (browser sign-in, or paste a key), Test, Disconnect, or On / Off for
 * keyless apps. [apps0] is for screenshots and tests (no network).
 */
@Composable
fun GatewayAppsSection(apps0: List<App>? = null, query: String = SettingsSearch.query.value) {
    val c = LocalBlaze.current.mono
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val v by ArgusApps.view.collectAsState()
    val st by ArgusApps.status.collectAsState()
    val acct by BlazeAccount.view.collectAsState()
    if (apps0 == null) LaunchedEffect(acct.user?.id) { ArgusApps.loadCached(); ArgusApps.refresh() }
    val apps = apps0 ?: v.apps
    var open by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf("") }
    val inputs = remember { mutableStateMapOf<String, String>() }

    val signedIn = apps0 != null || acct.signedIn
    if (!signedIn && apps.isEmpty()) {
        Note2(tr("Sign in to your {0} account to see every app your gateway offers.", BrandName.NAME))
        return
    }
    if (v.error.isNotBlank() && apps0 == null) Note2(v.error)
    if (apps.isEmpty()) { Note2(if (v.loading) tr("Loading your gateway's apps\u2026") else tr("No apps yet.")); return }

    // 16 Nov 2026: the search box moved to the top bar's magnifier (SearchTitle); [query] is its words.
    val groups = ArgusAppsModel.grouped(apps, query)
    GroupHead("Apps \u00b7 %02d".format(apps.size), right = {
        Label("${apps.count { it.connected }} connected", size = 9.sp, color = c.weak)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.clip(BlazeShapes.pill).then(if (apps0 == null) Modifier.clickable { scope.launch { ArgusApps.refresh() } } else Modifier).padding(horizontal = 6.dp, vertical = 2.dp)) {
            Label(if (v.loading) "Loading\u2026" else "Refresh", size = 9.sp, color = c.accent)
        }
    })
    if (groups.isEmpty()) Note2(tr("No app matches \u201c{0}\u201d.", query.trim()))
    groups.forEach { (cat, list) ->
        Spacer(Modifier.height(6.dp))
        Label(cat, size = 9.sp, color = c.weak, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        Rows {
            list.forEachIndexed { i, app ->
                val pill = ArgusAppsModel.pill(app)
                KitRow(app.name, st[app.id]?.takeIf { it.isNotBlank() } ?: ArgusAppsModel.sub(app), first = i == 0, pill = pill, pillOn = app.ready,
                    onClick = { open = if (open == app.id) "" else app.id }, lead = { AppIcon(app) })
                if (open == app.id) Column(Modifier.fillMaxWidth().glassInset(c).hairlineTop(c.hair).padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 12.dp)) {
                    val working = busy == app.id
                    fun run(block: suspend () -> String) { if (busy.isEmpty()) { busy = app.id; scope.launch { try { block() } finally { busy = "" } } } }
                    if (app.setup.isNotBlank() && !app.connected && !app.none) Note2(app.setup)
                    if (app.free.isNotBlank() && app.free != "free") Note2(tr("Cost: {0}", app.free))
                    when {
                        app.none -> KitButton(if (app.enabled) "Turn off" else "Turn on", if (app.enabled) Btn.LINE else Btn.ACCENT, enabled = !working) {
                            run { ArgusApps.setEnabled(app.id, !app.enabled) }
                        }
                        app.connected -> Row {
                            if (app.hasTest) { KitButton("Test", Btn.LINE, enabled = !working) { run { ArgusApps.test(app.id) } }; Spacer(Modifier.width(8.dp)) }
                            KitButton(if (app.enabled) "Turn off" else "Turn on", Btn.LINE, enabled = !working) { run { ArgusApps.setEnabled(app.id, !app.enabled) } }
                            Spacer(Modifier.width(8.dp))
                            KitButton("Disconnect", Btn.GHOST, enabled = !working) { run { ArgusApps.disconnect(app.id) } }
                        }
                        else -> {
                            if (app.oauth && app.oauthReady) {
                                Note2(tr("{0} opens {1} in your browser. Approve, and it shows here as connected.", BrandName.NAME, app.name))
                                KitButton("Connect ${app.name}", Btn.ACCENT, enabled = !working) { run { ArgusApps.connectOAuth(ctx, app.id) } }
                                // 2026-11-21 one-tap connect: with browser sign-in ready, the key box stays folded away
                                if (app.pastes && inputs["${app.id}:keys"] != "1") {
                                    Spacer(Modifier.height(10.dp))
                                    KitButton("Use a key instead", Btn.GHOST, enabled = !working) { inputs["${app.id}:keys"] = "1" }
                                } else if (app.pastes) { Spacer(Modifier.height(10.dp)); Label(tr("Or paste a key"), size = 9.sp, color = c.weak, modifier = Modifier.padding(bottom = 8.dp)) }
                            } else if (app.oauth && !app.pastes) {
                                Note2(tr("Browser sign-in for {0} isn't switched on in your gateway yet.", app.name))
                            }
                            if (app.pastes && (!(app.oauth && app.oauthReady) || inputs["${app.id}:keys"] == "1")) {
                                if (app.tokenLabel.isNotEmpty()) Field(app.tokenLabel, inputs["${app.id}:token"].orEmpty(), { inputs["${app.id}:token"] = it }, if (app.tokenOptional) "Optional" else "Paste it here", secret = true)
                                app.fields.forEach { f ->
                                    Field(f.label, inputs["${app.id}:${f.key}"].orEmpty(), { inputs["${app.id}:${f.key}"] = it }, if (f.optional) "Optional" else f.label, secret = f.secret)
                                }
                                KitButton("Save and test", Btn.ACCENT, enabled = !working) {
                                    val token = inputs["${app.id}:token"].orEmpty()
                                    val fields = app.fields.associate { f -> f.key to inputs["${app.id}:${f.key}"].orEmpty() }
                                    run {
                                        ArgusApps.saveConnection(app.id, token, fields).also {
                                            inputs.keys.filter { k -> k.startsWith("${app.id}:") }.forEach { k -> inputs.remove(k) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (working) Note2(tr("Working\u2026"))
                }
            }
        }
    }
}

@Composable
private fun Note2(text: String) {
    val c = LocalBlaze.current.mono
    Text(text, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
        style = TextStyle(fontFamily = Type.Sans, fontSize = 12.5.sp, lineHeight = 17.sp, color = c.body))
}

