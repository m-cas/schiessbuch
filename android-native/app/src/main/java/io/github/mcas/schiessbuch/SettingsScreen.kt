package io.github.mcas.schiessbuch

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

private val Danger = Color(0xFFD32F2F)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(store: Store, prefs: Prefs, tx: Tx, nav: Nav) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var showData by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(store.storageInfo()) }
    val empty = store.sessions.isEmpty()
    val tooBig = info.backupBytes > MAIL_LIMIT && !empty
    val backupName = "${tx.backupFile}-${LocalDate.now()}"

    /** Runs work that may take a moment (large backups) off the main thread and reports failures. */
    fun run(work: suspend () -> Unit) {
        busy = true
        scope.launch {
            try { work() }
            catch (e: Store.NotABackup) { message = tx.badBackup }
            catch (e: Exception) { message = tx.failed + "\n" + (e.message ?: "") }
            busy = false
            info = store.storageInfo()
        }
    }

    val saveDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) run {
            withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)!!.use(store::writeBackup) }
            message = tx.saved
        }
    }
    val openDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) run {
            val n = try { withContext(Dispatchers.IO) { store.restore(uri) } }
            catch (e: Store.NotABackup) { throw e }
            catch (e: Exception) { throw Exception(tx.restoreFailed + " " + (e.message ?: "")) }
            message = tx.restored(n)
        }
    }

    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).let { "${it.versionName} (${androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(it)})" } }.getOrDefault("–")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tx.settings) },
                navigationIcon = { IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tx.exitSettings) } },
            )
        },
    ) { pad ->
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 4.dp, bottom = pad.calculateBottomPadding() + 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item { SectionLabel(tx.language) }
                item {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        listOf("de" to "Deutsch", "en" to "English").forEachIndexed { i, (k, l) ->
                            SegmentedButton(selected = prefs.lang == k, onClick = { prefs.lang = k }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(l) }
                        }
                    }
                }
                item { SectionLabel(tx.logo) }
                item {
                    Card(Modifier.fillMaxWidth()) {
                        ListItem(
                            modifier = Modifier.clickable { prefs.clubLogo = !prefs.clubLogo },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            leadingContent = { Image(painterResource(R.drawable.club_logo), null, Modifier.size(40.dp).clip(RoundedCornerShape(8.dp))) },
                            headlineContent = { Text(tx.clubLogo) },
                            supportingContent = { Text(tx.clubLogoNote) },
                            trailingContent = { Switch(checked = prefs.clubLogo, onCheckedChange = { prefs.clubLogo = it }) },
                        )
                    }
                }
                item { SectionLabel(tx.export) }
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Action(Icons.Outlined.TableChart, tx.exportCsv, tx.exportNote, enabled = !empty) {
                            run { val f = withContext(Dispatchers.IO) { store.csvFile(tx) }; context.shareFile(f, "text/csv", tx.exportCsv) }
                        }
                    }
                }
                item { SectionLabel(tx.backupData) }
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Action(Icons.Outlined.Save, tx.saveBackup, null, enabled = !empty) { saveDoc.launch("$backupName.json") }
                        HorizontalDivider(Modifier.padding(start = 56.dp))
                        Action(Icons.Outlined.Email, tx.emailBackup, null, enabled = !empty) {
                            run {
                                val f = withContext(Dispatchers.IO) { store.backupFile(backupName) }
                                context.sendMail(tx.backupMailSubject(tx.longDate(LocalDate.now())), tx.backupMailText, listOf(f), tx.emailBackup)
                            }
                        }
                        HorizontalDivider(Modifier.padding(start = 56.dp))
                        Action(Icons.Outlined.Restore, tx.restoreBackup, null) { openDoc.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*")) }
                        if (tooBig) Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Filled.Warning, null, tint = Danger, modifier = Modifier.size(18.dp))
                            Text(tx.backupTooBig(formatBytes(info.backupBytes)), style = MaterialTheme.typography.bodySmall, color = Danger)
                        }
                        HorizontalDivider()
                        val angle = if (showData) 180f else 0f
                        ListItem(
                            modifier = Modifier.clickable { showData = !showData },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            leadingContent = { Icon(Icons.Outlined.Lock, null) },
                            headlineContent = {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(tx.dataOnPhone)
                                    if (tooBig) Icon(Icons.Filled.Warning, tx.backupTooBigShort, tint = Danger, modifier = Modifier.size(18.dp))
                                }
                            },
                            trailingContent = { Icon(Icons.Filled.ExpandMore, null, Modifier.rotate(angle)) },
                        )
                        AnimatedVisibility(showData) {
                            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(tx.dataOnPhoneText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(tx.storageUsed(formatBytes(info.photoBytes + info.dataBytes), formatBytes(info.free)), style = MaterialTheme.typography.bodySmall)
                                if (!empty) Text(tx.backupSize(info.photoCount, formatBytes(info.backupBytes)), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
                        Text(tx.version, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(version, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                item {
                    Button(onClick = { nav.pop() }, Modifier.fillMaxWidth().padding(top = 8.dp)) { Text(tx.exitSettings) }
                }
            }
            if (busy) CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
    }
    message?.let { MessageDialog(it, tx.ok) { message = null } }
}

@Composable
private fun Action(icon: ImageVector, title: String, note: String?, enabled: Boolean = true, onClick: () -> Unit) {
    val alpha = if (enabled) 1f else 0.38f
    ListItem(
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary.copy(alpha = alpha)) },
        headlineContent = { Text(title, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)) },
        supportingContent = if (note != null) ({ Text(note) }) else null,
    )
}
