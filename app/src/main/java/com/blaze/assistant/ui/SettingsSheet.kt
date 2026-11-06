package com.blaze.assistant.ui


import com.blaze.assistant.BrandName
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import com.blaze.assistant.assist.BlazeInteractionService
import com.blaze.assistant.voice.WakeModel
import com.blaze.assistant.voice.WakeWordService
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.blaze.assistant.agent.Agent
import com.blaze.assistant.agent.Settings
import com.blaze.assistant.agent.ModelCatalog
import com.blaze.assistant.agent.Providers
import com.blaze.assistant.agent.Quota
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.clip

/** Settings, grouped into pages. [page] null = the profile page (the root). */
data class SettingsPage(val id: String, val title: String, val sub: String)

/**
 * 0.52.3 (ARGUS x Hark): the root of Settings is the profile page ([com.blaze.assistant.ui.space.SpaceProfileScreen]):
 * Usage and plan; Profile, Scheduled Tasks, Accounts, Wallet; Security, System Preferences, Submit Feedback, About.
 * System Preferences lists Appearance, Language, Voice, Permissions and Updates. Back follows
 * [com.blaze.assistant.ui.space.SpaceSettings.PARENT] (everything else returns to the profile). The old Settings list,
 * Advanced, the Brain page's switches and the backup / offline brain sections are gone: the brain is picked
 * automatically. The pages below stay reachable by id (ARGUS's open_settings, /views, the + menu) and keep their prefs.
 */
val topPages = listOf(
    SettingsPage("system", "System Preferences", "Appearance, language, voice, permissions, updates"),
    SettingsPage("feedback", "Submit Feedback", "Tell the ${BrandName.NAME} team what works and what doesn't"),
    SettingsPage("look", "Appearance", "Light, Dark or Auto, and the accent colour"),
    SettingsPage("language", "Language", "Which language ${BrandName.NAME} replies in. Auto matches yours"),
    SettingsPage("doing", "Things ${BrandName.NAME} is doing", "Routines and reminders, on or off, and today's diary"),
    SettingsPage("bank", "Bank mode", "Use banking apps: ${BrandName.NAME} steps back while they're open"),
    SettingsPage("voice", "Voice", "Hey ${BrandName.NAME}, spoken replies, default assistant"),
    SettingsPage("permissions", "Permissions", "Phone control, notifications, mic, overlay"),
    SettingsPage("account", "Profile", "${BrandName.NAME} account and connected apps"),
    SettingsPage("updates", "Updates", "Check for a newer ${BrandName.NAME}; updates run in the background"),
    SettingsPage("about", "About", "Version, licences and what ${BrandName.NAME} keeps on this phone"),
)

/** Pages that left the Settings UI in 0.52.3 but still open by id (their prefs keep working). */
val advancedPages = listOf(
    SettingsPage("connections", "Accounts", "Google, GitHub, Spotify, Notion, Slack"),
    SettingsPage("desktop", "${BrandName.NAME} Desktop", "Pair with ${BrandName.NAME} on your computer"),
    SettingsPage("control", "Phone control", "Accessibility, skills, the pointer"),
    SettingsPage("persona", "Personality", "How ${BrandName.NAME} talks"),
    SettingsPage("feel", "Taps, sounds & panel", "Tap highlights, sounds, compact panel"),
    SettingsPage("privacy", "Security", "App lock, approvals, bank mode"),
    SettingsPage("watches", "Watches", "Values Magic Hand is watching for you"),
    SettingsPage("secrets", "Wallet", "Keys, tokens, logins and cards from the Secure Form, only on this phone"),
    SettingsPage("logins", "Saved logins", "Sign-ins from ${BrandName.NAME}'s secure form, encrypted on this phone"),
    SettingsPage("phonebrowser", "${BrandName.NAME} Phone", "${BrandName.NAME}'s own phone browser: sign out of sites, clear its data"),
    SettingsPage("proactive", "Proactive & triggers", "Speak up on its own, when-X-do-Y"),
    SettingsPage("commands", "Slash commands", "Your own /commands and prompt skills"),
    SettingsPage("orb", "Floating orb", "Keep ${BrandName.NAME} over other apps"),
    SettingsPage("logs", "Life logs", "Transcribe what the mic hears into memory, brainstorms"),
    SettingsPage("replies", "Notification replies", "Chat logs and replies from notifications, auto-reply"),
    SettingsPage("usage", "Usage", "Tokens and estimated cost"),
    SettingsPage("device", "Device & diagnostics", "Phone health, self-check, crash report"),
    SettingsPage("backup", "Backup", "Export or import everything"),
)

