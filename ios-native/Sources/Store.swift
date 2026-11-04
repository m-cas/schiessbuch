import Foundation
import UIKit
import Observation

/// Keeps all sessions in a JSON file and the target photos as JPEG files,
/// both in the app's own storage on this phone. Nothing is uploaded.
@Observable
final class Store {
    private(set) var sessions: [Session] = []
    var lastError: String? = nil

    @ObservationIgnored private let fm = FileManager.default
    @ObservationIgnored private let base: URL
    private var sessionsURL: URL { base.appendingPathComponent("sessions.json") }
    var photosDir: URL { base.appendingPathComponent("photos", isDirectory: true) }
    @ObservationIgnored private var thumbCache = NSCache<NSString, UIImage>()

    init(demo: Bool = false) {
        let support = fm.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        base = support.appendingPathComponent(demo ? "demo" : "data", isDirectory: true)
        try? fm.createDirectory(at: photosDir, withIntermediateDirectories: true)
        load()
        if demo && sessions.isEmpty { seedDemo() }
    }

    // MARK: sessions

    private func load() {
        guard let data = try? Data(contentsOf: sessionsURL) else { sessions = []; return }
        let list = (try? JSONDecoder().decode([Session].self, from: data)) ?? []
        sessions = sorted(list.map { $0.normalizedForCurrentVersion() })
    }
    private func persist() {
        do {
            let data = try JSONEncoder().encode(sessions)
            try data.write(to: sessionsURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        } catch { lastError = error.localizedDescription }
    }
    private func sorted(_ list: [Session]) -> [Session] {
        list.sorted { a, b in a.date != b.date ? a.date > b.date : (a.createdAt ?? 0) > (b.createdAt ?? 0) }
    }
    func session(_ id: String) -> Session? { sessions.first { $0.id == id } }

    func save(_ s: Session) {
        var s = s
        s.updatedAt = Date().timeIntervalSince1970 * 1000
        if let i = sessions.firstIndex(where: { $0.id == s.id }) { sessions[i] = s } else { sessions.append(s) }
        sessions = sorted(sessions)
        persist()
    }
    func delete(_ ids: Set<String>) {
        let gone = sessions.filter { ids.contains($0.id) }
        sessions.removeAll { ids.contains($0.id) }
        persist()
        for s in gone { s.photos.forEach(deletePhoto) }
    }

    // MARK: photos

    func photoURL(_ id: String) -> URL { photosDir.appendingPathComponent(id + ".jpg") }
    func image(_ id: String) -> UIImage? { UIImage(contentsOfFile: photoURL(id).path) }
    func thumbnail(_ id: String) -> UIImage? {
        if let c = thumbCache.object(forKey: id as NSString) { return c }
        guard let img = image(id), let t = img.preparingThumbnail(of: CGSize(width: 300, height: 300 * img.size.height / max(img.size.width, 1))) else { return nil }
        thumbCache.setObject(t, forKey: id as NSString)
        return t
    }
    /// Stores a photo, resized so the longer side is at most 2000 px (sharp enough to read every hole).
    func addPhoto(_ image: UIImage) -> String? {
        let scaled = image.resized(maxSide: 2000)
        guard let data = scaled.jpegData(compressionQuality: 0.85) else { return nil }
        let id = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
        do { try data.write(to: photoURL(id), options: .atomic); return id } catch { lastError = error.localizedDescription; return nil }
    }
    func deletePhoto(_ id: String) {
        try? fm.removeItem(at: photoURL(id))
        thumbCache.removeObject(forKey: id as NSString)
    }

    // MARK: storage

    struct StorageInfo { var photoBytes: Int64; var photoCount: Int; var dataBytes: Int64; var free: Int64?; var backupBytes: Int64 }
    func storageInfo() -> StorageInfo {
        var bytes: Int64 = 0, count = 0
        let used = Set(sessions.flatMap(\.photos))
        for id in used {
            if let a = try? fm.attributesOfItem(atPath: photoURL(id).path), let n = a[.size] as? NSNumber { bytes += n.int64Value; count += 1 }
        }
        let data = (try? fm.attributesOfItem(atPath: sessionsURL.path)[.size] as? NSNumber)?.int64Value ?? 0
        let free = (try? base.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]))?.volumeAvailableCapacityForImportantUsage
        return StorageInfo(photoBytes: bytes, photoCount: count, dataBytes: data, free: free, backupBytes: bytes * 4 / 3 + data)
    }
    static let mailLimit: Int64 = 20 * 1024 * 1024

    // MARK: backup (same format as the web app)

    private struct BackupPhoto: Codable { var id: String; var data: String }
    private struct Backup: Codable {
        var app: String; var version: Int?; var exported: String?
        var sessions: [Session]; var photos: [BackupPhoto]?
    }

    func backupFile(name: String) throws -> URL {
        var photos: [BackupPhoto] = []
        for id in Set(sessions.flatMap(\.photos)) {
            if let d = try? Data(contentsOf: photoURL(id)) { photos.append(BackupPhoto(id: id, data: "data:image/jpeg;base64," + d.base64EncodedString())) }
        }
        let b = Backup(app: "scorebook", version: 1, exported: ISO8601DateFormatter().string(from: Date()), sessions: sessions, photos: photos)
        let url = fm.temporaryDirectory.appendingPathComponent(name + ".json")
        try JSONEncoder().encode(b).write(to: url, options: .atomic)
        return url
    }

    enum RestoreError: Error { case notABackup }
    /// Adds the sessions and photos of a backup file; sessions with the same id are replaced.
    func restore(from url: URL) throws -> Int {
        let access = url.startAccessingSecurityScopedResource()
        defer { if access { url.stopAccessingSecurityScopedResource() } }
        let data = try Data(contentsOf: url)
        guard let b = try? JSONDecoder().decode(Backup.self, from: data), b.app == "scorebook" else { throw RestoreError.notABackup }
        for p in b.photos ?? [] {
            guard let comma = p.data.firstIndex(of: ","), let bytes = Data(base64Encoded: String(p.data[p.data.index(after: comma)...])) else { continue }
            try? bytes.write(to: photoURL(p.id), options: .atomic)
            thumbCache.removeObject(forKey: p.id as NSString)
        }
        var byId = Dictionary(uniqueKeysWithValues: sessions.map { ($0.id, $0) })
        for s in b.sessions { byId[s.id] = s.normalizedForCurrentVersion() }
        sessions = sorted(Array(byId.values))
        persist()
        return b.sessions.count
    }

    // MARK: export and email texts

    func csvFile(_ tx: Tx) throws -> URL {
        func q(_ v: String) -> String { "\"" + v.replacingOccurrences(of: "\"", with: "\"\"") + "\"" }
        var lines = [tx.csvHead.joined(separator: ",")]
        for s in sessions {
            for (i, r) in s.series.enumerated() {
                let st = r.stats
                let cols: [String] = [s.date, tx.disciplineName(s.discipline), tx.categoryName(s.categoryValue), s.descText, s.location ?? "", s.weapon ?? "", s.conditions ?? "",
                                      String(i + 1), tx.seriesLabel(r, discipline: s.discipline), r.hasResult ? tx.score(st.total) : "", String(st.max),
                                      r.normalized.map { tx.score($0) } ?? "", String(st.x), r.tief.map(String.init) ?? "", r.shotsText, s.notes ?? ""]
                lines.append(cols.map(q).joined(separator: ","))
            }
        }
        let url = fm.temporaryDirectory.appendingPathComponent("\(tx.csvFile)-\(Session.dayString(Date())).csv")
        try ("\u{FEFF}" + lines.joined(separator: "\n")).write(to: url, atomically: true, encoding: .utf8)
        return url
    }

    func mailText(_ list: [Session], _ tx: Tx) -> (subject: String, body: String) {
        let xs = list.sorted { $0.date < $1.date }
        let subject = xs.count == 1 ? tx.mailSubject(tx.longDate(xs[0].day)) : tx.mailSubjectN(xs.count, tx.longDate(xs.first?.day ?? Date()), tx.longDate(xs.last?.day ?? Date()))
        let parts = xs.map { s -> String in
            var L = ["\(tx.longDate(s.day)) — \(tx.categoryName(s.categoryValue))", "\(tx.discipline): \(tx.disciplineName(s.discipline))"]
            if !s.descText.isEmpty { L.append("\(tx.description): \(s.descText)") }
            if let v = s.location, !v.isEmpty { L.append("\(tx.range): \(v)") }
            if let v = s.weapon, !v.isEmpty { L.append("\(tx.weapon): \(v)") }
            if let v = s.conditions, !v.isEmpty { L.append("\(tx.conditions): \(v)") }
            for (i, r) in s.series.enumerated() {
                let st = r.stats
                var line = "  \(tx.seriesTitle(i + 1)) [\(tx.seriesLabel(r, discipline: s.discipline))]: \(r.shots.isEmpty ? (st.manual ? tx.totalOnly : "—") : r.shotsText) = \(tx.score(st.total))/\(st.max)"
                if st.x > 0 { line += " · \(st.x) X" }
                if let t = r.tief { line += " · \(tx.tief) \(t)" }
                if st.practiceCount > 0 && !r.shots.isEmpty { line += " (\(tx.mPractice(st.practice)))" }
                L.append(line)
            }
            if let n = s.notes, !n.isEmpty { L.append("\(tx.notes): \(n)") }
            if !s.photos.isEmpty { L.append("\(tx.targetPhotos): \(s.photos.count)") }
            return L.joined(separator: "\n")
        }
        return (subject, "\(tx.mailGreeting)\n\n\(parts.joined(separator: "\n\n"))\n\n")
    }

    // MARK: demo data for screenshots

    private func seedDemo() {
        let rows: [(String, Discipline, Category, String, [[Int]])] = [
            ("2026-10-28", .r300, .training, "Obligatorisches", [[10, 9, 9, 10, 8, 9, 10, 9, 9, 10], [9, 8, 9, 10, 9, 9, 8, 10, 9, 9]]),
            ("2026-09-12", .r300, .championship, "Vereinsmeisterschaft", [[10, 10, 9, 10, 9, 9, 10, 10, 9, 10]]),
            ("2026-06-03", .p25, .training, "", [[10, 9, 8, 9, 10, 9, 9, 10, 8, 9, 10, 10, 9, 9, 8]]),
            ("2025-09-20", .r300, .other, "Feldschiessen", [[9, 9, 8, 9, 10, 9, 8, 9, 9, 8]]),
            ("2025-06-14", .r300, .training, "Sommertraining", [[8, 9, 9, 8, 9, 10, 9, 8, 9, 9], [9, 9, 9, 10, 8, 9, 9, 9, 8, 9]]),
        ]
        sessions = rows.map { r in
            var s = Session(); s.date = r.0; s.preset = r.1.rawValue; s.category = r.2.rawValue; s.desc = r.3
            s.series = r.4.map { shots in var x = Series(format: r.1.defaultFormat); x.shots = shots.map { .value($0) }; return x }
            return s
        }
        sessions = sorted(sessions)
    }
}

extension UIImage {
    func resized(maxSide: CGFloat) -> UIImage {
        let side = max(size.width, size.height)
        guard side > maxSide else { return self }
        let scale = maxSide / side
        let target = CGSize(width: (size.width * scale).rounded(), height: (size.height * scale).rounded())
        let format = UIGraphicsImageRendererFormat.default(); format.scale = 1
        return UIGraphicsImageRenderer(size: target, format: format).image { _ in draw(in: CGRect(origin: .zero, size: target)) }
    }
}
