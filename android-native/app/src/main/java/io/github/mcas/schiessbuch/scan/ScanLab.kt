package io.github.mcas.schiessbuch.scan

import android.content.Context
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Test helper for .github/workflows/scan-lab.yml: reads every photo in the app's external "scanlab" folder
 * with the trained printout reader (assets/reader.*) exactly as the app does and writes the words as JSON,
 * so the Kotlin port can be compared with the reference (tools/reader/infer.py).
 * Started only by the test run (am start … --ez scanlab true); never used in normal operation.
 */
object ScanLab {
    fun run(context: Context) {
        val dir = context.getExternalFilesDir("scanlab") ?: return
        val reader = ScanImport.reader(context)
        val out = JSONArray()
        for (f in dir.listFiles().orEmpty().filter { it.name.endsWith(".jpg") }.sortedBy { it.name }) {
            val page = JSONObject().put("file", f.name)
            try {
                val bmp = BitmapFactory.decodeFile(f.path)
                val t0 = System.currentTimeMillis()
                val words = reader?.read(ScanImport.gray(bmp), bmp.width, bmp.height).orEmpty()
                page.put("ms", System.currentTimeMillis() - t0)
                page.put("words", JSONArray(words.map { JSONObject().put("t", it.text).put("x", it.x).put("y", it.y).put("w", it.w).put("h", it.h) }))
            } catch (e: Throwable) { page.put("error", e.toString()) }
            out.put(page)
        }
        File(dir, "reader.json").writeText(out.toString(1))
    }
}