/**
 * Pages that are views: opened by id from /views, the + menu, open_view and the "Open in …?" chip. "ai" (Brain) is
 * only the status card now: ARGUS Cloud picks the model for every message, nobody picks one.
 */
val viewPages = listOf(
    SettingsPage("ai", "Brain", "${BrandName.NAME} Cloud picks the best model for every message"),
    SettingsPage("terminal", "Jobs", "A real shell, jobs, Linux, Termux"),
    SettingsPage("memory", "Memory", "What ${BrandName.NAME} remembers and has learned"),
    SettingsPage("routines", "Scheduled Tasks", "Things ${BrandName.NAME} does on a schedule"),
    SettingsPage("specialists", "Agents", "Sub-agents ${BrandName.NAME} can delegate to"),
    SettingsPage("skills", "Skills", "Research, email, plans, docs, code: on, off, restore"),
    SettingsPage("learn", "Learn", "Flashcards, Google Classroom, low-data mode"),
    SettingsPage("appstore", "App Store", "Free open-source apps from their developers' own releases"),
    SettingsPage("seller", "Seller", "Brand kit, weekly store digest, replies from your PC"),
    SettingsPage("dashboard", "Dashboard", "Local web dashboard and analytics"),
    SettingsPage("plan", "Usage and plan", "${BrandName.NAME} Cloud plans, monthly or yearly"),
    SettingsPage("sync", "Cloud sync", "Chats, memory, settings and designs on all your phones, end-to-end encrypted"),
)

val settingsPages = topPages + advancedPages + viewPages

/** 0.52.3: page ids from before the declutter that now land on another page ("" = the profile). */
internal val settingsAliases = mapOf("router" to "ai", "backup_brain" to "ai", "advanced" to "", "settings" to "")

/** The page a start id opens: a known page, an alias, or what [com.blaze.assistant.agent.settingsPageFor] makes of it; null = the profile. */
internal fun settingsStart(start: String?): String? {
    val s = start?.trim().orEmpty()
    if (s.isEmpty()) return null
    settingsAliases[s]?.let { return it.ifEmpty { null } }
    if (settingsPages.any { it.id == s }) return s
    return com.blaze.assistant.agent.settingsPageFor(s).takeIf { it.isNotEmpty() }
}

/**
 * Settings. [start] null / "" opens the profile page; a page id opens that page (Back then closes Settings when it was
 * opened straight onto it). [onTab] switches the top bar's Home | Chat | Projects (and closes Settings); [onSearch]
 * opens Spotlight from the profile's floating Search pill.
 */
