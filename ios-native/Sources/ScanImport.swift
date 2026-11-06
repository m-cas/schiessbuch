import SwiftUI
import ImageIO
import UIKit

/// Turns results read from a photo of the range printout into a new session (one series per printout).
/// Known 300 m programs get their format; anything else becomes a custom series. Same rules as the web app.
enum ScanImport {
    /// Date: printed on the strip, else the day the photo was taken (`photoDay`, "YYYY-MM-DD"), else today.
    static func session(from results: [ShootingResult], base: Session, tx: Tx, photoDay: String? = nil) -> (session: Session, warnings: [String]) {
        var s = base
        s.preset = Discipline.r300.rawValue
        let r0 = results[0]
        if let p = photoDay { s.date = p }
        if let d = r0.date, let day = isoDay(d) { s.date = day }
        s.series = results.map(series)
        let desc = r0.competition ?? r0.event ?? ""
        if !desc.isEmpty { s.desc = desc }
        if let w = r0.weapon { s.weapon = w }
        let labels = results.compactMap { r -> String? in
            let pg = r.program.flatMap { $0.allSatisfy(\.isNumber) ? "Pg \($0)" : nil }
            let target = r.targetLabel.map { pg == nil ? "\(tx.scanTarget) \($0)" : $0 }
            let parts = [pg, target].compactMap { $0 }
            return parts.isEmpty ? nil : parts.joined(separator: " · ")
        }
        s.notes = tx.scanNote(labels.joined(separator: "; "))
        let warnings = results.enumerated().flatMap { i, r in
            r.warnings.map { (results.count > 1 ? "\(i + 1): " : "") + text($0, tx) }
        }
        return (s, warnings)
    }

    /// The day a photo was taken, from its EXIF data ("2026:07:02 17:50:12" → "2026-07-02").
    static func photoDay(_ data: Data) -> String? {
        guard let src = CGImageSourceCreateWithData(data as CFData, nil),
              let props = CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [CFString: Any] else { return nil }
        let exif = props[kCGImagePropertyExifDictionary] as? [CFString: Any]
        let tiff = props[kCGImagePropertyTIFFDictionary] as? [CFString: Any]
        guard let when = (exif?[kCGImagePropertyExifDateTimeOriginal] ?? tiff?[kCGImagePropertyTIFFDateTime]) as? String,
              when.count >= 10, !when.hasPrefix("0000") else { return nil }
        return String(when.prefix(10)).replacingOccurrences(of: ":", with: "-")
    }

    /// "19.9.2026" → "2026-09-19"
    static func isoDay(_ d: String) -> String? {
        let p = d.split(whereSeparator: { "./-".contains($0) }).compactMap { Int($0) }
        guard p.count == 3, (1...31).contains(p[0]), (1...12).contains(p[1]) else { return nil }
        let y = p[2] < 100 ? 2000 + p[2] : p[2]
        return String(format: "%04d-%02d-%02d", y, p[1], p[0])
    }

    static func series(_ r: ShootingResult) -> Series {
        let all = r.shots
        let shots = all.map { Shot.value(Int($0.value.rounded())) }
        let prac = r.sighters.map { Shot.value(Int($0.value.rounded())) }
        let counts = r.series.map(\.shots.count)
        let maxV = shots.map(\.points).max() ?? 0
        // Tiefschuss: printed T/P value, or the best 100-value of the counted shots
        let deep = r.deepShots.map(\.value).max() ?? all.compactMap(\.hundred).max()

        func fixed(_ f: SeriesFormat, _ list: [Shot]) -> Series {
            var x = Series(format: f); x.shots = list; x.tief = deep; return x
        }
        func custom(_ target: Target) -> Series {
            var x = Series(format: .custom)
            x.target = target.rawValue; x.planned = shots.count; x.practice = prac.count
            x.shots = prac + shots
            x.tief = target == .a100 ? nil : deep
            return x
        }
        if r.scoringMode == .hundred { return custom(.a100) }
        // Feldschiessen and Obligatorisch: printed in different groupings (6/3/3/6, 6/6/6 …), so known by shot count and values
        if shots.count == 18, maxV <= 4, prac.isEmpty { return fixed(.fs300, shots) }
        if shots.count == 20, maxV <= 5, prac.isEmpty { return fixed(.op300, shots) }
        if prac.count == 2, counts == [5, 2, 3] { return fixed(.p2s5r23, prac + shots) }
        if prac.count == 2, counts == [6, 4] { return fixed(.p2s6r4, prac + shots) }
        if prac.isEmpty, counts == [6, 4] { return fixed(.s6r4, shots) }
        if prac.isEmpty, counts == [10, 10] { return fixed(.s2x10, shots) }
        if prac.isEmpty, shots.count == 10 { return fixed(.s10, shots) }
        return custom(maxV <= 4 && shots.count >= 6 ? .b4 : .a10)
    }

    static func text(_ w: ResultWarning, _ tx: Tx) -> String {
        let f = ScoreMath.format
        switch w {
        case .noShotsFound: return tx.scanNone
        case let .seriesTotalMismatch(s, p, c): return tx.scanWSeries(s, f(p), f(c))
        case let .seriesHundredMismatch(s, p, c): return tx.scanWSeriesH(s, p, c)
        case let .grandTotalMismatch(p, c): return tx.scanWTotal(f(p), f(c))
        case let .hundredTotalMismatch(p, c): return tx.scanWTotalH(p, c)
        case let .shotValueMismatch(n, v, h): return tx.scanWShot(n, f(v), h)
        case let .missingShots(numbers): return tx.scanWMissing(numbers.map(String.init).joined(separator: ", "))
        case .copiesDisagree: return tx.scanWCopies
        }
    }
}
