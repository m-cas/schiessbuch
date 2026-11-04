package io.github.mcas.schiessbuch

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(store: Store, tx: Tx, nav: Nav, id: String) {
    val context = LocalContext.current
    val s = store.session(id)
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var viewer by remember { mutableStateOf<Int?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(s?.let { tx.shortDate(it.day) } ?: "") },
                navigationIcon = { IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tx.back) } },
                actions = {
                    if (s != null) {
                        TextButton(onClick = { nav.push(Screen.Editor(s, false)) }) { Text(tx.edit) }
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, tx.more) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(tx.email) }, leadingIcon = { Icon(Icons.Outlined.Email, null) },
                                onClick = { menu = false; context.mailSessions(store, listOf(s), tx) })
                            DropdownMenuItem(text = { Text(tx.delete, color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                onClick = { menu = false; confirmDelete = true })
                        }
                    }
                },
            )
        },
    ) { pad ->
        if (s == null) {
            Text(tx.noMatch, Modifier.padding(pad).padding(24.dp)); return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 4.dp, bottom = pad.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(tx.longDate(s.day), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${tx.categoryName(s.categoryValue)} · ${tx.disciplineName(s.discipline)}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        if (s.descText.isNotEmpty()) Text(s.descText)
                        listOf(tx.range to s.location, tx.weapon to s.weapon, tx.conditions to s.conditions).forEach { (k, v) ->
                            if (!v.isNullOrBlank()) Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                                Text(k, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(v)
                            }
                        }
                    }
                }
            }
            item { SectionLabel(tx.series) }
            item {
                Card(Modifier.fillMaxWidth()) {
                    s.series.forEachIndexed { i, r ->
                        if (i > 0) HorizontalDivider(Modifier.padding(start = 16.dp))
                        SeriesResult(i, r, s.discipline, tx)
                    }
                }
            }
            if (!s.notes.isNullOrBlank()) {
                item { SectionLabel(tx.notes) }
                item { Card(Modifier.fillMaxWidth()) { Text(s.notes, Modifier.padding(16.dp)) } }
            }
            item { SectionLabel(tx.targets) }
            item {
                Card(Modifier.fillMaxWidth()) {
                    if (s.photos.isEmpty()) Text(tx.noPhotos, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else FlowRow(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        s.photos.forEachIndexed { i, pid ->
                            PhotoThumb(store, pid, Modifier.size(width = 96.dp, height = 128.dp).clickable { viewer = i })
                        }
                    }
                }
            }
        }
    }
    if (s != null && confirmDelete) {
        ConfirmDialog(tx.confirmDelete(1, s.photos.size), tx.delete, tx.keep,
            onConfirm = { nav.pop(); store.delete(setOf(s.id)) }, onDismiss = { confirmDelete = false })
    }
    if (s != null) viewer?.let { start -> PhotoViewer(store, s.photos, start) { viewer = null } }
}

@Composable
fun SectionLabel(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
}

@Composable
private fun SeriesResult(index: Int, series: Series, d: Discipline, tx: Tx) {
    val st = series.stats
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(tx.seriesTitle(index + 1), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            if (series.hasResult) Text(buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = MaterialTheme.typography.titleLarge.fontSize)) { append(tx.score(st.total)) }
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) { append(" / ${st.max}") }
            }) else Text("–", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(tx.seriesLabel(series, d), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (series.shots.isNotEmpty()) Text(series.shotsText, style = MaterialTheme.typography.bodyLarge)
        series.groupTotals?.takeIf { series.shots.isNotEmpty() }?.let { gt ->
            Text(gt.joinToString(" · ") { "${tx.groupN(it.n)}: ${it.sum}/${it.max}" }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
        else if (st.manual) Text(tx.totalOnly, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val extra = listOfNotNull(
            series.tief?.let { "${tx.tief} $it" },
            if (st.x > 0) "${st.x} X" else null,
            if (st.practiceCount > 0 && series.shots.isNotEmpty()) tx.practiceSum(st.practice) else null,
        )
        if (extra.isNotEmpty()) Text(extra.joinToString("   "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
