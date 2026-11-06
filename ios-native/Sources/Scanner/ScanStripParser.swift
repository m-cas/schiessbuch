// Taken from the ShootingResultScanner package (reads photos of range result strips).
// Types renamed (Shot → ScanShot, Series → ScanSeries) to avoid clashing with the app model.
import Foundation

/// Parser for range printouts with one shot per line, as produced in Swiss 300 m / field shooting:
///
/// **Dot-matrix "Pg" strips** (printed twice side by side)
/// ```
/// Pg 32   23/0001
///  1 ↙   7
///  …
///  6 ↓   8    49      ← subtotal on the last shot of the series
///  …
/// 10 ←   7    37
///     86     86      ← total (large + small)
/// T/P 7  98          ← deep shot: shot 7 has 100-value 98
/// ```
/// **Thermal "Scheibe" strips** (ring + 100-value per shot)
/// ```
/// Scheibe 3/14
/// 19.9.2026 11:27
///  1 ↓  9  87P       ← P = sighter
///  1 ↙  9  90
/// Subtota 43 415     ← ring and 100 subtotal
/// ( 7 ↙ 74 74        ← best shot of the series, marks the series end
/// Total  89 848
/// ```
/// and 100-only programs (`1 ↙ 82`, `Total 171`).
struct StripParser: Sendable {
    var configuration: ScannerConfiguration

    enum Layout { case rings, ringsAndHundreds, hundreds }

    struct Num {
        var value: Int
        var inner: Bool
        var text: String
    }

    struct Row {
        var raw: String
        var lower: String
        var nums: [Num]
        var isSighter: Bool
        var hasWords: Bool
        var isBlank: Bool
        var isParenthesized: Bool
    }

    // MARK: - Parse

    func parse(lines: [String]) -> ShootingResult {
        // a running number after the 100-value on some thermal strips ("1 ↙ 9 85 37"): not part of the shot
        let rows = lines.map(Self.makeRow).map { row -> Row in
            let n = row.nums
            guard n.count == 4, (11...109).contains(n[2].value), ScoreMath.ring(forHundred: n[2].value) == n[1].value,
                  n[3].value < n[2].value else { return row }
            var r = row
            r.nums = Array(n.prefix(3))
            return r
        }
        let layout = Self.detectLayout(rows)
        var result = ShootingResult(scoringMode: layout == .hundreds ? .hundred : .integer, recognizedLines: lines)
        var state = State(layout: layout)
        // how often the shot numbering starts again at 1 (once: sighters before; more often: groups numbered from 1)
        var seen = false
        for row in rows where !row.hasWords && !row.isSighter && row.nums.count >= 2
            && !Self.isHeaderLine(row.raw) && Self.deepShot(in: row.raw) == nil {
            if row.nums[0].value == 1 { if seen { state.restarts += 1 }; seen = true }
        }

        for index in rows.indices {
            state.consume(rows[index], at: index, in: rows, result: &result)
        }
        state.closePending()

        result.sighters = state.sighters
        result.series = state.series
        result.deepShots = state.deepShots
        result.printedTotal = state.printedTotal
        result.printedHundredTotal = state.printedHundredTotal
        result.warnings = state.warnings + Self.validate(result, missing: state.missing)
        return result
    }

    // MARK: - State machine

    struct State {
        let layout: Layout
        var restarts = 0
        var sighters: [ScanShot] = []
        var series: [ScanSeries] = []
        var pending: [ScanShot] = []
        var lastIndex = 0
        var deepShots: [DeepShot] = []
        var printedTotal: Double?
        var printedHundredTotal: Int?
        var missing: [Int] = []
        var warnings: [ResultWarning] = []

        var seenShot: Bool { lastIndex > 0 }
        var pendingSum: Int { pending.reduce(0) { $0 + Int($1.value) } }

        init(layout: Layout) { self.layout = layout }

