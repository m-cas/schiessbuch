package io.github.mcas.schiessbuch.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import io.github.mcas.schiessbuch.Discipline
import io.github.mcas.schiessbuch.Series
import io.github.mcas.schiessbuch.SeriesFormat
import io.github.mcas.schiessbuch.Session
import io.github.mcas.schiessbuch.Shot
import io.github.mcas.schiessbuch.Target
import io.github.mcas.schiessbuch.Tx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.roundToInt

/** Reads a photo of the range printout on the device (ML Kit text recognition, no network). */
object ScanOcr {
    /** Words (and whole lines) recognized on the photo, boxes normalized with the origin at the bottom left. */
    suspend fun recognizeWords(context: Context, uri: Uri): Pair<List<Word>, List<Word>> = withContext(Dispatchers.Default) {
        val bmp = prepare(load(context, uri) ?: error("unreadable"))
        val blocks = recognize(bmp)
        blocks.flatMap { block -> block.lines.flatMap { line -> line.elements.map { word(it.text, it.boundingBox, bmp) } } }.filterNotNull() to
            blocks.flatMap { block -> block.lines.map { word(it.text, it.boundingBox, bmp) } }.filterNotNull()
    }

    @Volatile private var reader: Reader? = null

    /** The trained printout reader (assets/reader.json + reader.bin), null if the model is missing. */
    fun reader(context: Context): Reader? = reader ?: runCatching {
        Reader(context.assets.open("reader.json").use { String(it.readBytes()) }, context.assets.open("reader.bin").use { it.readBytes() })
    }.getOrNull()?.also { reader = it }

    /** Luminance 0..255 (as OpenCV computes it) */
    fun gray(bmp: Bitmap): FloatArray {
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return FloatArray(px.size) { val c = px[it]; 0.299f * ((c shr 16) and 255) + 0.587f * ((c shr 8) and 255) + 0.114f * (c and 255) }
    }

    suspend fun read(context: Context, uri: Uri): List<ScanResult> = withContext(Dispatchers.Default) {
        val original = load(context, uri) ?: error("unreadable")
        // the trained printout reader first (made for dot-matrix strips), ML Kit when it finds nothing
        var results = reader(context)?.let { r ->
            val g = gray(original)
            val parse = { gr: FloatArray -> Scan.mergeCopies(Scan.columns(r.read(gr, original.width, original.height)).flatMap { Scan.parseAll(it) }) }
            val first = parse(g)
            // nothing adds up: maybe a strip lies upside down on the photo, read it turned by 180° too
            if (first.isNotEmpty() && first.none { it.isVerified }) Scan.addTurned(first, parse(g.reversedArray())) else first
        } ?: emptyList()
        val bmp = prepare(original)
        if (results.isEmpty()) {
            val words = recognize(bmp).flatMap { block -> block.lines.flatMap { line -> line.elements.map { word(it.text, it.boundingBox, bmp) } } }.filterNotNull()
            results = Scan.mergeCopies(Scan.columns(words).flatMap { Scan.parseAll(it) })
        }
        // name, club and weapon printed sideways on SIUS-style strips (Pg strips have none)
        if (results.isNotEmpty() && results.any { r -> r.program?.all { it.isDigit() } != true }) {
            val heads = listOf(90f, -90f).map { deg ->
                val turned = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg) }, true)
                Scan.parseHeader(Scan.rows(recognize(turned).flatMap { b -> b.lines.mapNotNull { word(it.text, it.boundingBox, turned) } }))
            }
            heads.maxBy { it.score }.takeIf { it.score > 0 }?.let { Scan.applyHeader(it, results) }
        }
        results
    }

    private fun word(text: String, box: android.graphics.Rect?, bmp: Bitmap): Word? {
        if (box == null || text.isBlank()) return null
        val w = bmp.width.toDouble(); val h = bmp.height.toDouble()
        return Word(text.trim(), box.left / w, 1 - box.bottom / h, box.width() / w, box.height() / h)
    }

    private suspend fun recognize(bmp: Bitmap): List<Text.TextBlock> = suspendCancellableCoroutine { cont ->
        val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        client.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { cont.resume(it.textBlocks); client.close() }
            .addOnFailureListener { cont.resumeWithException(it); client.close() }
    }

    /** Upright, at most 2400 px on the longer side. */
    private fun load(context: Context, uri: Uri): Bitmap? {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 2400) sample *= 2
        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val rotation = when (ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (rotation != 0f) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation) }, true)
        return bmp
    }

    /** Grey and more contrast, so blue, yellow and grey paper look the same to the text recognition. */
    private fun prepare(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = 1.35f; val t = (1 - c) * 128
        val m = ColorMatrix().apply { setSaturation(0f) }
        m.postConcat(ColorMatrix(floatArrayOf(c, 0f, 0f, 0f, t, 0f, c, 0f, 0f, t, 0f, 0f, c, 0f, t, 0f, 0f, 0f, 1f, 0f)))
        Canvas(out).drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(m) })
        return out
    }
}

