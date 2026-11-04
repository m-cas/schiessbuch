package io.github.mcas.schiessbuch

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** One shot value; X is an inner ten (counts as 10) on pistol A10 targets. */
sealed class Shot {
    data class Value(val v: Int) : Shot()
    data object X : Shot()

    val points: Int get() = if (this is Value) v else 10
    val label: String get() = if (this is Value) v.toString() else "X"

    fun toJson(): Any = if (this is Value) v else "X"

    companion object {
        fun fromJson(o: Any?): Shot = when (o) {
            is Number -> Value(o.toDouble().roundToInt())
            is String -> if (o.equals("X", true)) X else Value(o.toIntOrNull() ?: 0)
            else -> Value(0)
        }
    }
}

enum class Target(val key: String, val max: Int) {
    A10("A10", 10), A100("A100", 100), B4("B4", 4), B5("B5", 5), A5("A5", 5);
    companion object { fun of(k: String?) = entries.firstOrNull { it.key == k } ?: A10 }
}

enum class SegmentKind { PRACTICE, SINGLE, SERIES }

data class Segment(val kind: SegmentKind, val count: Int, val start: Int = 0, val target: Target? = null)

enum class SeriesFormat(val key: String) {
    S10("s10"), S2X10("s2x10"), P2S6R4("p2s6r4"), P2S5R23("p2s5r23"), OP300("op300"), FS300("fs300"),
    PSTD("pstd"), POP("pop"), PFS("pfs"), AP10S("ap10s"), AP20S("ap20s"), CUSTOM("custom");

    /** Fixed parts of the format; null for custom. */
    val parts: List<Segment>?
        get() {
            fun s(k: SegmentKind, n: Int, t: Target? = null) = Segment(k, n, 0, t)
            val P = SegmentKind.PRACTICE; val E = SegmentKind.SINGLE; val R = SegmentKind.SERIES
            return when (this) {
                S10 -> listOf(s(E, 10))
                S2X10 -> listOf(s(E, 10), s(E, 10))   // two groups of 10 single shots, one total
                P2S6R4 -> listOf(s(P, 2), s(E, 6), s(R, 4))
                P2S5R23 -> listOf(s(P, 2), s(E, 5), s(R, 2), s(R, 3))
                OP300 -> listOf(s(E, 5, Target.A5), s(E, 5, Target.B4), s(R, 2, Target.B4), s(R, 3, Target.B4), s(R, 5, Target.B4))
                FS300 -> listOf(s(E, 6, Target.B4), s(R, 3, Target.B4), s(R, 3, Target.B4), s(R, 6, Target.B4))
                PSTD -> listOf(s(R, 5), s(R, 5), s(R, 5))
                POP -> listOf(s(E, 5), s(R, 5), s(R, 5), s(R, 5))
                PFS -> listOf(s(E, 3), s(R, 5), s(R, 5), s(R, 5))
                AP10S -> listOf(s(E, 10))
                AP20S -> listOf(s(E, 20))
                CUSTOM -> null
            }
        }
    val slots: Int get() = parts?.sumOf { it.count } ?: 10

    companion object { fun of(k: String?) = entries.firstOrNull { it.key == k } }
}

enum class Discipline(val key: String) {
    R300("r300"), P25("p25"), P50("p50"), AP10("ap10"), AG10("ag10"), OTHER("other");

    val formats: List<SeriesFormat>
        get() = when (this) {
            R300 -> listOf(SeriesFormat.S10, SeriesFormat.S2X10, SeriesFormat.P2S6R4, SeriesFormat.P2S5R23, SeriesFormat.OP300, SeriesFormat.FS300, SeriesFormat.CUSTOM)
            P25, P50 -> listOf(SeriesFormat.PSTD, SeriesFormat.POP, SeriesFormat.PFS, SeriesFormat.CUSTOM)
            AP10, AG10 -> listOf(SeriesFormat.AP10S, SeriesFormat.AP20S, SeriesFormat.CUSTOM)
            OTHER -> listOf(SeriesFormat.CUSTOM)
        }
    val defaultFormat: SeriesFormat get() = formats.first()
    /** Pistol and air pistol always score 0–10; the others may pick a target for custom series. */
    val hasTargetChoice: Boolean get() = this == R300 || this == OTHER
    val allowsX: Boolean get() = this == P25 || this == P50

    companion object {
        /** Disciplines saved by earlier web versions carried a target. */
        fun of(k: String?): Discipline = entries.firstOrNull { it.key == k }
            ?: if (k in setOf("r300a10", "r300a100", "r300a5", "r300b4")) R300 else OTHER
    }
}

enum class Category(val key: String) {
    TRAINING("training"), CHAMPIONSHIP("championship"), OTHER("other");
    companion object { fun of(k: String?) = entries.firstOrNull { it.key == k } }
}

