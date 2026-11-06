// Taken from the ShootingResultScanner package (reads photos of range result strips).
// Types renamed (Shot → ScanShot, Series → ScanSeries) to avoid clashing with the app model.
import Foundation

/// Shooter and event data printed sideways at the top of SIUS-style strips:
/// ```
/// Schützengesellschaft Ziefen        ← organizer
/// 16. Föiflibertalschiessen          ← event
/// Castelli Massimo 1965 V            ← name, birth year, category
/// Muttenz, Schützengesellschaft      ← club
/// Sturmgewehr 90                     ← weapon
/// Gruppenstich                       ← competition (Stich)
/// Auszeichnung ab: 82
/// ```
public struct HeaderInfo: Sendable, Equatable {
    public var shooterName: String?
    public var birthYear: Int?
    public var category: String?
    public var club: String?
    public var weapon: String?
    public var organizer: String?
    public var event: String?
    public var competition: String?
    public var awardThreshold: Int?
    public var payoutThreshold: Int?

    public init() {}

    var score: Int {
        [shooterName, club, weapon, event, competition].compactMap { $0 }.count
            + (awardThreshold != nil ? 1 : 0) + (payoutThreshold != nil ? 1 : 0)
    }
}

enum HeaderParser {
    static func parse(lines rawLines: [String]) -> HeaderInfo {
        var h = HeaderInfo()
        let lines = rawLines.map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
        let joined = lines.joined(separator: "\n")

        if let m = GenericSheetParser.firstMatch(#"(?i)auszeichnung\s*ab\s*:?\s*(\d{2,4})"#, in: joined), let v = Int(m[1]) {
            h.awardThreshold = v
        }
        if let m = GenericSheetParser.firstMatch(#"(?i)auszahlung\s*ab\s*:?\s*(\d{2,4})"#, in: joined), let v = Int(m[1]) {
            h.payoutThreshold = v
        }

        guard let nameIndex = lines.firstIndex(where: { nameMatch($0) != nil }),
              let name = nameMatch(lines[nameIndex]) else { return h }
        h.shooterName = name.name
        h.birthYear = name.year
        h.category = name.category

        let before = lines[..<nameIndex].filter(isMeaningful)
        if before.count >= 2 { h.organizer = before[before.count - 2] }
        h.event = before.last

        let after = Array(lines[(nameIndex + 1)...]).filter(isMeaningful)
        if let first = after.first, !isWeapon(first) { h.club = first }
        if let w = after.firstIndex(where: isWeapon) {
            h.weapon = after[w]
            if w + 1 < after.count, !isBoilerplate(after[w + 1]) { h.competition = after[w + 1] }
        }
        return h
    }

    /// "Castelli Massimo 1965 V" → name, year, category
    static func nameMatch(_ line: String) -> (name: String, year: Int, category: String?)? {
        let pattern = #"^(\p{Lu}[\p{L}'’\-]+(?:\s+\p{Lu}[\p{L}'’\-]+){1,3})\s+((?:19|20)\d{2})(?:\s+([A-Z]{1,2}))?$"#
        guard let m = GenericSheetParser.firstMatch(pattern, in: line), m.count >= 3, let year = Int(m[2]) else { return nil }
        return (m[1], year, m.count > 3 ? m[3] : nil)
    }

    static let weaponWords = ["sturmgewehr", "stgw", "karabiner", "standardgewehr", "freigewehr",
                              "langgewehr", "ordonnanz", "pistole", "gewehr", "fass", "fusil", "pistolet"]
    static let boilerplateWords = ["einzelschütze", "kranzkarte", "naturalgabe", "auszeichnung", "auszahlung", "kopie", "original"]

    static func isWeapon(_ line: String) -> Bool {
        let lower = line.lowercased()
        return weaponWords.contains { lower.contains($0) }
    }

    static func isBoilerplate(_ line: String) -> Bool {
        let lower = line.lowercased()
        return boilerplateWords.contains { lower.contains($0) }
    }

    /// Skips stamps ("BEZAHLT" partly visible as "AHLT") and stray characters.
    static func isMeaningful(_ line: String) -> Bool {
        let letters = line.filter(\.isLetter)
        if letters.count < 4 { return false }
        if line.count <= 7 && line == line.uppercased() { return false }
        return true
    }
}
