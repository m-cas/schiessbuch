package io.github.mcas.schiessbuch

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material.icons.outlined.Print
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun MainScreen(store: Store, prefs: Prefs, tx: Tx, nav: Nav) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var chartOpen by remember { mutableStateOf(prefs.chartOpen) }
    var filter by rememberSaveable { mutableStateOf(prefs.filterDiscipline) }
    var cats by remember { mutableStateOf(prefs.filterCategories) }
    var openYears by remember { mutableStateOf<Set<String>?>(null) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selection by remember { mutableStateOf(setOf<String>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(setOf<String>()) }
    // phone turned sideways (or a large screen): more on each row
    val wide = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 600

    val rows = store.sessions.filter { s -> (filter == "all" || s.discipline.key == filter) && s.categoryValue.key in cats }
    val years = rows.groupBy { it.year }.toSortedMap(compareByDescending { it }).toList()
    val open = openYears ?: years.take(1).map { it.first }.toSet()
    val tooBig = remember(store.sessions.size) { store.storageInfo().backupBytes > MAIL_LIMIT }
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()

    fun endSelection() { selecting = false; selection = emptySet() }
    fun toggle(id: String) { selection = if (id in selection) selection - id else selection + id }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
          // settings icon and title, then three equal buttons below, like the web version
          Column(Modifier.background(MaterialTheme.colorScheme.surface)) {
            TopAppBar(
                title = { Text(if (selecting) tx.selected(selection.size) else tx.appName, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    // tap: settings; hold for 10 seconds: switch between the app logo and the SG Muttenz logo (launcher icon too)
                    Box(
                        Modifier.padding(start = 6.dp).size(48.dp)
                            .semantics { role = Role.Button; contentDescription = tx.settings; onClick { nav.push(Screen.Settings); true } }
                            .pointerInput(Unit) {
                                var held = false
                                detectTapGestures(
                                    onPress = {
                                        held = false
                                        val timer = scope.launch {
                                            delay(10_000)
                                            held = true
                                            prefs.clubLogo = !prefs.clubLogo
                                            context.applyLauncherIcon(prefs.clubLogo)
                                            android.widget.Toast.makeText(context, if (prefs.clubLogo) tx.logoClubOn else tx.logoClubOff, android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                        tryAwaitRelease()
                                        timer.cancel()
                                    },
                                    onTap = { if (!held) nav.push(Screen.Settings) },
                                )
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            painterResource(if (prefs.clubLogo) R.drawable.club_logo else R.drawable.app_logo), null,
                            Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)),
                        )
                        if (tooBig) Box(Modifier.align(Alignment.TopEnd).offset((-4).dp, 4.dp).size(10.dp).background(Color(0xFFD32F2F), CircleShape))
                    }
                },
            )
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TopButton(tx.chTitle, tx.chTitleShort, prominent = false, modifier = Modifier.weight(1f)) { nav.push(Screen.Champ) }
                TopButton(if (selecting) tx.done else tx.select, if (selecting) tx.done else tx.select, prominent = false,
                    enabled = store.sessions.isNotEmpty(), modifier = Modifier.weight(1f)) { if (selecting) endSelection() else selecting = true }
                TopButton(tx.newSession, tx.newSessionShort, prominent = true, enabled = !selecting, modifier = Modifier.weight(1f)) {
                    nav.push(Screen.Editor(newSession(store), true))
                }
            }
          }
        },
        bottomBar = {
            if (selecting) BottomAppBar {
                IconButton(onClick = { confirmDelete = true }, enabled = selection.isNotEmpty()) {
                    Icon(Icons.Outlined.Delete, tx.delete, tint = if (selection.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
                }
                Spacer(Modifier.weight(1f))
                Text(tx.selected(selection.size), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { SessionPrinter.print(context, store, store.sessions.filter { it.id in selection }, tx) }, enabled = selection.isNotEmpty()) {
                    Icon(Icons.Outlined.Print, tx.print)
                }
                IconButton(onClick = { context.mailSessions(store, store.sessions.filter { it.id in selection }, tx) }, enabled = selection.isNotEmpty()) {
                    Icon(Icons.Outlined.Email, tx.email)
                }
            }
        },
    ) { pad ->
        if (store.sessions.isEmpty()) {
            EmptyState(tx, Modifier.padding(pad)) { nav.push(Screen.Editor(newSession(store), true)) }
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 4.dp, bottom = pad.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                // Two clearly separate filters: one discipline (joined bar), any number of categories (chips).
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(tx.discipline.uppercase(tx.locale), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val line = MaterialTheme.colorScheme.outline
                        Row(
                            Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(9.dp))
                                .border(1.dp, line, RoundedCornerShape(9.dp)),
                        ) {
                            val keys = listOf("all" to tx.all) + Discipline.entries.map { it.key to tx.disciplineShort(it) }
                            keys.forEachIndexed { i, (k, l) ->
                                val on = filter == k
                                if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(line))
                                Box(
                                    Modifier.weight(1f).fillMaxHeight()
                                        .background(if (on) MaterialTheme.colorScheme.onSurface else Color.Transparent)
                                        .selectable(selected = on, onClick = { filter = k; prefs.filterDiscipline = k }),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        l, maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelMedium,
                                        fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                                        color = if (on) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                        HorizontalDivider(Modifier.padding(vertical = 6.dp))
                        Text(tx.category.uppercase(tx.locale), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Category.entries.forEach { c ->
                                val on = c.key in cats
                                FilterChip(
                                    selected = on,
                                    onClick = {
                                        val next = if (on) cats - c.key else cats + c.key
                                        if (next.isNotEmpty()) { cats = next; prefs.filterCategories = next }
                                    },
                                    label = { Text(tx.categoryName(c)) },
                                    leadingIcon = if (on) ({ Icon(Icons.Filled.Check, null, Modifier.size(FilterChipDefaults.IconSize)) }) else null,
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                                        selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        StatsRow(rows, tx)
                        if (rows.isNotEmpty()) {
                            // the chart can be folded away; the choice is remembered
                            Row(
                                Modifier.fillMaxWidth().clickable { chartOpen = !chartOpen; prefs.chartOpen = chartOpen }.semantics { contentDescription = tx.chartToggle },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(tx.trendYears.uppercase(tx.locale), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                                Icon(Icons.Filled.ExpandMore, null, Modifier.size(20.dp).rotate(if (chartOpen) 180f else 0f), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (chartOpen) TrendChart(rows, tx, showTitle = false)
                        }
                    }
                }
                Text(tx.normCaption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 10.dp))
            }
            if (rows.isEmpty()) item { Text(tx.noMatch, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp)) }
            years.forEach { (year, list) ->
                val isOpen = year in open
                item(key = "y$year") {
                    YearHeader(year, list.size, isOpen, if (selecting) list.count { it.id in selection } else 0, tx) {
                        openYears = if (isOpen) open - year else open + year
                    }
                }
                if (isOpen) item(key = "l$year") {
                    Card(Modifier.fillMaxWidth()) {
                        list.forEachIndexed { i, s ->
                            if (i > 0) HorizontalDivider(Modifier.padding(start = 16.dp))
                            SessionRow(
                                s, tx, selecting, s.id in selection, wide, s.id in expanded,
                                Modifier.combinedClickable(
                                    onClick = { if (selecting) toggle(s.id) else nav.push(Screen.Detail(s.id)) },
                                    onLongClick = { if (!selecting) { selecting = true; selection = setOf(s.id) } },
                                ),
                                onChevron = { expanded = if (s.id in expanded) expanded - s.id else expanded + s.id },
                            )
                            if (!selecting && s.id in expanded) SessionCard(store, s, tx) { nav.push(Screen.Detail(s.id)) }
                        }
                    }
                }
            }
        }
    }
    if (confirmDelete) {
        val photos = store.sessions.filter { it.id in selection }.sumOf { it.photos.size }
        ConfirmDialog(tx.confirmDelete(selection.size, photos), tx.delete, tx.keep, onConfirm = { store.delete(selection); endSelection() }, onDismiss = { confirmDelete = false })
    }
    androidx.activity.compose.BackHandler(enabled = selecting) { endSelection() }
}

@Composable
private fun EmptyState(tx: Tx, modifier: Modifier, onNew: () -> Unit) {
    Column(modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Image(painterResource(R.drawable.app_logo), null, Modifier.size(72.dp).clip(RoundedCornerShape(16.dp)))
        Spacer(Modifier.height(16.dp))
        Text(tx.emptyTitle, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(tx.emptyText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        Button(onClick = onNew) { Icon(Icons.Filled.Add, null); Spacer(Modifier.size(8.dp)); Text(tx.newSession) }
    }
}

@Composable
private fun StatsRow(rows: List<Session>, tx: Tx) {
    val values = rows.flatMap { s -> s.series.mapNotNull { it.normalized } }
    Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
        StatTile(tx.sessions, rows.size.toString(), tx)
        VerticalDivider(Modifier.padding(horizontal = 8.dp))
        StatTile(tx.avgSeries, if (values.isEmpty()) "–" else tx.score(values.average()), tx)
        VerticalDivider(Modifier.padding(horizontal = 8.dp))
        StatTile(tx.bestSeries, values.maxOrNull()?.let(tx::score) ?: "–", tx)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.StatTile(label: String, value: String, tx: Tx) {
    Column(Modifier.weight(1f)) {
        Text(label.uppercase(tx.locale), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun YearHeader(year: String, count: Int, open: Boolean, picked: Int, tx: Tx, onClick: () -> Unit) {
    val angle by animateFloatAsState(if (open) 180f else 0f, label = "chevron")
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(year, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
            Text(
                if (picked > 0) tx.sessionsN(count) + " · " + tx.selected(picked) else tx.sessionsN(count),
                style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        Icon(Icons.Filled.ExpandMore, null, Modifier.rotate(angle), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SessionRow(
    s: Session, tx: Tx, selecting: Boolean, picked: Boolean, wide: Boolean, open: Boolean,
    modifier: Modifier, onChevron: () -> Unit,
) {
    val title = if (s.descText.isEmpty()) tx.categoryName(s.categoryValue) else "${tx.categoryName(s.categoryValue)} · ${s.descText}"
    val angle by animateFloatAsState(if (open) 180f else 0f, label = "row")
    ListItem(
        modifier = modifier,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = if (selecting) ({ Checkbox(checked = picked, onCheckedChange = null) }) else null,
        headlineContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(tx.shortDate(s.day), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = !wide))
                if (wide) {
                    // phone turned sideways: discipline and series results on the same line
                    Text(tx.disciplineShort(s.discipline), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    Text(s.series.filter { it.hasResult }.joinToString(" · ") { "${tx.score(it.stats.total)}/${it.stats.max}" }, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        },
        trailingContent = if (selecting) null else ({
            IconButton(onClick = onChevron) { Icon(Icons.Filled.ExpandMore, tx.details, Modifier.rotate(angle), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }),
    )
}

/** The compact card under a row when its chevron is opened: photo, details and results; a tap opens the session. */
@Composable
private fun SessionCard(store: Store, s: Session, tx: Tx, onOpen: () -> Unit) {
    val done = s.series.filter { it.hasResult }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        if (s.photos.isNotEmpty()) PhotoThumb(store, s.photos.first(), Modifier.size(64.dp))
        else Box(Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.TrackChanges, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(listOfNotNull(tx.longDate(s.day), tx.disciplineShort(s.discipline), s.location?.takeIf { it.isNotBlank() }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(listOfNotNull(tx.seriesN(s.series.size), tx.shotsN(s.countedShots), if (s.photos.isEmpty()) null else tx.photosN(s.photos.size)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(horizontalAlignment = Alignment.End) {
            if (done.isEmpty()) Text("–", color = MaterialTheme.colorScheme.onSurfaceVariant)
            done.take(4).forEach { r ->
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = if (done.size == 1) MaterialTheme.typography.titleLarge.fontSize else MaterialTheme.typography.bodyLarge.fontSize)) { append(tx.score(r.stats.total)) }
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = MaterialTheme.typography.bodySmall.fontSize)) { append(" / ${r.stats.max}") }
                    },
                )
            }
        }
    }
}

/**
 * A button like the web version: 42 dp high, 15 sp semibold, rounded. Shows the full label when it fits at
 * that size, otherwise the short one ("Meistersch." / "Champ", "Neue Üb." / "New Sess.").
 */
@Composable
private fun TopButton(full: String, short: String, prominent: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    val measurer = rememberTextMeasurer()
    val shape = RoundedCornerShape(10.dp)
    val colors = if (prominent) ButtonDefaults.buttonColors() else ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface)
    val content: @Composable RowScope.() -> Unit = {
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val fits = measurer.measure(full, style).size.width <= constraints.maxWidth
            Text(if (fits) full else short, style = style, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
        }
    }
    val pad = PaddingValues(horizontal = 6.dp)
    if (prominent) Button(onClick, modifier.heightIn(min = 42.dp), enabled = enabled, shape = shape, colors = colors, contentPadding = pad, content = content)
    else OutlinedButton(onClick, modifier.heightIn(min = 42.dp), enabled = enabled, shape = shape, colors = colors, contentPadding = pad,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), content = content)
}
