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
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The championship page: the items of the loaded championship, activated with + and reset with ✕.
 *  The cog at the top left opens the championship settings, where the club's CSV file is loaded. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChampScreen(store: Store, tx: Tx, nav: Nav) {
    val cs = store.champ
    val st = cs.state
    var resetItem by remember { mutableStateOf<ChampItem?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tx.chTitle) },
                navigationIcon = {
                    Row {
                        IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tx.back) }
                        IconButton(onClick = { nav.push(Screen.ChampSettings) }) { Icon(Icons.Outlined.Settings, tx.chSettings) }
                    }
                },
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 4.dp, bottom = pad.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val cur = st.current
            if (cur == null) item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(tx.chEmptyH, style = MaterialTheme.typography.titleMedium)
                        Text(tx.chEmptyCog, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = { nav.push(Screen.ChampSettings) }) { Text(tx.chSettings) }
                    }
                }
            } else {
                val done = cur.items.count { cs.sessionFor(it) != null }
                item {
                    Column(Modifier.padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${tx.chTitle} ${cur.year}".uppercase(tx.locale), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FitText(cur.name, MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), min = 13)
                        LinearProgressIndicator(progress = { if (cur.items.isEmpty()) 0f else done.toFloat() / cur.items.size }, modifier = Modifier.fillMaxWidth())
                        Text(tx.chProgress(done, cur.items.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(cur.items, key = { "c" + it.id }) { item ->
                    ChampRow(item, cs.sessionFor(item), tx = tx,
                        onActivate = { cs.activate(item.id)?.let { nav.push(Screen.Editor(it, false)) } },
                        onReset = { resetItem = item },
                        onOpen = { nav.push(Screen.Detail(it)) })
                }
            }
        }
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
}

/** Championship settings: address of the club's CSV file, load or reload it, or pick a file. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChampSettingsScreen(store: Store, tx: Tx, nav: Nav) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cs = store.champ
    val st = cs.state
    var url by rememberSaveable { mutableStateOf(st.url) }
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<ChampParser.Result?>(null) }
    var warnings by remember { mutableStateOf(listOf<String>()) }
    var showWarnings by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun apply(r: ChampParser.Result) {
        cs.apply(r.champ); warnings = r.warnings; pending = null
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
            else loaded(String(text, Charsets.UTF_8).takeIf { !it.contains('\uFFFD') } ?: String(text, Charsets.ISO_8859_1))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tx.chSettings) },
                navigationIcon = { IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tx.back) } },
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
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
            st.current?.let { Text(tx.chLoadedInfo(it.year, it.items.size), style = MaterialTheme.typography.bodyMedium) }
            Text(tx.chOnlyOne, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(tx.chEmptyP, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("id;jahr;titel;disziplin;serien;datum;schiessstand;notizen", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
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
    message?.let { MessageDialog(it, tx.ok) { message = null } }
}

/** One item: description on the left, the round + or ✕ button at the bottom right. */
@Composable
private fun ChampRow(item: ChampItem, session: Session?, tx: Tx, onActivate: () -> Unit, onReset: () -> Unit, onOpen: (String) -> Unit) {
    val active = session != null
    val what = tx.disciplineName(item.discipline) + " · " + item.series.joinToString(" | ") { it.groupText(tx) }
    val meta = listOfNotNull(item.day?.let(tx::longDate), item.location).filter { it.isNotEmpty() }.joinToString(" · ")
    Card(
        Modifier.fillMaxWidth(),
        colors = if (active) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)) else CardDefaults.cardColors(),
    ) {
        Row(Modifier.padding(start = 14.dp, top = 12.dp, end = 10.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
            Icon(
                if (active) Icons.Filled.CheckCircle else Icons.Outlined.Circle, null,
                tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                modifier = Modifier.align(Alignment.Top),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val fade = Modifier.alpha(if (active) 0.6f else 1f)
                FitText(item.title, MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold), min = 11, modifier = fade)
                Text(what, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = fade)
                if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = fade)
                if (!item.notes.isNullOrEmpty()) Text(item.notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = fade)
                if (session != null) {
                    val done = session.series.filter { it.hasResult }
                    val res = if (done.isEmpty()) tx.chNoResult else done.joinToString(" · ") { "${tx.score(it.stats.total)}/${it.stats.max}" }
                    TextButton(onClick = { onOpen(session.id) }, contentPadding = PaddingValues(0.dp)) {
                        Text("$res · ${tx.shortDate(session.day)} ›", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            if (active) FilledTonalIconButton(onClick = onReset, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.Close, tx.chReset, tint = MaterialTheme.colorScheme.error)
            } else FilledIconButton(onClick = onActivate, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.Add, tx.chActivate)
            }
        }
    }
}

/** One-line text that shrinks (down to [min] sp) instead of wrapping. */
@Composable
private fun FitText(text: String, style: androidx.compose.ui.text.TextStyle, min: Int, modifier: Modifier = Modifier) {
    androidx.compose.foundation.text.BasicText(
        text, modifier,
        style = style.copy(color = if (style.color == androidx.compose.ui.graphics.Color.Unspecified) MaterialTheme.colorScheme.onSurface else style.color),
        maxLines = 1,
        softWrap = false,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = min.sp, maxFontSize = style.fontSize, stepSize = 0.5.sp),
    )
}
