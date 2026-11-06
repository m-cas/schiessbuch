package io.github.mcas.schiessbuch.scan

// Reads the text of range result strips (Swiss 300 m / field shooting printouts) into shots and series.
// Kotlin port of the ShootingResultScanner parser (StripParser, GenericSheetParser, HeaderParser,
// LayoutAnalyzer, ResultAssembler). Pure Kotlin, so it is tested without a device (ScanParserTest).

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

enum class ScoringMode { AUTOMATIC, DECIMAL, INTEGER, HUNDRED }

data class ScanShot(val number: Int?, val value: Double, val hundred: Int? = null, val inner: Boolean = false)

class ScanSeries(val number: Int, val shots: List<ScanShot>, var printedTotal: Double? = null, var printedHundredTotal: Int? = null) {
    val computedTotal get() = Scan.round1(shots.sumOf { it.value })
    val total get() = printedTotal ?: computedTotal
    val computedHundredTotal: Int? get() = if (shots.isNotEmpty() && shots.all { it.hundred != null }) shots.sumOf { it.hundred!! } else null
    val hundredTotal get() = printedHundredTotal ?: computedHundredTotal
    val isConsistent: Boolean?
        get() {
            val c = mutableListOf<Boolean>()
            printedTotal?.let { c += abs(it - computedTotal) < Scan.TOL }
            val ch = computedHundredTotal
            if (printedHundredTotal != null && ch != null) c += printedHundredTotal == ch
            return if (c.isEmpty()) null else !c.contains(false)
        }
}

data class DeepShot(val shot: Int, val value: Int)

sealed class ScanWarning {
    data object NoShots : ScanWarning()
    data class SeriesTotal(val series: Int, val printed: Double, val computed: Double) : ScanWarning()
    data class SeriesHundred(val series: Int, val printed: Int, val computed: Int) : ScanWarning()
    data class GrandTotal(val printed: Double, val computed: Double) : ScanWarning()
    data class HundredTotal(val printed: Int, val computed: Int) : ScanWarning()
    data class ShotValue(val shot: Int, val value: Double, val hundred: Int) : ScanWarning()
    data class Missing(val numbers: List<Int>) : ScanWarning()
    data object Copies : ScanWarning()
}

class ScanResult(var scoringMode: ScoringMode, val recognizedLines: List<String>) {
    var sighters: List<ScanShot> = emptyList()
    var series: List<ScanSeries> = emptyList()
    var printedTotal: Double? = null
    var printedHundredTotal: Int? = null
    var innerTenCount: Int? = null
    var deepShots: List<DeepShot> = emptyList()
    var warnings: MutableList<ScanWarning> = mutableListOf()
    var copiesRead = 1
    var copiesAgree: Boolean? = null
    var program: String? = null
    var targetLabel: String? = null
    var date: String? = null
    var time: String? = null
    var discipline: String? = null
    var shooterName: String? = null
    var birthYear: Int? = null
    var category: String? = null
    var club: String? = null
    var weapon: String? = null
    var organizer: String? = null
    var event: String? = null
    var competition: String? = null
    var awardThreshold: Int? = null
    var payoutThreshold: Int? = null

    val shots get() = series.flatMap { it.shots }
    val computedTotal get() = Scan.round1(series.sumOf { it.total })
    val total get() = printedTotal ?: computedTotal
    val hundredTotal: Int?
        get() {
            printedHundredTotal?.let { return it }
            val p = series.map { it.hundredTotal }
            return if (p.isEmpty() || p.contains(null)) null else p.sumOf { it!! }
        }
    val maximumPossible get() = shots.size * (if (scoringMode == ScoringMode.HUNDRED) 100.0 else 10.0)
    val isVerified get() = warnings.isEmpty() && (printedTotal != null || series.any { it.isConsistent == true })
}

class HeaderInfo {
    var shooterName: String? = null
    var birthYear: Int? = null
    var category: String? = null
    var club: String? = null
    var weapon: String? = null
    var organizer: String? = null
    var event: String? = null
    var competition: String? = null
    var awardThreshold: Int? = null
    var payoutThreshold: Int? = null
    val score get() = listOfNotNull(shooterName, club, weapon, event, competition).size +
        (if (awardThreshold != null) 1 else 0) + (if (payoutThreshold != null) 1 else 0)
}