        mutating func consume(_ row: Row, at i: Int, in rows: [Row], result: inout ShootingResult) {
            if row.isBlank { closePending(); return }          // a gap between printed blocks ends a series
            StripParser.readMetadata(row, into: &result)
            if StripParser.isHeaderLine(row.raw) { return }
            if let deep = StripParser.deepShot(in: row.raw) { deepShots.append(deep); return }
            if row.isParenthesized { closePending(); return }
            if StripParser.subtotalWords.contains(where: { row.lower.contains($0) }) {
                closePending(printed: row.nums.map(\.value)); return
            }
            if StripParser.totalWords.contains(where: { row.lower.contains($0) }) {
                closePending(); setTotals(row.nums.map(\.value)); return
            }
            if row.hasWords || row.nums.isEmpty { return }

            var nums = row.nums
            if row.isSighter {
                if nums.count >= 2, let made = makeShot(number: nums[0].value, values: Array(nums.dropFirst())) {
                    sighters.append(made.shot)
                }
                return
            }

            // numbering starts again at 1 after a few shots: those were the sighters (Probe), e.g. "1 9 / 2 10 19", then "1 + 10 …"
            // Several restarts: groups numbered from 1 each (Obligatorisch on the form: 5 shots per group) - a new series.
            if nums.count >= 2, nums[0].value == 1, lastIndex > 0 {
                let before = series.flatMap(\.shots) + pending
                if restarts == 1, sighters.isEmpty, printedTotal == nil, before.count <= 3 {
                    sighters = before; series = []; pending = []; printedTotal = nil
                } else {
                    closePending()
                }
                lastIndex = 0
            }
            // a stray character in front of the shot number (dash of the line above, dirt, a piece of a cut-off copy):
            // "6 20 + 7 89", "36 10 > 3 36"
            if nums.count >= 3, nums[0].value != lastIndex + 1, nums[0].value != lastIndex, nums[1].value == lastIndex + 1 {
                nums.removeFirst()
            }
            let expected = lastIndex + 1
            let first = nums[0]
            // a printout continuing an earlier one (Feldschiessen on the form: PgFS2 starts at shot 7, PgFS3 at 13)
            if lastIndex == 0, series.isEmpty, pending.isEmpty, nums.count >= 2, (2...30).contains(first.value),
               StripParser.nextShotNumber(after: i, in: rows) == first.value + 1,
               commitShot(number: first.value, values: Array(nums.dropFirst())) {
                return
            }

            // 1. "<nr> [arrow] <value> [subtotal]"
            if nums.count >= 2 {
                var index: Int?
                if first.value == expected {
                    index = expected
                } else if first.value > expected, first.value <= expected + 3 {
                    index = first.value                        // a line was not read
                } else if first.value > expected, nums.count <= 4,
                          first.text.count > String(expected).count,
                          first.text.hasPrefix(String(expected)) {
                    index = expected                           // arrow read as a digit and glued on: "1↗" → "17"
                } else if expected >= 10, first.text.count < String(expected).count,
                          String(expected).hasSuffix(first.text), nums.count <= 3 {
                    index = expected                           // the underlined "10" of the last row read as "0"
                }
                if let index, commitShot(number: index, values: Array(nums.dropFirst())) {
                    if index > expected { missing += Array(expected..<index) }
                    return
                }
            }
            guard seenShot else { return }                     // header noise before shot 1

            // 2. Repeated shot number = best shot of the series (thermal strips) → series end
            if layout == .ringsAndHundreds, first.value <= lastIndex, nums.count >= 3 {
                closePending(); return
            }
            // 3. ScanShot whose number could not be read, between shot n-1 and n+1
            if layout == .rings, nums.count <= 2, first.value <= 10,
               StripParser.nextShotNumber(after: i, in: rows) == expected + 1,
               commitShot(number: expected, values: nums) {
                return
            }
            // 3b. Shot number lost (smudge), but the arrow and one value are there, right after the previous shot: "> 7"
            if nums.count == 1, row.raw.range(of: #"^\s*[>+↑↓←→↖↗↘↙]"#, options: .regularExpression) != nil,
               first.value <= (layout == .hundreds ? 100 : 10), commitShot(number: expected, values: nums) {
                return
            }
            // 4. Otherwise: the total line ("86  86")
            closePending()
            setTotals(nums.map(\.value))
        }

        mutating func commitShot(number: Int, values: [Num]) -> Bool {
            guard let made = makeShot(number: number, values: values) else { return false }
            pending.append(made.shot)
            lastIndex = number
            if let subtotal = made.subtotal { closePending(printed: [subtotal]) }
            return true
        }

        mutating func makeShot(number: Int, values r: [Num]) -> (shot: ScanShot, subtotal: Int?)? {
            guard let last = r.last else { return nil }
            switch layout {
            case .hundreds:
                // "10 > 75 874": the series total printed on the last shot row
                if r.count >= 2 {
                    let a = r[r.count - 2]
                    if a.value <= 100, last.value > 100 || (!pending.isEmpty && last.value == pendingSum + a.value) {
                        return (ScanShot(number: number, value: Double(a.value), hundred: a.value, isInnerTen: a.inner), last.value)
                    }
                }
                return (ScanShot(number: number, value: Double(last.value), hundred: last.value, isInnerTen: last.inner), nil)

            case .ringsAndHundreds:
                if r.count >= 2, last.value > 10 {
                    let ring = r[r.count - 2]
                    if ScoreMath.ring(forHundred: last.value) != ring.value {
                        warnings.append(.shotValueMismatch(shot: number, value: Double(ring.value), hundred: last.value))
                    }
                    return (ScanShot(number: number, value: Double(ring.value), hundred: last.value,
                                 isInnerTen: ring.inner || last.inner), nil)
                }
                if last.value > 10 {                       // ring not readable, derive it
                    return (ScanShot(number: number, value: Double(ScoreMath.ring(forHundred: last.value)),
                                 hundred: last.value), nil)
                }
                return (ScanShot(number: number, value: Double(last.value), isInnerTen: last.inner), nil)

            case .rings:
                if r.count == 1 {
                    guard last.value <= 10 else { return nil }
                    return (ScanShot(number: number, value: Double(last.value), isInnerTen: last.inner), nil)
                }
                let a = r[r.count - 2]
                let isSubtotal = last.value > 10 || (!pending.isEmpty && last.value == pendingSum + a.value)
                if isSubtotal, a.value <= 10 {
                    return (ScanShot(number: number, value: Double(a.value), isInnerTen: a.inner), last.value)
                }
                guard last.value <= 10 else { return nil }
                // the extra number in front is a misread direction arrow
                return (ScanShot(number: number, value: Double(last.value), isInnerTen: last.inner), nil)
            }
        }

        mutating func closePending(printed values: [Int] = []) {
            if pending.isEmpty {
                // a subtotal line right after the series was closed (e.g. by the parentheses line) still belongs to it
                guard !values.isEmpty, let prev = series.last, prev.printedTotal == nil, prev.printedHundredTotal == nil else { return }
                pending = prev.shots
                series.removeLast()
            }
            var s = ScanSeries(number: series.count + 1, shots: pending)
            switch layout {
            case .rings, .hundreds:
                if let v = values.last { s.printedTotal = Double(v) }
            case .ringsAndHundreds:
                if values.count >= 2 {
                    s.printedTotal = Double(values[values.count - 2])
                    s.printedHundredTotal = values[values.count - 1]
                } else if let v = values.last {
                    if v > 10 * pending.count { s.printedHundredTotal = v } else { s.printedTotal = Double(v) }
                }
            }
            series.append(s)
            pending.removeAll()
        }

        mutating func setTotals(_ values: [Int]) {
            guard let lastValue = values.last else { return }
            switch layout {
            case .rings, .hundreds:
                let computed = Int(ScoreMath.round1(series.reduce(0) { $0 + $1.total }))
                let candidate = values.first { $0 == computed } ?? lastValue
                // A number with fewer digits than the shots add up to is not a total (Pg 31 prints none; smudge below the strip).
                let shotSum = Int(series.reduce(0) { $0 + $1.shots.reduce(0) { $0 + $1.value } }.rounded())
                if candidate != computed, candidate != shotSum, shotSum >= 10, String(candidate).count < String(shotSum).count { return }
                // A misread copy of the total must not overwrite one that matches.
                if printedTotal == nil || candidate == computed { printedTotal = Double(candidate) }
            case .ringsAndHundreds:
                if values.count >= 2 {
                    printedTotal = Double(values[values.count - 2])
                    printedHundredTotal = lastValue
                } else {
                    let shotCount = series.reduce(0) { $0 + $1.shots.count }
                    if lastValue > 10 * shotCount { printedHundredTotal = lastValue } else { printedTotal = Double(lastValue) }
                }
            }
        }
    }

