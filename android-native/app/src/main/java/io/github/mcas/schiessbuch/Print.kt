package io.github.mcas.schiessbuch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.print.PrintAttributes
import android.print.PrintManager
import android.util.Base64
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayOutputStream

/** Prints the selected sessions as one full-width A4 report through the Android print dialog. */
object SessionPrinter {
    private var web: WebView? = null   // kept until the print job has taken the page

    fun print(context: Context, store: Store, list: List<Session>, tx: Tx) {
        val html = html(list.sortedBy { it.date }, store, tx)
        val w = WebView(context)
        web = w
        w.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                val pm = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
                val name = "${tx.appName} – ${tx.printTitle}"
                pm.print(name, view.createPrintDocumentAdapter(name),
                    PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build())
                web = null
            }
        }
        w.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun photo(store: Store, id: String): String? {
        val f = store.photoFile(id)
        if (!f.exists()) return null
        val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
        val b = BitmapFactory.decodeFile(f.path, opts) ?: return null
        val out = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.JPEG, 70, out)
        return "<img src=\"data:image/jpeg;base64,${Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)}\">"
    }

    fun html(xs: List<Session>, store: Store, tx: Tx): String {
        val range = if (xs.size > 1) "${tx.longDate(xs.first().day)} – ${tx.longDate(xs.last().day)}" else xs.firstOrNull()?.let { tx.longDate(it.day) } ?: ""
        val blocks = xs.joinToString("") { s ->
            var total = 0.0; var max = 0
            s.series.filter { it.hasResult }.forEach { total += it.stats.total; max += it.stats.max }
            val meta = listOfNotNull(
                s.location?.takeIf { it.isNotEmpty() }?.let { "${tx.range}: $it" },
                s.weapon?.takeIf { it.isNotEmpty() }?.let { "${tx.weapon}: $it" },
                s.conditions?.takeIf { it.isNotEmpty() }?.let { "${tx.conditions}: $it" },
            ).joinToString(" · ")
            val rows = s.series.mapIndexed { i, r ->
                val st = r.stats
                val groups = if (r.shots.isEmpty()) "" else r.groupTotals?.joinToString(" · ") { "${tx.groupN(it.n)}: ${it.sum}/${it.max}" } ?: ""
                val shots = if (r.shots.isEmpty()) (if (st.manual) tx.totalOnly else "—") else r.shotsText
                "<tr><td class=c>${i + 1}</td><td>${esc(tx.seriesLabel(r, s.discipline))}</td><td>${esc(shots)}${if (groups.isEmpty()) "" else "<br><b>${esc(groups)}</b>"}</td>" +
                    "<td class=c>${if (st.x > 0) st.x else ""}</td><td class=c>${r.tief ?: ""}</td><td class=r><b>${tx.score(st.total)}</b> / ${st.max}</td></tr>"
            }.joinToString("")
            val pics = s.photos.take(4).mapNotNull { photo(store, it) }.joinToString("")
            "<div class=s><div class=h><span>${esc(tx.longDate(s.day))} · ${esc(tx.categoryName(s.categoryValue))} · ${esc(tx.disciplineName(s.discipline))}</span>" +
                (if (max > 0) "<span>${tx.printTotal} ${tx.score(total)} / $max</span>" else "") + "</div>" +
                (if (s.descText.isEmpty()) "" else "<p class=d>${esc(s.descText)}</p>") +
                (if (meta.isEmpty()) "" else "<p class=m>${esc(meta)}</p>") +
                "<table><tr><th class=c>#</th><th>${tx.seriesFormat}</th><th>${tx.shotsH}</th><th class=c>X</th><th class=c>${tx.tiefShort}</th><th class=r>${tx.scoreH}</th></tr>$rows</table>" +
                (s.notes?.takeIf { it.isNotEmpty() }?.let { "<p class=m><b>${tx.notes}:</b> ${esc(it)}</p>" } ?: "") +
                (if (pics.isEmpty()) "" else "<div class=p>$pics</div>") + "</div>"
        }
        return """<!doctype html><html><head><meta charset=utf-8><style>
@page { size: A4; margin: 14mm 12mm; }
body { font: 10.5pt/1.4 sans-serif; color: #000; margin: 0; }
.top { display: flex; justify-content: space-between; align-items: baseline; border-bottom: 2px solid #2C5A38; padding-bottom: 4pt; margin-bottom: 10pt; }
.top b { font-size: 15pt; color: #2C5A38; } .top span { font-size: 9.5pt; color: #444; }
.s { page-break-inside: avoid; break-inside: avoid; margin: 0 0 12pt; padding-bottom: 8pt; border-bottom: 1px solid #ccc; }
.h { display: flex; justify-content: space-between; gap: 10pt; font-size: 12pt; font-weight: 700; }
p { margin: 2pt 0; } .d { font-weight: 600; } .m { color: #333; font-size: 9.5pt; }
table { width: 100%; border-collapse: collapse; margin: 5pt 0; font-size: 9.5pt; }
th, td { border: 1px solid #bbb; padding: 3pt 5pt; vertical-align: top; text-align: left; }
th { background: #E5F0E7; } .c { text-align: center; width: 1%; white-space: nowrap; } .r { text-align: right; width: 1%; white-space: nowrap; }
.p { display: flex; gap: 6pt; margin-top: 5pt; } .p img { width: 24%; height: 42mm; object-fit: cover; border: 1px solid #bbb; }
</style></head><body><div class=top><b>${tx.appName} · ${tx.printTitle}</b><span>${esc(range)} · ${tx.sessionsN(xs.size)}</span></div>$blocks</body></html>"""
    }
}

/** Shows the launcher icon that matches the logo choice (two activity aliases, only one enabled). */
fun Context.applyLauncherIcon(club: Boolean) {
    val pm = packageManager
    fun set(alias: String, on: Boolean) = pm.setComponentEnabledSetting(
        android.content.ComponentName(this, "${MainActivity::class.java.name.substringBeforeLast('.')}.$alias"),
        if (on) android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED else android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
        android.content.pm.PackageManager.DONT_KILL_APP,
    )
    set("LauncherClub", club)
    set("LauncherDefault", !club)
}