/** A recognized word; box normalized to 0…1 with the origin at the bottom left. */
data class Word(val text: String, val x: Double, val y: Double, val w: Double, val h: Double) {
    val midY get() = y + h / 2
    val maxX get() = x + w
}

object Scan {
    const val TOL = 0.051
    fun round1(x: Double) = (x * 10).roundToInt() / 10.0
    fun ringForHundred(h: Int) = if (h <= 0) 0 else ceil(h / 10.0).toInt()
    fun fmt(x: Double) = if (x == Math.rint(x)) x.toLong().toString() else String.format(java.util.Locale.ROOT, "%.1f", x)

    // ---------- generic sheet helpers ----------
    private val DATE_RE = Regex("""\b\d{1,2}[./-]\d{1,2}[./-]\d{2,4}\b""")
    private val TIME_RE = Regex("""\b\d{1,2}:\d{2}(?::\d{2})?\b""")
    fun stripDatesAndTimes(line: String) = line.replace(DATE_RE, " ").replace(Regex("""\b\d{4}-\d{2}-\d{2}\b"""), " ").replace(TIME_RE, " ")

    class Token(val value: Double, val decimals: Int, val inner: Boolean)

    fun parseToken(raw: String): Token? {
        var t = raw.trim(',', ';', '\'', '"', '`', '´', '_', '~')
        if (t.isEmpty() || t.startsWith("-")) return null
        if (t == "X" || t == "x") return Token(10.0, 0, true)
        var inner = false
        if (t.endsWith("*") || t.endsWith("+")) { inner = true; t = t.dropLast(1) }
        if (t.none { it in '0'..'9' }) return null
        var s = t.map { c -> when (c) { 'O', 'o', 'Q' -> '0'; 'l', 'I', 'i', '|', '!' -> '1'; ',' -> '.'; else -> c } }.joinToString("")
        while (s.endsWith(".")) s = s.dropLast(1)
        if (!Regex("""^\d{1,4}(\.\d{1,2})?$""").matches(s)) return null
        val dec = if (s.contains('.')) s.substringAfter('.').length else 0
        return Token(s.toDouble(), dec, inner)
    }

    fun numberTokens(text: String) = text.split(Regex("""[\s|;=:()\[\]{}/]+""")).mapNotNull { parseToken(it) }
    private fun isIndexSequence(tk: List<Token>) = tk.size >= 4 && tk.all { it.decimals == 0 } && tk.zipWithNext().all { (a, b) -> b.value == a.value + 1 }
    private fun stripSeriesLabel(line: String): Pair<Int?, String> {
        for (re in listOf(Regex("""^\s*(?:serie|series|série|passe|pass|s)\s*\.?\s*(\d{1,2})\s*[:.)\-]?\s+""", RegexOption.IGNORE_CASE), Regex("""^\s*(\d{1,2})\s*[.):]\s+"""))) {
            val m = re.find(line) ?: continue
            return m.groupValues[1].toInt() to line.substring(m.range.last + 1)
        }
        return null to line
    }
    private val G_NEUTRAL = setOf("summe", "sum", "somme", "somma", "zwischentotal", "subtotal", "serie", "series", "série", "passe", "pass",
        "ring", "ringe", "punkte", "pkt", "pts", "points", "punti", "schuss", "shot", "shots", "coups", "colpi")
    private val TOTAL_KW = listOf("total", "gesamt", "gesamttotal", "endresultat", "resultat", "résultat", "ergebnis", "result", "totale", "risultato", "score")
    private val INNER_KW = listOf("innenzehner", "innen-zehner", "mouche", "mouches", "mouchen", "inner", "x-count", "xs", "centri")
    private val DISC_KW = listOf("luftgewehr", "luftpistole", "kleinkaliber", "sportpistole", "schnellfeuer", "ordonnanz", "gewehr 300", "gewehr 50",
        "pistole 25", "pistole 50", "air rifle", "air pistol", "smallbore", "carabine", "pistolet", "fusil", "carabina", "pistola", "10m", "25m", "50m", "300m")
    private fun containsWords(line: String) = line.split(Regex("""\s+""")).any { w ->
        val x = w.lowercase().trim { !it.isLetterOrDigit() }
        x.length >= 2 && x.all { it.isLetter() } && x !in G_NEUTRAL
    }
    private fun containsKeyword(lower: String, kws: List<String>): Boolean { val ws = lower.split(Regex("""[^\p{L}-]+""")).toSet(); return kws.any { it in ws } }
    private fun looksDecimal(lines: List<String>) = lines.flatMap { numberTokens(stripDatesAndTimes(it)) }.count { it.decimals == 1 && it.value <= 10.9 } >= 3

