package io.github.mcas.schiessbuch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.StatFs
import android.util.Base64
import androidx.compose.runtime.mutableStateListOf
import androidx.exifinterface.media.ExifInterface
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.LocalDate
import kotlin.math.max

/**
 * Keeps all sessions in a JSON file and the target photos as JPEG files in the
 * app's own storage on this phone. Nothing is uploaded.
 */
class Store(private val context: Context, demo: Boolean = false) {
    private val base = File(context.filesDir, if (demo) "demo" else "data").apply { mkdirs() }
    private val sessionsFile = File(base, "sessions.json")
    val photosDir = File(base, "photos").apply { mkdirs() }
    val sessions = mutableStateListOf<Session>()
    val champ = ChampStore(File(base, "championship.json"), this)

    init {
        load()
        if (demo && sessions.isEmpty()) seedDemo()
    }

    // ---- sessions

    private fun load() {
        val list = runCatching {
            val a = JSONArray(sessionsFile.readText())
            (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(Session::fromJson)?.normalizedForCurrentVersion() }
        }.getOrDefault(emptyList())
        sessions.clear(); sessions.addAll(sorted(list))
    }
    private fun persist() {
        val tmp = File(base, "sessions.json.tmp")
        tmp.writeText(JSONArray(sessions.map { it.toJson() }).toString())
        tmp.renameTo(sessionsFile)
    }
    private fun sorted(list: List<Session>) = list.sortedWith(compareByDescending<Session> { it.date }.thenByDescending { it.createdAt ?: 0.0 })
    fun session(id: String) = sessions.firstOrNull { it.id == id }

    fun save(s: Session) {
        val updated = s.copy(updatedAt = System.currentTimeMillis().toDouble())
        val list = sessions.filter { it.id != s.id } + updated
        sessions.clear(); sessions.addAll(sorted(list))
        persist()
    }
    fun delete(ids: Set<String>) {
        val gone = sessions.filter { it.id in ids }
        sessions.removeAll { it.id in ids }
        persist()
        gone.forEach { s -> s.photos.forEach(::deletePhoto) }
    }

    // ---- photos

