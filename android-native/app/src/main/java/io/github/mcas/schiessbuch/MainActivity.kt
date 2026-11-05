package io.github.mcas.schiessbuch

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.core.content.FileProvider
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Test screenshots start the app with: --ez demo true --es lang de --es screen editor|detail|settings
        val demo = intent.getBooleanExtra("demo", false)
        val store = Store(applicationContext, demo)
        val prefs = Prefs(this)
        intent.getStringExtra("lang")?.let { prefs.lang = it }
        val startScreen = intent.getStringExtra("screen")
        if (demo) store.champ.seedDemo(Tx(prefs.lang))
        setContent { AppTheme { App(store, prefs, startScreen) } }
    }
}

/** Settings kept on the phone, observable by the UI. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private var _lang by mutableStateOf(sp.getString("lang", null) ?: if (java.util.Locale.getDefault().language == "de") "de" else "en")
    private var _clubLogo by mutableStateOf(sp.getBoolean("clubLogo", false))
    var lang: String get() = _lang; set(v) { _lang = v; sp.edit().putString("lang", v).apply() }
    var clubLogo: Boolean get() = _clubLogo; set(v) { _clubLogo = v; sp.edit().putBoolean("clubLogo", v).apply() }
    var chartOpen: Boolean get() = sp.getBoolean("chartOpen", true); set(v) { sp.edit().putBoolean("chartOpen", v).apply() }
    var filterDiscipline: String get() = sp.getString("filterDiscipline", "all") ?: "all"; set(v) { sp.edit().putString("filterDiscipline", v).apply() }
    var filterCategories: Set<String>
        get() = sp.getStringSet("filterCategories", null) ?: Category.entries.map { it.key }.toSet()
        set(v) { sp.edit().putStringSet("filterCategories", v).apply() }
}

sealed class Screen {
    data object Main : Screen()
    data class Detail(val id: String) : Screen()
    data class Editor(val session: Session, val isNew: Boolean) : Screen()
    data object Settings : Screen()
    data object Champ : Screen()
    data object ChampSettings : Screen()
    data object Help : Screen()
}

class Nav(start: Screen) {
    val stack = mutableStateListOf(start)
    val current: Screen get() = stack.last()
    fun push(s: Screen) { stack.add(s) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    fun replaceTop(s: Screen) { stack[stack.lastIndex] = s }
}

@Composable
fun App(store: Store, prefs: Prefs, startScreen: String?) {
    val nav = remember { Nav(Screen.Main) }
    val tx = Tx(prefs.lang)
    LaunchedEffect(Unit) {
        when (startScreen) {
            "settings" -> nav.push(Screen.Settings)
            "champ" -> nav.push(Screen.Champ)
            "champset" -> { nav.push(Screen.Champ); nav.push(Screen.ChampSettings) }
            "help" -> { nav.push(Screen.Settings); nav.push(Screen.Help) }
            "detail" -> store.sessions.firstOrNull()?.let { nav.push(Screen.Detail(it.id)) }
            "editor" -> {
                var s = newSession(store)
                s = s.copy(desc = "Training", series = listOf(Series.of(SeriesFormat.P2S6R4).copy(shots = listOf(8, 9, 10, 9, 9).map { Shot.Value(it) }), Series.of(SeriesFormat.S10)))
                nav.push(Screen.Editor(s, true))
            }
        }
    }
    BackHandler(enabled = nav.stack.size > 1 && nav.current !is Screen.Editor) { nav.pop() }
    when (val s = nav.current) {
        Screen.Main -> MainScreen(store, prefs, tx, nav)
        is Screen.Detail -> DetailScreen(store, tx, nav, s.id)
        is Screen.Editor -> EditorScreen(store, tx, nav, s.session, s.isNew)
        Screen.Settings -> SettingsScreen(store, prefs, tx, nav)
        Screen.Champ -> ChampScreen(store, tx, nav)
        Screen.ChampSettings -> ChampSettingsScreen(store, tx, nav)
        Screen.Help -> HelpScreen(tx, nav)
    }
}

fun newSession(store: Store): Session {
    val last = store.sessions.firstOrNull()
    val d = last?.discipline ?: Discipline.R300
    return Session(
        preset = d.key,
        category = (last?.categoryValue ?: Category.TRAINING).key,
        location = last?.location, weapon = last?.weapon,
        series = listOf(Series.of(d.defaultFormat)),
    )
}

// ---- theme: club green, following the phone's light/dark setting

private val Green = Color(0xFF2C5A38)
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) darkColorScheme(
        primary = Color(0xFF7FBF8C), onPrimary = Color(0xFF0F1A12), primaryContainer = Color(0xFF24382A), onPrimaryContainer = Color(0xFFDBE8DD),
        secondaryContainer = Color(0xFF2B3A2E), onSecondaryContainer = Color(0xFFDBE8DD),
        background = Color(0xFF111512), surface = Color(0xFF111512),
    ) else lightColorScheme(
        primary = Green, onPrimary = Color.White, primaryContainer = Color(0xFFDBE8DD), onPrimaryContainer = Color(0xFF0F2A17),
        secondaryContainer = Color(0xFFDBE8DD), onSecondaryContainer = Color(0xFF0F2A17),
        background = Color(0xFFF7F8F4), surface = Color(0xFFF7F8F4),
    )
    MaterialTheme(colorScheme = scheme, content = content)
}

// ---- sharing through the Android share sheet and email apps

fun Context.uriFor(file: File) = FileProvider.getUriForFile(this, "$packageName.files", file)

fun Context.shareFile(file: File, mime: String, title: String) {
    val i = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uriFor(file))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    startActivity(Intent.createChooser(i, title))
}

fun Context.sendMail(subject: String, body: String, files: List<File>, title: String) {
    val uris = ArrayList(files.map { uriFor(it) })
    val i = if (uris.size <= 1) Intent(Intent.ACTION_SEND).apply { uris.firstOrNull()?.let { putExtra(Intent.EXTRA_STREAM, it) } }
    else Intent(Intent.ACTION_SEND_MULTIPLE).apply { putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris) }
    i.type = if (uris.isEmpty()) "text/plain" else if (files.all { it.extension == "jpg" }) "image/jpeg" else "*/*"
    i.putExtra(Intent.EXTRA_SUBJECT, subject)
    i.putExtra(Intent.EXTRA_TEXT, body)
    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    startActivity(Intent.createChooser(i, title))
}

fun Context.mailSessions(store: Store, list: List<Session>, tx: Tx) {
    val (subject, body) = store.mailText(list, tx)
    val files = list.sortedBy { it.date }.flatMap { s -> s.photos.map(store::photoFile) }.filter { it.exists() }
    sendMail(subject, body, files, tx.email)
}

fun formatBytes(n: Long): String = when {
    n < 1024 * 1024 -> "${maxOf(1, n / 1024)} KB"
    n < 1024L * 1024 * 1024 -> String.format(java.util.Locale.ROOT, "%.1f MB", n / 1048576.0)
    else -> String.format(java.util.Locale.ROOT, "%.1f GB", n / 1073741824.0)
}
const val MAIL_LIMIT = 20L * 1024 * 1024