@Composable
fun SettingsScreen(start: String?, a11yOn: Boolean, onOpenAccessibility: () -> Unit, onDismiss: () -> Unit,
                   onTab: (String) -> Unit = {}, onSearch: () -> Unit = {}) {
    val c = LocalBlaze.current.mono
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val first = remember { settingsStart(start) }
    var page by remember { mutableStateOf(first) }
    // Opened straight onto a page (from a /command or ARGUS): Back from that page closes Settings.
    fun back() {
        val p = page
        if (p == null || (first != null && p == first)) onDismiss()
        else page = com.blaze.assistant.ui.space.SpaceSettings.parentOf(p)
    }
    BackHandler { back() }
    val current = settingsPages.firstOrNull { it.id == page }
    val account by com.blaze.assistant.connect.BlazeAccount.view.collectAsState()
    val ents by com.blaze.assistant.connect.Entitlements.snap.collectAsState()
    val scope = rememberCoroutineScope()
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty() }

    com.blaze.assistant.ui.space.SpaceBackdrop {
    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        if (page == null) {
            // The profile page: Hark's top bar (no round button), then the grouped cards.
            val me = account.user
            com.blaze.assistant.ui.space.SpaceTopBar(tab = "", onTab = onTab, onLeft = {}, showLeft = false,
                initial = com.blaze.assistant.ui.space.SpaceTabs.initial(me?.name ?: Settings.userName, me?.email),
                onAvatar = { onDismiss() })
            com.blaze.assistant.ui.space.SpaceProfileScreen(
                name = com.blaze.assistant.ui.space.SpaceProfile.title(me?.name, Settings.userName),
                email = com.blaze.assistant.ui.space.SpaceProfile.subtitle(me?.email, me != null),
                signedIn = me != null,
                planLine = ents?.let { com.blaze.assistant.connect.Billing.planName(it.plan) + " plan" }.orEmpty(),
                onPage = { page = it },
                onSignOut = { scope.launch { com.blaze.assistant.connect.BlazeAccount.signOut() } },
                onSearch = onSearch,
                modifier = Modifier.weight(1f),
                version = version)
        } else {
            BackTitle(current?.title ?: "Settings", onBack = { back() }, modifier = Modifier.hairlineBottom(c.hair).padding(vertical = 2.dp),
                right = { ThemeSwitch() })
            Column(Modifier.fillMaxSize().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
                when (page) {
                    com.blaze.assistant.ui.space.SpaceProfile.SYSTEM_PAGE -> com.blaze.assistant.ui.space.SpaceSystemPage { page = it }
                    com.blaze.assistant.ui.space.SpaceProfile.FEEDBACK_PAGE -> com.blaze.assistant.ui.space.SpaceFeedbackPage(version)
                    "permissions" -> { PermissionsPage { page = it }; ControlPage(a11yOn, onOpenAccessibility) }
                    "updates" -> UpdatesPage()
                    "about" -> AboutPage()
                    // 0.52.3: one brain, picked automatically. The page is only its status (no switches, no model).
                    "ai" -> BrainCard { page = "account" }
                    "account" -> AccountPage(onOpen = { page = it })
                    "sync" -> CloudSyncPage(onSignIn = { page = "account" })
                    "plan" -> CheckoutPage()
                    "feel" -> FeelPage()
                    "look" -> LookPage()
                    "language" -> LanguagePage()
                    "doing" -> DoingPage()
                    "bank" -> BankModePage()
                    "connections" -> ConnectionsPage()
                    "desktop" -> DesktopPage()
                    "persona" -> PersonaSection()
                    "control" -> ControlPage(a11yOn, onOpenAccessibility)
                    "commands" -> CommandsSection()
                    "skills" -> SkillLibraryPage()
                    "voice" -> { VoiceSection(); MyVoiceSection(); VoiceTuning(); VoiceExtras() }
                    "orb" -> OrbSection()
                    "routines" -> SchedulePage()
                    "memory" -> { MemoryPage(); LearningSection() }
                    "usage" -> { UsageSection(); CostCap() }
                    // 0.52.3 Security: app lock and approvals, then Bank mode (it left the top list).
                    "privacy" -> { SecuritySection(); Spacer(Modifier.height(18.dp)); BankModePage() }
                    "device" -> DevicePage()
                    "proactive" -> ProactivePage()
                    "specialists" -> SpecialistsPage()
                    "terminal" -> TerminalPage()
                    "dashboard" -> DashboardPage()
                    "backup" -> BackupSection()
                    "learn" -> LearnPage()
                    "seller" -> SellerPage()
                    "appstore" -> AppStorePage()
                    "logs" -> LifeLogsPage()
                    "replies" -> RepliesPage()
                    "logins" -> SavedLoginsPage()
                    "secrets" -> SavedSecretsPage()
                    "phonebrowser" -> PhoneBrowserSettingsPage()
                    "watches" -> com.blaze.assistant.phone.MagicHandWatchesPage()
                }
                Spacer(Modifier.height(30.dp))
            }
        }
    }
    }
}

@Composable
private fun ControlPage(a11yOn: Boolean, onOpenAccessibility: () -> Unit) {
    val c = LocalBlaze.current.mono
    var control by remember { mutableStateOf(Settings.phoneControl) }
    Spacer(Modifier.height(8.dp))
    ToggleRow("Phone control", "Open apps, tap, type and scroll for you", control) { control = !control; Settings.phoneControl = control }
    // v0.24.5: the pointer is always shown while Blaze controls apps (no switch; the old show_pointer pref is ignored).
    Text("${BrandName.NAME}'s mascot pointer is always on screen while it controls your phone. It never blocks your taps or ${BrandName.NAME}'s. Tap the small Stop chip at the bottom-left to stop.",
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp), style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.weak))
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth().hairline(if (a11yOn) c.hair else c.accent).clickable(onClick = onOpenAccessibility).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).background(if (a11yOn) c.accent else c.weak, BlazeShapes.pill))
        Spacer(Modifier.width(10.dp))
        Text(if (a11yOn) "Accessibility access is on" else "Turn on \u201cBlaze phone control\u201d in Accessibility",
            modifier = Modifier.weight(1f), style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = c.body))
        Label("Open →", color = c.accent)
    }
    if (!a11yOn) Text("If Android calls it a restricted setting: App info → ${BrandName.NAME} → ⋮ → Allow restricted settings, then try again.",
        style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, lineHeight = 18.sp, color = c.sub), modifier = Modifier.padding(top = 8.dp))
    SkillsSection()
    NotificationAccessRow()
}