    fun photoFile(id: String) = File(photosDir, "$id.jpg")
    fun loadBitmap(id: String, maxSide: Int = 0): Bitmap? {
        val f = photoFile(id)
        if (!f.exists()) return null
        if (maxSide <= 0) return BitmapFactory.decodeFile(f.path)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
    /** Stores a photo from the camera or gallery, upright and at most 2000 px on the longer side. */
    fun addPhoto(uri: Uri): String? = runCatching {
        val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 2000) sample *= 2
        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })!!
        val rotation = when (ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (rotation != 0f) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation) }, true)
        val side = max(bmp.width, bmp.height)
        if (side > 2000) {
            val k = 2000f / side
            bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt(), (bmp.height * k).toInt(), true)
        }
        val id = java.util.UUID.randomUUID().toString().replace("-", "")
        photoFile(id).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        id
    }.getOrNull()
    fun deletePhoto(id: String) { photoFile(id).delete() }

    // ---- storage

    data class StorageInfo(val photoBytes: Long, val photoCount: Int, val dataBytes: Long, val free: Long, val backupBytes: Long)
    fun storageInfo(): StorageInfo {
        val used = sessions.flatMap { it.photos }.toSet().map(::photoFile).filter { it.exists() }
        val bytes = used.sumOf { it.length() }
        val data = sessionsFile.length()
        val free = runCatching { StatFs(base.path).availableBytes }.getOrDefault(0L)
        return StorageInfo(bytes, used.size, data, free, bytes * 4 / 3 + data)
    }

    // ---- backup (same format as the web app)

    fun writeBackup(out: java.io.OutputStream) {
        out.bufferedWriter().use { w ->
            w.write("{\"app\":\"scorebook\",\"version\":1,\"exported\":")
            w.write(JSONObject.quote(Instant.now().toString()))
            w.write(",\"sessions\":")
            w.write(JSONArray(sessions.map { it.toJson() }).toString())
            w.write(",\"photos\":[")
            var first = true
            for (id in sessions.flatMap { it.photos }.toSet()) {
                val f = photoFile(id)
                if (!f.exists()) continue
                if (!first) w.write(",")
                first = false
                w.write("{\"id\":"); w.write(JSONObject.quote(id))
                w.write(",\"data\":\"data:image/jpeg;base64,")
                w.write(Base64.encodeToString(f.readBytes(), Base64.NO_WRAP))
                w.write("\"}")
            }
            w.write("]")
            if (!champ.state.isEmpty) { w.write(",\"championship\":"); w.write(champ.state.toJson().toString()) }
            w.write("}")
        }
    }
    fun backupFile(name: String): File {
        val f = File(shareDir(), "$name.json")
        f.outputStream().use(::writeBackup)
        return f
    }

    class NotABackup : Exception()
    /** Adds the sessions and photos of a backup; sessions with the same id are replaced. */
    fun restore(uri: Uri): Int {
        val text = context.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
        val o = runCatching { JSONObject(text) }.getOrNull()
        if (o == null || o.optString("app") != "scorebook" || o.optJSONArray("sessions") == null) throw NotABackup()
        o.optJSONArray("photos")?.let { a ->
            for (i in 0 until a.length()) {
                val p = a.optJSONObject(i) ?: continue
                val id = p.optString("id"); val data = p.optString("data")
                val comma = data.indexOf(',')
                if (id.isEmpty() || comma < 0) continue
                runCatching { photoFile(id).writeBytes(Base64.decode(data.substring(comma + 1), Base64.DEFAULT)) }
            }
        }
        val incoming = o.getJSONArray("sessions").let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(Session::fromJson)?.normalizedForCurrentVersion() } }
        val byId = LinkedHashMap<String, Session>()
        sessions.forEach { byId[it.id] = it }
        incoming.forEach { byId[it.id] = it }
        sessions.clear(); sessions.addAll(sorted(byId.values.toList()))
        persist()
        val c = ChampState.fromJson(o.optJSONObject("championship"))
        if (!c.isEmpty) champ.set(if (c.url.isEmpty()) c.copy(url = champ.state.url) else c)
        return incoming.size
    }

    // ---- export and email texts

    fun shareDir() = File(context.cacheDir, "share").apply { mkdirs() }

    fun csvFile(tx: Tx): File {
        fun q(v: String) = "\"" + v.replace("\"", "\"\"") + "\""
        val lines = mutableListOf(tx.csvHead.joinToString(","))
        for (s in sessions) s.series.forEachIndexed { i, r ->
            val st = r.stats
            lines += listOf(s.date, tx.disciplineName(s.discipline), tx.categoryName(s.categoryValue), s.descText, s.location ?: "", s.weapon ?: "", s.conditions ?: "",
                (i + 1).toString(), tx.seriesLabel(r, s.discipline), if (r.hasResult) tx.score(st.total) else "", st.max.toString(),
                r.normalized?.let(tx::score) ?: "", st.x.toString(), r.tief?.toString() ?: "", r.shotsText, s.notes ?: "").joinToString(",") { q(it) }
        }
        val f = File(shareDir(), "${tx.csvFile}-${LocalDate.now()}.csv")
        f.writeText("﻿" + lines.joinToString("\n"))
        return f
    }

    fun mailText(list: List<Session>, tx: Tx): Pair<String, String> {
        val xs = list.sortedBy { it.date }
        val subject = if (xs.size == 1) tx.mailSubject(tx.longDate(xs[0].day)) else tx.mailSubjectN(xs.size, tx.longDate(xs.first().day), tx.longDate(xs.last().day))
        val parts = xs.map { s ->
            val L = mutableListOf("${tx.longDate(s.day)} — ${tx.categoryName(s.categoryValue)}", "${tx.discipline}: ${tx.disciplineName(s.discipline)}")
            if (s.descText.isNotEmpty()) L += "${tx.description}: ${s.descText}"
            s.location?.takeIf { it.isNotEmpty() }?.let { L += "${tx.range}: $it" }
            s.weapon?.takeIf { it.isNotEmpty() }?.let { L += "${tx.weapon}: $it" }
            s.conditions?.takeIf { it.isNotEmpty() }?.let { L += "${tx.conditions}: $it" }
            s.series.forEachIndexed { i, r ->
                val st = r.stats
                var line = "  ${tx.seriesTitle(i + 1)} [${tx.seriesLabel(r, s.discipline)}]: ${if (r.shots.isEmpty()) (if (st.manual) tx.totalOnly else "—") else r.shotsText} = ${tx.score(st.total)}/${st.max}"
                if (st.x > 0) line += " · ${st.x} X"
                r.tief?.let { line += " · ${tx.tief} $it" }
                if (st.practiceCount > 0 && r.shots.isNotEmpty()) line += " (${tx.mPractice(st.practice)})"
                L += line
            }
            s.notes?.takeIf { it.isNotEmpty() }?.let { L += "${tx.notes}: $it" }
            if (s.photos.isNotEmpty()) L += "${tx.targetPhotos}: ${s.photos.size}"
            L.joinToString("\n")
        }
        return subject to "${tx.mailGreeting}\n\n${parts.joinToString("\n\n")}\n\n"
    }

    // ---- sample data for screenshots

    private fun seedDemo() {
        data class Row(val date: String, val d: Discipline, val c: Category, val desc: String, val series: List<List<Int>>)
        val rows = listOf(
            Row("2026-10-28", Discipline.R300, Category.TRAINING, "Obligatorisches", listOf(listOf(10, 9, 9, 10, 8, 9, 10, 9, 9, 10), listOf(9, 8, 9, 10, 9, 9, 8, 10, 9, 9))),
            Row("2026-09-12", Discipline.R300, Category.CHAMPIONSHIP, "Vereinsmeisterschaft", listOf(listOf(10, 10, 9, 10, 9, 9, 10, 10, 9, 10))),
            Row("2026-06-03", Discipline.P25, Category.TRAINING, "", listOf(listOf(10, 9, 8, 9, 10, 9, 9, 10, 8, 9, 10, 10, 9, 9, 8))),
            Row("2025-09-20", Discipline.R300, Category.OTHER, "Feldschiessen", listOf(listOf(9, 9, 8, 9, 10, 9, 8, 9, 9, 8))),
            Row("2025-06-14", Discipline.R300, Category.TRAINING, "Sommertraining", listOf(listOf(8, 9, 9, 8, 9, 10, 9, 8, 9, 9), listOf(9, 9, 9, 10, 8, 9, 9, 9, 8, 9))),
        )
        val list = rows.map { r ->
            Session(date = r.date, preset = r.d.key, category = r.c.key, desc = r.desc,
                series = r.series.map { shots -> Series.of(r.d.defaultFormat).copy(shots = shots.map { Shot.Value(it) }) })
        }
        sessions.clear(); sessions.addAll(sorted(list))
    }
}

fun Bitmap.jpegBytes(q: Int = 85): ByteArray = ByteArrayOutputStream().also { compress(Bitmap.CompressFormat.JPEG, q, it) }.toByteArray()
