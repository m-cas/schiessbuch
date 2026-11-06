// Taken from the ShootingResultScanner package (reads photos of range result strips).
// Types renamed (Shot → ScanShot, Series → ScanSeries) to avoid clashing with the app model.
import Foundation

/// Turns recognized text into results. Pure Foundation, so it can be tested without a device
/// and used with any OCR engine.
///
/// One block of text can hold several printouts (e.g. `Scheibe 3/6` and `Scheibe 3/7` on one strip).
/// It is split at each printout header and every part is parsed by the strip parser, or by the
/// generic sheet parser for decimal / full-page layouts.
public struct ResultParser: Sendable {
    public var configuration: ScannerConfiguration

    public init(configuration: ScannerConfiguration = ScannerConfiguration()) {
        self.configuration = configuration
    }

    /// All results found in the lines (only those with at least one shot).
    public func parseAll(lines: [String]) -> [ShootingResult] {
        let results = sections(of: lines).compactMap { section -> ShootingResult? in
            let result = parseSection(section)
            return result.series.isEmpty ? nil : result
        }
        // A printout whose shot numbers continue the one before is the same program (PgFS1/PgFS2/PgFS3, shots 1-18).
        var out: [ShootingResult] = []
        for r in results {
            if var prev = out.last, let first = r.shots.first?.number, let last = prev.shots.last?.number,
               first == last + 1, r.sighters.isEmpty {
                let start = prev.series.count
                prev.series += r.series.enumerated().map { i, s in var x = s; x.number = start + i + 1; return x }
                prev.printedTotal = nil
                prev.printedHundredTotal = nil
                prev.deepShots += r.deepShots
                prev.warnings = StripParser.validate(prev, missing: [])
                out[out.count - 1] = prev
            } else {
                out.append(r)
            }
        }
        return out
    }

    /// The first result, or an empty result carrying `.noShotsFound`.
    public func parse(lines: [String]) -> ShootingResult {
        parseAll(lines: lines).first ?? GenericSheetParser(configuration: configuration).parse(lines: lines)
    }

    private func parseSection(_ lines: [String]) -> ShootingResult {
        let generic = GenericSheetParser(configuration: configuration)
        if configuration.scoringMode == .decimal || (configuration.scoringMode == .automatic && Self.looksDecimal(lines)) {
            return generic.parse(lines: lines)
        }
        let strip = StripParser(configuration: configuration).parse(lines: lines)
        return strip.series.isEmpty ? generic.parse(lines: lines) : strip
    }

    private func sections(of lines: [String]) -> [[String]] {
        var result: [[String]] = [[]]
        for line in lines {
            if StripParser.isHeaderLine(line), !(result.last?.isEmpty ?? true) { result.append([]) }
            result[result.count - 1].append(line)
        }
        return result.filter { !$0.isEmpty }
    }

    static func looksDecimal(_ lines: [String]) -> Bool {
        lines.flatMap { GenericSheetParser.numberTokens(in: GenericSheetParser.stripDatesAndTimes($0)) }
            .filter { $0.decimals == 1 && $0.value <= 10.9 }
            .count >= 3
    }
}

/// Combines results from one photo or scan.
enum ResultAssembler {
    /// The Pg printers print every result twice side by side. Copies with the same
    /// program + label are merged; the copy whose totals check out wins.
    static func mergeCopies(_ results: [ShootingResult]) -> [ShootingResult] {
        var groups: [[ShootingResult]] = []
        for result in results {
            if let i = groups.firstIndex(where: { g in g.contains { c in
                sameCopy(c, result) && (c.program == nil || result.program == nil || c.program == result.program
                                        || lev(c.program!, result.program!) <= 1) } }) {
                groups[i].append(result)
            } else {
                groups.append([result])
            }
        }
        return groups.map(mergeGroup)
    }

