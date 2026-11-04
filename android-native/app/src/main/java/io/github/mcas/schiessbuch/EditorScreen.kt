package io.github.mcas.schiessbuch

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

private val Orange = Color(0xFFE08A1E)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditorScreen(store: Store, tx: Tx, nav: Nav, original: Session, isNew: Boolean) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var draft by remember(original.id) { mutableStateOf(original) }
    var active by remember { mutableIntStateOf(0) }
    var added by remember { mutableStateOf(listOf<String>()) }
    var removed by remember { mutableStateOf(listOf<String>()) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var alert by remember { mutableStateOf<String?>(null) }
    var pickDate by remember { mutableStateOf(false) }
    val dirty = draft != original || added.isNotEmpty() || removed.isNotEmpty()
    val d = draft.discipline

    fun setSeries(i: Int, s: Series) { draft = draft.copy(series = draft.series.toMutableList().also { it[i] = s }) }
    fun close() = nav.pop()
    fun cancel() { added.forEach(store::deletePhoto); close() }
    fun save() {
        draft.series.forEachIndexed { i, s ->
            val t = s.tief
            if (t != null && t !in 0..100) { active = i; alert = tx.tiefError(i + 1); return }
        }
        val series = draft.series.map { s ->
            val st = s.stats
            if (s.format == SeriesFormat.CUSTOM) s.copy(type = SeriesFormat.CUSTOM.key, planned = st.counted, practice = st.practiceCount)
            else s.copy(planned = st.slots, practice = 0, target = Target.A10.key)
        }
        store.save(draft.copy(series = series, desc = draft.desc?.trim()))
        removed.forEach(store::deletePhoto)
        close()
    }
    fun addShot(v: Shot) {
        val s = draft.series.getOrNull(active) ?: return
        val slots = s.stats.slots
        if (s.shots.size >= slots) { haptic.performHapticFeedback(HapticFeedbackType.LongPress); return }
        val next = s.copy(shots = s.shots + v, total = null)
        setSeries(active, next)
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        if (next.shots.size >= slots && active < draft.series.size - 1) active += 1
    }
    fun removeLast() {
        if (draft.series.getOrNull(active) == null) return
        if (draft.series[active].shots.isEmpty() && active > 0) active -= 1
        val s = draft.series[active]
        if (s.shots.isNotEmpty()) { setSeries(active, s.copy(shots = s.shots.dropLast(1))); haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    }
    fun changeDiscipline(nd: Discipline) {
        if (draft.series.none { it.hasResult }) {
            draft = draft.copy(preset = nd.key, series = draft.series.map { Series.of(nd.defaultFormat) }); active = 0; return
        }
        draft = draft.copy(preset = nd.key, series = draft.series.map { orig ->
            var s = orig
            if (s.format !in nd.formats) {
                val st = s.stats
                s = s.copy(type = SeriesFormat.CUSTOM.key, practice = st.practiceCount, planned = maxOf(st.counted, s.shots.size - st.practiceCount))
            }
            if (!nd.hasTargetChoice) s = s.copy(target = Target.A10.key)
            s.clamped(nd)
        })
    }
    fun addPhotos(uris: List<Uri>) {
        val ids = uris.mapNotNull { store.addPhoto(it) }
        if (ids.isNotEmpty()) { draft = draft.copy(photos = draft.photos + ids); added = added + ids }
    }
    fun removePhoto(id: String) {
        draft = draft.copy(photos = draft.photos - id)
        if (id in added) { added = added - id; store.deletePhoto(id) } else removed = removed + id
    }

    val cameraFile = remember { File(store.shareDir(), "camera.jpg") }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) addPhotos(listOf(Uri.fromFile(cameraFile)))
    }
    val library = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { addPhotos(it) }

    BackHandler { if (dirty) confirmDiscard = true else cancel() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) tx.newSessionTitle else tx.editSessionTitle) },
                navigationIcon = { IconButton(onClick = { if (dirty) confirmDiscard = true else cancel() }) { Icon(Icons.Filled.Close, tx.cancel) } },
                actions = { Button(onClick = ::save, modifier = Modifier.padding(end = 8.dp)) { Text(tx.save) } },
            )
        },
        bottomBar = {
            if (!WindowInsets.isImeVisible) draft.series.getOrNull(active)?.let { s ->
                ShotKeypad(s, active, d, tx, ::addShot, ::removeLast)
            }
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 4.dp, bottom = pad.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SectionLabel(tx.details) }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box {
                            OutlinedTextField(
                                value = tx.longDate(draft.day), onValueChange = {}, readOnly = true, label = { Text(tx.date) },
                                trailingIcon = { Icon(Icons.Outlined.CalendarMonth, null) }, modifier = Modifier.fillMaxWidth(),
                            )
                            Box(Modifier.matchParentSize().clickable { pickDate = true })
                        }
                        Dropdown(tx.discipline, tx.disciplineName(d), Discipline.entries, { tx.disciplineName(it) }, ::changeDiscipline)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(tx.category, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                Category.entries.forEachIndexed { i, c ->
                                    SegmentedButton(
                                        selected = draft.categoryValue == c, onClick = { draft = draft.copy(category = c.key) },
                                        shape = SegmentedButtonDefaults.itemShape(i, Category.entries.size),
                                    ) { Text(tx.categoryName(c), maxLines = 1) }
                                }
                            }
                        }
                        TextInput(tx.description, tx.descriptionPh, draft.desc) { draft = draft.copy(desc = it) }
                        TextInput(tx.range, tx.rangePh, draft.location) { draft = draft.copy(location = it) }
                        TextInput(tx.weapon, tx.weaponPh, draft.weapon) { draft = draft.copy(weapon = it) }
                        TextInput(tx.conditions, tx.conditionsPh, draft.conditions) { draft = draft.copy(conditions = it) }
                    }
                }
            }
            itemsIndexed(draft.series, key = { i, _ -> "s$i" }) { i, s ->
                SeriesEditor(
                    s, i, d, i == active, draft.series.size > 1, tx,
                    onChange = { setSeries(i, it) },
                    onActivate = { active = i },
                    onRemove = {
                        draft = draft.copy(series = draft.series.toMutableList().also { it.removeAt(i) })
                        active = minOf(active, draft.series.size - 1)
                    },
                )
            }
            item {
                OutlinedButton(onClick = { draft = draft.copy(series = draft.series + Series.of(d.defaultFormat)); active = draft.series.size - 1 }, Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.AddCircleOutline, null); Spacer(Modifier.width(8.dp)); Text(tx.addSeries)
                }
            }
            item { SectionLabel(tx.targets) }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (draft.photos.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            draft.photos.forEach { pid ->
                                Box {
                                    PhotoThumb(store, pid, Modifier.size(width = 96.dp, height = 128.dp))
                                    IconButton(
                                        onClick = { removePhoto(pid) }, modifier = Modifier.align(Alignment.TopEnd).size(32.dp),
                                        colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.55f), contentColor = Color.White),
                                    ) { Icon(Icons.Filled.Close, tx.delete, Modifier.size(18.dp)) }
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = {
                                runCatching { camera.launch(context.uriFor(cameraFile)) }
                            }, Modifier.weight(1f)) { Icon(Icons.Outlined.PhotoCamera, null); Spacer(Modifier.width(6.dp)); Text(tx.takePhoto, maxLines = 1) }
                            FilledTonalButton(onClick = {
                                library.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            }, Modifier.weight(1f)) { Icon(Icons.Outlined.PhotoLibrary, null); Spacer(Modifier.width(6.dp)); Text(tx.fromLibrary, maxLines = 1) }
                        }
                    }
                }
            }
            item { SectionLabel(tx.notes) }
            item {
                Card(Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = draft.notes ?: "", onValueChange = { draft = draft.copy(notes = it) },
                        placeholder = { Text(tx.notesPh) }, minLines = 3, maxLines = 8,
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    )
                }
            }
        }
    }

    if (pickDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = draft.day.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { draft = draft.copy(date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()) }
                    pickDate = false
                }) { Text(tx.ok) }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text(tx.cancel) } },
        ) { DatePicker(state) }
    }
    if (confirmDiscard) ConfirmDialog(tx.discardChanges, tx.discard, tx.keep, onConfirm = ::cancel, onDismiss = { confirmDiscard = false })
    alert?.let { MessageDialog(it, tx.ok) { alert = null } }
}

