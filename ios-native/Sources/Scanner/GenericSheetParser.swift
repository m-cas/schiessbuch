// Taken from the ShootingResultScanner package (reads photos of range result strips).
// Types renamed (Shot → ScanShot, Series → ScanSeries) to avoid clashing with the app model.
import Foundation

/// Fallback parser for full-page result sheets (decimal scores, one series per line).
/// Strip printouts with one shot per line are handled by `StripParser`.
///
/// Pure Foundation, no Vision dependency, so it can be unit-tested and reused
/// with any OCR engine. Supported layouts:
///  * one series per line: `Serie 1  10.5 10.2 9.8 … = 102.5` (optional running total)
///  * shots on one line, subtotal on the next line
///  * a shot strip, one shot per line (`1  10.4` / `1  10`), with `Summe 98` subtotals or none
struct GenericSheetParser: Sendable {
    var configuration: ScannerConfiguration

    init(configuration: ScannerConfiguration = ScannerConfiguration()) {
        self.configuration = configuration
    }

    // MARK: - Public

    func parse(lines rawLines: [String]) -> ShootingResult {
        let lines = rawLines
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }

        let mode = resolveMode(lines)
        let maxShot = mode == .decimal ? 10.9 : 10.0
        let tol = ScoreMath.tolerance

        var result = ShootingResult(scoringMode: mode, recognizedLines: rawLines)
        var series: [ScanSeries] = []
        var loose: [ScanShot] = []
        var nextShotNumber = 1

        func appendSeries(_ shots: [ScanShot], total: Double?, label: Int? = nil) {
            series.append(ScanSeries(number: label ?? series.count + 1, shots: shots, printedTotal: total))
        }

        func flushLoose(total: Double?) {
            guard !loose.isEmpty else { return }
            if total == nil, let size = chunkSize(for: loose.count), size < loose.count {
                stride(from: 0, to: loose.count, by: size).forEach {
                    appendSeries(Array(loose[$0..<min($0 + size, loose.count)]), total: nil)
                }
            } else {
                appendSeries(loose, total: total)
            }
            loose.removeAll()
        }

        for line in lines {
            if extractMetadata(from: line, into: &result) { continue }

            let (label, body) = Self.stripSeriesLabel(Self.stripDatesAndTimes(line))
            let lower = body.lowercased()
            let tokens = Self.numberTokens(in: body)
            if tokens.isEmpty || Self.isIndexSequence(tokens) { continue }

            // Grand total / inner-ten lines
            let isTotalLine = Self.containsKeyword(lower, Self.totalKeywords)
            let isInnerLine = Self.containsKeyword(lower, Self.innerTenKeywords)
            if isTotalLine || isInnerLine {
                let total = isTotalLine ? tokens.filter { $0.value > maxShot }.map(\.value).max() : nil
                if let total { result.printedTotal = total }
                if isInnerLine,
                   let n = tokens.first(where: { !$0.hasDecimal && $0.value < 200 && $0.value != total }) {
                    result.innerTenCount = Int(n.value)
                }
                continue
            }

            // Pick the tokens that can be scores in this mode.
            var values: [NumberToken]
            if mode == .decimal {
                // Decimal sheets print every shot with one decimal ("0.0", "10.4");
                // plain integers are shot numbers or whole-ring columns.
                values = tokens.filter { $0.decimals == 1 }
            } else {
                values = tokens.filter { !$0.hasDecimal }
                // "<shot number>  <ring>" strip line
                if values.count == 2, values[1].value <= maxShot,
                   (nextShotNumber...(nextShotNumber + 3)).contains(Int(values[0].value)) {
                    nextShotNumber = Int(values[0].value) + 1
                    values.removeFirst()
                }
            }
            if values.isEmpty { continue }

            // Lines with other words and few numbers are headers, names, club info…
            if Self.containsWords(body) && label == nil && values.count < 3 { continue }

            let shots = values.filter { $0.value <= maxShot }.map { ScanShot(value: $0.value, isInnerTen: $0.isInnerTen) }
            let bigs = values.filter { $0.value > maxShot }.map(\.value)
            let shotSum = ScoreMath.round1(shots.reduce(0) { $0 + $1.value })

            if !shots.isEmpty && !bigs.isEmpty {
                if shots.count >= 2 || label != nil {
                    // Full series line; with a running total, pick the value that matches.
                    flushLoose(total: nil)
                    let total = bigs.first { abs($0 - shotSum) < tol } ?? bigs[0]
                    appendSeries(shots, total: total, label: label)
                } else {
                    loose.append(contentsOf: shots)
                    flushLoose(total: bigs[0])
                }
            } else if shots.count >= 3 {
                flushLoose(total: nil)
                appendSeries(shots, total: nil, label: label)
            } else if !shots.isEmpty {
                loose.append(contentsOf: shots)
            } else {
                // Only values > 10.9: a subtotal, or an unlabeled grand total.
                if !loose.isEmpty {
                    let looseSum = ScoreMath.round1(loose.reduce(0) { $0 + $1.value })
                    flushLoose(total: bigs.first { abs($0 - looseSum) < tol } ?? bigs[0])
                } else if let last = series.last, last.printedTotal == nil {
                    series[series.count - 1].printedTotal =
                        bigs.first { abs($0 - last.computedTotal) < tol } ?? bigs[0]
                } else if result.printedTotal == nil, !series.isEmpty {
                    let seriesSum = ScoreMath.round1(series.reduce(0) { $0 + $1.total })
                    if let grand = bigs.first(where: { abs($0 - seriesSum) < tol }) {
                        result.printedTotal = grand
                    }
                }
            }
        }
        flushLoose(total: nil)

