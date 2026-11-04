package io.github.mcas.schiessbuch

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The championship page: load the club's CSV file, activate the items one by one, keep earlier years in a history. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChampScreen(store: Store, tx: Tx, nav: Nav) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cs = store.champ
    val st = cs.state
    var tab by rememberSaveable { mutableStateOf(0) }
    var url by rememberSaveable { mutableStateOf(st.url) }
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<ChampParser.Result?>(null) }
    var warnings by remember { mutableStateOf(listOf<String>()) }
    var showWarnings by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var resetItem by remember { mutableStateOf<ChampItem?>(null) }
    var dropYear by remember { mutableStateOf<Int?>(null) }
    var openYears by remember { mutableStateOf(setOf<Int>()) }

    fun apply(r: ChampParser.Result) {
        cs.apply(r.champ); warnings = r.warnings; pending = null; tab = 0
        message = tx.chLoaded(r.champ.items.size, r.champ.year) + if (r.warnings.isEmpty()) "" else " · " + tx.chWarnN(r.warnings.size)
    }
    fun loaded(text: String) {
        try {
            val r = ChampParser.parse(text, tx)
            if (cs.state.current == null) apply(r) else pending = r
        } catch (e: ChampParser.Failure) { message = e.message } catch (e: Exception) { message = tx.chBadFile }
    }
    fun fetch() {
        cs.setUrl(url.trim())
        if (!url.trim().startsWith("http", ignoreCase = true)) { message = tx.chNeedUrl; return }
        busy = true
        scope.launch {
            try { val text = withContext(Dispatchers.IO) { ChampParser.download(url) }; loaded(text) }
            catch (e: Exception) { message = tx.chFetchFail + (e.message?.takeIf { it.startsWith("HTTP") }?.let { " ($it)" } ?: "") }
            busy = false
        }
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val text = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openInputStream(uri)!!.use { it.readBytes() } }.getOrNull() }
            if (text == null) message = tx.chBadFile
            else loaded(String(text, Charsets.UTF_8).takeIf { !it.contains('�') } ?: String(text, Charsets.ISO_8859_1))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tx.chTitle) },
                navigationIcon = { IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tx.back) } },
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 4.dp, bottom = pad.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(tx.chCurrent, if (st.history.isEmpty()) tx.chHistory else "${tx.chHistory} (${st.history.size})").forEachIndexed { i, l ->
                        SegmentedButton(selected = tab == i, onClick = { tab = i }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(l) }
                    }
                }
            }
            if (tab == 0) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = url, onValueChange = { url = it }, label = { Text(tx.chUrl) }, singleLine = true,
                            placeholder = { Text("https://…/meisterschaft.csv") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { fetch() }),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { fetch() }, enabled = !busy, modifier = Modifier.weight(1f)) {
                                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Outlined.Download, null)
                                Spacer(Modifier.width(8.dp)); Text(if (st.current == null) tx.chLoad else tx.chReload)
                            }
                            OutlinedButton(onClick = { openFile.launch(arrayOf("text/*", "application/octet-stream", "*/*")) }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Outlined.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text(tx.chFromFile, maxLines = 1)
                            }
                        }
                        if (warnings.isNotEmpty()) TextButton(onClick = { showWarnings = !showWarnings }) {
                            Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp)); Text(tx.chWarnN(warnings.size), color = MaterialTheme.colorScheme.error)
                        }
                        if (showWarnings) warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
                val cur = st.current
                if (cur == null) item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(tx.chEmptyH, style = MaterialTheme.typography.titleMedium)
                            Text(tx.chEmptyP, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("id;jahr;titel;disziplin;serien;datum;schiessstand;notizen", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                } else {
                    val done = cur.items.count { cs.sessionFor(it) != null }
                    item {
                        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("${tx.chTitle} ${cur.year}".uppercase(tx.locale), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(cur.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            LinearProgressIndicator(progress = { if (cur.items.isEmpty()) 0f else done.toFloat() / cur.items.size }, modifier = Modifier.fillMaxWidth())
                            Text(tx.chProgress(done, cur.items.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    items(cur.items, key = { "c" + it.id }) { item ->
                        ChampRow(item, cs.sessionFor(item), live = true, tx = tx,
                            onActivate = { cs.activate(item.id)?.let { nav.push(Screen.Editor(it, false)) } },
                            onReset = { resetItem = item },
                            onOpen = { nav.push(Screen.Detail(it)) })
                    }
                }
            } else {
                if (st.history.isEmpty()) item { Text(tx.chNoHistory, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp)) }
                st.history.forEach { h ->
                    val open = h.year in openYears
                    val done = h.items.count { cs.sessionFor(it) != null }
                    item(key = "h${h.year}") {
                        Card(modifier = Modifier.fillMaxWidth(), onClick = { openYears = if (open) openYears - h.year else openYears + h.year }) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("${h.year} · ${h.name}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    Text(tx.chProgress(done, h.items.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Icon(Icons.Filled.ExpandMore, null, Modifier.rotate(if (open) 180f else 0f))
                            }
                        }
                    }
                    if (open) {
                        items(h.items, key = { "h${h.year}-" + it.id }) { item ->
                            ChampRow(item, cs.sessionFor(item), live = false, tx = tx, onActivate = {}, onReset = {}, onOpen = { nav.push(Screen.Detail(it)) })
                        }
                        item(key = "hd${h.year}") {
                            TextButton(onClick = { dropYear = h.year }) { Text(tx.chHistDel, color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        }
    }

    pending?.let { p ->
        val cur = st.current
        AlertDialog(
            onDismissRequest = { pending = null },
            text = { Text(if (cur != null && cur.year != p.champ.year) tx.chAskNew(p.champ.year, cur.year) else tx.chAskSame(p.champ.year)) },
            confirmButton = { TextButton(onClick = { apply(p) }) { Text(tx.chLoad) } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(tx.cancel) } },
        )
    }
    resetItem?.let { item ->
        AlertDialog(
            onDismissRequest = { resetItem = null },
            text = { Text(tx.chResetAsk) },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { cs.reset(item.id, false); resetItem = null }) { Text(tx.chKeep) }
                    TextButton(onClick = { cs.reset(item.id, true); resetItem = null }) { Text(tx.chDelete, color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { resetItem = null }) { Text(tx.cancel) }
                }
            },
        )
    }
    dropYear?.let { y ->
        ConfirmDialog("${tx.chHistDel}: $y?", tx.chHistDel, tx.cancel, onConfirm = { cs.dropHistory(y) }, onDismiss = { dropYear = null })
    }
    message?.let { MessageDialog(it, tx.ok) { message = null } }
}

@Composable
private fun ChampRow(item: ChampItem, session: Session?, live: Boolean, tx: Tx, onActivate: () -> Unit, onReset: () -> Unit, onOpen: (String) -> Unit) {
    val active = session != null
    val what = tx.disciplineName(item.discipline) + " · " + item.series.joinToString(" | ") { it.groupText(tx) }
    val meta = listOfNotNull(item.day?.let(tx::longDate), item.location).filter { it.isNotEmpty() }.joinToString(" · ")
    Card(
        Modifier.fillMaxWidth(),
        colors = if (active && live) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)) else CardDefaults.cardColors(),
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                if (active) Icons.Filled.CheckCircle else Icons.Outlined.Circle, null,
                tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                val fade = Modifier.alpha(if (active && live) 0.6f else 1f)
                Text(item.title, fontWeight = FontWeight.SemiBold, modifier = fade)
                Text(what, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = fade)
                if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = fade)
                if (!item.notes.isNullOrEmpty()) Text(item.notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = fade)
                if (session != null) {
                    val done = session.series.filter { it.hasResult }
                    val res = if (done.isEmpty()) tx.chNoResult else done.joinToString(" · ") { "${tx.score(it.stats.total)}/${it.stats.max}" }
                    TextButton(onClick = { onOpen(session.id) }, contentPadding = PaddingValues(0.dp)) {
                        Text("$res · ${tx.shortDate(session.day)} ›", fontWeight = FontWeight.SemiBold)
                    }
                } else if (!live) Text(tx.chNotShot, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (live) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    if (active) OutlinedButton(onClick = onReset) { Text(tx.chReset) }
                    else Button(onClick = onActivate) { Text(tx.chActivate) }
                }
            }
        }
    }
}
