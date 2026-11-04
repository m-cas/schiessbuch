import SwiftUI

/// How to use the app; the text (help.json) is the same in the web, iPhone and Android versions.
struct HelpView: View {
    @Environment(\.lang) private var lang
    private struct Section: Decodable, Hashable { let t: String; let p: [String] }

    private var sections: [Section] {
        guard let url = Bundle.main.url(forResource: "help", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let all = try? JSONDecoder().decode([String: [Section]].self, from: data) else { return [] }
        return all[lang] ?? all["en"] ?? []
    }

    var body: some View {
        List {
            ForEach(Array(sections.enumerated()), id: \.offset) { i, s in
                SwiftUI.Section {
                    DisclosureGroup(isExpanded: Binding(get: { open.contains(i) }, set: { if $0 { open.insert(i) } else { open.remove(i) } })) {
                        ForEach(s.p, id: \.self) { Text($0).font(.callout).padding(.vertical, 2) }
                    } label: {
                        Text(s.t).font(.headline)
                    }
                }
            }
        }
        .navigationTitle(Tx(lang).helpH)
        .navigationBarTitleDisplayMode(.inline)
    }
    @State private var open: Set<Int> = [0]
}