@Composable
private fun TextInput(label: String, placeholder: String, value: String?, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value ?: "", onValueChange = onChange, singleLine = true,
        label = { Text(label) }, placeholder = { Text(placeholder) }, modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Dropdown(label: String, value: String, options: List<T>, name: (T) -> String, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = value, onValueChange = {}, readOnly = true, label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o -> DropdownMenuItem(text = { Text(name(o)) }, onClick = { open = false; onPick(o) }) }
        }
    }
}

// ---- one series

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SeriesEditor(
    series: Series, index: Int, d: Discipline, isActive: Boolean, canRemove: Boolean, tx: Tx,
    onChange: (Series) -> Unit, onActivate: () -> Unit, onRemove: () -> Unit,
) {
    val st = series.stats
    Card(
        Modifier.fillMaxWidth(),
        border = if (isActive) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isActive) { Icon(Icons.Filled.Edit, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(6.dp)) }
                Text(tx.seriesTitle(index + 1), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text("${tx.score(st.total)} / ${st.max}", fontWeight = FontWeight.SemiBold)
            }
            if (d.formats.size > 1) Dropdown(tx.format, tx.formatName(series.format, d), d.formats, { tx.formatName(it, d) }) { f ->
                onChange(changeFormat(series, f, d)); onActivate()
            }
            if (series.format == SeriesFormat.CUSTOM && d.hasTargetChoice) Dropdown(tx.target, "${series.targetValue.key} · 0–${series.targetValue.max}", Target.entries, { "${it.key} · 0–${it.max}" }) { t ->
                onChange(series.copy(target = t.key).clamped(d))
            }
            ShotsGrid(series, isActive, tx, onActivate) { j -> onChange(series.copy(shots = series.shots.toMutableList().also { it.removeAt(j) })); onActivate() }
            if (st.practiceCount > 0 && series.shots.isNotEmpty()) Text(tx.practiceSum(st.practice), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (series.format == SeriesFormat.CUSTOM) {
                Stepper(tx.practiceShots, st.practiceCount, 0..20) { n ->
                    onChange(series.copy(practice = n)); onActivate()
                }
                Stepper(tx.countedShots, st.counted, 1..200) { n ->
                    val p = st.practiceCount
                    onChange(series.copy(planned = n, shots = series.shots.take(p + n))); onActivate()
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberInput(tx.orTotal, series.total?.let(tx::score) ?: "", decimal = true, Modifier.weight(1f)) {
                    onChange(series.copy(total = it.replace(',', '.').toDoubleOrNull()))
                }
                NumberInput(tx.tief, series.tief?.toString() ?: "", decimal = false, Modifier.weight(1f)) {
                    onChange(series.copy(tief = it.toIntOrNull()))
                }
            }
            if (canRemove) TextButton(onClick = onRemove, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Icon(Icons.Outlined.RemoveCircleOutline, null); Spacer(Modifier.width(6.dp)); Text(tx.removeSeries)
            }
        }
    }
}

