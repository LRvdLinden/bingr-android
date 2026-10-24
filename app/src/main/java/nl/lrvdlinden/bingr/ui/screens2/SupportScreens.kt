package nl.lrvdlinden.bingr

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * Build 146: de tabs Support en Diagnose in Instellingen (= de Apple-apps, Samsung TV en de webspeler).
 * Telefoon/tablet met aanraken, Android TV met de afstandsbediening: alle rijen en knoppen zijn focusbaar, tekstvelden
 * openen het tv-toetsenbord. Contract: docs/ACCOUNT.md → "Support vanuit de app" en "Diagnose-bijlage".
 */

/** Rood bolletje met het aantal ongelezen antwoorden (niets bij 0). */
@Composable
fun SupportBadge(count: Int, modifier: Modifier = Modifier) {
    val text = Build146Logic.Support.badge(count) ?: return
    Box(modifier.clip(RoundedCornerShape(50)).background(Theme.red).padding(horizontal = tvs(6), vertical = 1.dp),
        contentAlignment = Alignment.Center) {
        Text(text, color = Color.White, fontSize = tsp(11), fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** Tekst bij een status, zoals op support.bingr.online. */
fun supportStatusLabel(status: String?): String = L(Build146Logic.Support.statusKey(status))

private fun groupTitle(g: Build146Logic.Support.Group): String = when (g) {
    Build146Logic.Support.Group.OPEN -> L("Open")
    Build146Logic.Support.Group.IN_PROGRESS -> L("In progress")
    Build146Logic.Support.Group.CLOSED -> L("Closed")
}

/** Wat er bij "Diagnose meesturen" meegaat: na een mislukte vorige start de startdiagnose, anders de app-diagnose. */
internal suspend fun supportDiagnostics(): JsonObject {
    val p = StartDiagnostics.lastProblem
    return if (p != null) Diagnostics.start(p) else Diagnostics.app()
}

/** De inhoud van de tab Support (in de lijst van Instellingen). */
fun LazyListScope.supportTabItems(onOpen: (Int) -> Unit, onNew: () -> Unit) {
    item {
        val ctx = LocalContext.current
        if (!Account.isSignedIn || !Account.configured) {
            S2Section(L("Support"), L("Sign in with your Bingr account to see your support tickets here, reply to them and create a new ticket. Without an account you can still ask a question at support.bingr.online.")) {
                S2Row(L("Open support.bingr.online"), icon = Icons.Default.OpenInBrowser, value = Support.URL.removePrefix("https://"),
                    onClick = { Support.open(ctx) })
            }
            return@item
        }
        LaunchedEffect(Unit) { SupportCenter.refresh(force = true) }
        S2Section(footer = L("Answers from Bingr Support appear here and are also sent by email.")) {
            S2Row(L("New ticket"), icon = Icons.Default.Add, onClick = onNew)
            if (StartDiagnostics.lastProblem != null) {
                S2Row(L("Send start diagnostics"), icon = Icons.Default.MonitorHeart,
                    subtitle = L("The previous start of Bingr didn't finish. Send the start diagnostics with a new ticket."), onClick = onNew)
            }
            S2Row(L("Refresh"), icon = Icons.Default.Refresh, enabled = !SupportCenter.loading, onClick = { SupportCenter.refresh(force = true) },
                trailing = { if (SupportCenter.loading) CircularProgressIndicator(color = Theme.red, strokeWidth = 2.dp, modifier = Modifier.size(18.dp)) })
            SupportCenter.listError?.let { S2Row(it, tint = Theme.red) }
        }
    }
    item {
        if (!Account.isSignedIn || !Account.configured) return@item
        val all = SupportCenter.tickets
        if (SupportCenter.loaded && all.isEmpty()) {
            S2Section { S2Row(L("You don't have any support tickets yet."), tint = Theme.secondary) }
        }
        for (g in Build146Logic.Support.Group.entries) {
            val list = all.filter { it.group == g }
            if (list.isEmpty()) continue
            S2Section("${groupTitle(g)} (${list.size})") {
                list.forEach { t ->
                    val time = (t.lastReplyAt ?: t.createdAt)?.let { s2DateTime(it) }
                    S2Row(t.subject.ifBlank { "#${t.number}" },
                        subtitle = listOfNotNull("#${t.number}", supportStatusLabel(t.status), time?.let { L("Updated %@", it) }).joinToString(" · "),
                        onClick = { onOpen(t.number) },
                        trailing = {
                            if (t.unread) Box(Modifier.size(tvs(10)).clip(CircleShape).background(Theme.red))
                        })
                }
            }
        }
    }
}

/** Tekstvak voor een bericht: op telefoon/tablet meerdere regels, op tv één regel (de focus blijft dan te verplaatsen). */
@Composable
private fun S2TextArea(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    val tv = AppState.isTV
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) },
        singleLine = tv, minLines = if (tv) 1 else 4, maxLines = if (tv) 1 else 10,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Color.White, unfocusedBorderColor = Color.White.copy(alpha = 0.25f),
            focusedLabelColor = Color.White, unfocusedLabelColor = Theme.secondary, cursorColor = Theme.red,
            focusedTextColor = Color.White, unfocusedTextColor = Color.White,
            focusedContainerColor = Theme.card, unfocusedContainerColor = Theme.card,
        ),
        modifier = modifier.fillMaxWidth()
    )
}

