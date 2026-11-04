import Foundation

// MARK: - Shot

/// One shot value. "X" is an inner ten (counts as 10) on pistol A10 targets.
enum Shot: Codable, Hashable {
    case value(Int)
    case x

    var points: Int { if case .value(let v) = self { return v } else { return 10 } }
    var label: String { if case .value(let v) = self { return String(v) } else { return "X" } }

    init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if let i = try? c.decode(Int.self) { self = .value(i); return }
        if let d = try? c.decode(Double.self) { self = .value(Int(d.rounded())); return }
        let s = try c.decode(String.self)
        if s.uppercased() == "X" { self = .x } else { self = .value(Int(s) ?? 0) }
    }
    func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        switch self { case .value(let v): try c.encode(v); case .x: try c.encode("X") }
    }
}

// MARK: - Targets, formats, disciplines

enum Target: String, CaseIterable, Identifiable {
    case a10 = "A10", a100 = "A100", b4 = "B4", b5 = "B5", a5 = "A5"
    var id: String { rawValue }
    var max: Int {
        switch self { case .a10: 10; case .a100: 100; case .b4: 4; case .b5: 5; case .a5: 5 }
    }
}

enum SegmentKind: String { case practice, single, series }

struct Segment {
    let kind: SegmentKind
    let count: Int
    var start: Int = 0
    var target: Target? = nil   // fixed target for this part (OP / Feldschiessen rifle)
}

enum SeriesFormat: String, CaseIterable, Identifiable {
    case s10, p2s6r4, p2s5r23, op300, fs300, pstd, pop, pfs, ap10s, ap20s, custom
    var id: String { rawValue }

    /// Fixed parts of the format; nil for custom.
    var parts: [Segment]? {
        func s(_ k: SegmentKind, _ n: Int, _ t: Target? = nil) -> Segment { Segment(kind: k, count: n, target: t) }
        switch self {
        case .s10: return [s(.single, 10)]
        case .p2s6r4: return [s(.practice, 2), s(.single, 6), s(.series, 4)]
        case .p2s5r23: return [s(.practice, 2), s(.single, 5), s(.series, 2), s(.series, 3)]
        case .op300: return [s(.single, 5, .a5), s(.single, 5, .b4), s(.series, 2, .b4), s(.series, 3, .b4), s(.series, 5, .b4)]
        case .fs300: return [s(.single, 6, .b4), s(.series, 3, .b4), s(.series, 3, .b4), s(.series, 6, .b4)]
        case .pstd: return [s(.series, 5), s(.series, 5), s(.series, 5)]
        case .pop: return [s(.single, 5), s(.series, 5), s(.series, 5), s(.series, 5)]
        case .pfs: return [s(.single, 3), s(.series, 5), s(.series, 5), s(.series, 5)]
        case .ap10s: return [s(.single, 10)]
        case .ap20s: return [s(.single, 20)]
        case .custom: return nil
        }
    }
}

enum Discipline: String, CaseIterable, Identifiable {
    case r300, p25, p50, ap10, ag10, other
    var id: String { rawValue }

    var formats: [SeriesFormat] {
        switch self {
        case .r300: return [.s10, .p2s6r4, .p2s5r23, .op300, .fs300, .custom]
        case .p25, .p50: return [.pstd, .pop, .pfs, .custom]
        case .ap10, .ag10: return [.ap10s, .ap20s, .custom]   // air pistol and air rifle 10m
        case .other: return [.custom]
        }
    }
    var defaultFormat: SeriesFormat { formats[0] }
    /// Pistol and air pistol always score 0–10; the others may choose a target for custom series.
    var hasTargetChoice: Bool { self == .r300 || self == .other }
    /// Inner ten (X) key for pistol on the 10-point target.
    var allowsX: Bool { self == .p25 || self == .p50 }

    /// Disciplines saved by earlier web versions carried a target.
    static func from(_ raw: String?) -> Discipline {
        if let raw, let d = Discipline(rawValue: raw) { return d }
        switch raw ?? "" {
        case "r300a10", "r300a100", "r300a5", "r300b4": return .r300
        default: return .other
        }
    }
}

enum Category: String, CaseIterable, Identifiable {
    case training, championship, other
    var id: String { rawValue }
}

// MARK: - Series

struct Series: Codable, Hashable {
    var type: String = SeriesFormat.s10.rawValue
    var target: String? = Target.a10.rawValue
    var planned: Int? = 10
    var practice: Int? = 0
    var shots: [Shot] = []
    var total: Double? = nil
    var tief: Int? = nil
    var desc: String? = nil          // legacy, merged into the session description

