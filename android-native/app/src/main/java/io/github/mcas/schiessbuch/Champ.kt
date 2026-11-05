package io.github.mcas.schiessbuch

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * A championship: the sessions the club asks to be shot in one year, loaded from a CSV file.
 * Kept on the phone in the same JSON shape as the web app ("championship" in backups).
 */
data class ChampSeries(val type: String, val target: String?, val planned: Int?, val practice: Int?, val parts: List<SeriesPart>? = null) {
    fun toJson() = JSONObject().apply {
        put("type", type); put("target", target ?: JSONObject.NULL); put("planned", planned ?: JSONObject.NULL); put("practice", practice ?: JSONObject.NULL)
        if (!parts.isNullOrEmpty()) put("parts", JSONArray(parts.map { it.toJson() }))
    }
    /** Only the grouping and practice shots: "3 Probe + 10 + 10", "2 Probe + 6 + 4", "10". */
    fun groupText(tx: Tx): String {
        val p = parts ?: SeriesFormat.of(type)?.parts?.mapIndexed { i, g -> SeriesPart(g.kind, g.count, Target.A10.key, i + 1) }
            ?: ((if ((practice ?: 0) > 0) listOf(SeriesPart(SegmentKind.PRACTICE, practice!!, Target.A10.key, 1)) else emptyList()) + SeriesPart(SegmentKind.SINGLE, planned ?: 10, Target.A10.key, 1))
        return groupingText(p, tx::chPrac)
    }
    companion object {
        fun fromJson(o: JSONObject) = ChampSeries(o.optStringOrNull("type") ?: SeriesFormat.CUSTOM.key, o.optStringOrNull("target"), o.optIntOrNull("planned"), o.optIntOrNull("practice"),
            o.optJSONArray("parts")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(SeriesPart::fromJson) } }?.takeIf { it.isNotEmpty() })
    }
}

data class ChampItem(
    val id: String, val title: String, val preset: String, val series: List<ChampSeries>,
    val date: String? = null, val location: String? = null, val notes: String? = null, val sessionId: String? = null,
) {
    val discipline: Discipline get() = Discipline.of(preset)
    val day: java.time.LocalDate? get() = date?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
    fun toJson() = JSONObject().apply {
        put("id", id); put("title", title); put("preset", preset); put("series", JSONArray(series.map { it.toJson() }))
        put("date", date ?: JSONObject.NULL); put("location", location ?: JSONObject.NULL); put("notes", notes ?: JSONObject.NULL); put("sessionId", sessionId ?: JSONObject.NULL)
    }
    companion object {
        fun fromJson(o: JSONObject) = ChampItem(
            o.optStringOrNull("id") ?: "", o.optStringOrNull("title") ?: "", o.optStringOrNull("preset") ?: Discipline.OTHER.key,
            o.optJSONArray("series")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(ChampSeries::fromJson) } } ?: emptyList(),
            o.optStringOrNull("date"), o.optStringOrNull("location"), o.optStringOrNull("notes"), o.optStringOrNull("sessionId"),
        )
    }
}

data class Championship(val year: Int, val name: String, val loadedAt: Double?, val items: List<ChampItem>) {
    fun toJson() = JSONObject().apply { put("year", year); put("name", name); put("loadedAt", loadedAt ?: JSONObject.NULL); put("items", JSONArray(items.map { it.toJson() })) }
    companion object {
        fun fromJson(o: JSONObject?): Championship? {
            if (o == null || !o.has("year")) return null
            return Championship(o.optInt("year"), o.optStringOrNull("name") ?: "", o.optDoubleOrNull("loadedAt"),
                o.optJSONArray("items")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(ChampItem::fromJson) } } ?: emptyList())
        }
    }
}

data class ChampState(val url: String = "", val current: Championship? = null, val history: List<Championship> = emptyList()) {
    val isEmpty get() = current == null && history.isEmpty()
    fun toJson() = JSONObject().apply { put("url", url); put("current", current?.toJson() ?: JSONObject.NULL); put("history", JSONArray(history.map { it.toJson() })) }
    companion object {
        fun fromJson(o: JSONObject?): ChampState = if (o == null) ChampState() else ChampState(
            o.optStringOrNull("url") ?: "", Championship.fromJson(o.optJSONObject("current")),
            o.optJSONArray("history")?.let { a -> (0 until a.length()).mapNotNull { Championship.fromJson(a.optJSONObject(it)) } } ?: emptyList(),
        )
    }
}