/** De diagnose die meegaat, leesbaar ("Bekijken"). */
@Composable
fun DiagnosticsViewDialog(json: JsonObject?, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val fr = remember { FocusRequester() }
        Column(Modifier.padding(24.dp).widthIn(max = 720.dp).clip(RoundedCornerShape(14.dp)).background(Theme.elevated).padding(20.dp)) {
            Text(L("Diagnostics"), color = Color.White, fontSize = tsp(18), fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Box(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (json == null) CircularProgressIndicator(color = Theme.red, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                else Text(Diagnostics.pretty(json), color = Theme.secondary, fontSize = tsp(11), fontFamily = FontFamily.Monospace)
            }
            Spacer(Modifier.height(12.dp))
            NetflixButton(L("Close"), Icons.Default.Close, kind = NetflixKind.PRIMARY, modifier = Modifier.fillMaxWidth().focusRequester(fr)) { onDismiss() }
        }
        LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    }
}

/** Nieuw ticket: onderwerp, categorie, bericht en "Diagnose meesturen" (standaard aan, met "Bekijken"). */
@Composable
fun SupportNewScreen(onClose: () -> Unit, onCreated: (Int) -> Unit) {
    var subject by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(if (StartDiagnostics.lastProblem != null) "apps" else "afspelen") }
    var withDiag by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var view by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<JsonObject?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val first = remember { FocusRequester() }
    S2FullDialog(onClose) {
        S2Page(L("New ticket"), onClose = onClose) {
            item {
                S2Section {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        S2TextField(subject, { subject = it.take(120) }, L("Subject"), plain = false, modifier = Modifier.focusRequester(first))
                        S2TextArea(message, { message = it.take(5000) }, L("Your message"))
                    }
                }
            }
            item {
                S2Section(footer = if (StartDiagnostics.lastProblem != null) L("The previous start of Bingr didn't finish: the start diagnostics are sent.")
                    else L("Device, app version, servers (without names or addresses), network and memory. No email, names, passwords or addresses.")) {
                    S2Dropdown(L("Category"), Build146Logic.Support.categories.map { it to L(Build146Logic.Support.categoryKey(it)) }, category) { category = it }
                    S2Toggle(L("Send diagnostics"), withDiag) { withDiag = it }
                    if (withDiag) S2Row(L("View"), icon = Icons.Default.Visibility, onClick = {
                        view = true; preview = null
                        scope.launch { preview = runCatching { supportDiagnostics() }.getOrNull() }
                    })
                }
            }
            error?.let { e -> item { Text(e, color = Theme.red, fontSize = tsp(13), modifier = Modifier.padding(horizontal = Theme.edge, vertical = 8.dp)) } }
            item {
                Box(Modifier.widthIn(max = S2MaxWidth).fillMaxWidth().padding(horizontal = Theme.edge, vertical = 16.dp)) {
                    if (busy) CircularProgressIndicator(color = Theme.red, modifier = Modifier.align(Alignment.Center))
                    else NetflixButton(L("Send"), Icons.AutoMirrored.Filled.Send, kind = NetflixKind.ACCENT, modifier = Modifier.fillMaxWidth()) {
                        error = null; busy = true
                        AppScope.launch {
                            try {
                                val d = if (withDiag) runCatching { supportDiagnostics() }.getOrNull() else null
                                val (n, err) = SupportCenter.create(subject, category, message, d)
                                if (n != null) {
                                    if (d != null && d.jS("kind") == "start") StartDiagnostics.clearProblem()
                                    onCreated(n)
                                } else error = err
                            } finally { busy = false }
                        }
                    }
                }
            }
        }
        LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    }
    if (view) DiagnosticsViewDialog(preview) { view = false }
}