    private fun genericParse(rawLines: List<String>, mode0: ScoringMode, shotsPerSeries: Int?): ScanResult {
        val lines = rawLines.map { it.trim() }.filter { it.isNotEmpty() }
        val mode = if (mode0 != ScoringMode.AUTOMATIC) mode0 else if (looksDecimal(lines)) ScoringMode.DECIMAL else ScoringMode.INTEGER
        val maxShot = if (mode == ScoringMode.DECIMAL) 10.9 else 10.0
        val r = ScanResult(mode, rawLines)
        val out = mutableListOf<ScanSeries>()
        val loose = mutableListOf<ScanShot>()
        var next = 1
        fun append(shots: List<ScanShot>, total: Double?, label: Int? = null) { out += ScanSeries(label ?: (out.size + 1), shots.toList(), total) }
        fun chunk(n: Int) = if ((shotsPerSeries ?: 0) > 0) shotsPerSeries else if (n > 10 && n % 10 == 0) 10 else null
        fun flush(total: Double?) {
            if (loose.isEmpty()) return
            val size = if (total == null) chunk(loose.size) else null
            if (size != null && size < loose.size) loose.chunked(size).forEach { append(it, null) } else append(loose, total)
            loose.clear()
        }
        for (line in lines) {
            if (r.date == null) DATE_RE.find(line)?.let { r.date = it.value }
            if (r.shooterName == null) {
                val m = Regex("""^\s*(?:name|schütze|schützin|tireur|tireuse|tiratore|tiratrice|shooter|athlete|athlet)\s*[:.]?\s+(.+)$""", RegexOption.IGNORE_CASE).find(line)
                if (m != null) { r.shooterName = m.groupValues.last().trim(); continue }
            }
            if (r.discipline == null && DISC_KW.any { line.lowercase().contains(it) }) r.discipline = line
            val (label, body) = stripSeriesLabel(stripDatesAndTimes(line))
            val lower = body.lowercase()
            val tokens = numberTokens(body)
            if (tokens.isEmpty() || isIndexSequence(tokens)) continue
            val isTotal = containsKeyword(lower, TOTAL_KW); val isInner = containsKeyword(lower, INNER_KW)
            if (isTotal || isInner) {
                val total = if (isTotal) tokens.filter { it.value > maxShot }.maxOfOrNull { it.value } else null
                if (total != null) r.printedTotal = total
                if (isInner) tokens.firstOrNull { it.decimals == 0 && it.value < 200 && it.value != total }?.let { r.innerTenCount = it.value.toInt() }
                continue
            }
            var values = if (mode == ScoringMode.DECIMAL) tokens.filter { it.decimals == 1 } else tokens.filter { it.decimals == 0 }
            if (mode != ScoringMode.DECIMAL && values.size == 2 && values[1].value <= maxShot && values[0].value.toInt() in next..(next + 3)) {
                next = values[0].value.toInt() + 1; values = values.drop(1)
            }
            if (values.isEmpty()) continue
            if (containsWords(body) && label == null && values.size < 3) continue
            val shots = values.filter { it.value <= maxShot }.map { ScanShot(null, it.value, null, it.inner) }
            val bigs = values.filter { it.value > maxShot }.map { it.value }
            val sum = round1(shots.sumOf { it.value })
            when {
                shots.isNotEmpty() && bigs.isNotEmpty() ->
                    if (shots.size >= 2 || label != null) { flush(null); append(shots, bigs.firstOrNull { abs(it - sum) < TOL } ?: bigs[0], label) }
                    else { loose += shots; flush(bigs[0]) }
                shots.size >= 3 -> { flush(null); append(shots, null, label) }
                shots.isNotEmpty() -> loose += shots
                loose.isNotEmpty() -> { val ls = round1(loose.sumOf { it.value }); flush(bigs.firstOrNull { abs(it - ls) < TOL } ?: bigs[0]) }
                out.isNotEmpty() && out.last().printedTotal == null -> { val last = out.last(); last.printedTotal = bigs.firstOrNull { abs(it - last.computedTotal) < TOL } ?: bigs[0] }
                r.printedTotal == null && out.isNotEmpty() -> { val ss = round1(out.sumOf { it.total }); bigs.firstOrNull { abs(it - ss) < TOL }?.let { r.printedTotal = it } }
            }
        }
        flush(null)
        r.series = out
        if (out.isEmpty()) r.warnings = mutableListOf(ScanWarning.NoShots)
        else {
            for (s in out) if (s.isConsistent == false) r.warnings += ScanWarning.SeriesTotal(s.number, s.printedTotal!!, s.computedTotal)
            r.printedTotal?.let { p -> val c = round1(out.sumOf { it.total }); if (abs(p - c) >= TOL) r.warnings += ScanWarning.GrandTotal(p, c) }
        }
        return r
    }