    // MARK: - Validation

    static func validate(_ r: ShootingResult, missing: [Int]) -> [ResultWarning] {
        if r.series.isEmpty { return [.noShotsFound] }
        var w: [ResultWarning] = []
        if !missing.isEmpty { w.append(.missingShots(numbers: missing)) }
        for s in r.series {
            if let p = s.printedTotal, abs(p - s.computedTotal) >= ScoreMath.tolerance {
                w.append(.seriesTotalMismatch(series: s.number, printed: p, computed: s.computedTotal))
            }
            if let p = s.printedHundredTotal, let c = s.computedHundredTotal, p != c {
                w.append(.seriesHundredMismatch(series: s.number, printed: p, computed: c))
            }
        }
        if let p = r.printedTotal, abs(p - r.computedTotal) >= ScoreMath.tolerance {
            w.append(.grandTotalMismatch(printed: p, computed: r.computedTotal))
        }
        if let p = r.printedHundredTotal {
            let parts = r.series.map(\.hundredTotal)
            if !parts.contains(where: { $0 == nil }) {
                let c = parts.reduce(0) { $0 + ($1 ?? 0) }
                if p != c { w.append(.hundredTotalMismatch(printed: p, computed: c)) }
            }
        }
        return w
    }

    // MARK: - Layout detection