private fun changeFormat(series: Series, f: SeriesFormat, d: Discipline): Series {
    val prev = series.stats
    val parts = f.parts
    val s = if (parts != null) {
        val n = parts.sumOf { it.count }
        series.copy(type = f.key, planned = n, practice = 0, target = Target.A10.key, shots = series.shots.take(n))
    } else series.copy(type = f.key, practice = prev.practiceCount, planned = maxOf(prev.counted, series.shots.size - prev.practiceCount))
    return s.clamped(d)
}

/** Keeps its own text so partial input such as "9." survives; reports every change. */
@Composable
private fun NumberInput(label: String, initial: String, decimal: Boolean, modifier: Modifier, onChange: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    // follow changes made elsewhere (e.g. typing shots clears the total)
    androidx.compose.runtime.LaunchedEffect(initial) {
        if (text.replace(',', '.').toDoubleOrNull() != initial.replace(',', '.').toDoubleOrNull()) text = initial
    }
    OutlinedTextField(
        value = text,
        onValueChange = { v ->
            val clean = if (decimal) v.filter { it.isDigit() || it == '.' || it == ',' } else v.filter { it.isDigit() }.take(3)
            text = clean; onChange(clean)
        },
        label = { Text(label, maxLines = 1) }, placeholder = { Text("—") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        modifier = modifier,
    )
}

@Composable
private fun Stepper(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        OutlinedIconButton(onClick = { onChange(value - 1) }, enabled = value > range.first) { Icon(Icons.Filled.Remove, null) }
        Text("$value", Modifier.width(44.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
        OutlinedIconButton(onClick = { onChange(value + 1) }, enabled = value < range.last) { Icon(Icons.Filled.Add, null) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShotsGrid(series: Series, isActive: Boolean, tx: Tx, onActivate: () -> Unit, onRemoveShot: (Int) -> Unit) {
    val segs = series.segments
    val st = series.stats
    val fg = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val line = MaterialTheme.colorScheme.outlineVariant
    val accent = MaterialTheme.colorScheme.primary
    Column(Modifier.fillMaxWidth().clickable(onClick = onActivate), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        segs.forEach { g ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (segs.size > 1) Text(
                    (tx.segmentName(g.kind, g.count) + (g.target?.let { " · " + it.key } ?: "")).uppercase(tx.locale),
                    style = MaterialTheme.typography.labelSmall, color = muted,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (j in g.start until g.start + g.count) {
                        val practice = g.kind == SegmentKind.PRACTICE
                        if (j < series.shots.size) {
                            val v = series.shots[j]
                            val isX = !practice && v == Shot.X
                            val top = !practice && v.points == series.maxValueAt(j)
                            val c = if (practice) muted else if (top) Orange else fg
                            Box(
                                Modifier.size(38.dp)
                                    .background(if (isX) fg else Color.Transparent, CircleShape)
                                    .drawBehind {
                                        drawCircle(c, size.minDimension / 2 - 1.5f, style = Stroke(width = 1.5.dp.toPx(), pathEffect = if (practice) PathEffect.dashPathEffect(floatArrayOf(6f, 6f)) else null))
                                    }
                                    .clickable { onRemoveShot(j) },
                                contentAlignment = Alignment.Center,
                            ) { Text(v.label, fontWeight = FontWeight.SemiBold, color = if (isX) MaterialTheme.colorScheme.surface else c) }
                        } else {
                            val next = isActive && j == series.shots.size
                            val c = if (next) accent else line
                            Box(
                                Modifier.size(38.dp).drawBehind {
                                    drawCircle(c, size.minDimension / 2 - 1.5f, style = Stroke(width = 1.2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))))
                                },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(if (practice) "P" else "${j + 1 - st.practiceCount}", style = MaterialTheme.typography.labelMedium, color = if (next) accent else muted)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---- keypad at the bottom of the editor

@Composable
private fun ShotKeypad(series: Series, index: Int, d: Discipline, tx: Tx, onValue: (Shot) -> Unit, onBack: () -> Unit) {
    val st = series.stats
    val next = series.shots.size
    val full = next >= st.slots
    val m = if (full) series.targetValue.max else series.maxValueAt(next)
    val seg = series.segmentAt(next)
    val allowX = d.allowsX && m == 10 && seg?.target == null && seg?.kind != SegmentKind.PRACTICE
    Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val what = if (full) tx.complete else if (series.isPractice(next)) "${tx.practiceShot} ${next + 1}" else "${tx.shot} ${next + 1 - st.practiceCount}"
            Text(tx.seriesTitle(index + 1) + " · " + what + (seg?.target?.let { " · " + it.key } ?: ""), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (m > 20) {
                var typed by remember { mutableStateOf("") }
                val v = typed.toIntOrNull()
                val ok = !full && v != null && v in 0..m
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = typed, onValueChange = { typed = it.filter(Char::isDigit).take(3) }, enabled = !full,
                        placeholder = { Text(tx.typeValue(m)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
                    )
                    Button(onClick = { onValue(Shot.Value(v!!)); typed = "" }, enabled = ok) { Text(tx.add) }
                    FilledIconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.Backspace, tx.removeLast) }
                }
            } else {
                val values = (if (allowX) listOf<Shot>(Shot.X) else emptyList()) + (m downTo 0).map { Shot.Value(it) }
                val keys: List<Shot?> = values + listOf(null)
                keys.chunked(6).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { key ->
                            val mod = Modifier.weight(1f).height(46.dp)
                            val k: Shot = key ?: Shot.Value(-1)
                            when {
                                key == null -> OutlinedButton(onClick = onBack, mod, contentPadding = PaddingValues(0.dp), shape = RoundedCornerShape(10.dp)) {
                                    Icon(Icons.AutoMirrored.Outlined.Backspace, tx.removeLast)
                                }
                                k == Shot.X -> Button(onClick = { onValue(k) }, mod, enabled = !full, contentPadding = PaddingValues(0.dp), shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onSurface, contentColor = MaterialTheme.colorScheme.surface)) {
                                    Text("X", style = MaterialTheme.typography.titleLarge)
                                }
                                else -> FilledTonalButton(onClick = { onValue(k) }, mod, enabled = !full, contentPadding = PaddingValues(0.dp), shape = RoundedCornerShape(10.dp)) {
                                    Text(k.label, style = MaterialTheme.typography.titleLarge)
                                }
                            }
                        }
                        repeat(6 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}
