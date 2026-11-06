// Taken from the ShootingResultScanner package (reads photos of range result strips).
// Types renamed (Shot → ScanShot, Series → ScanSeries) to avoid clashing with the app model.
import Foundation

/// How shot values are printed.
public enum ScoringMode: String, Codable, Sendable {
    /// Detect from the recognized text.
    case automatic
    /// Tenth-ring scoring, 0.0 – 10.9.
    case decimal
    /// Whole rings (0 – 10, or 0 – 4/5 on field targets). A 100-scale value may be printed next to it.
    case integer
    /// Only the 100-scale value is printed (e.g. "Hunderter" programs: `1 ↙ 82`).
    case hundred
}

public struct ScannerConfiguration: Sendable {
    public var scoringMode: ScoringMode
    /// Split a shot list without subtotals into series of this size (`nil` = infer).
    public var shotsPerSeries: Int?
    /// Vision results below this confidence (0…1) are dropped.
    public var minimumOCRConfidence: Float
    public var recognitionLanguages: [String]
    /// Also read text printed sideways (name, club, weapon, Stich on SIUS-style strips).
    /// Costs two extra OCR passes, only run when no "Pg" printout was found.
    public var readSidewaysHeader: Bool
    /// Straighten/crop the paper only when it fills at least this part of the photo.
    /// Prevents cropping away a second strip lying next to the first.
    public var minimumPageAreaForCrop: Double

    public init(
        scoringMode: ScoringMode = .automatic,
        shotsPerSeries: Int? = nil,
        minimumOCRConfidence: Float = 0.3,
        recognitionLanguages: [String] = ["de-DE", "fr-FR", "it-IT", "en-US"],
        readSidewaysHeader: Bool = true,
        minimumPageAreaForCrop: Double = 0.5
    ) {
        self.scoringMode = scoringMode
        self.shotsPerSeries = shotsPerSeries
        self.minimumOCRConfidence = minimumOCRConfidence
        self.recognitionLanguages = recognitionLanguages
        self.readSidewaysHeader = readSidewaysHeader
        self.minimumPageAreaForCrop = minimumPageAreaForCrop
    }
}

public struct ScanShot: Hashable, Codable, Sendable {
    /// ScanShot number as printed.
    public var number: Int?
    /// Score in the result's scoring mode (rings, or the 100-value in `.hundred` mode).
    public var value: Double
    /// 100-scale value printed next to the ring ("Hunderterwertung"), if any.
    public var hundred: Int?
    /// Inner ten / Mouche ("X", "10*").
    public var isInnerTen: Bool

    public init(number: Int? = nil, value: Double, hundred: Int? = nil, isInnerTen: Bool = false) {
        self.number = number
        self.value = value
        self.hundred = hundred
        self.isInnerTen = isInnerTen
    }
}

public struct ScanSeries: Hashable, Codable, Sendable, Identifiable {
    public var id: Int { number }
    public var number: Int
    public var shots: [ScanShot]
    public var printedTotal: Double?
    public var printedHundredTotal: Int?

    public init(number: Int, shots: [ScanShot], printedTotal: Double? = nil, printedHundredTotal: Int? = nil) {
        self.number = number
        self.shots = shots
        self.printedTotal = printedTotal
        self.printedHundredTotal = printedHundredTotal
    }

    public var computedTotal: Double { ScoreMath.round1(shots.reduce(0) { $0 + $1.value }) }
    public var total: Double { printedTotal ?? computedTotal }

    public var computedHundredTotal: Int? {
        guard !shots.isEmpty, shots.allSatisfy({ $0.hundred != nil }) else { return nil }
        return shots.reduce(0) { $0 + ($1.hundred ?? 0) }
    }
    public var hundredTotal: Int? { printedHundredTotal ?? computedHundredTotal }

    /// `nil` when nothing printed to check against.
    public var isConsistent: Bool? {
        var checks: [Bool] = []
        if let p = printedTotal { checks.append(abs(p - computedTotal) < ScoreMath.tolerance) }
        if let p = printedHundredTotal, let c = computedHundredTotal { checks.append(p == c) }
        return checks.isEmpty ? nil : !checks.contains(false)
    }
}

/// "T/P 8 92": shot 8 has the 100-scale value 92 (deep shot used for tie-breaks).
public struct DeepShot: Hashable, Codable, Sendable {
    public var shot: Int
    public var value: Int
}