// ---- reading the CSV file

object ChampParser {
    class Failure(message: String) : Exception(message)
    data class Result(val champ: Championship, val warnings: List<String>)

    private val columns = mapOf(
        "id" to listOf("id", "nr", "nummer", "no"), "year" to listOf("jahr", "year"),
        "title" to listOf("titel", "title", "name", "beschreibung", "description", "anlass", "event"),
        "disc" to listOf("disziplin", "discipline", "waffe"), "series" to listOf("serien", "series", "programm", "program", "format"),
        "date" to listOf("datum", "date", "bis", "deadline", "termin"), "location" to listOf("schiessstand", "stand", "range", "location", "ort"),
        "notes" to listOf("notizen", "notes", "bemerkung", "bemerkungen", "info"), "champ" to listOf("meisterschaft", "championship"),
    )

    fun rows(text: String, sep: Char): List<List<String>> {
        val rows = mutableListOf<List<String>>(); var row = mutableListOf<String>(); val cell = StringBuilder(); var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (quoted) {
                if (c == '"') { if (i + 1 < text.length && text[i + 1] == '"') { cell.append('"'); i++ } else quoted = false } else cell.append(c)
            } else when (c) {
                '"' -> quoted = true
                sep -> { row.add(cell.toString()); cell.clear() }
                '\n', '\r' -> {
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    row.add(cell.toString()); cell.clear(); rows.add(row); row = mutableListOf()
                }
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row.add(cell.toString()); rows.add(row) }
        return rows.map { r -> r.map { it.trim() } }.filter { r -> r.any { it.isNotEmpty() } }
    }

    fun discipline(v: String): Discipline? = when (v.lowercase().replace(" ", "")) {
        "r300", "g300", "gewehr300m", "gewehr300", "rifle300m", "rifle300", "300m", "gewehr" -> Discipline.R300
        "p25", "pistole25m", "pistol25m", "25m" -> Discipline.P25
        "p50", "pistole50m", "pistol50m", "50m" -> Discipline.P50
        "ap10", "lp10", "luftpistole10m", "airpistol10m", "luftpistole" -> Discipline.AP10
        "ag10", "lug10", "lg10", "luftgewehr10m", "airrifle10m", "airgun10m", "luftgewehr" -> Discipline.AG10
        "other", "andere", "anderes" -> Discipline.OTHER
        else -> null
    }

    fun date(v: String): String? {
        val s = v.trim()
        Regex("""^(\d{4})-(\d{1,2})-(\d{1,2})$""").find(s)?.let { m -> val (y, mo, d) = m.destructured; return "%s-%02d-%02d".format(y, mo.toInt(), d.toInt()) }
        Regex("""^(\d{1,2})[./](\d{1,2})[./](\d{4})$""").find(s)?.let { m -> val (d, mo, y) = m.destructured; return "%s-%02d-%02d".format(y, mo.toInt(), d.toInt()) }
        return null
    }

    /** Series are separated by "!"; parts joined by "+" form ONE series (each part its own group with a subtotal).
     *  A part is a format code (s10, op300, pstd …), custom:SHOTS[:TARGET[:PRACTICE]], or just a number of shots. */
    private class Part(val code: SeriesFormat?, val n: Int, val t: String, val p: Int, val segs: List<Triple<SegmentKind, Int, String>>)

    private fun part(raw: String, d: Discipline): Part? {
        val tk = raw.lowercase()
        val f = SeriesFormat.of(tk)
        if (f != null && f != SeriesFormat.CUSTOM && f in d.formats)
            return Part(f, f.slots, Target.A10.key, 0, f.parts!!.map { Triple(it.kind, it.count, (it.target ?: Target.A10).key) })
        val m = Regex("""^(?:custom|eigene|c)?:?(\d{1,3})(?::(a10|a100|b4|b5|a5))?(?::(\d{1,2}))?$""").find(tk) ?: return null
        val n = m.groupValues[1].toIntOrNull()?.takeIf { it > 0 }?.let { minOf(200, it) } ?: return null
        val t = m.groupValues[2].takeIf { it.isNotEmpty() && d.hasTargetChoice }?.uppercase() ?: Target.A10.key
        val p = minOf(20, m.groupValues[3].toIntOrNull() ?: 0)
        return Part(null, n, t, p, (if (p > 0) listOf(Triple(SegmentKind.PRACTICE, p, t)) else emptyList()) + Triple(SegmentKind.SINGLE, n, t))
    }