/** Een ticket: het gesprek, reageren en sluiten; gesloten = alleen lezen, met een knop naar een nieuw ticket. */
@Composable
fun SupportTicketScreen(number: Int, onClose: () -> Unit, onNew: () -> Unit) {
    var state by remember { mutableStateOf<SupportCenter.Loaded?>(null) }
    var reload by remember { mutableStateOf(0) }
    var reply by remember { mutableStateOf("") }
    var withDiag by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmClose by remember { mutableStateOf(false) }
    LaunchedEffect(number, reload) { state = SupportCenter.get(number) }
    // 404 (ander account op dit apparaat, of het ticket bestaat niet meer): terug naar de lijst
    LaunchedEffect(state) { if (state is SupportCenter.Loaded.NotFound) onClose() }
    S2FullDialog(onClose) {
        val s = state
        val ok = s as? SupportCenter.Loaded.Ok
        S2Page(ok?.ticket?.subject?.ifBlank { null } ?: "#$number", onClose = onClose) {
            when (s) {
                null -> item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Theme.red, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                    }
                }
                is SupportCenter.Loaded.Failed -> item {
                    S2Section {
                        S2Row(s.message, tint = Theme.red)
                        S2Row(L("Try again"), icon = Icons.Default.Refresh, onClick = { state = null; reload++ })
                    }
                }
                is SupportCenter.Loaded.NotFound -> {}
                is SupportCenter.Loaded.Ok -> {
                    val t = s.ticket
                    item {
                        S2Section {
                            S2Row("#${t.number} · ${supportStatusLabel(t.status)}", icon = Icons.Default.Info,
                                subtitle = listOfNotNull(t.category?.let { L(Build146Logic.Support.categoryKey(it)) },
                                    t.createdAt?.let { s2DateTime(it) }).joinToString(" · "))
                        }
                    }
                    item {
                        S2Section(L("Conversation")) {
                            s.messages.forEach { m ->
                                when (m.author) {
                                    "event" -> S2Row(L("Status: %@", supportStatusLabel(m.body)), tint = Theme.secondary,
                                        subtitle = m.createdAt?.let { s2DateTime(it) })
                                    else -> {
                                        val who = if (m.author == "support") L("Bingr Support") else L("You")
                                        // focusbaar op tv, zodat een lang gesprek met de afstandsbediening te lezen is
                                        S2Row(m.body, subtitle = listOfNotNull(who, m.createdAt?.let { s2DateTime(it) },
                                            m.diagnostics?.let { L("Diagnostics attached") }).joinToString(" · "),
                                            tint = if (m.author == "support") Color.White else Theme.secondary.copy(alpha = 1f),
                                            onClick = if (AppState.isTV) ({}) else null)
                                    }
                                }
                            }
                        }
                    }
                    if (t.canReply) {
                        item {
                            S2Section(L("Reply")) {
                                Column(Modifier.padding(12.dp)) { S2TextArea(reply, { reply = it.take(5000) }, L("Your reply")) }
                                S2Toggle(L("Send diagnostics"), withDiag) { withDiag = it }
                                if (busy) S2Row("", leading = { CircularProgressIndicator(color = Theme.red, strokeWidth = 2.dp, modifier = Modifier.size(18.dp)) })
                                else S2Row(L("Send"), icon = Icons.AutoMirrored.Filled.Send, enabled = reply.isNotBlank(), onClick = {
                                    error = null; busy = true
                                    AppScope.launch {
                                        try {
                                            val d = if (withDiag) runCatching { supportDiagnostics() }.getOrNull() else null
                                            val err = SupportCenter.reply(t.number, reply, d)
                                            if (err == null) { reply = ""; withDiag = false; reload++ } else error = err
                                        } finally { busy = false }
                                    }
                                })
                                error?.let { S2Row(it, tint = Theme.red) }
                            }
                        }
                        item {
                            S2Section {
                                S2Row(L("Close ticket"), icon = Icons.Default.Close, tint = Theme.red, onClick = { confirmClose = true })
                            }
                        }
                    } else {
                        item {
                            S2Section(footer = L("This ticket is closed. Create a new ticket if you still have a question.")) {
                                S2Row(L("New ticket"), icon = Icons.Default.Add, onClick = onNew)
                            }
                        }
                    }
                }
            }
        }
    }
    if (confirmClose) {
        S2ActionDialog(L("Close this ticket?"), listOf(
            S2Action(L("Close ticket"), Icons.Default.Close, destructive = true) {
                AppScope.launch { val err = SupportCenter.close(number); if (err == null) reload++ else error = err }
            },
        ), onDismiss = { confirmClose = false })
    }
}