/** Turns scan results into a new session: one series per printout, known 300 m programs get their format. */
object ScanImport {
    /**
     * The day the photo was taken ("YYYY-MM-DD"): EXIF date of the picture, else the file's date, else null.
     * Used when the strip has no date printed.
     */
    fun photoDay(context: Context, uri: Uri): String? {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { ins ->
                val ex = ExifInterface(ins)
                val w = ex.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: ex.getAttribute(ExifInterface.TAG_DATETIME)
                if (w != null && w.length >= 10 && !w.startsWith("0000")) return w.substring(0, 10).replace(':', '-')
            }
        }
        val ms = runCatching {
            if (uri.scheme == "file") java.io.File(uri.path!!).lastModified()
            else context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use 0L
                listOf("datetaken", "date_taken_ms", "date_modified", "last_modified").firstNotNullOfOrNull { col ->
                    c.getColumnIndex(col).takeIf { it >= 0 }?.let { c.getLong(it) }?.takeIf { it > 0 }
                        ?.let { if (col == "date_modified") it * 1000 else it }
                } ?: 0L
            } ?: 0L
        }.getOrDefault(0L)
        if (ms <= 0) return null
        return java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
    }

    /** Date: printed on the strip, else the day the photo was taken ([photoDay]), else today. */
    fun session(results: List<ScanResult>, base: Session, tx: Tx, photoDay: String? = null): Pair<Session, List<String>> {
        val r0 = results.first()
        // only the program goes into the notes; target and running number ("21/0002") are not needed
        val labels = results.mapNotNull { r -> r.program?.takeIf { p -> p.isNotEmpty() && p.all { it.isDigit() } }?.let { "Pg $it" } }.distinct()
        val s = base.copy(
            preset = Discipline.R300.key,
            date = r0.date?.let(::isoDay) ?: photoDay ?: base.date,
            series = results.map(::series),
            desc = (r0.competition ?: r0.event)?.takeIf { it.isNotBlank() } ?: base.desc,
            weapon = r0.weapon ?: base.weapon,
            notes = tx.scanNote(labels.joinToString("; ")),
        )
        val warnings = results.flatMapIndexed { i, r -> r.warnings.map { (if (results.size > 1) "${i + 1}: " else "") + text(it, tx) } }
        return s to warnings
    }

    /** "19.9.2026" → "2026-09-19" */
    fun isoDay(d: String): String? {
        val p = d.split('.', '/', '-').mapNotNull { it.toIntOrNull() }
        if (p.size != 3 || p[0] !in 1..31 || p[1] !in 1..12) return null
        val y = if (p[2] < 100) 2000 + p[2] else p[2]
        return "%04d-%02d-%02d".format(y, p[1], p[0])
    }

    fun series(r: ScanResult): Series {
        val all = r.shots
        val shots = all.map { Shot.Value(it.value.roundToInt()) }
        val prac = r.sighters.map { Shot.Value(it.value.roundToInt()) }
        val counts = r.series.map { it.shots.size }
        val maxV = shots.maxOfOrNull { it.v } ?: 0
        // Tiefschuss: printed T/P value, or the best 100-value of the counted shots
        val deep = r.deepShots.maxOfOrNull { it.value } ?: all.mapNotNull { it.hundred }.maxOrNull()
        fun fixed(f: SeriesFormat, list: List<Shot>) = Series(type = f.key, target = Target.A10.key, planned = f.parts?.sumOf { it.count } ?: list.size, practice = 0, shots = list, tief = deep)
        fun custom(t: Target) = Series(type = SeriesFormat.CUSTOM.key, target = t.key, planned = shots.size, practice = prac.size, shots = prac + shots, tief = if (t == Target.A100) null else deep)
        return when {
            r.scoringMode == ScoringMode.HUNDRED -> custom(Target.A100)
            // Feldschiessen and Obligatorisch: printed in different groupings (6/3/3/6, 6/6/6 …), so known by shot count and values
            shots.size == 18 && maxV <= 4 && prac.isEmpty() -> fixed(SeriesFormat.FS300, shots)
            shots.size == 20 && maxV <= 5 && prac.isEmpty() -> fixed(SeriesFormat.OP300, shots)
            prac.size == 2 && counts == listOf(5, 2, 3) -> fixed(SeriesFormat.P2S5R23, prac + shots)
            prac.size == 2 && counts == listOf(6, 4) -> fixed(SeriesFormat.P2S6R4, prac + shots)
            prac.isEmpty() && counts == listOf(6, 4) -> fixed(SeriesFormat.S6R4, shots)
            prac.isEmpty() && counts == listOf(10, 10) -> fixed(SeriesFormat.S2X10, shots)
            prac.isEmpty() && shots.size == 10 -> fixed(SeriesFormat.S10, shots)
            else -> custom(if (maxV <= 4 && shots.size >= 6) Target.B4 else Target.A10)
        }
    }

    fun text(w: ScanWarning, tx: Tx): String {
        val f = Scan::fmt
        return when (w) {
            ScanWarning.NoShots -> tx.scanNone
            is ScanWarning.SeriesTotal -> tx.scanWSeries(w.series, f(w.printed), f(w.computed))
            is ScanWarning.SeriesHundred -> tx.scanWSeriesH(w.series, w.printed, w.computed)
            is ScanWarning.GrandTotal -> tx.scanWTotal(f(w.printed), f(w.computed))
            is ScanWarning.HundredTotal -> tx.scanWTotalH(w.printed, w.computed)
            is ScanWarning.ShotValue -> tx.scanWShot(w.shot, f(w.value), w.hundred)
            is ScanWarning.Missing -> tx.scanWMissing(w.numbers.joinToString(", "))
            ScanWarning.Copies -> tx.scanWCopies
        }
    }
}