    fun series(text: String, d: Discipline, warn: (String) -> Unit): List<ChampSeries> {
        val out = mutableListOf<ChampSeries>()
        for (group in text.split('!').map { it.trim() }.filter { it.isNotEmpty() }) {
            val parts = group.split('+').map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { raw -> part(raw, d) ?: run { warn(raw); null } }
            if (parts.isEmpty()) continue
            val one = parts.singleOrNull()
            when {
                one?.code != null -> out.add(ChampSeries(one.code.key, Target.A10.key, one.code.slots, 0))
                one != null -> out.add(ChampSeries(SeriesFormat.CUSTOM.key, one.t, one.n, one.p))
                else -> {
                    val segs = parts.flatMapIndexed { i, p -> p.segs.map { (k, n, t) -> SeriesPart(k, n, t, i + 1) } }
                    out.add(ChampSeries(SeriesFormat.CUSTOM.key, segs.first().t, segs.filter { it.k != SegmentKind.PRACTICE }.sumOf { it.n },
                        segs.filter { it.k == SegmentKind.PRACTICE }.sumOf { it.n }, segs))
                }
            }
        }
        if (out.isEmpty()) out.add(ChampSeries(d.defaultFormat.key, Target.A10.key, d.defaultFormat.slots, 0))
        return out
    }

    fun parse(input: String, tx: Tx): Result {
        val text = input.removePrefix("﻿")
        val first = text.lineSequence().firstOrNull { it.isNotBlank() } ?: ""
        val sep = listOf(';', '\t', ',').maxBy { c -> first.count { it == c } }
        val all = rows(text, sep)
        if (all.size < 2) throw Failure(tx.chNoRows)
        val head = all[0].map { h -> h.lowercase().filter { it.isLetter() } }
        val col = columns.mapNotNull { (k, names) -> head.indexOfFirst { it in names }.takeIf { it >= 0 }?.let { k to it } }.toMap()
        if ("title" !in col || "disc" !in col) throw Failure(tx.chNeedCols)
        val warnings = mutableListOf<String>(); val items = mutableListOf<ChampItem>(); val ids = mutableSetOf<String>()
        var year: Int? = null; var name = ""
        all.drop(1).forEachIndexed { n, r ->
            val line = n + 2
            fun get(k: String) = col[k]?.let { r.getOrNull(it) } ?: ""
            val title = get("title"); if (title.isEmpty()) return@forEachIndexed
            val d = discipline(get("disc")) ?: run { warnings.add(tx.chBadDisc(line, get("disc"))); return@forEachIndexed }
            if (year == null) year = Regex("""\d{4}""").find(get("year"))?.value?.toInt()
            if (name.isEmpty()) name = get("champ")
            var id = get("id").ifEmpty { title }
            while (id in ids) id += "*"
            ids.add(id)
            items.add(ChampItem(id, title, d.key, series(get("series"), d) { warnings.add(tx.chBadSeries(line, it)) },
                date(get("date")), get("location").ifEmpty { null }, get("notes").ifEmpty { null }))
        }
        if (items.isEmpty()) throw Failure(tx.chNoRows)
        if (year == null) year = items.firstNotNullOfOrNull { it.date }?.take(4)?.toIntOrNull()
        val y = year ?: throw Failure(tx.chNoYear)
        return Result(Championship(y, name.ifEmpty { tx.chDefaultName(y) }, System.currentTimeMillis().toDouble(), items), warnings)
    }

    /** Turns share links of common services into direct download links. */
    fun directUrl(s: String): String {
        val u = runCatching { URI(s.trim()) }.getOrNull() ?: return s.trim()
        val host = u.host ?: return s.trim()
        return when {
            host == "dropbox.com" || host.endsWith(".dropbox.com") ->
                URI(u.scheme, "dl.dropboxusercontent.com", u.path, u.query?.split('&')?.filterNot { it.startsWith("dl=") }?.joinToString("&")?.ifEmpty { null }, null).toString()
            host == "drive.google.com" && Regex("""/file/d/([^/]+)""").containsMatchIn(u.path) ->
                "https://drive.google.com/uc?export=download&id=" + Regex("""/file/d/([^/]+)""").find(u.path)!!.groupValues[1]
            host == "github.com" && u.path.contains("/blob/") -> "https://raw.githubusercontent.com" + u.path.replace("/blob/", "/")
            else -> s.trim()
        }
    }