        result.series = series
        result.warnings = validate(series: series, printedTotal: result.printedTotal)
        return result
    }

    // MARK: - Mode & validation

    private func resolveMode(_ lines: [String]) -> ScoringMode {
        if configuration.scoringMode != .automatic { return configuration.scoringMode }
        let decimalShots = lines
            .flatMap { Self.numberTokens(in: Self.stripDatesAndTimes($0)) }
            .filter { $0.decimals == 1 && $0.value <= 10.9 }
            .count
        return decimalShots >= 3 ? .decimal : .integer
    }

    private func chunkSize(for count: Int) -> Int? {
        if let size = configuration.shotsPerSeries, size > 0 { return size }
        return count > 10 && count % 10 == 0 ? 10 : nil
    }

    private func validate(series: [ScanSeries], printedTotal: Double?) -> [ResultWarning] {
        var warnings: [ResultWarning] = []
        if series.isEmpty { return [.noShotsFound] }
        for s in series where s.isConsistent == false {
            warnings.append(.seriesTotalMismatch(series: s.number, printed: s.printedTotal!, computed: s.computedTotal))
        }
        if let printed = printedTotal {
            let computed = ScoreMath.round1(series.reduce(0) { $0 + $1.total })
            if abs(printed - computed) >= ScoreMath.tolerance {
                warnings.append(.grandTotalMismatch(printed: printed, computed: computed))
            }
        }
        return warnings
    }

    // MARK: - Metadata

    /// Returns true when the line was consumed as metadata only (e.g. the name line).
    private func extractMetadata(from line: String, into result: inout ShootingResult) -> Bool {
        if result.date == nil, let date = Self.firstMatch(Self.datePattern, in: line)?.first {
            result.date = date
        }
        if result.shooterName == nil,
           let name = Self.firstMatch(#"(?i)^\s*(?:name|schütze|schützin|tireur|tireuse|tiratore|tiratrice|shooter|athlete|athlet)\s*[:.]?\s+(.+)$"#, in: line)?.last {
            result.shooterName = name.trimmingCharacters(in: .whitespaces)
            return true
        }
        let lower = line.lowercased()
        if result.discipline == nil, Self.disciplineKeywords.contains(where: { lower.contains($0) }) {
            result.discipline = line
        }
        return false
    }

    // MARK: - Tokenizing (internal for tests)

    struct NumberToken: Equatable {
        var value: Double
        var decimals: Int
        var isInnerTen: Bool
        var hasDecimal: Bool { decimals > 0 }
    }

    static func numberTokens(in text: String) -> [NumberToken] {
        let separators = CharacterSet.whitespaces.union(CharacterSet(charactersIn: "|;=:()[]{}/"))
        return text.components(separatedBy: separators).compactMap(parseToken)
    }

    /// Parses one token, repairing common OCR confusions ("1O,4" → 10.4, "l0" → 10).
    static func parseToken(_ raw: String) -> NumberToken? {
        var t = raw.trimmingCharacters(in: CharacterSet(charactersIn: ",;'\"`´_~"))
        if t.isEmpty || t.hasPrefix("-") { return nil }          // negatives are x/y coordinates
        if t == "X" || t == "x" { return NumberToken(value: 10, decimals: 0, isInnerTen: true) }
        var inner = false
        if t.hasSuffix("*") || t.hasSuffix("+") { inner = true; t.removeLast() }
        guard t.contains(where: { $0.isASCII && $0.isNumber }) else { return nil }

        var s = String(t.map { c -> Character in
            switch c {
            case "O", "o", "Q": return "0"
            case "l", "I", "i", "|", "!": return "1"
            case ",": return "."
            default: return c
            }
        })
        while s.hasSuffix(".") { s.removeLast() }
        guard s.range(of: #"^\d{1,4}(\.\d{1,2})?$"#, options: .regularExpression) != nil,
              let value = Double(s) else { return nil }
        let decimals = s.split(separator: ".").dropFirst().first?.count ?? 0
        return NumberToken(value: value, decimals: decimals, isInnerTen: inner)
    }

    /// "1 2 3 4 5 6" header rows (shot or series numbers).
    static func isIndexSequence(_ tokens: [NumberToken]) -> Bool {
        guard tokens.count >= 4, tokens.allSatisfy({ !$0.hasDecimal }) else { return false }
        return zip(tokens, tokens.dropFirst()).allSatisfy { $1.value == $0.value + 1 }
    }

    /// Removes a leading "Serie 3", "Passe 2", "S1", "3." or "3)" and returns the number.
    static func stripSeriesLabel(_ line: String) -> (Int?, String) {
        let patterns = [
            #"(?i)^\s*(?:serie|series|série|passe|pass|s)\s*\.?\s*(\d{1,2})\s*[:.)\-]?\s+"#,
            #"^\s*(\d{1,2})\s*[.):]\s+"#
        ]
        for pattern in patterns {
            guard let regex = try? NSRegularExpression(pattern: pattern),
                  let m = regex.firstMatch(in: line, range: NSRange(line.startIndex..., in: line)),
                  let numRange = Range(m.range(at: 1), in: line),
                  let full = Range(m.range, in: line) else { continue }
            return (Int(line[numRange]), String(line[full.upperBound...]))
        }
        return (nil, line)
    }

    static func stripDatesAndTimes(_ line: String) -> String {
        var s = line
        for pattern in [datePattern, #"\b\d{4}-\d{2}-\d{2}\b"#, #"\b\d{1,2}:\d{2}(?::\d{2})?\b"#] {
            s = s.replacingOccurrences(of: pattern, with: " ", options: .regularExpression)
        }
        return s
    }

    static func containsWords(_ line: String) -> Bool {
        line.split(whereSeparator: { $0.isWhitespace }).contains { word in
            let w = word.lowercased().trimmingCharacters(in: .punctuationCharacters)
            return w.count >= 2 && w.allSatisfy(\.isLetter) && !neutralWords.contains(w)
        }
    }

    static func containsKeyword(_ lower: String, _ keywords: [String]) -> Bool {
        let words = Set(lower.split(whereSeparator: { !$0.isLetter && $0 != "-" }).map(String.init))
        return keywords.contains { words.contains($0) }
    }

    static func firstMatch(_ pattern: String, in text: String) -> [String]? {
        guard let regex = try? NSRegularExpression(pattern: pattern),
              let m = regex.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)) else { return nil }
        return (0..<m.numberOfRanges).compactMap { Range(m.range(at: $0), in: text).map { String(text[$0]) } }
    }

    // MARK: - Vocabulary (DE / FR / IT / EN)

    static let datePattern = #"\b\d{1,2}[./-]\d{1,2}[./-]\d{2,4}\b"#

    static let totalKeywords = [
        "total", "gesamt", "gesamttotal", "endresultat", "resultat", "résultat", "ergebnis",
        "result", "totale", "risultato", "score"
    ]
    static let innerTenKeywords = [
        "innenzehner", "innen-zehner", "mouche", "mouches", "mouchen", "inner", "x-count", "xs", "centri"
    ]
    /// Words that may appear on shot / subtotal lines without making them "text" lines.
    static let neutralWords: Set<String> = [
        "summe", "sum", "somme", "somma", "zwischentotal", "subtotal",
        "serie", "series", "série", "passe", "pass",
        "ring", "ringe", "punkte", "pkt", "pts", "points", "punti",
        "schuss", "shot", "shots", "coups", "colpi"
    ]
    static let disciplineKeywords = [
        "luftgewehr", "luftpistole", "kleinkaliber", "sportpistole", "schnellfeuer", "ordonnanz",
        "gewehr 300", "gewehr 50", "pistole 25", "pistole 50", "air rifle", "air pistol", "smallbore",
        "carabine", "pistolet", "fusil", "carabina", "pistola", "10m", "25m", "50m", "300m"
    ]
}