data class Stats(val total: Double, val max: Int, val x: Int, val slots: Int, val counted: Int,
                 val practice: Int, val practiceCount: Int, val manual: Boolean)

data class Series(
    val type: String = SeriesFormat.S10.key,
    val target: String? = Target.A10.key,
    val planned: Int? = 10,
    val practice: Int? = 0,
    val shots: List<Shot> = emptyList(),
    val total: Double? = null,
    val tief: Int? = null,
    val desc: String? = null,
) {
    /** The format, with older "10 shots" series of another length treated as custom. */
    val format: SeriesFormat
        get() {
            val f = SeriesFormat.of(type) ?: SeriesFormat.S10
            return if (f == SeriesFormat.S10 && max(planned ?: 10, shots.size) != 10) SeriesFormat.CUSTOM else f
        }
    val targetValue: Target get() = Target.of(target)
    val hasResult: Boolean get() = shots.isNotEmpty() || total != null

    val segments: List<Segment>
        get() {
            format.parts?.let { parts ->
                var start = 0
                return parts.map { p -> p.copy(start = start).also { start += p.count } }
            }
            val p = (practice ?: 0).coerceIn(0, 20)
            val n = max(planned ?: 0, shots.size - p)
            return if (p > 0) listOf(Segment(SegmentKind.PRACTICE, p, 0), Segment(SegmentKind.SINGLE, n, p))
            else listOf(Segment(SegmentKind.SINGLE, n, 0))
        }
    fun segmentAt(i: Int): Segment? = segments.firstOrNull { i >= it.start && i < it.start + it.count }
    fun isPractice(i: Int) = segmentAt(i)?.kind == SegmentKind.PRACTICE
    fun maxValueAt(i: Int): Int = segmentAt(i)?.target?.max ?: targetValue.max

    val stats: Stats
        get() {
            val segs = segments
            val slots = segs.sumOf { it.count }
            val practiceCount = segs.filter { it.kind == SegmentKind.PRACTICE }.sumOf { it.count }
            var sum = 0; var x = 0; var prac = 0
            shots.forEachIndexed { i, s -> if (isPractice(i)) prac += s.points else { sum += s.points; if (s == Shot.X) x++ } }
            val maxSum = segs.filter { it.kind != SegmentKind.PRACTICE }.sumOf { it.count * (it.target?.max ?: targetValue.max) }
            return Stats(total ?: sum.toDouble(), maxSum, x, slots, slots - practiceCount, prac, practiceCount, total != null)
        }
    /** Result converted to 10 shots on A10 (0–100), null when nothing was entered. */
    val normalized: Double?
        get() {
            if (!hasResult) return null
            val s = stats
            if (s.max <= 0) return null
            return (s.total / s.max * 1000).roundToInt() / 10.0
        }
    /** "(7 8) · 10 9 9" with practice shots in brackets. */
    val shotsText: String
        get() = segments.mapNotNull { g ->
            val end = min(shots.size, g.start + g.count)
            if (g.start >= end) null else {
                val v = shots.subList(g.start, end).joinToString(" ") { it.label }
                if (g.kind == SegmentKind.PRACTICE) "($v)" else v
            }
        }.joinToString(" · ")

    /** Keeps every shot within its target; X only where it is allowed. */
    fun clamped(d: Discipline): Series = copy(shots = shots.mapIndexed { j, v ->
        val m = maxValueAt(j)
        if (v == Shot.X) { if (d.allowsX && m == 10 && segmentAt(j)?.target == null) Shot.X else Shot.Value(min(10, m)) }
        else Shot.Value(min(v.points, m))
    })

    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type); put("target", target ?: JSONObject.NULL); put("planned", planned ?: JSONObject.NULL)
        put("practice", practice ?: JSONObject.NULL); put("shots", JSONArray(shots.map { it.toJson() }))
        put("total", total ?: JSONObject.NULL); put("tief", tief ?: JSONObject.NULL)
    }

    companion object {
        fun of(format: SeriesFormat) = Series(type = format.key, planned = format.parts?.sumOf { it.count } ?: 10)
        fun fromJson(o: JSONObject): Series {
            val shots = o.optJSONArray("shots")?.let { a -> (0 until a.length()).map { Shot.fromJson(a.opt(it)) } } ?: emptyList()
            return Series(
                type = o.optStringOrNull("type") ?: SeriesFormat.S10.key,
                target = o.optStringOrNull("target"),
                planned = o.optIntOrNull("planned"),
                practice = o.optIntOrNull("practice"),
                shots = shots,
                total = o.optDoubleOrNull("total"),
                tief = o.optIntOrNull("tief"),
                desc = o.optStringOrNull("desc"),
            )
        }
    }
}

