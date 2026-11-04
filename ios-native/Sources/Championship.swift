import Foundation

/// A championship: the sessions the club asks to be shot in one year, loaded from a CSV file.
/// Stored on the phone in the same JSON shape as the web app ("championship" in backups).
struct ChampSeries: Codable, Hashable {
    var type: String
    var target: String?
    var planned: Int?
    var practice: Int?
}

struct ChampItem: Codable, Hashable, Identifiable {
    var id: String
    var title: String
    var preset: String
    var series: [ChampSeries]
    var date: String?
    var location: String?
    var notes: String?
    var sessionId: String?

    var discipline: Discipline { Discipline.from(preset) }
    var day: Date? { date.flatMap { Session.dayFormatter.date(from: $0) } }
}

struct Championship: Codable, Hashable {
    var year: Int
    var name: String
    var loadedAt: Double?
    var items: [ChampItem]
}

struct ChampState: Codable, Hashable {
    var url: String = ""
    var current: Championship? = nil
    var history: [Championship] = []

    enum CodingKeys: String, CodingKey { case url, current, history }
    init() {}
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        url = (try? c.decodeIfPresent(String.self, forKey: .url)) ?? ""
        current = try? c.decodeIfPresent(Championship.self, forKey: .current)
        history = (try? c.decodeIfPresent([Championship].self, forKey: .history)) ?? []
    }
}

// MARK: - Reading the CSV file

enum ChampParser {
    struct Result { var champ: Championship; var warnings: [String] }
    enum Failure: Error { case noRows, needColumns, noYear }

    static let columns: [String: [String]] = [
        "id": ["id", "nr", "nummer", "no"], "year": ["jahr", "year"],
        "title": ["titel", "title", "name", "beschreibung", "description", "anlass", "event"],
        "disc": ["disziplin", "discipline", "waffe"], "series": ["serien", "series", "programm", "program", "format"],
        "date": ["datum", "date", "bis", "deadline", "termin"], "location": ["schiessstand", "stand", "range", "location", "ort"],
        "notes": ["notizen", "notes", "bemerkung", "bemerkungen", "info"], "champ": ["meisterschaft", "championship"],
    ]

    static func rows(_ text: String, sep: Character) -> [[String]] {
        var rows: [[String]] = [], row: [String] = [], cell = "", quoted = false
        let chars = Array(text)
        var i = 0
        while i < chars.count {
            let c = chars[i]
            if quoted {
                if c == "\"" { if i + 1 < chars.count && chars[i + 1] == "\"" { cell.append("\""); i += 1 } else { quoted = false } } else { cell.append(c) }
            } else if c == "\"" { quoted = true }
            else if c == sep { row.append(cell); cell = "" }
            else if c == "\n" || c == "\r" || c == "\r\n" {
                row.append(cell); rows.append(row); row = []; cell = ""
                if c == "\r" && i + 1 < chars.count && chars[i + 1] == "\n" { i += 1 }
            } else { cell.append(c) }
            i += 1
        }
        if !cell.isEmpty || !row.isEmpty { row.append(cell); rows.append(row) }
        return rows.map { $0.map { $0.trimmingCharacters(in: .whitespaces) } }.filter { $0.contains { !$0.isEmpty } }
    }

    static func discipline(_ v: String) -> Discipline? {
        let s = v.lowercased().replacingOccurrences(of: " ", with: "")
        if ["r300", "g300", "gewehr300m", "gewehr300", "rifle300m", "rifle300", "300m", "gewehr"].contains(s) { return .r300 }
        if ["p25", "pistole25m", "pistol25m", "25m"].contains(s) { return .p25 }
        if ["p50", "pistole50m", "pistol50m", "50m"].contains(s) { return .p50 }
        if ["ap10", "lp10", "luftpistole10m", "airpistol10m", "luftpistole", "10m"].contains(s) { return .ap10 }
        if ["other", "andere", "anderes"].contains(s) { return .other }
        return nil
    }

    static func date(_ v: String) -> String? {
        let s = v.trimmingCharacters(in: .whitespaces)
        let iso = s.split(separator: "-").map(String.init)
        if iso.count == 3, iso[0].count == 4, let y = Int(iso[0]), let m = Int(iso[1]), let d = Int(iso[2]) { return String(format: "%04d-%02d-%02d", y, m, d) }
        let dm = s.split(whereSeparator: { $0 == "." || $0 == "/" }).map(String.init)
        if dm.count == 3, dm[2].count == 4, let d = Int(dm[0]), let m = Int(dm[1]), let y = Int(dm[2]) { return String(format: "%04d-%02d-%02d", y, m, d) }
        return nil
    }