    /** Downloads the file (call off the main thread). */
    fun download(url: String): String {
        var target = URL(directUrl(url))
        repeat(5) {
            val c = target.openConnection() as HttpURLConnection
            c.connectTimeout = 15000; c.readTimeout = 20000; c.useCaches = false; c.instanceFollowRedirects = true
            val code = c.responseCode
            if (code in 300..399) { c.getHeaderField("Location")?.let { target = URL(target, it) }; c.disconnect(); return@repeat }
            if (code !in 200..299) { c.disconnect(); throw Failure("HTTP $code") }
            return c.inputStream.use { it.readBytes() }.let { b -> String(b, Charsets.UTF_8).takeIf { !it.contains('�') } ?: String(b, Charsets.ISO_8859_1) }
        }
        throw Failure("HTTP redirect")
    }
}

// ---- keeping it on the phone

class ChampStore(private val file: File, private val store: Store) {
    var state by mutableStateOf(load())
        private set

    private fun load(): ChampState = runCatching { ChampState.fromJson(JSONObject(file.readText())) }.getOrDefault(ChampState())
    private fun persist() { runCatching { val tmp = File(file.path + ".tmp"); tmp.writeText(state.toJson().toString()); tmp.renameTo(file) } }
    fun set(s: ChampState) { state = s; persist() }
    fun setUrl(u: String) = set(state.copy(url = u))

    fun sessionFor(item: ChampItem): Session? = item.sessionId?.let(store::session)

    /** Makes a loaded file the current championship. Only one is kept: a file of another year replaces it
     *  (recorded sessions stay); the same year loaded again keeps the activated items. */
    fun apply(new: Championship) {
        var p = new
        val cur = state.current
        if (cur != null && cur.year == p.year) {
            val m = cur.items.filter { it.sessionId != null }.associate { it.id to it.sessionId }
            p = p.copy(items = p.items.map { if (it.sessionId == null && m[it.id] != null) it.copy(sessionId = m[it.id]) else it })
        }
        set(state.copy(current = p, history = emptyList()))
    }

    private fun updateItem(id: String, f: (ChampItem) -> ChampItem) {
        val cur = state.current ?: return
        set(state.copy(current = cur.copy(items = cur.items.map { if (it.id == id) f(it) else it })))
    }

    /** Creates the session for an item and marks the item as active. */
    fun activate(id: String): Session? {
        val item = state.current?.items?.firstOrNull { it.id == id } ?: return null
        val last = store.sessions.firstOrNull()
        val d = item.discipline
        val s = Session(
            preset = d.key, category = Category.CHAMPIONSHIP.key, desc = item.title,
            location = item.location ?: last?.location, weapon = if (last?.discipline == d) last.weapon else null, notes = item.notes,
            series = item.series.map { c ->
                Series(type = (SeriesFormat.of(c.type) ?: d.defaultFormat).key, target = c.target ?: Target.A10.key,
                    planned = c.planned ?: SeriesFormat.of(c.type)?.slots ?: 10, practice = c.practice ?: 0, parts = c.parts)
            },
        )
        store.save(s)
        updateItem(id) { it.copy(sessionId = s.id) }
        return store.session(s.id)
    }

    fun reset(id: String, deleteSession: Boolean) {
        val item = state.current?.items?.firstOrNull { it.id == id } ?: return
        if (deleteSession) item.sessionId?.let { store.delete(setOf(it)) }
        updateItem(id) { it.copy(sessionId = null) }
    }


    /** Sample championship for the test screenshots. */
    fun seedDemo(tx: Tx) {
        if (state.current != null) return
        val csv = """
            id;jahr;meisterschaft;titel;disziplin;serien;datum;schiessstand
            VM1;2026;Vereinsmeisterschaft SG Muttenz 2026;Runde 1 Gewehr;r300;s10!s10;30.04.2026;Muttenz
            OP;2026;;Obligatorisches;r300;op300;31.08.2026;
            P25;2026;;Pistole Standard;p25;pstd;30.06.2026;Muttenz
            FREI;2026;;Final Gewehr;r300;custom:20:A100:2;30.09.2026;Muttenz
        """.trimIndent()
        val r = runCatching { ChampParser.parse(csv, tx) }.getOrNull() ?: return
        val linked = store.sessions.firstOrNull { it.categoryValue == Category.CHAMPIONSHIP }?.id
        set(state.copy(current = r.champ.copy(items = r.champ.items.mapIndexed { i, it -> if (i == 0) it.copy(sessionId = linked) else it })))
    }
}
