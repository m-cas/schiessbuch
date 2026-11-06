// Taken from the ShootingResultScanner package (reads photos of range result strips).
// Types renamed (Shot → ScanShot, Series → ScanSeries) to avoid clashing with the app model.
import CoreGraphics
import Foundation

/// A piece of recognized text with its position.
/// `box` is normalized (0…1) with the origin at the bottom-left, as Vision reports it.
public struct TextFragment: Sendable {
    public var text: String
    public var box: CGRect
    public var confidence: Float

    public init(text: String, box: CGRect, confidence: Float = 1) {
        self.text = text
        self.box = box
        self.confidence = confidence
    }
}

/// Rebuilds printed lines from word positions.
///
/// Pg printers print each result twice side by side, and a photo can show several strips.
/// Every "Pg …" header starts a column; words are assigned to the column they start in,
/// so the copies are never mixed into one line. A larger vertical gap becomes an empty
/// line, which the parser uses as a series break.
enum LayoutAnalyzer {
    static func columns(from words: [TextFragment]) -> [[String]] {
        guard !words.isEmpty else { return [] }
        let starts = columnStarts(words)
        var buckets = Array(repeating: [TextFragment](), count: starts.count)
        for word in words {
            let i = starts.lastIndex { word.box.minX >= $0 } ?? 0
            buckets[i].append(word)
        }
        // a copy whose header was misread is still found by its repeating rows
        for _ in 0..<6 {
            var changed = false
            buckets = buckets.flatMap { b -> [[TextFragment]] in
                guard let x = copySplit(b) else { return [b] }
                let l = b.filter { $0.box.minX < x }, r = b.filter { $0.box.minX >= x }
                // both sides must look like a printout: at least 3 rows with two or more numbers
                func full(_ ws: [TextFragment]) -> Bool {
                    rows(ws).filter { line in line.split(separator: " ").filter { $0.contains(where: \.isNumber) }.count >= 2 }.count >= 3
                }
                if l.isEmpty || r.isEmpty || !full(l) || !full(r) { return [b] }
                changed = true
                return [l, r]
            }
            if !changed { break }
        }
        return buckets.map(rows).filter { !$0.isEmpty }
    }

    /// Copies printed side by side repeat every row at the same horizontal distance (same text, same height).
    /// Returns the x where the right copy starts, or nil. Needs that pattern in at least half the rows, so a
    /// value equal to its shot number ("9 > 9") in a single copy doesn't count. Same rules as scan.js.
    static func copySplit(_ words: [TextFragment]) -> CGFloat? {
        guard words.count >= 8 else { return nil }
        var pairs: [(a: TextFragment, dx: CGFloat)] = []
        for a in words {
            // digits only: arrows and smudges repeat too
            for b in words where b.box.minX > a.box.minX && a.text == b.text && a.text.contains(where: \.isNumber) {
                if abs(a.box.midY - b.box.midY) < 0.5 * min(a.box.height, b.box.height) {
                    pairs.append((a, b.box.minX - a.box.minX))
                }
            }
        }
        guard pairs.count >= 4 else { return nil }
        var bins: [Int: Int] = [:]
        for p in pairs {
            let k = Int((p.dx / 0.01).rounded())
            for j in (k - 1)...(k + 1) { bins[j, default: 0] += 1 }
        }
        guard let best = bins.max(by: { $0.value < $1.value })?.key else { return nil }
        let near = pairs.filter { abs($0.dx - CGFloat(best) * 0.01) <= 0.015 }
        guard !near.isEmpty else { return nil }
        let dx = near.map(\.dx).reduce(0, +) / CGFloat(near.count)
        let rowCount = rows(words).filter { !$0.isEmpty }.count
        let rowMids = Set(near.map { Int(($0.a.box.midY / max(0.004, $0.a.box.height * 0.8)).rounded()) })
        guard near.count >= 4, Double(rowMids.count) >= max(4, 0.5 * Double(rowCount)) else { return nil }
        let left = near.map(\.a.box.minX).min()!
        // split in the middle of the widest empty strip just before where the right copy lines up
        let lo = left + dx - 0.15, hi = left + dx + 0.01
        let iv = words.filter { $0.box.maxX > lo && $0.box.minX < hi }
            .map { (max(lo, $0.box.minX), min(hi, $0.box.maxX)) }.sorted { $0.0 < $1.0 }
        var bestX = hi - 0.01, bestW: CGFloat = 0, cur = lo
        for (x0, x1) in iv {
            if x0 - cur > bestW { bestX = (cur + x0) / 2; bestW = x0 - cur }
            cur = max(cur, x1)
        }
        if hi - cur > bestW { bestX = (cur + hi) / 2 }
        return bestX
    }

    /// Lines in reading order (for the sideways header pass, where Vision lines are enough).
    static func readingOrder(_ lines: [TextFragment]) -> [String] {
        rows(lines)
    }

    static func columnStarts(_ words: [TextFragment]) -> [CGFloat] {
        let anchors = words.filter { isColumnAnchor($0, among: words) }.map(\.box.minX).sorted()
        var clusters: [CGFloat] = []
        for x in anchors where clusters.last.map({ x - $0 >= 0.06 }) ?? true {
            clusters.append(x)
        }
        guard clusters.count > 1 else { return [0] }
        var starts = clusters.map { max(0, $0 - 0.03) }
        starts[0] = 0
        return starts
    }

    /// "Pg" / "Pg191", or "P9"/"Ps" followed by a "21/0002" label on the same line.
    static func isColumnAnchor(_ word: TextFragment, among words: [TextFragment]) -> Bool {
        let t = word.text
        if t.range(of: #"^[Pp][gq]\d{0,3}$"#, options: .regularExpression) != nil { return true }
        guard t.range(of: #"^[PF][9s]\d{0,3}$"#, options: .regularExpression) != nil else { return false }
        return words.contains { v in
            v.box.minX > word.box.maxX && v.box.minX - word.box.maxX < 0.35
                && abs(v.box.midY - word.box.midY) < word.box.height
                && v.text.range(of: #"^\d{1,3}/\d{2,4}$"#, options: .regularExpression) != nil
        }
    }

    static func rows(_ fragments: [TextFragment]) -> [String] {
        let sorted = fragments.sorted { $0.box.midY > $1.box.midY }
        var rows: [[TextFragment]] = []
        var rowMid: [CGFloat] = []
        for f in sorted {
            if let mid = rowMid.last, let ref = rows.last?.first,
               abs(mid - f.box.midY) < min(ref.box.height, f.box.height) * 0.6 {
                rows[rows.count - 1].append(f)
                let row = rows[rows.count - 1]
                rowMid[rowMid.count - 1] = row.map(\.box.midY).reduce(0, +) / CGFloat(row.count)
            } else {
                rows.append([f])
                rowMid.append(f.box.midY)
            }
        }

        let gaps = zip(rowMid, rowMid.dropFirst()).map { $0 - $1 }
        let typical = median(gaps)
        var out: [String] = []
        for (i, row) in rows.enumerated() {
            if i > 0, typical > 0, gaps[i - 1] > typical * 1.7 { out.append("") }
            out.append(row.sorted { $0.box.minX < $1.box.minX }.map(\.text).joined(separator: " "))
        }
        return out
    }

    private static func median(_ values: [CGFloat]) -> CGFloat {
        guard !values.isEmpty else { return 0 }
        let s = values.sorted()
        return s[s.count / 2]
    }
}