@Composable
private fun CommandsSection() {
    val c = LocalBlaze.current.mono
    var list by remember { mutableStateOf(com.blaze.assistant.agent.Commands.custom()) }
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    Spacer(Modifier.height(12.dp))
    Text("Type / in the chat box to see every command. Make your own: ${BrandName.NAME} swaps {input} for whatever you type after it.",
        style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, lineHeight = 19.sp, color = c.sub))
    Spacer(Modifier.height(14.dp))
    if (list.isNotEmpty()) {
        Label("Yours", modifier = Modifier.padding(bottom = 8.dp))
        Column(Modifier.fillMaxWidth().hairline(c.hair)) {
            list.forEach { cmd ->
                Row(Modifier.fillMaxWidth().hairlineBottom(c.hair).padding(start = 14.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("/" + cmd.name, style = TextStyle(fontFamily = Type.Mono, fontSize = 14.sp, color = c.ink))
                        Text(cmd.prompt.orEmpty().take(80), style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.sub))
                    }
                    Box(Modifier.clip(BlazeShapes.pill).clickable { com.blaze.assistant.agent.Commands.deleteCustom(cmd.name); list = com.blaze.assistant.agent.Commands.custom() }
                        .padding(12.dp)) { Label("×", size = 14.sp) }
                }
            }
        }
        Spacer(Modifier.height(18.dp))
    }
    Label("New command", modifier = Modifier.padding(bottom = 8.dp))
    Field("Name", name, { name = it }, "standup")
    Spacer(Modifier.height(10.dp))
    Field("Short description", desc, { desc = it }, "Write my daily standup")
    Spacer(Modifier.height(10.dp))
    Field("Prompt", prompt, { prompt = it }, "Turn these notes into a 3-line standup: {input}")
    Spacer(Modifier.height(10.dp))
    Box(Modifier.fillMaxWidth().height(46.dp).clip(BlazeShapes.pill).glassButton(c).clickable {
        com.blaze.assistant.agent.Commands.saveCustom(name, desc, prompt)
        list = com.blaze.assistant.agent.Commands.custom(); name = ""; desc = ""; prompt = ""
    }, contentAlignment = Alignment.Center) { ButtonText("Save command", color = Color.White) }
}

@Composable
private fun SquareToggle(on: Boolean) {
    val c = LocalBlaze.current.mono
    // v0.24.8: round (pill track, round knob, blue when on); the name stays so callers don't change.
    BlazeSwitchTrack(on, 46.dp, 26.dp)
}

@Composable
internal fun Field(label: String, value: String, onChange: (String) -> Unit, placeholder: String, secret: Boolean = false) {
    val c = LocalBlaze.current.mono
    // v0.45.7: every password / passphrase field gets a show/hide eye (sign-in, create account, reset, cloud sync).
    var reveal by remember { mutableStateOf(false) }
    Label(label, modifier = Modifier.padding(bottom = 8.dp))
    Row(Modifier.fillMaxWidth().hairline(c.hair).padding(start = 14.dp, end = if (secret) 4.dp else 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).padding(vertical = 14.dp)) {
            if (value.isEmpty()) Text(placeholder, maxLines = 1,
                style = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, color = c.weak))
            BasicTextField(
                value = value, onValueChange = onChange, singleLine = true,
                textStyle = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, color = c.ink),
                cursorBrush = SolidColor(c.accent),
                visualTransformation = if (SecretField.masked(secret, reveal)) PasswordVisualTransformation() else VisualTransformation.None,
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (secret) RevealEye(reveal) { reveal = !reveal }
    }
    Spacer(Modifier.height(14.dp))
}

/** v0.45.7: the eye on a secret field: 40 dp target, "Show password" / "Hide password" for TalkBack. */
@Composable
internal fun RevealEye(reveal: Boolean, onToggle: () -> Unit) {
    val c = LocalBlaze.current.mono
    Box(Modifier.size(40.dp).clip(androidx.compose.foundation.shape.CircleShape).clickable(onClickLabel = SecretField.label(reveal), onClick = onToggle),
        contentAlignment = Alignment.Center) {
        androidx.compose.material3.Icon(if (reveal) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
            contentDescription = SecretField.label(reveal), tint = c.sub, modifier = Modifier.size(20.dp))
    }
}

