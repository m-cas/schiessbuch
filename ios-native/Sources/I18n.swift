import Foundation

/// French and Italian texts from Resources/i18n.json, keyed by the English text.
/// "{}" in a key stands for a value filled in at run time ("Series {}" matches "Series 3").
/// The same file is used by the web and Android apps.
enum I18n {
    private struct Pattern { let key: String; let regex: NSRegularExpression }
    private static let table: [String: [String]] = {
        guard let url = Bundle.main.url(forResource: "i18n", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: [String]] else { return [:] }
        return obj
    }()
    private static let patterns: [Pattern] = table.keys.filter { $0.contains("{}") }
        .sorted { $0.replacingOccurrences(of: "{}", with: "").count > $1.replacingOccurrences(of: "{}", with: "").count }
        .compactMap { k in
            let body = k.components(separatedBy: "{}").map { NSRegularExpression.escapedPattern(for: $0) }.joined(separator: "([\\s\\S]+?)")
            return (try? NSRegularExpression(pattern: "^" + body + "$")).map { Pattern(key: k, regex: $0) }
        }
    private static let lock = NSLock()
    private static var memo: [String: String] = [:]

    static func tr(_ en: String, _ lang: String) -> String {
        guard !en.isEmpty, lang == "fr" || lang == "it" else { return en }
        let i = lang == "fr" ? 0 : 1
        let memoKey = lang + "\u{1}" + en
        lock.lock(); defer { lock.unlock() }
        if let hit = memo[memoKey] { return hit }
        var out = table[en].flatMap { $0.count > i ? $0[i] : nil }
        if out == nil {
            let ns = en as NSString
            for p in patterns {
                guard let m = p.regex.firstMatch(in: en, range: NSRange(location: 0, length: ns.length)),
                      let target = table[p.key], target.count > i else { continue }
                var parts = target[i].components(separatedBy: "{}")
                var s = parts.removeFirst()
                for (n, rest) in parts.enumerated() {
                    let r = m.range(at: n + 1)
                    s += (r.location != NSNotFound ? ns.substring(with: r) : "") + rest
                }
                out = s
                break
            }
        }
        let result = out ?? en
        memo[memoKey] = result
        return result
    }
}