    /// One token per series: a format code (s10, op300, pstd …) or custom:SHOTS[:TARGET[:PRACTICE]], or just a number of shots.
    static func series(_ text: String, _ d: Discipline, warn: (String) -> Void) -> [ChampSeries] {
        let toks = text.split(whereSeparator: { " +,|\t".contains($0) }).map(String.init)
        var out: [ChampSeries] = []
        for raw in toks {
            let tk = raw.lowercased()
            if let f = SeriesFormat(rawValue: tk), f != .custom, d.formats.contains(f) {
                out.append(ChampSeries(type: f.rawValue, target: Target.a10.rawValue, planned: f.parts?.reduce(0) { $0 + $1.count }, practice: 0)); continue
            }
            var parts = tk.split(separator: ":").map(String.init)
            if let first = parts.first, ["custom", "eigene", "c"].contains(first) { parts.removeFirst() }
            if let n = parts.first.flatMap(Int.init), n > 0, parts.count <= 3 {
                let tgt = parts.count > 1 ? Target(rawValue: parts[1].uppercased()) : nil
                let prac = parts.count > 2 ? Int(parts[2]) : 0
                if (parts.count > 1 && tgt == nil) || prac == nil { warn(raw); continue }
                out.append(ChampSeries(type: SeriesFormat.custom.rawValue, target: (d.hasTargetChoice ? tgt : nil)?.rawValue ?? Target.a10.rawValue,
                                       planned: min(200, n), practice: min(20, prac ?? 0)))
                continue
            }
            warn(raw)
        }
        if out.isEmpty {
            let f = d.defaultFormat
            out.append(ChampSeries(type: f.rawValue, target: Target.a10.rawValue, planned: f.parts?.reduce(0) { $0 + $1.count } ?? 10, practice: 0))
        }
        return out
    }

    static func parse(_ input: String, tx: Tx) throws -> Result {
        let text = input.hasPrefix("\u{FEFF}") ? String(input.dropFirst()) : input
        let first = text.split(whereSeparator: \.isNewline).first { !$0.trimmingCharacters(in: .whitespaces).isEmpty }.map(String.init) ?? ""
        let sep: Character = [";", "\t", ","].max { a, b in first.filter { $0 == a }.count < first.filter { $0 == b }.count } ?? ";"
        let all = rows(text, sep: sep)
        guard all.count >= 2 else { throw Failure.noRows }
        let head = all[0].map { $0.lowercased().filter { $0.isLetter } }
        var col: [String: Int] = [:]
        for (k, names) in columns { if let i = head.firstIndex(where: names.contains) { col[k] = i } }
        guard col["title"] != nil, col["disc"] != nil else { throw Failure.needColumns }

        var warnings: [String] = [], items: [ChampItem] = [], ids = Set<String>()
        var year: Int? = nil, name = ""
        for (n, r) in all.dropFirst().enumerated() {
            let line = n + 2
            func get(_ k: String) -> String { guard let i = col[k], i < r.count else { return "" }; return r[i] }
            let title = get("title"); if title.isEmpty { continue }
            guard let d = discipline(get("disc")) else { warnings.append(tx.chBadDisc(line, get("disc"))); continue }
            let yv = get("year")
            if year == nil, let m = yv.range(of: #"\d{4}"#, options: .regularExpression) { year = Int(yv[m]) }
            if name.isEmpty { name = get("champ") }
            var id = get("id").isEmpty ? title : get("id")
            while ids.contains(id) { id += "*" }
            ids.insert(id)
            let series = series(get("series"), d) { warnings.append(tx.chBadSeries(line, $0)) }
            items.append(ChampItem(id: id, title: title, preset: d.rawValue, series: series, date: date(get("date")),
                                   location: get("location"), notes: get("notes"), sessionId: nil))
        }
        guard !items.isEmpty else { throw Failure.noRows }
        if year == nil, let d = items.compactMap(\.date).first { year = Int(d.prefix(4)) }
        guard let y = year else { throw Failure.noYear }
        return Result(champ: Championship(year: y, name: name.isEmpty ? tx.chDefaultName(y) : name, loadedAt: Date().timeIntervalSince1970 * 1000, items: items), warnings: warnings)
    }

    /// Turns share links of common services into direct download links.
    static func directURL(_ s: String) -> URL? {
        guard var c = URLComponents(string: s.trimmingCharacters(in: .whitespaces)), let host = c.host else { return nil }
        if host == "dropbox.com" || host.hasSuffix(".dropbox.com") {
            c.host = "dl.dropboxusercontent.com"; c.queryItems = c.queryItems?.filter { $0.name != "dl" }
        } else if host == "drive.google.com", let r = c.path.range(of: #"/file/d/[^/]+"#, options: .regularExpression) {
            let id = c.path[r].split(separator: "/").last.map(String.init) ?? ""
            return URL(string: "https://drive.google.com/uc?export=download&id=\(id)")
        } else if host == "github.com", c.path.contains("/blob/") {
            c.host = "raw.githubusercontent.com"; c.path = c.path.replacingOccurrences(of: "/blob/", with: "/")
        }
        return c.url
    }
}

// MARK: - Keeping it on the phone

extension Store {
    var champURL: URL { dataDir.appendingPathComponent("championship.json") }

