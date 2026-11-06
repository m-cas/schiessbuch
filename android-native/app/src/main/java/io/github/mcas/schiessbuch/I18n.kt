package io.github.mcas.schiessbuch

import android.content.Context
import org.json.JSONObject

/**
 * French and Italian texts from assets/i18n.json, keyed by the English text.
 * "{}" in a key stands for a value filled in at run time ("Series {}" matches "Series 3").
 * The same file is used by the web and iPhone apps.
 */
object I18n {
    private var table: Map<String, List<String>> = emptyMap()
    private var patterns: List<Pair<String, Regex>> = emptyList()
    private val memo = HashMap<String, String>()

    fun load(context: Context) {
        if (table.isNotEmpty()) return
        runCatching {
            val o = JSONObject(context.assets.open("i18n.json").bufferedReader().use { it.readText() })
            val t = HashMap<String, List<String>>()
            o.keys().forEach { k -> val a = o.getJSONArray(k); t[k] = (0 until a.length()).map { a.getString(it) } }
            table = t
            patterns = t.keys.filter { it.contains("{}") }
                .sortedByDescending { it.replace("{}", "").length }
                .map { k -> k to Regex("^" + k.split("{}").joinToString("([\\s\\S]+?)") { Regex.escape(it) } + "$") }
        }
    }

    @Synchronized
    fun tr(en: String, lang: String): String {
        if (en.isEmpty() || (lang != "fr" && lang != "it")) return en
        val i = if (lang == "fr") 0 else 1
        val key = lang + "\u0001" + en
        memo[key]?.let { return it }
        var out = table[en]?.getOrNull(i)
        if (out == null) {
            for ((k, re) in patterns) {
                val m = re.matchEntire(en) ?: continue
                val target = table[k]?.getOrNull(i) ?: continue
                val parts = target.split("{}")
                out = buildString {
                    append(parts[0])
                    for (n in 1 until parts.size) { append(m.groupValues.getOrElse(n) { "" }); append(parts[n]) }
                }
                break
            }
        }
        val result = out ?: en
        memo[key] = result
        return result
    }
}