/** v0.45.7 Settings › Language: Auto (match the user) or one fixed reply language. */
@Composable
private fun LanguagePage() {
    val c = LocalBlaze.current.mono
    var sel by remember { mutableStateOf(com.blaze.assistant.agent.ReplyLanguage.current()) }
    Text("${BrandName.NAME} replies in this language. Auto answers in whatever language you write in.",
        style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, lineHeight = 19.sp, color = c.sub), modifier = Modifier.padding(vertical = 8.dp))
    com.blaze.assistant.agent.ReplyLanguage.OPTIONS.forEach { o ->
        val on = o.code == sel
        Row(Modifier.fillMaxWidth().hairlineBottom(c.hair).clickable(onClickLabel = o.label) {
                com.blaze.assistant.agent.ReplyLanguage.set(o.code); sel = o.code; FontPick.refresh()
            }.padding(vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
            // The label in its own script, drawn by the system font (Inter has no Ge'ez or Arabic letters).
            Text(o.label, modifier = Modifier.weight(1f),
                style = TextStyle(fontFamily = Type.sansFor(o.label), fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, fontSize = 17.sp, color = c.ink))
            if (on) androidx.compose.material3.Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = c.accent, modifier = Modifier.size(20.dp))
        }
    }
}

/** Provider dropdown: the built-in services plus a custom OpenAI-compatible one. */
@Composable
private fun ProviderPicker(pid: String, onPick: (String) -> Unit) {
    val c = LocalBlaze.current.mono
    var open by remember { mutableStateOf(false) }
    Label("AI provider", modifier = Modifier.padding(bottom = 8.dp))
    Row(
        Modifier.fillMaxWidth().hairline(if (open) c.ink else c.hair).clickable { open = !open }
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(Providers.byId(pid).name, modifier = Modifier.weight(1f),
            style = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, color = c.ink))
        Label(if (open) "▲" else "▼", color = c.sub)
    }
    if (open) {
        Column(Modifier.fillMaxWidth().hairline(c.hair)) {
            Providers.all.forEach { p ->
                val on = p.id == pid
                val hasKey = Settings.keyFor(p.id).isNotBlank()
                Row(
                    Modifier.fillMaxWidth().clip(BlazeShapes.small).background(if (on) PageGlass.fill(c, 0.10f) else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable { onPick(p.id); open = false }
                        .hairlineBottom(c.hair).padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(6.dp).background(if (on) c.accent else c.paper, BlazeShapes.pill))
                    Spacer(Modifier.width(10.dp))
                    Text(p.name, modifier = Modifier.weight(1f),
                        style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = c.ink))
                    if (hasKey) Label("Key saved", color = c.accent, size = 9.sp)
                }
            }
        }
    }
    Spacer(Modifier.height(14.dp))
}

/** Plan & quota: balance, rate limits and plan as the provider reports them to this key. */
@Composable
private fun QuotaPanel(key: String, pid: String, model: String, base: String) {
    val c = LocalBlaze.current.mono
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    var report by remember { mutableStateOf<Quota.Report?>(null) }
    var loading by remember { mutableStateOf(false) }
    val prov = Providers.byId(pid)

    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Label("Plan & quota", modifier = Modifier.weight(1f))
        Label(if (loading) "Checking" else "Check", color = if (key.isBlank() || loading) c.weak else c.accent, size = 11.sp,
            modifier = Modifier.clickable(enabled = key.isNotBlank() && !loading) {
                Settings.providerId = pid; Settings.apiKey = key; Settings.model = model
                if (pid == Providers.CUSTOM) Settings.customBase = base
                loading = true
                scope.launch { report = Quota.check(); loading = false }
            }.padding(6.dp))
    }
    Column(Modifier.fillMaxWidth().hairline(c.hair)) {
        val r = report
        if (r == null) {
            Text(if (key.isBlank()) "Add your key to see your plan and limits." else "Tap Check to see your plan, limits and balance.",
                modifier = Modifier.padding(14.dp),
                style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.sub))
        } else {
            r.rows.forEach { row ->
                Row(Modifier.fillMaxWidth().hairlineBottom(c.hair).padding(horizontal = 14.dp, vertical = 11.dp)) {
                    Label(row.label, modifier = Modifier.weight(1f), size = 10.sp)
                    Text(row.value, style = TextStyle(fontFamily = Type.Mono, fontSize = 12.sp, color = c.ink))
                }
            }
            r.note?.let {
                Text(it, modifier = Modifier.padding(14.dp),
                    style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, lineHeight = 18.sp, color = c.sub))
            }
        }
        if (prov.quotaPage.isNotBlank()) {
            Row(Modifier.fillMaxWidth().clickable { uri.openUri(prov.quotaPage) }.padding(14.dp)) {
                Text("Full usage dashboard", modifier = Modifier.weight(1f),
                    style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.body))
                Label("Open →", color = c.accent, size = 11.sp)
            }
        }
    }
    Spacer(Modifier.height(18.dp))
}


