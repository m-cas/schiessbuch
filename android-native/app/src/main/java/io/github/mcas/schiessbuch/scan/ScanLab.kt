package io.github.mcas.schiessbuch.scan

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Test helper for .github/workflows/scan-lab.yml: reads every photo in the app's external "scanlab" folder
 * with ML Kit and writes the recognized words (and the parsed result) next to it as JSON.
 * Started only by the test run (am start … --ez scanlab true); never used in normal operation.
 */
object ScanLab {
    suspend fun run(context: Context) {
        val dir = context.getExternalFilesDir("scanlab") ?: return
        val out = JSONArray()
        for (f in dir.listFiles().orEmpty().filter { it.name.endsWith(".jpg") }.sortedBy { it.name }) {
            val page = JSONObject().put("file", f.name)
            try {
                val (words, lines) = ScanOcr.recognizeWords(context, Uri.fromFile(f))
                page.put("words", JSONArray(words.map { JSONObject().put("t", it.text).put("x", it.x).put("y", it.y).put("w", it.w).put("h", it.h) }))
                page.put("lines", JSONArray(lines.map { JSONObject().put("t", it.text).put("x", it.x).put("y", it.y).put("w", it.w).put("h", it.h) }))
                val results = Scan.mergeCopies(Scan.columns(words).flatMap { Scan.parseAll(it) })
                page.put("results", JSONArray(results.map { r ->
                    JSONObject().put("program", r.program).put("target", r.targetLabel)
                        .put("shots", JSONArray(r.shots.map { it.value })).put("total", r.total).put("verified", r.isVerified)
                        .put("warnings", JSONArray(r.warnings.map { it.toString() }))
                }))
            } catch (e: Exception) { page.put("error", e.toString()) }
            out.put(page)
        }
        File(dir, "mlkit.json").writeText(out.toString(1))
    }
}