// MARK: Diagnose

/** De inhoud van de tab Diagnose: apparaat, startdiagnose, app-diagnose en "Naar support sturen". */
fun LazyListScope.diagnoseTabItems() {
    item {
        S2Section(L("This device")) {
            S2Row(L("Device type"), value = Diagnostics.deviceTypeLabel())
            S2Row(L("Model"), value = Diagnostics.modelLabel)
            S2Row(L("System"), value = "${Diagnostics.osName} ${Diagnostics.osVersion}")
            S2Row(L("App version"), value = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            S2Row(L("Language"), value = Diagnostics.language)
        }
    }
    item {
        val p = StartDiagnostics.lastProblem
        var sending by remember { mutableStateOf(false) }
        var result by remember { mutableStateOf<String?>(null) }
        S2Section(L("Start diagnostics"), if (p == null) L("How the app started: milestones with time and memory. If a start doesn't finish or Bingr crashes, it shows here the next time.") else null) {
            if (p == null) {
                S2Row(L("The last start went fine."), tint = Theme.secondary)
                val marks = StartDiagnostics.marks()
                if (marks.isNotEmpty()) S2Row(marks.joinToString(" · ") { m -> "${m.name} ${"%.1f".format(m.ms / 1000.0)}s" + (m.mb?.let { " ${it}MB" } ?: "") },
                    tint = Theme.secondary, onClick = if (AppState.isTV) ({}) else null)
            } else {
                S2Row(Build146Logic.Diag.text(p), tint = Theme.secondary, onClick = if (AppState.isTV) ({}) else null)
                if (Account.isSignedIn && Account.configured) {
                    if (sending) S2Row("", leading = { CircularProgressIndicator(color = Theme.red, strokeWidth = 2.dp, modifier = Modifier.size(18.dp)) })
                    else S2Row(L("Send to support"), icon = Icons.AutoMirrored.Filled.Send, onClick = {
                        sending = true; result = null
                        AppScope.launch {
                            try {
                                val (n, err) = SupportCenter.create(L("Start diagnostics") + " · " + Diagnostics.modelLabel, "apps",
                                    L("Start diagnostics from the app (previous start didn't finish)."), Diagnostics.start(p))
                                result = if (n != null) { StartDiagnostics.clearProblem(); L("Sent as ticket #%d.", n) } else err
                            } finally { sending = false }
                        }
                    })
                } else S2Row(L("Sign in to send this to support."), tint = Theme.secondary)
                S2Row(L("Dismiss"), tint = Theme.red, onClick = { StartDiagnostics.clearProblem() })
            }
            result?.let { S2Row(it, tint = Theme.secondary) }
        }
    }
    item {
        var running by remember { mutableStateOf(false) }
        var diag by remember { mutableStateOf<JsonObject?>(null) }
        var sending by remember { mutableStateOf(false) }
        var result by remember { mutableStateOf<String?>(null) }
        var view by remember { mutableStateOf(false) }
        S2Section(L("App diagnostics"), L("No personal data: no email, names, usernames, passwords, server names or addresses.")) {
            if (running) S2Row(L("Checking…"), leading = { CircularProgressIndicator(color = Theme.red, strokeWidth = 2.dp, modifier = Modifier.size(18.dp)) })
            else S2Row(if (diag == null) L("Run diagnostics") else L("Run again"), icon = Icons.Default.MonitorHeart, onClick = {
                running = true; result = null
                AppScope.launch { try { diag = runCatching { Diagnostics.app() }.getOrNull() } finally { running = false } }
            })
            val d = diag
            if (d != null) {
                val st = d.jO("state")
                val net = st.jO("network")
                fun speed(k: String) = when (net.jS(k)) {
                    "ok" -> L("OK"); "slow" -> L("Slow"); "fail" -> L("Not reachable"); "skipped" -> L("Not checked"); else -> L("Unknown")
                }
                Diagnostics.serverUpdates().forEachIndexed { i, (kind, at) ->
                    S2Row(L("Server %d (%@)", i + 1, kind), value = if (at > 0) s2DateTime(at) else L("Never updated"))
                }
                if (Store.playlists.isEmpty()) S2Row(L("Servers"), value = "0")
                S2Row(L("Last account sync"), value = st.jS("lastSync")?.let { Net.parseISODate(it) }?.let { s2DateTime(it) }
                    ?.let { if (st.jB("lastSyncOk") == false) "$it · " + L("failed") else it } ?: L("Not yet"))
                S2Row(L("Bingr server"), value = speed("backend") + (net.jL("backendMs")?.let { " · $it ms" } ?: ""))
                S2Row(L("Your IPTV server"), value = speed("provider") + (net.jL("providerMs")?.let { " · $it ms" } ?: ""))
                S2Row(L("Network"), value = listOfNotNull(net.jS("type"), if (net.jB("vpn") == true) "VPN" else null).joinToString(" · "))
                val sto = st.jO("storage")
                val dl = Diagnostics.storage().downloadsMB
                S2Row(L("Storage"), value = L("Cache %d MB · downloads %d MB · free %d MB", sto.jI("cacheMB") ?: 0, dl, sto.jI("freeMB") ?: 0))
                val mem = d.jO("memory")
                S2Row(L("Memory"), value = L("App %d MB · in use %d of %d MB", mem.jI("appMB") ?: 0, mem.jI("usedMB") ?: 0, mem.jI("totalMB") ?: 0))
                S2Row(L("Profiles"), value = (st.jI("profiles") ?: 0).toString())
                S2Row(L("Load servers on open"), value = loadOnOpenTitle(Playlists.loadOnOpen))
                S2Row(L("View"), icon = Icons.Default.Visibility, onClick = { view = true })
                if (Account.isSignedIn && Account.configured) {
                    if (sending) S2Row("", leading = { CircularProgressIndicator(color = Theme.red, strokeWidth = 2.dp, modifier = Modifier.size(18.dp)) })
                    else S2Row(L("Send to support"), icon = Icons.AutoMirrored.Filled.Send, onClick = {
                        sending = true; result = null
                        AppScope.launch {
                            try {
                                val (n, err) = SupportCenter.create(L("App diagnostics") + " · " + Diagnostics.modelLabel, "apps",
                                    L("App diagnostics sent from the app."), d)
                                result = if (n != null) L("Sent as ticket #%d.", n) else err
                            } finally { sending = false }
                        }
                    })
                } else S2Row(L("Sign in to send this to support."), tint = Theme.secondary)
            }
            result?.let { S2Row(it, tint = Theme.secondary) }
        }
        if (view) DiagnosticsViewDialog(diag) { view = false }
    }
}