@Composable
internal fun ToggleRow(title: String, sub: String, on: Boolean, onToggle: () -> Unit) {
    val c = LocalBlaze.current.mono
    Row(
        Modifier.fillMaxWidth().hairlineBottom(c.hair).clickable(onClick = onToggle).padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.Medium, fontSize = 17.sp, color = c.ink))
            Text(sub, style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.sub))
        }
        SquareToggle(on)
    }
}

@Composable
private fun VoiceSection() {
    val c = LocalBlaze.current.mono
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var speak by remember { mutableStateOf(Settings.speakReplies) }
    var wake by remember { mutableStateOf(Settings.wakeWord) }
    var isDefault by remember { mutableStateOf(BlazeInteractionService.isDefault(ctx)) }
    val dl by WakeModel.progress.collectAsState()
    var modelReady by remember { mutableStateOf(WakeModel.ready(ctx)) }
    var err by remember { mutableStateOf("") }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) { isDefault = BlazeInteractionService.isDefault(ctx); kotlinx.coroutines.delay(1500) }
    }
    val micPerm = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }

    fun setWake(on: Boolean) {
        err = ""
        wake = on; Settings.wakeWord = on
        if (!on) { WakeWordService.stop(ctx); return }
        if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) micPerm.launch(android.Manifest.permission.RECORD_AUDIO)
        if (WakeModel.ready(ctx)) { WakeWordService.start(ctx); return }
        scope.launch {
            val r = WakeModel.download(ctx)
            modelReady = WakeModel.ready(ctx)
            if (r.isSuccess && modelReady) WakeWordService.start(ctx)
            else { err = "Download failed. Check your connection and try again."; wake = false; Settings.wakeWord = false }
        }
    }

    Spacer(Modifier.height(22.dp))
    Label("Voice & assistant", modifier = Modifier.padding(bottom = 4.dp))
    ToggleRow("Speak replies", "Read answers aloud when you ask by voice", speak) { speak = !speak; Settings.speakReplies = speak }
    ToggleRow(
        "\u201cHey ${BrandName.NAME}\u201d",
        when {
            dl in 0..99 -> "Downloading voice model\u2026 $dl%"
            wake && modelReady -> "Listening offline for \u201cHey ${BrandName.NAME}\u201d or \u201cWake up ${BrandName.NAME}\u201d"
            else -> "Wake ${BrandName.NAME} hands-free. One-time ${WakeModel.SIZE_MB} MB offline download"
        },
        wake
    ) { if (dl !in 0..99) setWake(!wake) }
    if (err.isNotEmpty()) Text(err, style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.accent),
        modifier = Modifier.padding(top = 8.dp))

    Spacer(Modifier.height(16.dp))
    Row(
        Modifier.fillMaxWidth().hairline(if (isDefault) c.hair else c.accent)
            .clickable { BlazeInteractionService.openPicker(ctx) }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(6.dp).background(if (isDefault) c.accent else c.weak, BlazeShapes.pill))
        Spacer(Modifier.width(10.dp))
        Text(
            if (isDefault) "${BrandName.NAME} is your default assistant" else "Make ${BrandName.NAME} your default assistant",
            modifier = Modifier.weight(1f),
            style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = c.body)
        )
        Label("Open →", color = c.accent)
    }
    if (!isDefault) Text(
        "Pick Digital assistant app \u2192 ${BrandName.NAME}. Then long-press power or home to talk to ${BrandName.NAME} instead of the phone's built-in assistant.",
        style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, lineHeight = 18.sp, color = c.sub),
        modifier = Modifier.padding(top = 8.dp)
    )
}


@Composable
private fun OrbSection() {
    val c = LocalBlaze.current.mono
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var on by remember { mutableStateOf(Overlay.orbShowing) }
    var allowed by remember { mutableStateOf(Overlay.canDrawOverApps(ctx)) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) { allowed = Overlay.canDrawOverApps(ctx); on = Overlay.orbShowing; kotlinx.coroutines.delay(1200) }
    }
    ToggleRow("Floating orb", if (on) "${BrandName.NAME} stays on screen. Tap to chat, hold to talk, drag to move"
        else "Keep ${BrandName.NAME} on screen while you use other apps", on) {
        if (on) { Overlay.hideOrb(ctx); on = false }
        else if (!Overlay.showOrb(ctx)) Overlay.requestOverlayPermission(ctx) else on = true
    }
    if (!allowed) Row(
        Modifier.fillMaxWidth().padding(top = 10.dp).hairline(c.hair).clickable { Overlay.requestOverlayPermission(ctx) }.padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Allow “Display over other apps” so you can type in the orb's chat", modifier = Modifier.weight(1f),
            style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.body))
        Label("Allow →", color = c.accent, size = 11.sp)
    }
}