public enum ResultWarning: Hashable, Codable, Sendable {
    case noShotsFound
    case seriesTotalMismatch(series: Int, printed: Double, computed: Double)
    case seriesHundredMismatch(series: Int, printed: Int, computed: Int)
    case grandTotalMismatch(printed: Double, computed: Double)
    case hundredTotalMismatch(printed: Int, computed: Int)
    case shotValueMismatch(shot: Int, value: Double, hundred: Int)
    case missingShots(numbers: [Int])
    case copiesDisagree

    public var message: String {
        let f = ScoreMath.format
        switch self {
        case .noShotsFound:
            return "No shots found. Make sure the strip is sharp, well lit and fills most of the photo."
        case let .seriesTotalMismatch(s, p, c):
            return "ScanSeries \(s): printed \(f(p)), shots add up to \(f(c))."
        case let .seriesHundredMismatch(s, p, c):
            return "ScanSeries \(s): printed 100-total \(p), shots add up to \(c)."
        case let .grandTotalMismatch(p, c):
            return "Printed total \(f(p)), series add up to \(f(c))."
        case let .hundredTotalMismatch(p, c):
            return "Printed 100-total \(p), shots add up to \(c)."
        case let .shotValueMismatch(n, v, h):
            return "ScanShot \(n): ring \(f(v)) doesn't fit the 100-value \(h)."
        case let .missingShots(numbers):
            return "ScanShot(s) \(numbers.map(String.init).joined(separator: ", ")) could not be read."
        case .copiesDisagree:
            return "The copies on the strip were read differently."
        }
    }
}

public struct ShootingResult: Codable, Sendable {
    // Shooter & event (from the sideways header on SIUS-style strips)
    public var shooterName: String?
    public var birthYear: Int?
    /// Age category as printed after the year, e.g. "V" (veteran), "S", "J".
    public var category: String?
    public var club: String?
    public var weapon: String?
    public var organizer: String?
    public var event: String?
    /// Stich / competition, e.g. "Gruppenstich".
    public var competition: String?

    // Printout
    /// "59" for "Pg 59", or a program line such as "A10 Benutzerprogramm".
    public var program: String?
    /// "21/0002" (Pg strips) or "3/14" (Scheibe).
    public var targetLabel: String?
    public var date: String?
    public var time: String?
    public var discipline: String?

    public var scoringMode: ScoringMode
    /// Sighting shots (P), not counted.
    public var sighters: [ScanShot] = []
    public var series: [ScanSeries] = []
    public var printedTotal: Double?
    public var printedHundredTotal: Int?
    public var innerTenCount: Int?
    public var deepShots: [DeepShot] = []

    /// "Auszeichnung ab: 82"
    public var awardThreshold: Int?
    /// "Auszahlung ab: 148"
    public var payoutThreshold: Int?

    /// How many printed copies of this result were found (the Pg printers print two side by side).
    public var copiesRead: Int = 1
    /// `true` when all copies gave the same shots.
    public var copiesAgree: Bool?

    public var warnings: [ResultWarning] = []
    public var recognizedLines: [String] = []

    public init(scoringMode: ScoringMode, recognizedLines: [String] = []) {
        self.scoringMode = scoringMode
        self.recognizedLines = recognizedLines
    }

    public var shots: [ScanShot] { series.flatMap(\.shots) }
    public var computedTotal: Double { ScoreMath.round1(series.reduce(0) { $0 + $1.total }) }
    public var total: Double { printedTotal ?? computedTotal }
    public var hundredTotal: Int? {
        if let p = printedHundredTotal { return p }
        let parts = series.map(\.hundredTotal)
        return parts.isEmpty || parts.contains(where: { $0 == nil }) ? nil : parts.reduce(0) { $0 + ($1 ?? 0) }
    }
    /// Highest score possible for the number of shots (assumes a 10 ring / 100 scale).
    public var maximumPossible: Double { Double(shots.count) * (scoringMode == .hundred ? 100 : 10) }
    public var meetsAward: Bool? { awardThreshold.map { total >= Double($0) } }
    public var meetsPayout: Bool? { payoutThreshold.map { total >= Double($0) } }
    /// No warnings and at least one printed total confirmed the shots.
    public var isVerified: Bool {
        warnings.isEmpty && (printedTotal != nil || series.contains { $0.isConsistent == true })
    }
}

public enum ScoreMath {
    static let tolerance = 0.051
    static func round1(_ x: Double) -> Double { (x * 10).rounded() / 10 }
    public static func format(_ x: Double) -> String {
        x == x.rounded() ? String(Int(x)) : String(format: "%.1f", x)
    }
    /// Ring that belongs to a 100-scale value on Swiss A10/B targets: 91–100 → 10, 81–90 → 9, …
    static func ring(forHundred h: Int) -> Int { h <= 0 ? 0 : Int((Double(h) / 10).rounded(.up)) }
}