    static func detectLayout(_ rows: [Row]) -> Layout {
        var dual = 0, ringSingles = 0, hundredSingles = 0
        for row in rows where !row.hasWords && !row.isParenthesized && row.nums.count >= 2
            && deepShot(in: row.raw) == nil {
            let r = row.nums.dropFirst().map(\.value)
            if r.count >= 2, r[r.count - 1] > 10, ScoreMath.ring(forHundred: r[r.count - 1]) == r[r.count - 2] {
                dual += 1
            } else if r.count == 1, r[0] > 10 {
                hundredSingles += 1
            } else {
                ringSingles += 1
            }
        }
        if dual > ringSingles + hundredSingles { return .ringsAndHundreds }
        if hundredSingles > ringSingles { return .hundreds }
        return .rings
    }

    static func nextShotNumber(after i: Int, in rows: [Row]) -> Int? {
        rows.dropFirst(i + 1).first { !$0.hasWords && !$0.isBlank && $0.nums.count >= 2 }?.nums.first?.value
    }

    // MARK: - Rows & tokens

    static func makeRow(_ line: String) -> Row {
        let trimmed = line.trimmingCharacters(in: .whitespaces)
        let cleaned = GenericSheetParser.stripDatesAndTimes(trimmed)
        var nums: [Num] = []
        var sighter = false
        var words = false

        for piece in cleaned.split(whereSeparator: { $0.isWhitespace || "|;=:()[]{}".contains($0) }) {
            var t = Substring(piece)
            // Direction arrows, "+", "÷", dots … around the numbers
            while let c = t.first, !(c.isLetter || c.isNumber) { t.removeFirst() }
            var inner = false
            while let c = t.last, !(c.isLetter || c.isNumber) {
                if "*✱✲✳#".contains(c) { inner = true }
                t.removeLast()
            }
            guard !t.isEmpty else { continue }
            var s = String(t)

            if !s.contains(where: { $0.isASCII && $0.isNumber }) {
                if s == "P" || s == "p" { sighter = true; continue }
                if s == "X" || s == "x" { nums.append(Num(value: 10, inner: true, text: s)); continue }
                if let digit = singleCharDigits[s] {           // dot-matrix digits read as letters
                    nums.append(Num(value: digit, inner: inner, text: String(digit))); continue
                }
                if s.count >= 2, s.allSatisfy(\.isLetter), !neutralWords.contains(s.lowercased()) { words = true }
                continue
            }
            if s.count > 1, s.last == "P" || s.last == "p" { sighter = true; s.removeLast() }   // "87P"
            if s.count > 1, s.first == "P" { sighter = true; s.removeFirst() }                    // "P9"
            s = String(s.map { c -> Character in
                switch c {
                case "O", "o", "Q", "D": return "0"
                case "l", "I", "i", "|", "!": return "1"
                case "B": return "8"
                case "S": return "5"
                case "Z": return "2"
                case "G": return "6"
                default: return c
                }
            })
            guard s.range(of: #"^\d{1,4}$"#, options: .regularExpression) != nil, let v = Int(s) else { continue }
            nums.append(Num(value: v, inner: inner, text: s))
        }

        let opens = trimmed.first.map { "([{<".contains($0) } ?? false
        let closes = trimmed.last.map { ")]}>".contains($0) } ?? false
        return Row(raw: trimmed, lower: trimmed.lowercased(), nums: nums, isSighter: sighter,
                   hasWords: words, isBlank: trimmed.isEmpty, isParenthesized: opens || closes)
    }

    // MARK: - Metadata

    static let pgPattern = #"(?i)^\s*[PF][g9qs]\s*(\d{1,3})\s+(\d{1,3}\s*/\s*\d{2,4})"#
    static let scheibePattern = #"(?i)^\s*(?:scheibe|cible|bersaglio)\s*(\d{1,3}(?:\s*/\s*\d{1,3})?)"#   // "Cible 5/20" on French strips
    static let deepShotPattern = #"(?i)^\s*[TI1l]\s*/\s*[PF]\s*(\d{1,2})\s+(\d{1,3})\b"#

    /// "Pg 32  23/0005", "Scheibe 3/14", or a misread header that still ends in a run number ("g32 23/0005")
    static func isHeaderLine(_ line: String) -> Bool {
        GenericSheetParser.firstMatch(pgPattern, in: line) != nil
            || GenericSheetParser.firstMatch(scheibePattern, in: line) != nil
            || line.range(of: #"^\s*\S{1,6}\s+\d{1,3}/\d{4}\s*$"#, options: .regularExpression) != nil
    }

    static func deepShot(in line: String) -> DeepShot? {
        guard let m = GenericSheetParser.firstMatch(deepShotPattern, in: line), m.count == 3,
              let shot = Int(m[1]), let value = Int(m[2]) else { return nil }
        return DeepShot(shot: shot, value: value)
    }

    static func readMetadata(_ row: Row, into r: inout ShootingResult) {
        if r.date == nil, let d = GenericSheetParser.firstMatch(GenericSheetParser.datePattern, in: row.raw)?.first {
            r.date = d
        }
        if r.time == nil, let t = GenericSheetParser.firstMatch(#"\b\d{1,2}:\d{2}(?::\d{2})?\b"#, in: row.raw)?.first {
            r.time = t
        }
        if let m = GenericSheetParser.firstMatch(pgPattern, in: row.raw), m.count == 3 {
            r.program = m[1]
            r.targetLabel = m[2].replacingOccurrences(of: " ", with: "")
        } else if let m = GenericSheetParser.firstMatch(scheibePattern, in: row.raw), m.count == 2 {
            r.targetLabel = m[1].replacingOccurrences(of: " ", with: "")
        } else if r.program == nil, row.lower.contains("progr") {
            r.program = row.raw
        }
    }

    // MARK: - Vocabulary

    static let subtotalWords = ["subtot", "zwischen", "summe", "sous-tot", "somme", "somma", "total part", "tot. part", "totale parz"]
    static let totalWords = ["tota", "gesamt", "résultat", "resultat", "risultato"]
    static let neutralWords: Set<String> = ["ring", "ringe", "pkt", "pts", "punkte", "points", "punti"]
    static let singleCharDigits: [String: Int] = [
        "O": 0, "o": 0, "I": 1, "l": 1, "Z": 2, "J": 3, "S": 5, "G": 6, "B": 8
    ]
}
