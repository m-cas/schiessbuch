import SwiftUI
import UIKit
import WebKit

/// Prints the selected sessions as one full-width A4 report (date, discipline, series table, notes, up to four photos).
@MainActor
final class SessionPrinter: NSObject, WKNavigationDelegate {
    static let shared = SessionPrinter()
    private var web: WKWebView?
    private var jobName = ""

    func print(_ list: [Session], store: Store, tx: Tx, photos: Bool = true) {
        let html = Self.html(list.sorted { $0.date < $1.date }, store: store, tx: tx, photos: photos)
        jobName = "\(tx.appName) – \(tx.printTitle)"
        let w = WKWebView(frame: CGRect(x: 0, y: 0, width: 595, height: 842))
        w.navigationDelegate = self
        web = w
        w.loadHTMLString(html, baseURL: nil)
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        let info = UIPrintInfo(dictionary: nil)
        info.outputType = .general
        info.jobName = jobName
        let pc = UIPrintInteractionController.shared
        pc.printInfo = info
        pc.printFormatter = webView.viewPrintFormatter()
        pc.present(animated: true) { [weak self] _, _, _ in self?.web = nil }
    }

    private static func esc(_ s: String) -> String {
        s.replacingOccurrences(of: "&", with: "&amp;").replacingOccurrences(of: "<", with: "&lt;").replacingOccurrences(of: ">", with: "&gt;").replacingOccurrences(of: "\"", with: "&quot;")
    }

    static func html(_ xs: [Session], store: Store, tx: Tx, photos: Bool = true) -> String {
        let range = xs.count > 1 ? "\(tx.longDate(xs[0].day)) – \(tx.longDate(xs[xs.count - 1].day))" : xs.first.map { tx.longDate($0.day) } ?? ""
        let blocks = xs.map { s -> String in
            var total = 0.0, max = 0
            for r in s.series where r.hasResult { total += r.stats.total; max += r.stats.max }
            let meta = [s.location.map { "\(tx.range): \($0)" }, s.weapon.map { "\(tx.weapon): \($0)" }, s.conditions.map { "\(tx.conditions): \($0)" }]
                .compactMap { $0 }.filter { !$0.hasSuffix(": ") }.joined(separator: " · ")
            let rows = s.series.enumerated().map { i, r -> String in
                let st = r.stats
                let groups = (r.groupTotals.map { g in r.shots.isEmpty ? "" : g.map { "\(tx.groupN($0.n)): \($0.sum)/\($0.max)" }.joined(separator: " · ") }) ?? ""
                let shots = r.shots.isEmpty ? (st.manual ? tx.totalOnly : "—") : r.shotsText
                return "<tr><td class=c>\(i + 1)</td><td>\(esc(tx.seriesLabel(r, discipline: s.discipline)))</td><td>\(esc(shots))\(groups.isEmpty ? "" : "<br><b>\(esc(groups))</b>")</td><td class=c>\(st.x > 0 ? String(st.x) : "")</td><td class=c>\(r.tief.map(String.init) ?? "")</td><td class=r><b>\(tx.score(st.total))</b> / \(st.max)</td></tr>"
            }.joined()
            let pics = (photos ? Array(s.photos.prefix(4)) : []).compactMap { id -> String? in
                guard let img = store.image(id) else { return nil }
                let scale = min(1, 600 / Swift.max(img.size.width, img.size.height))
                let size = CGSize(width: img.size.width * scale, height: img.size.height * scale)
                let small = UIGraphicsImageRenderer(size: size).image { _ in img.draw(in: CGRect(origin: .zero, size: size)) }
                guard let data = small.jpegData(compressionQuality: 0.7) else { return nil }
                return "<img src=\"data:image/jpeg;base64,\(data.base64EncodedString())\">"
            }.joined()
            return """
            <div class=s><div class=h><span>\(esc(tx.longDate(s.day))) · \(esc(tx.categoryName(s.categoryValue))) · \(esc(tx.disciplineName(s.discipline)))</span>\(max > 0 ? "<span>\(tx.printTotal) \(tx.score(total)) / \(max)</span>" : "")</div>
            \(s.descText.isEmpty ? "" : "<p class=d>\(esc(s.descText))</p>")\(meta.isEmpty ? "" : "<p class=m>\(esc(meta))</p>")
            <table><tr><th class=c>#</th><th>\(tx.seriesFormat)</th><th>\(tx.shotsH)</th><th class=c>X</th><th class=c>\(tx.tiefShort)</th><th class=r>\(tx.scoreH)</th></tr>\(rows)</table>
            \((s.notes ?? "").isEmpty ? "" : "<p class=m><b>\(tx.notes):</b> \(esc(s.notes ?? ""))</p>")\(pics.isEmpty ? "" : "<div class=p>\(pics)</div>")</div>
            """
        }.joined()
        return """
        <!doctype html><html><head><meta charset=utf-8><style>
        @page { size: A4; margin: 14mm 12mm; }
        body { font: 10.5pt/1.4 -apple-system, Helvetica, sans-serif; color: #000; margin: 0; }
        .top { display: flex; justify-content: space-between; align-items: baseline; border-bottom: 2px solid #2C5A38; padding-bottom: 4pt; margin-bottom: 10pt; }
        .top b { font-size: 15pt; color: #2C5A38; } .top span { font-size: 9.5pt; color: #444; }
        .s { page-break-inside: avoid; break-inside: avoid; margin: 0 0 12pt; padding-bottom: 8pt; border-bottom: 1px solid #ccc; }
        .h { display: flex; justify-content: space-between; gap: 10pt; font-size: 12pt; font-weight: 700; }
        p { margin: 2pt 0; } .d { font-weight: 600; } .m { color: #333; font-size: 9.5pt; }
        table { width: 100%; border-collapse: collapse; margin: 5pt 0; font-size: 9.5pt; }
        th, td { border: 1px solid #bbb; padding: 3pt 5pt; vertical-align: top; text-align: left; }
        th { background: #E5F0E7; } .c { text-align: center; width: 1%; white-space: nowrap; } .r { text-align: right; width: 1%; white-space: nowrap; }
        .p { display: flex; gap: 6pt; margin-top: 5pt; } .p img { width: 24%; height: 42mm; object-fit: cover; border: 1px solid #bbb; }
        </style></head><body><div class=top><b>\(tx.appName) · \(tx.printTitle)</b><span>\(esc(range)) · \(tx.sessionsN(xs.count))</span></div>\(blocks)</body></html>
        """
    }
}