    /// Results from reading the photo turned by 180° (a strip lying upside down): only printouts with a cleanly read
    /// run number and at least 4 shots count (an upright strip read upside down gives junk), merged with the first pass.
    static func addTurned(_ results: [ShootingResult], _ turned: [ShootingResult]) -> [ShootingResult] {
        let ok = turned.filter { r in
            (r.targetLabel?.range(of: #"^\d{1,3}/\d{4}$"#, options: .regularExpression) != nil) && r.shots.count >= 4
        }
        return ok.isEmpty ? results : mergeCopies(results + ok)
    }

    static func lev(_ a: String, _ b: String) -> Int {
        let a = Array(a), b = Array(b)
        var d = Array(0...b.count)
        guard !a.isEmpty else { return b.count }
        for i in 1...a.count {
            var p = d[0]; d[0] = i
            if b.isEmpty { continue }
            for j in 1...b.count {
                let t = d[j]
                d[j] = min(d[j] + 1, d[j - 1] + 1, p + (a[i - 1] != b[j - 1] ? 1 : 0))
                p = t
            }
        }
        return d[b.count]
    }

    /// Two readings of the same printout: same target (a misread character or two allowed), or mostly the same shots.
    static func sameCopy(_ a: ShootingResult, _ b: ShootingResult) -> Bool {
        if let ta = a.targetLabel, let tb = b.targetLabel {
            if ta == tb { return true }
            // two cleanly read labels that differ are two runs ("23/0004", "23/0005"); only a malformed one may be a misreading
            let clean = { (l: String) in l.range(of: #"^\d{1,2}/\d{4}$"#, options: .regularExpression) != nil }
            if clean(ta) && clean(tb) { return false }
            return lev(ta, tb) <= 2 && abs(a.shots.count - b.shots.count) <= 1
        }
        let x = a.shots.map(\.value), y = b.shots.map(\.value)
        guard x.count >= 4, x.count == y.count else { return false }
        return Double(zip(x, y).filter { $0 == $1 }.count) >= 0.7 * Double(x.count)
    }

    /// Neither copy adds up: take each series from the copy whose series matches its printed subtotal.
    static func combineCopies(_ copies: [ShootingResult]) -> ShootingResult? {
        let base = copies[0]
        guard copies.allSatisfy({ c in c.series.count == base.series.count
            && zip(c.series, base.series).allSatisfy { $0.shots.count == $1.shots.count } }) else { return nil }
        var r = base
        r.series = base.series.indices.map { i in copies.map { $0.series[i] }.first { $0.isConsistent == true } ?? base.series[i] }
        r.printedTotal = copies.compactMap(\.printedTotal).first { abs($0 - r.computedTotal) < ScoreMath.tolerance } ?? base.printedTotal
        r.warnings = StripParser.validate(r, missing: [])
        return r.isVerified ? r : nil
    }

    private static func mergeGroup(_ copies: [ShootingResult]) -> ShootingResult {
        guard copies.count > 1 else { return copies[0] }
        func rank(_ r: ShootingResult) -> (Int, Int, Int) {
            (r.isVerified ? 0 : 1, r.warnings.count, -r.shots.count)
        }
        var best = copies.min { rank($0) < rank($1) }!
        if !best.isVerified, let combined = combineCopies(copies) { best = combined }
        // the label as printed: the form the copies agree on, else the one shaped like "21/0004"
        let labels = copies.compactMap(\.targetLabel)
        func matches(_ s: String, _ re: String) -> Bool { s.range(of: re, options: .regularExpression) != nil }
        let shaped = labels.filter { matches($0, #"^\d{1,3}/\d{1,4}$"#) && !matches($0, #"^\d{3}/\d{4}$"#) }
        let common = labels.first { l in labels.filter { $0 == l }.count > 1 }
        best.targetLabel = common ?? shaped.first { matches($0, #"^\d{2}/\d{4}$"#) } ?? shaped.first ?? best.targetLabel
        let agree = Set(copies.map { $0.shots.map(\.value) }).count == 1
        best.copiesRead = copies.count
        best.copiesAgree = agree
        if !agree && !best.isVerified { best.warnings.append(.copiesDisagree) }
        if best.deepShots.isEmpty, let other = copies.max(by: { $0.deepShots.count < $1.deepShots.count }) {
            best.deepShots = other.deepShots
        }
        return best
    }

    /// Applies the sideways header (name, club, …) to every result from the same strip.
    static func apply(_ header: HeaderInfo, to results: inout [ShootingResult]) {
        for i in results.indices {
            results[i].shooterName = results[i].shooterName ?? header.shooterName
            results[i].birthYear = results[i].birthYear ?? header.birthYear
            results[i].category = results[i].category ?? header.category
            results[i].club = results[i].club ?? header.club
            results[i].weapon = results[i].weapon ?? header.weapon
            results[i].organizer = results[i].organizer ?? header.organizer
            results[i].event = results[i].event ?? header.event
            results[i].competition = results[i].competition ?? header.competition
        }
        // "Auszeichnung ab: 82" belongs to the result where 82 is a plausible limit
        // (e.g. 10 shots → max 100); "Auszahlung ab: 148" to a 2-shot 100-scale program (max 200).
        assign(header.awardThreshold, to: &results) { $0.awardThreshold = $1 }
        assign(header.payoutThreshold, to: &results) { $0.payoutThreshold = $1 }
    }

    private static func assign(_ threshold: Int?, to results: inout [ShootingResult],
                               _ set: (inout ShootingResult, Int) -> Void) {
        guard let t = threshold else { return }
        let value = Double(t)
        if let i = results.firstIndex(where: { $0.maximumPossible > 0 && value <= $0.maximumPossible && value >= $0.maximumPossible / 2 }) {
            set(&results[i], t)
        }
    }
}

extension ShootingResult {
    var copyKey: String? { targetLabel.map { "\(program ?? "")|\($0)" } }
}