@Composable
private fun PersonaSection() {
    val c = LocalBlaze.current.mono
    var pid by remember { mutableStateOf(Settings.personaId) }
    var custom by remember { mutableStateOf(Settings.customPersona) }
    var stream by remember { mutableStateOf(Settings.streamReplies) }
    Spacer(Modifier.height(22.dp))
    Label("Personality", modifier = Modifier.padding(bottom = 8.dp))
    Column(Modifier.fillMaxWidth().hairline(c.hair)) {
        com.blaze.assistant.agent.Personas.all.forEach { p ->
            Row(Modifier.fillMaxWidth().clip(BlazeShapes.small).background(if (p.id == pid) PageGlass.fill(c, 0.10f) else androidx.compose.ui.graphics.Color.Transparent)
                .clickable { pid = p.id; Settings.personaId = p.id }.padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).background(if (p.id == pid) c.accent else Color.Transparent, BlazeShapes.pill))
                Spacer(Modifier.width(10.dp))
                Text(p.name, modifier = Modifier.weight(1f), style = TextStyle(fontFamily = Type.Display, fontSize = 15.sp, color = c.ink))
                Text(p.blurb, style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.sub))
            }
        }
    }
    if (pid == com.blaze.assistant.agent.Personas.CUSTOM) {
        Spacer(Modifier.height(10.dp))
        Field("Describe ${BrandName.NAME}'s personality", custom, { custom = it; Settings.customPersona = it },
            "e.g. Talk like a calm big brother, mix in some Nyanja, keep it short")
    }
    Spacer(Modifier.height(6.dp))
    var dots by remember { mutableStateOf(Settings.particles) }
    ToggleRow("Background particles", "Drifting dots. Turn off for the smoothest, lightest feel", dots) { dots = !dots; Settings.particles = dots }
    // 0.50.0: Blaze's edge particles while it controls the screen (ui/ScreenGlow)
    var glowOn by remember { mutableStateOf(Settings.bool("screen_glow", true)) }
    ToggleRow("Glow while ${BrandName.NAME} uses the screen", "Particles round the screen edges while ${BrandName.NAME} taps and types for you", glowOn) { glowOn = !glowOn; Settings.setBool("screen_glow", glowOn) }
    ToggleRow("Stream replies", "Words appear as ${BrandName.NAME} writes them", stream) { stream = !stream; Settings.streamReplies = stream }
    // 0.51.0 BlazeQuery (Halo Persona Studio removed: Halo is part of Blaze now)
    var bqOn by remember { mutableStateOf(Settings.bool("blazequery", true)) }
    ToggleRow("BlazeQuery: fresh facts for every reply", "Checks trusted sources (official docs, registries, release notes; never Wikipedia) as you send, and gives ${BrandName.NAME} a short summary. Nothing is sent from private chats", bqOn) { bqOn = !bqOn; Settings.setBool("blazequery", bqOn) }
}

@Composable
private fun BackupSection() {
    val c = LocalBlaze.current.mono
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var msg by remember { mutableStateOf("") }
    val exporter = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        msg = try {
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(Settings.exportJson().toByteArray()) }
            "Saved. Keep this file private: it holds your API keys."
        } catch (e: Exception) { "Couldn't save: ${e.message}" }
    }
    val importer = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        msg = try {
            val n = Settings.importJson(ctx.contentResolver.openInputStream(uri)!!.bufferedReader().readText())
            com.blaze.assistant.agent.Memory.init(ctx, mergeLegacy = true)
            "Restored $n settings. Close and reopen Settings to see them."
        } catch (e: Exception) { "That file isn't a ${BrandName.NAME} backup." }
    }
    Spacer(Modifier.height(22.dp))
    Label("Backup", modifier = Modifier.padding(bottom = 8.dp))
    Text("Updates keep everything automatically. Your chats, memory and sign-ins are never copied to Google's cloud backup, " +
        "so Export (settings, keys and memory) if you'll reinstall or switch phones. Keep the file private.",
        style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, lineHeight = 19.sp, color = c.sub))
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.weight(1f).height(46.dp).hairline(c.hair).clickable { exporter.launch("blaze-backup.json") },
            contentAlignment = Alignment.Center) { Label("Export", color = c.ink, size = 11.sp) }
        Box(Modifier.weight(1f).height(46.dp).hairline(c.hair).clickable { importer.launch(arrayOf("application/json", "*/*")) },
            contentAlignment = Alignment.Center) { Label("Import", color = c.ink, size = 11.sp) }
    }
    if (msg.isNotEmpty()) Text(msg, modifier = Modifier.padding(top = 8.dp),
        style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.accent))
}