    enum CodingKeys: String, CodingKey { case type, target, planned, practice, shots, total, tief, desc }

    init(format: SeriesFormat = .s10) {
        type = format.rawValue
        if let parts = format.parts { planned = parts.reduce(0) { $0 + $1.count } } else { planned = 10 }
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        type = (try? c.decodeIfPresent(String.self, forKey: .type)) ?? SeriesFormat.s10.rawValue
        target = try? c.decodeIfPresent(String.self, forKey: .target)
        planned = Self.int(c, .planned)
        practice = Self.int(c, .practice)
        shots = (try? c.decodeIfPresent([Shot].self, forKey: .shots)) ?? []
        if let d = try? c.decodeIfPresent(Double.self, forKey: .total) { total = d }
        tief = Self.int(c, .tief)
        desc = try? c.decodeIfPresent(String.self, forKey: .desc)
    }
    private static func int(_ c: KeyedDecodingContainer<CodingKeys>, _ k: CodingKeys) -> Int? {
        if let i = try? c.decodeIfPresent(Int.self, forKey: k) { return i }
        if let d = try? c.decodeIfPresent(Double.self, forKey: k) { return Int(d) }
        if let s = try? c.decodeIfPresent(String.self, forKey: k) { return Int(s) }
        return nil
    }

    /// The format, with older "10 shots" series of another length treated as custom.
    var format: SeriesFormat {
        let f = SeriesFormat(rawValue: type) ?? .s10
        if f == .s10 && max(planned ?? 10, shots.count) != 10 { return .custom }
        return f
    }
    var targetValue: Target { Target(rawValue: target ?? "") ?? .a10 }
    var hasResult: Bool { !shots.isEmpty || total != nil }

    var segments: [Segment] {
        if let parts = format.parts {
            var start = 0
            return parts.map { var p = $0; p.start = start; start += p.count; return p }
        }
        let p = max(0, min(20, practice ?? 0))
        let n = max(planned ?? 0, shots.count - p)
        return p > 0 ? [Segment(kind: .practice, count: p, start: 0), Segment(kind: .single, count: n, start: p)]
                     : [Segment(kind: .single, count: n, start: 0)]
    }
    func segment(at index: Int) -> Segment? { segments.first { index >= $0.start && index < $0.start + $0.count } }
    func isPractice(_ index: Int) -> Bool { segment(at: index)?.kind == .practice }
    /// Highest value for the shot at this position.
    func maxValue(at index: Int) -> Int { segment(at: index)?.target?.max ?? targetValue.max }

    struct Stats { var total: Double; var max: Int; var x: Int; var slots: Int; var counted: Int; var practice: Int; var practiceCount: Int; var manual: Bool }
    var stats: Stats {
        let segs = segments
        let slots = segs.reduce(0) { $0 + $1.count }
        let practiceCount = segs.filter { $0.kind == .practice }.reduce(0) { $0 + $1.count }
        var sum = 0, x = 0, prac = 0
        for (i, s) in shots.enumerated() {
            if isPractice(i) { prac += s.points } else { sum += s.points; if s == .x { x += 1 } }
        }
        let maxSum = segs.filter { $0.kind != .practice }.reduce(0) { $0 + $1.count * ($1.target?.max ?? targetValue.max) }
        return Stats(total: total ?? Double(sum), max: maxSum, x: x, slots: slots, counted: slots - practiceCount, practice: prac, practiceCount: practiceCount, manual: total != nil)
    }
    /// Result converted to 10 shots on A10 (0–100), nil when nothing was entered.
    var normalized: Double? {
        guard hasResult else { return nil }
        let s = stats
        guard s.max > 0 else { return nil }
        return (s.total / Double(s.max) * 1000).rounded() / 10
    }
    /// "(7 8) · 10 9 9" with practice shots in brackets.
    var shotsText: String {
        segments.compactMap { g -> String? in
            let end = min(shots.count, g.start + g.count)
            guard g.start < end else { return nil }
            let v = shots[g.start..<end].map(\.label).joined(separator: " ")
            return g.kind == .practice ? "(\(v))" : v
        }.joined(separator: " · ")
    }
}

// MARK: - Session