    // ---------- strip parser ----------
    private val PG_RE = Regex("""^\s*[PF][g9qs]\s*(\d{1,3})\s+(\d{1,3}\s*/\s*\d{2,4})""", RegexOption.IGNORE_CASE)
    private val SCHEIBE_RE = Regex("""^\s*scheibe\s*(\d{1,3}(?:\s*/\s*\d{1,3})?)""", RegexOption.IGNORE_CASE)
    private val DEEP_RE = Regex("""^\s*[TI1l]\s*/\s*[PF]\s*(\d{1,2})\s+(\d{1,3})\b""", RegexOption.IGNORE_CASE)
    private val SUBTOTAL_WORDS = listOf("subtot", "zwischen", "summe", "sous-tot", "somme", "somma")
    private val TOTAL_WORDS = listOf("tota", "gesamt", "résultat", "resultat", "risultato")
    private val NEUTRAL = setOf("ring", "ringe", "pkt", "pts", "punkte", "points", "punti")
    private val SINGLE_DIGITS = mapOf("O" to 0, "o" to 0, "I" to 1, "l" to 1, "Z" to 2, "J" to 3, "S" to 5, "G" to 6, "B" to 8)
    fun isHeaderLine(l: String) = PG_RE.containsMatchIn(l) || SCHEIBE_RE.containsMatchIn(l)
    private fun deepShot(line: String) = DEEP_RE.find(line)?.let { DeepShot(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }

    private class Num(val value: Int, val inner: Boolean, val text: String)
    private class Row(val raw: String, val lower: String, val nums: List<Num>, val isSighter: Boolean, val hasWords: Boolean, val isBlank: Boolean, val isParen: Boolean)

    private fun makeRow(line: String): Row {
        val trimmed = line.trim()
        val cleaned = stripDatesAndTimes(trimmed)
        val nums = mutableListOf<Num>()
        var sighter = false; var words = false
        for (piece in cleaned.split(Regex("""[\s|;=:()\[\]{}]+"""))) {
            var t = piece
            while (t.isNotEmpty() && !t.first().isLetterOrDigit()) t = t.drop(1)
            var inner = false
            while (t.isNotEmpty() && !t.last().isLetterOrDigit()) { if (t.last() in "*✱✲✳#") inner = true; t = t.dropLast(1) }
            if (t.isEmpty()) continue
            var s = t
            if (s.none { it in '0'..'9' }) {
                if (s == "P" || s == "p") { sighter = true; continue }
                if (s == "X" || s == "x") { nums += Num(10, true, s); continue }
                SINGLE_DIGITS[s]?.let { d -> nums += Num(d, inner, d.toString()) } ?: run {
                    if (s.length >= 2 && s.all { it.isLetter() } && s.lowercase() !in NEUTRAL) words = true
                }
                continue
            }
            if (s.length > 1 && (s.last() == 'P' || s.last() == 'p')) { sighter = true; s = s.dropLast(1) }
            if (s.length > 1 && s.first() == 'P') { sighter = true; s = s.drop(1) }
            s = s.map { c -> when (c) { 'O', 'o', 'Q', 'D' -> '0'; 'l', 'I', 'i', '|', '!' -> '1'; 'B' -> '8'; 'S' -> '5'; 'Z' -> '2'; 'G' -> '6'; else -> c } }.joinToString("")
            if (!Regex("""^\d{1,4}$""").matches(s)) continue
            nums += Num(s.toInt(), inner, s)
        }
        val opens = trimmed.isNotEmpty() && trimmed.first() in "([{<"
        val closes = trimmed.isNotEmpty() && trimmed.last() in ")]}>"
        return Row(trimmed, trimmed.lowercase(), nums, sighter, words, trimmed.isEmpty(), opens || closes)
    }

    private enum class Layout { R, RH, H }

    private fun detectLayout(rows: List<Row>): Layout {
        var dual = 0; var ringS = 0; var hundredS = 0
        for (row in rows) {
            if (row.hasWords || row.isParen || row.nums.size < 2 || deepShot(row.raw) != null) continue
            val r = row.nums.drop(1).map { it.value }
            if (r.size >= 2 && r.last() > 10 && ringForHundred(r.last()) == r[r.size - 2]) dual++
            else if (r.size == 1 && r[0] > 10) hundredS++ else ringS++
        }
        return if (dual > ringS + hundredS) Layout.RH else if (hundredS > ringS) Layout.H else Layout.R
    }

    private fun readMetadata(row: Row, r: ScanResult) {
        if (r.date == null) DATE_RE.find(row.raw)?.let { r.date = it.value }
        if (r.time == null) TIME_RE.find(row.raw)?.let { r.time = it.value }
        PG_RE.find(row.raw)?.let { r.program = it.groupValues[1]; r.targetLabel = it.groupValues[2].replace(" ", ""); return }
        SCHEIBE_RE.find(row.raw)?.let { r.targetLabel = it.groupValues[1].replace(" ", ""); return }
        if (r.program == null && row.lower.contains("progr")) r.program = row.raw
    }

    private class Made(val shot: ScanShot, val sub: Int?)

    private fun stripParse(lines: List<String>): ScanResult {
        val rows = lines.map(::makeRow)
        val layout = detectLayout(rows)
        val r = ScanResult(if (layout == Layout.H) ScoringMode.HUNDRED else ScoringMode.INTEGER, lines)
        val sighters = mutableListOf<ScanShot>(); val series = mutableListOf<ScanSeries>(); var pending = mutableListOf<ScanShot>()
        var last = 0; val deep = mutableListOf<DeepShot>(); var pt: Double? = null; var pht: Int? = null
        val missing = mutableListOf<Int>(); val warnings = mutableListOf<ScanWarning>()

        fun makeShot(number: Int, vals: List<Num>): Made? {
            val l = vals.lastOrNull() ?: return null
            return when (layout) {
                Layout.H -> Made(ScanShot(number, l.value.toDouble(), l.value, l.inner), null)
                Layout.RH -> when {
                    vals.size >= 2 && l.value > 10 -> {
                        val ring = vals[vals.size - 2]
                        if (ringForHundred(l.value) != ring.value) warnings += ScanWarning.ShotValue(number, ring.value.toDouble(), l.value)
                        Made(ScanShot(number, ring.value.toDouble(), l.value, ring.inner || l.inner), null)
                    }
                    l.value > 10 -> Made(ScanShot(number, ringForHundred(l.value).toDouble(), l.value), null)
                    else -> Made(ScanShot(number, l.value.toDouble(), null, l.inner), null)
                }
                Layout.R -> {
                    if (vals.size == 1) return if (l.value <= 10) Made(ScanShot(number, l.value.toDouble(), null, l.inner), null) else null
                    val a = vals[vals.size - 2]
                    val isSub = l.value > 10 || (pending.isNotEmpty() && l.value == pending.sumOf { it.value }.toInt() + a.value)
                    if (isSub && a.value <= 10) Made(ScanShot(number, a.value.toDouble(), null, a.inner), l.value)
                    else if (l.value > 10) null
                    else Made(ScanShot(number, l.value.toDouble(), null, l.inner), null)
                }
            }
        }
        fun close(values: List<Int> = emptyList()) {
            if (pending.isEmpty()) return
            val s = ScanSeries(series.size + 1, pending)
            if (layout != Layout.RH) { if (values.isNotEmpty()) s.printedTotal = values.last().toDouble() }
            else if (values.size >= 2) { s.printedTotal = values[values.size - 2].toDouble(); s.printedHundredTotal = values.last() }
            else if (values.isNotEmpty()) { val v = values[0]; if (v > 10 * pending.size) s.printedHundredTotal = v else s.printedTotal = v.toDouble() }
            series += s; pending = mutableListOf()
        }
        fun setTotals(values: List<Int>) {
            if (values.isEmpty()) return
            val lastV = values.last()
            if (layout != Layout.RH) {
                val computed = round1(series.sumOf { it.total }).roundToInt()
                val cand = values.firstOrNull { it == computed } ?: lastV
                if (pt == null || cand == computed) pt = cand.toDouble()
            } else if (values.size >= 2) { pt = values[values.size - 2].toDouble(); pht = lastV }
            else { val n = series.sumOf { it.shots.size }; if (lastV > 10 * n) pht = lastV else pt = lastV.toDouble() }
        }
        fun commit(number: Int, vals: List<Num>): Boolean {
            val made = makeShot(number, vals) ?: return false
            pending += made.shot; last = number
            made.sub?.let { close(listOf(it)) }
            return true
        }
        fun nextShotNumber(i: Int) = rows.drop(i + 1).firstOrNull { !it.hasWords && !it.isBlank && it.nums.size >= 2 }?.nums?.first()?.value

        rows.forEachIndexed { i, row ->
            if (row.isBlank) { close(); return@forEachIndexed }
            readMetadata(row, r)
            if (isHeaderLine(row.raw)) return@forEachIndexed
            deepShot(row.raw)?.let { deep += it; return@forEachIndexed }
            if (row.isParen) { close(); return@forEachIndexed }
            if (SUBTOTAL_WORDS.any { row.lower.contains(it) }) { close(row.nums.map { it.value }); return@forEachIndexed }
            if (TOTAL_WORDS.any { row.lower.contains(it) }) { close(); setTotals(row.nums.map { it.value }); return@forEachIndexed }
            if (row.hasWords || row.nums.isEmpty()) return@forEachIndexed
            val nums = row.nums
            if (row.isSighter) {
                if (nums.size >= 2) makeShot(nums[0].value, nums.drop(1))?.let { sighters += it.shot }
                return@forEachIndexed
            }
            val expected = last + 1; val first = nums[0]
            if (nums.size >= 2) {
                val index = when {
                    first.value == expected -> expected
                    first.value > expected && first.value <= expected + 3 -> first.value
                    first.value > expected && nums.size <= 4 && first.text.length > expected.toString().length && first.text.startsWith(expected.toString()) -> expected
                    else -> null
                }
                if (index != null && commit(index, nums.drop(1))) { for (k in expected until index) missing += k; return@forEachIndexed }
            }
            if (last == 0) return@forEachIndexed
            if (layout == Layout.RH && first.value <= last && nums.size >= 3) { close(); return@forEachIndexed }
            if (layout == Layout.R && nums.size <= 2 && first.value <= 10 && nextShotNumber(i) == expected + 1 && commit(expected, nums)) return@forEachIndexed
            close(); setTotals(nums.map { it.value })
        }
        close()
        r.sighters = sighters; r.series = series; r.deepShots = deep; r.printedTotal = pt; r.printedHundredTotal = pht
        r.warnings = (warnings + validate(r, missing)).toMutableList()
        return r
    }

    private fun validate(r: ScanResult, missing: List<Int>): List<ScanWarning> {
        if (r.series.isEmpty()) return listOf(ScanWarning.NoShots)
        val w = mutableListOf<ScanWarning>()
        if (missing.isNotEmpty()) w += ScanWarning.Missing(missing)
        for (s in r.series) {
            s.printedTotal?.let { if (abs(it - s.computedTotal) >= TOL) w += ScanWarning.SeriesTotal(s.number, it, s.computedTotal) }
            val c = s.computedHundredTotal
            if (s.printedHundredTotal != null && c != null && s.printedHundredTotal != c) w += ScanWarning.SeriesHundred(s.number, s.printedHundredTotal!!, c)
        }
        r.printedTotal?.let { if (abs(it - r.computedTotal) >= TOL) w += ScanWarning.GrandTotal(it, r.computedTotal) }
        r.printedHundredTotal?.let { p ->
            val parts = r.series.map { it.hundredTotal }
            if (!parts.contains(null)) { val c = parts.sumOf { it!! }; if (c != p) w += ScanWarning.HundredTotal(p, c) }
        }
        return w
    }

    // ---------- result parser ----------
    fun parseAll(lines: List<String>, mode: ScoringMode = ScoringMode.AUTOMATIC, shotsPerSeries: Int? = null): List<ScanResult> {
        val sections = mutableListOf(mutableListOf<String>())
        for (l in lines) { if (isHeaderLine(l) && sections.last().isNotEmpty()) sections += mutableListOf<String>(); sections.last() += l }
        return sections.filter { it.isNotEmpty() }.map { sec ->
            if (mode == ScoringMode.DECIMAL || (mode == ScoringMode.AUTOMATIC && looksDecimal(sec))) genericParse(sec, mode, shotsPerSeries)
            else stripParse(sec).let { if (it.series.isNotEmpty()) it else genericParse(sec, mode, shotsPerSeries) }
        }.filter { it.series.isNotEmpty() }
    }

    // ---------- header (sideways text on SIUS-style strips) ----------
    private val NAME_RE = Regex("""^(\p{Lu}[\p{L}'’\-]+(?:\s+\p{Lu}[\p{L}'’\-]+){1,3})\s+((?:19|20)\d{2})(?:\s+([A-Z]{1,2}))?$""")
    private val WEAPON = listOf("sturmgewehr", "stgw", "karabiner", "standardgewehr", "freigewehr", "langgewehr", "ordonnanz", "pistole", "gewehr", "fass", "fusil", "pistolet")
    private val BOILER = listOf("einzelschütze", "kranzkarte", "naturalgabe", "auszeichnung", "auszahlung", "kopie", "original")
    private fun isWeapon(l: String) = WEAPON.any { l.lowercase().contains(it) }
    private fun isBoiler(l: String) = BOILER.any { l.lowercase().contains(it) }
    private fun meaningful(l: String) = !(l.count { it.isLetter() } < 4 || (l.length <= 7 && l == l.uppercase()))

    fun parseHeader(raw: List<String>): HeaderInfo {
        val h = HeaderInfo()
        val lines = raw.map { it.trim() }.filter { it.isNotEmpty() }
        val joined = lines.joinToString("\n")
        Regex("""auszeichnung\s*ab\s*:?\s*(\d{2,4})""", RegexOption.IGNORE_CASE).find(joined)?.let { h.awardThreshold = it.groupValues[1].toInt() }
        Regex("""auszahlung\s*ab\s*:?\s*(\d{2,4})""", RegexOption.IGNORE_CASE).find(joined)?.let { h.payoutThreshold = it.groupValues[1].toInt() }
        val ni = lines.indexOfFirst { NAME_RE.matches(it) }
        if (ni < 0) return h
        val m = NAME_RE.find(lines[ni])!!
        h.shooterName = m.groupValues[1]; h.birthYear = m.groupValues[2].toInt(); h.category = m.groupValues[3].ifEmpty { null }
        val before = lines.take(ni).filter(::meaningful)
        if (before.size >= 2) h.organizer = before[before.size - 2]
        h.event = before.lastOrNull()
        val after = lines.drop(ni + 1).filter(::meaningful)
        after.firstOrNull()?.let { if (!isWeapon(it)) h.club = it }
        val w = after.indexOfFirst(::isWeapon)
        if (w >= 0) { h.weapon = after[w]; if (w + 1 < after.size && !isBoiler(after[w + 1])) h.competition = after[w + 1] }
        return h
    }

    fun applyHeader(h: HeaderInfo, results: List<ScanResult>) {
        for (r in results) {
            r.shooterName = r.shooterName ?: h.shooterName; r.birthYear = r.birthYear ?: h.birthYear; r.category = r.category ?: h.category
            r.club = r.club ?: h.club; r.weapon = r.weapon ?: h.weapon; r.organizer = r.organizer ?: h.organizer
            r.event = r.event ?: h.event; r.competition = r.competition ?: h.competition
        }
        h.awardThreshold?.let { v -> results.firstOrNull { it.maximumPossible > 0 && v <= it.maximumPossible && v >= it.maximumPossible / 2 }?.awardThreshold = v }
        h.payoutThreshold?.let { v -> results.firstOrNull { it.maximumPossible > 0 && v <= it.maximumPossible && v >= it.maximumPossible / 2 }?.payoutThreshold = v }
    }

    // ---------- layout: word boxes → printed lines ----------
    fun rows(frags: List<Word>): List<String> {
        val sorted = frags.sortedByDescending { it.midY }
        val rows = mutableListOf<MutableList<Word>>(); val mids = mutableListOf<Double>()
        for (f in sorted) {
            if (rows.isNotEmpty() && abs(mids.last() - f.midY) < minOf(rows.last()[0].h, f.h) * 0.6) {
                rows.last() += f; mids[mids.size - 1] = rows.last().sumOf { it.midY } / rows.last().size
            } else { rows += mutableListOf(f); mids += f.midY }
        }
        val gaps = mids.zipWithNext { a, b -> a - b }
        val typical = gaps.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }
        val out = mutableListOf<String>()
        rows.forEachIndexed { i, row ->
            if (i > 0 && typical > 0 && gaps[i - 1] > typical * 1.7) out += ""
            out += row.sortedBy { it.x }.joinToString(" ") { it.text }
        }
        return out
    }

