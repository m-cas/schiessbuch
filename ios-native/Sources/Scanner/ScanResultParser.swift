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
        sections(of: lines).compactMap { section in
            let result = parseSection(section)
            return result.series.isEmpty ? nil : result
        }
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
        var groupIndex: [String: Int] = [:]
        for result in results {
            if let key = result.copyKey, let i = groupIndex[key] {
                groups[i].append(result)
            } else {
                if let key = result.copyKey { groupIndex[key] = groups.count }
                groups.append([result])
            }
        }
        return groups.map(mergeGroup)
    }

    private static func mergeGroup(_ copies: [ShootingResult]) -> ShootingResult {
        guard copies.count > 1 else { return copies[0] }
        func rank(_ r: ShootingResult) -> (Int, Int, Int) {
            (r.isVerified ? 0 : 1, r.warnings.count, -r.shots.count)
        }
        var best = copies.min { rank($0) < rank($1) }!
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