    func loadChamp() {
        guard let d = try? Data(contentsOf: champURL), let s = try? JSONDecoder().decode(ChampState.self, from: d) else { return }
        champ = s
    }
    func saveChamp() {
        try? JSONEncoder().encode(champ).write(to: champURL, options: .atomic)
    }

    /// Makes a loaded file the current championship. The previous one of another year moves to the history;
    /// activated items keep their session when the same year is loaded again.
    func applyChamp(_ new: Championship) {
        var p = new
        func keep(_ from: Championship?) {
            guard let from else { return }
            let m = Dictionary(from.items.compactMap { i in i.sessionId.map { (i.id, $0) } }, uniquingKeysWith: { a, _ in a })
            for i in p.items.indices where p.items[i].sessionId == nil { p.items[i].sessionId = m[p.items[i].id] }
        }
        if let cur = champ.current, cur.year == p.year { keep(cur) }
        else {
            if let cur = champ.current { champ.history = champ.history.filter { $0.year != cur.year } + [cur] }
            keep(champ.history.first { $0.year == p.year })
            champ.history = champ.history.filter { $0.year != p.year }.sorted { $0.year > $1.year }
        }
        champ.current = p
        saveChamp()
    }

    func champSession(_ item: ChampItem) -> Session? { item.sessionId.flatMap(session) }

    /// Creates the session for an item and marks the item as active.
    func activate(_ itemId: String) -> Session? {
        guard var cur = champ.current, let i = cur.items.firstIndex(where: { $0.id == itemId }) else { return nil }
        let item = cur.items[i]
        let last = sessions.first
        var s = Session()
        s.preset = item.discipline.rawValue
        s.category = Category.championship.rawValue
        s.desc = item.title
        s.location = (item.location?.isEmpty == false ? item.location : last?.location)
        s.weapon = last?.discipline == item.discipline ? last?.weapon : nil
        s.notes = item.notes?.isEmpty == false ? item.notes : nil
        s.series = item.series.map { c in
            var r = Series(format: SeriesFormat(rawValue: c.type) ?? item.discipline.defaultFormat)
            r.target = c.target ?? Target.a10.rawValue
            r.planned = c.planned ?? r.planned
            r.practice = c.practice ?? 0
            return r
        }
        save(s)
        cur.items[i].sessionId = s.id
        champ.current = cur
        saveChamp()
        return s
    }

    func resetChamp(_ itemId: String, deleteSession: Bool) {
        guard var cur = champ.current, let i = cur.items.firstIndex(where: { $0.id == itemId }) else { return }
        if deleteSession, let sid = cur.items[i].sessionId { delete([sid]) }
        cur.items[i].sessionId = nil
        champ.current = cur
        saveChamp()
    }

    /// Sample championship for the test screenshots ("-demo").
    func seedDemoChamp() {
        let csv = """
        id;jahr;meisterschaft;titel;disziplin;serien;datum;schiessstand
        VM1;2026;Vereinsmeisterschaft SG Muttenz 2026;Runde 1 Gewehr;r300;s10+s10;30.04.2026;Muttenz
        OP;2026;;Obligatorisches;r300;op300;31.08.2026;
        P25;2026;;Pistole Standard;p25;pstd;30.06.2026;Muttenz
        FREI;2026;;Final Gewehr;r300;custom:20:A100:2;30.09.2026;Muttenz
        """
        guard let r = try? ChampParser.parse(csv, tx: Tx("de")) else { return }
        champ.current = r.champ
        if let s = sessions.first(where: { $0.categoryValue == .championship }) { champ.current?.items[0].sessionId = s.id }
    }

    func dropHistory(_ year: Int) {
        champ.history.removeAll { $0.year == year }
        saveChamp()
    }
}