struct Session: Codable, Identifiable, Hashable {
    var id: String = UUID().uuidString
    var date: String = Session.dayString(Date())
    var preset: String = Discipline.r300.rawValue
    var category: String? = Category.training.rawValue
    var desc: String? = nil
    var location: String? = nil
    var weapon: String? = nil
    var conditions: String? = nil
    var notes: String? = nil
    var photos: [String] = []
    var series: [Series] = [Series()]
    var createdAt: Double? = Date().timeIntervalSince1970 * 1000
    var updatedAt: Double? = nil
    // legacy fields from earlier web versions
    var max: Int? = nil
    var tief: Int? = nil
    var program: String? = nil

    enum CodingKeys: String, CodingKey {
        case id, date, preset, category, desc, location, weapon, conditions, notes, photos, series, createdAt, updatedAt, max, tief, program
    }

    init() {}

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = (try? c.decode(String.self, forKey: .id)) ?? UUID().uuidString
        date = (try? c.decodeIfPresent(String.self, forKey: .date)) ?? Session.dayString(Date())
        preset = (try? c.decodeIfPresent(String.self, forKey: .preset)) ?? Discipline.r300.rawValue
        category = try? c.decodeIfPresent(String.self, forKey: .category)
        desc = try? c.decodeIfPresent(String.self, forKey: .desc)
        location = try? c.decodeIfPresent(String.self, forKey: .location)
        weapon = try? c.decodeIfPresent(String.self, forKey: .weapon)
        conditions = try? c.decodeIfPresent(String.self, forKey: .conditions)
        notes = try? c.decodeIfPresent(String.self, forKey: .notes)
        photos = (try? c.decodeIfPresent([String].self, forKey: .photos)) ?? []
        series = (try? c.decodeIfPresent([Series].self, forKey: .series)) ?? []
        createdAt = try? c.decodeIfPresent(Double.self, forKey: .createdAt)
        updatedAt = try? c.decodeIfPresent(Double.self, forKey: .updatedAt)
        max = (try? c.decodeIfPresent(Int.self, forKey: .max)) ?? nil
        tief = (try? c.decodeIfPresent(Int.self, forKey: .tief)) ?? nil
        program = try? c.decodeIfPresent(String.self, forKey: .program)
    }

    var discipline: Discipline { Discipline.from(preset) }
    var categoryValue: Category {
        if let c = Category(rawValue: category ?? "") { return c }
        return (program ?? "").lowercased().contains("training") ? .training : .other
    }
    var descText: String {
        if let d = desc { return d }
        return series.compactMap { $0.desc }.filter { !$0.isEmpty }.joined(separator: " · ")
    }
    var year: String { date.count >= 4 ? String(date.prefix(4)) : "—" }
    var day: Date { Session.dayFormatter.date(from: date) ?? Date() }
    var countedShots: Int { series.reduce(0) { $0 + $1.stats.counted } }
    /// Average of the series converted to 10 shots on A10.
    var normalizedAverage: Double? {
        let v = series.compactMap(\.normalized)
        return v.isEmpty ? nil : (v.reduce(0, +) / Double(v.count) * 10).rounded() / 10
    }

    /// Brings sessions from earlier versions to the current model.
    func normalizedForCurrentVersion() -> Session {
        var s = self
        s.preset = discipline.rawValue
        if s.category == nil || Category(rawValue: s.category!) == nil { s.category = categoryValue.rawValue }
        if s.desc == nil { s.desc = descText }
        let legacyTarget: Target
        switch self.max ?? 10 {
        case 100: legacyTarget = .a100
        case 5: legacyTarget = .a5
        case 4: legacyTarget = .b4
        default: legacyTarget = .a10
        }
        s.series = s.series.map { orig in
            var r = orig
            r.desc = nil
            if orig.target == nil {
                if legacyTarget != .a10 && [.s10, .p2s6r4, .p2s5r23].contains(orig.format) {
                    r.planned = Swift.max(orig.planned ?? 0, orig.shots.count); r.type = SeriesFormat.custom.rawValue
                }
                r.target = legacyTarget.rawValue
            }
            return r
        }
        if let t = tief, !s.series.isEmpty, s.series[0].tief == nil { s.series[0].tief = t }
        s.tief = nil; s.max = nil
        return s
    }

    static let dayFormatter: DateFormatter = {
        let f = DateFormatter(); f.calendar = Calendar(identifier: .gregorian); f.locale = Locale(identifier: "en_US_POSIX"); f.dateFormat = "yyyy-MM-dd"; return f
    }()
    static func dayString(_ d: Date) -> String { dayFormatter.string(from: d) }
}