    private fun isAnchor(w: Word, words: List<Word>): Boolean {
        if (Regex("""^[Pp][gq]\d{0,3}$""").matches(w.text)) return true
        if (!Regex("""^[PF][9s]\d{0,3}$""").matches(w.text)) return false
        return words.any { v -> v.x > w.maxX && v.x - w.maxX < 0.35 && abs(v.midY - w.midY) < w.h && Regex("""^\d{1,3}/\d{2,4}$""").matches(v.text) }
    }

    fun columns(words: List<Word>): List<List<String>> {
        if (words.isEmpty()) return emptyList()
        val anchors = words.filter { isAnchor(it, words) }.map { it.x }.sorted()
        val clusters = mutableListOf<Double>()
        for (x in anchors) if (clusters.isEmpty() || x - clusters.last() >= 0.06) clusters += x
        val starts = if (clusters.size > 1) clusters.map { maxOf(0.0, it - 0.03) }.toMutableList().also { it[0] = 0.0 } else mutableListOf(0.0)
        val buckets = starts.map { mutableListOf<Word>() }
        for (w in words) buckets[starts.indexOfLast { w.x >= it }.coerceAtLeast(0)] += w
        return buckets.map(::rows).filter { it.isNotEmpty() }
    }

    // ---------- copies printed side by side ----------
    fun mergeCopies(results: List<ScanResult>): List<ScanResult> {
        val groups = mutableListOf<MutableList<ScanResult>>(); val idx = mutableMapOf<String, Int>()
        for (r in results) {
            val key = r.targetLabel?.let { "${r.program ?: ""}|$it" }
            if (key != null && key in idx) groups[idx[key]!!] += r
            else { if (key != null) idx[key] = groups.size; groups += mutableListOf(r) }
        }
        return groups.map { copies ->
            if (copies.size == 1) return@map copies[0]
            val best = copies.sortedWith(compareBy<ScanResult>({ if (it.isVerified) 0 else 1 }, { it.warnings.size }, { -it.shots.size })).first()
            val agree = copies.map { c -> c.shots.map { it.value } }.toSet().size == 1
            best.copiesRead = copies.size; best.copiesAgree = agree
            if (!agree && !best.isVerified) best.warnings += ScanWarning.Copies
            if (best.deepShots.isEmpty()) best.deepShots = copies.maxBy { it.deepShots.size }.deepShots
            best
        }
    }
}