data class Session(
    val id: String = UUID.randomUUID().toString(),
    val date: String = LocalDate.now().toString(),
    val preset: String = Discipline.R300.key,
    val category: String? = Category.TRAINING.key,
    val desc: String? = null,
    val location: String? = null,
    val weapon: String? = null,
    val conditions: String? = null,
    val notes: String? = null,
    val photos: List<String> = emptyList(),
    val series: List<Series> = listOf(Series()),
    val createdAt: Double? = System.currentTimeMillis().toDouble(),
    val updatedAt: Double? = null,
    // legacy fields from earlier web versions
    val legacyMax: Int? = null,
    val legacyTief: Int? = null,
    val program: String? = null,
) {
    val discipline: Discipline get() = Discipline.of(preset)
    val categoryValue: Category
        get() = Category.of(category) ?: if ((program ?: "").lowercase().contains("training")) Category.TRAINING else Category.OTHER
    val descText: String get() = desc ?: series.mapNotNull { it.desc }.filter { it.isNotEmpty() }.joinToString(" · ")
    val year: String get() = if (date.length >= 4) date.substring(0, 4) else "—"
    val day: LocalDate get() = runCatching { LocalDate.parse(date) }.getOrDefault(LocalDate.now())
    val countedShots: Int get() = series.sumOf { it.stats.counted }
    /** Average of the series converted to 10 shots on A10. */
    val normalizedAverage: Double?
        get() {
            val v = series.mapNotNull { it.normalized }
            return if (v.isEmpty()) null else (v.average() * 10).roundToInt() / 10.0
        }

    /** Brings sessions from earlier versions to the current model. */
    fun normalizedForCurrentVersion(): Session {
        val legacyTarget = when (legacyMax) { 100 -> Target.A100; 5 -> Target.A5; 4 -> Target.B4; else -> Target.A10 }
        var list = series.map { s ->
            var r = s.copy(desc = null)
            if (s.target == null) {
                if (legacyTarget != Target.A10 && s.format in setOf(SeriesFormat.S10, SeriesFormat.P2S6R4, SeriesFormat.P2S5R23)) {
                    r = r.copy(planned = max(s.planned ?: 0, s.shots.size), type = SeriesFormat.CUSTOM.key)
                }
                r = r.copy(target = legacyTarget.key)
            }
            r
        }
        if (legacyTief != null && list.isNotEmpty() && list[0].tief == null) list = listOf(list[0].copy(tief = legacyTief)) + list.drop(1)
        return copy(
            preset = discipline.key,
            category = categoryValue.key,
            desc = descText,
            series = list,
            legacyMax = null, legacyTief = null,
        )
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("date", date); put("preset", preset); put("category", category ?: JSONObject.NULL)
        put("desc", desc ?: JSONObject.NULL); put("location", location ?: JSONObject.NULL); put("weapon", weapon ?: JSONObject.NULL)
        put("conditions", conditions ?: JSONObject.NULL); put("notes", notes ?: JSONObject.NULL)
        put("photos", JSONArray(photos)); put("series", JSONArray(series.map { it.toJson() }))
        put("createdAt", createdAt ?: JSONObject.NULL); put("updatedAt", updatedAt ?: JSONObject.NULL)
        if (program != null) put("program", program)
    }

    companion object {
        fun fromJson(o: JSONObject): Session {
            val photos = o.optJSONArray("photos")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it, null) } } ?: emptyList()
            val series = o.optJSONArray("series")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(Series::fromJson) } } ?: emptyList()
            return Session(
                id = o.optStringOrNull("id") ?: UUID.randomUUID().toString(),
                date = o.optStringOrNull("date") ?: LocalDate.now().toString(),
                preset = o.optStringOrNull("preset") ?: Discipline.R300.key,
                category = o.optStringOrNull("category"),
                desc = o.optStringOrNull("desc"),
                location = o.optStringOrNull("location"),
                weapon = o.optStringOrNull("weapon"),
                conditions = o.optStringOrNull("conditions"),
                notes = o.optStringOrNull("notes"),
                photos = photos,
                series = series,
                createdAt = o.optDoubleOrNull("createdAt"),
                updatedAt = o.optDoubleOrNull("updatedAt"),
                legacyMax = o.optIntOrNull("max"),
                legacyTief = o.optIntOrNull("tief"),
                program = o.optStringOrNull("program"),
            )
        }
    }
}

fun JSONObject.optStringOrNull(k: String): String? = if (isNull(k) || !has(k)) null else optString(k)
fun JSONObject.optDoubleOrNull(k: String): Double? = if (isNull(k) || !has(k)) null else optDouble(k).takeIf { !it.isNaN() }
fun JSONObject.optIntOrNull(k: String): Int? = optDoubleOrNull(k)?.roundToInt()
