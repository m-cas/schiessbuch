package io.github.mcas.schiessbuch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONObject

/** How to use the app; the text (help.json) is the same in the web, iPhone and Android versions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(tx: Tx, nav: Nav) {
    val context = LocalContext.current
    val sections = remember(tx.lang) {
        runCatching {
            val o = JSONObject(context.assets.open("help.json").bufferedReader().use { it.readText() })
            val a = o.optJSONArray(tx.lang) ?: o.getJSONArray("en")
            (0 until a.length()).map { i ->
                val s = a.getJSONObject(i); val p = s.getJSONArray("p")
                s.getString("t") to (0 until p.length()).map { p.getString(it) }
            }
        }.getOrDefault(emptyList())
    }
    var open by remember { mutableStateOf(setOf(0)) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tx.helpH) },
                navigationIcon = { IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tx.back) } },
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 4.dp, bottom = pad.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(sections) { i, (title, paras) ->
                val isOpen = i in open
                Card(onClick = { open = if (isOpen) open - i else open + i }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Icon(Icons.Filled.ExpandMore, null, Modifier.rotate(if (isOpen) 180f else 0f))
                        }
                        if (isOpen) paras.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
        }
    }
}