@Composable
private fun SkillsSection() {
    val c = LocalBlaze.current.mono
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var tick by remember { mutableStateOf(0) }
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    Spacer(Modifier.height(22.dp))
    Label("Skills", modifier = Modifier.padding(bottom = 8.dp))
    val subs = mapOf("Contacts" to "Find people by name", "Messages" to "Read and send texts (asks before sending)",
        "Calls" to "Recent calls, place calls (asks first)", "Calendar" to "Read and add events")
    com.blaze.assistant.phone.Skills.groups.forEach { (name, perms) ->
        val on = remember(tick) { com.blaze.assistant.phone.Skills.granted(ctx, perms) }
        ToggleRow(name, subs[name] ?: "", on) {
            if (!on) launcher.launch(perms)
            else ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:" + ctx.packageName)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
    Text("Quick settings (flashlight, volume, battery, Wi-Fi panel) work without any permission.",
        modifier = Modifier.padding(top = 8.dp), style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.sub))
}

@Composable
private fun UsageSection() {
    val c = LocalBlaze.current.mono
    val v by com.blaze.assistant.agent.Usage.version.collectAsState()
    val today = remember(v) { com.blaze.assistant.agent.Usage.today() }
    val month = remember(v) { com.blaze.assistant.agent.Usage.month() }
    fun fmt(t: com.blaze.assistant.agent.Usage.Totals): String {
        val tok = t.input + t.output
        val tk = if (tok >= 1_000_000) "%.1fM".format(tok / 1e6) else if (tok >= 1000) "%.1fk".format(tok / 1e3) else "$tok"
        val cost = if (t.priced) (if (t.cost < 0.01 && t.cost > 0) "<$0.01" else "$%.2f".format(t.cost)) else "price unknown"
        return "$tk tokens · ${t.calls} calls · $cost"
    }
    Spacer(Modifier.height(22.dp))
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Label("Usage", modifier = Modifier.weight(1f))
        ActionText("Reset", color = c.sub, size = 10.sp, modifier = Modifier.clickable { com.blaze.assistant.agent.Usage.reset() })
    }
    Column(Modifier.fillMaxWidth().hairline(c.hair)) {
        listOf("Today" to today, "This month" to month).forEach { (k, t) ->
            Row(Modifier.fillMaxWidth().hairlineBottom(c.hair).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(k, modifier = Modifier.weight(1f), style = TextStyle(fontFamily = Type.Display, fontSize = 15.sp, color = c.ink))
                Text(fmt(t), style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.body))
            }
        }
    }
    Text("Estimated from your provider's counts and list prices. Free tiers show $0.00.",
        modifier = Modifier.padding(top = 6.dp), style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.sub))
}

@Composable
private fun SecuritySection() {
    var lock by remember { mutableStateOf(Settings.appLock) }
    var risky by remember { mutableStateOf(Settings.confirmRisky) }
    Spacer(Modifier.height(22.dp))
    Label("Privacy & safety", modifier = Modifier.padding(bottom = 4.dp))
    ToggleRow("Lock ${BrandName.NAME}", "Fingerprint, face or PIN to open the app", lock) { lock = !lock; Settings.appLock = lock }
    ToggleRow("Ask before risky actions", "${BrandName.NAME} asks before tapping Send, Pay, Buy, Delete or Post", risky) { risky = !risky; Settings.confirmRisky = risky }
}


/** More keys for the same provider: when one runs out, Blaze moves to the next with the same model. */
@Composable
internal fun ExtraKeys(pid: String) {
    val c = LocalBlaze.current.mono
    var tick by remember { mutableStateOf(0) }
    val extras = remember(pid, tick) { com.blaze.assistant.agent.KeyRing.extras(pid) }
    var adding by remember(pid) { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        extras.forEachIndexed { i, k ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Key ${i + 2}  \u2022\u2022\u2022\u2022${k.takeLast(4)}", modifier = Modifier.weight(1f),
                    style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.ink))
                ActionText("Remove", color = c.accent, size = 9.sp, modifier = Modifier.clickable {
                    com.blaze.assistant.agent.KeyRing.removeExtra(pid, k); tick++ }.padding(6.dp))
            }
        }
        Field("Add another key (optional)", adding, { adding = it }, "Used when the first key runs out", secret = true)
        if (adding.isNotBlank()) ActionText("Save this key", color = c.accent, size = 10.sp, modifier = Modifier.clickable {
            com.blaze.assistant.agent.KeyRing.add(pid, adding); com.blaze.assistant.agent.KeyRing.clear(pid); adding = ""; tick++
        }.padding(vertical = 8.dp))
    }
}
