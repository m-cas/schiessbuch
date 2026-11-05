import SwiftUI
import UniformTypeIdentifiers

/// The championship page: the items of the loaded championship, activated one by one with + and reset with ✕.
/// The cog at the top left opens the championship settings, where the club's CSV file is loaded.
struct ChampionshipView: View {
    @Environment(Store.self) private var store
    @Environment(\.lang) private var lang
    @Environment(\.dismiss) private var dismiss

    @State private var resetItem: ChampItem? = nil
    @State private var editing: Session? = nil
    @State private var showSettings = false
    @State private var path: [String] = []

    private var tx: Tx { Tx(lang) }

    var body: some View {
        NavigationStack(path: $path) {
            List {
                if let cur = store.champ.current {
                    let done = cur.items.filter { store.champSession($0) != nil }.count
                    Section {
                        VStack(alignment: .leading, spacing: 8) {
                            Text("\(tx.chTitle) \(String(cur.year))").font(.caption).foregroundStyle(.secondary).textCase(.uppercase)
                            Text(cur.name).font(.title3.weight(.bold)).lineLimit(1).minimumScaleFactor(0.6)
                            ProgressView(value: Double(done), total: Double(max(cur.items.count, 1)))
                            Text(tx.chProgress(done, cur.items.count)).font(.footnote).foregroundStyle(.secondary).monospacedDigit()
                        }
                        .padding(.vertical, 4)
                    }
                    Section {
                        ForEach(cur.items) { item in row(item) }
                    }
                } else {
                    Section {
                        VStack(alignment: .leading, spacing: 10) {
                            Text(tx.chEmptyH).font(.headline)
                            Text(tx.chEmptyCog).font(.subheadline).foregroundStyle(.secondary)
                            Button(tx.chSettings) { showSettings = true }.buttonStyle(.borderedProminent)
                        }
                        .padding(.vertical, 4)
                    }
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle(tx.chTitle)
            .navigationBarTitleDisplayMode(.inline)
            .navigationDestination(for: String.self) { id in SessionDetailView(id: id) }
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button { showSettings = true } label: { Image(systemName: "gearshape") }
                        .accessibilityLabel(tx.chSettings)
                }
                ToolbarItem(placement: .confirmationAction) { Button(tx.done) { dismiss() } }
            }
            .sheet(item: $editing) { s in SessionEditor(original: s, isNew: false) }
            .sheet(isPresented: $showSettings) { ChampSettingsView() }
            // an alert always shows all three choices (a confirmation dialog hides Cancel when shown as a popover)
            .alert(tx.chResetAsk, isPresented: Binding(get: { resetItem != nil }, set: { if !$0 { resetItem = nil } })) {
                Button(tx.chKeep) { if let i = resetItem { store.resetChamp(i.id, deleteSession: false) }; resetItem = nil }
                Button(tx.chDelete, role: .destructive) { if let i = resetItem { store.resetChamp(i.id, deleteSession: true) }; resetItem = nil }
                Button(tx.cancel, role: .cancel) { resetItem = nil }
            }
        }
    }

    /// One item: description on the left, the round + or ✕ button at the bottom right.
    private func row(_ item: ChampItem) -> some View {
        let s = store.champSession(item)
        let active = s != nil
        let what = tx.disciplineName(item.discipline) + " · " + item.series.map { $0.groupText(tx) }.joined(separator: " | ")
        let meta = [item.day.map { tx.longDate($0) }, item.location].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · ")
        return HStack(alignment: .bottom, spacing: 10) {
            Image(systemName: active ? "checkmark.circle.fill" : "circle")
                .font(.title3).foregroundStyle(active ? Color.accentColor : Color.secondary)
                .frame(maxHeight: .infinity, alignment: .top)
            VStack(alignment: .leading, spacing: 3) {
                // only the description fades on a completed item; result and button stay fully visible
                Group {
                    Text(item.title).fontWeight(.semibold).lineLimit(1).minimumScaleFactor(0.7)
                    Text(what).font(.caption).foregroundStyle(.secondary)
                    if !meta.isEmpty { Text(meta).font(.caption).foregroundStyle(.secondary) }
                    if let n = item.notes, !n.isEmpty { Text(n).font(.caption).foregroundStyle(.secondary) }
                }
                .opacity(active ? 0.55 : 1)
                if let s {
                    Button { path.append(s.id) } label: {
                        Text("\(result(s)) · \(tx.shortDate(s.day)) ›").font(.subheadline.weight(.semibold)).monospacedDigit()
                    }
                    .buttonStyle(.borderless)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Button {
                if active { resetItem = item } else if let s = store.activate(item.id) { editing = s }
            } label: {
                Image(systemName: active ? "xmark" : "plus")
                    .font(.system(size: active ? 15 : 18, weight: .bold))
                    .frame(width: 36, height: 36)
                    .foregroundStyle(active ? Color.red : Color.white)
                    .background(Circle().fill(active ? Color(.secondarySystemFill) : Color.accentColor))
            }
            .buttonStyle(.borderless)
            .accessibilityLabel(active ? tx.chReset : tx.chActivate)
        }
        .padding(.vertical, 2)
    }

    private func result(_ s: Session) -> String {
        let done = s.series.filter(\.hasResult)
        if done.isEmpty { return tx.chNoResult }
        return done.map { "\(tx.score($0.stats.total))/\($0.stats.max)" }.joined(separator: " · ")
    }
}

/// Championship settings: address of the club's CSV file, load or reload it, or pick a file.
struct ChampSettingsView: View {
    @Environment(Store.self) private var store
    @Environment(\.lang) private var lang
    @Environment(\.dismiss) private var dismiss

    @State private var url = ""
    @State private var busy = false
    @State private var pending: ChampParser.Result? = nil
    @State private var warnings: [String] = []
    @State private var message: String? = nil
    @State private var importing = false

    private var tx: Tx { Tx(lang) }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    TextField("https://…/meisterschaft.csv", text: $url)
                        .keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                        .onSubmit { fetch() }
                    Button { fetch() } label: {
                        HStack {
                            Label(store.champ.current == nil ? tx.chLoad : tx.chReload, systemImage: "arrow.down.circle")
                            if busy { Spacer(); ProgressView() }
                        }
                    }
                    .disabled(busy)
                    Button { importing = true } label: { Label(tx.chFromFile, systemImage: "folder") }
                    if !warnings.isEmpty {
                        DisclosureGroup {
                            ForEach(warnings, id: \.self) { Text($0).font(.footnote) }
                        } label: {
                            Label(tx.chWarnN(warnings.count), systemImage: "exclamationmark.triangle").foregroundStyle(.orange)
                        }
                    }
                } header: { Text(tx.chUrl) } footer: {
                    VStack(alignment: .leading, spacing: 6) {
                        if let cur = store.champ.current { Text(tx.chLoadedInfo(cur.year, cur.items.count)) }
                        Text(tx.chOnlyOne)
                    }
                }
                Section {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(tx.chEmptyP).font(.footnote).foregroundStyle(.secondary)
                        Text("id;jahr;titel;disziplin;serien;datum;schiessstand;notizen").font(.caption.monospaced()).textSelection(.enabled)
                    }
                }
            }
            .navigationTitle(tx.chSettings)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button(tx.done) { dismiss() } } }
            .fileImporter(isPresented: $importing, allowedContentTypes: [.commaSeparatedText, .plainText, .text, .data]) { result in
                guard case .success(let u) = result else { return }
                let access = u.startAccessingSecurityScopedResource()
                defer { if access { u.stopAccessingSecurityScopedResource() } }
                if let d = try? Data(contentsOf: u) { loaded(d) } else { message = tx.chBadFile }
            }
            .alert(pendingQuestion, isPresented: Binding(get: { pending != nil }, set: { if !$0 { pending = nil } })) {
                Button(tx.chLoad) { if let p = pending { apply(p) } }
                Button(tx.cancel, role: .cancel) { pending = nil }
            }
            .alert(message ?? "", isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })) {
                Button(tx.ok, role: .cancel) {}
            }
            .onAppear { url = store.champ.url }
        }
    }

    private var pendingQuestion: String {
        guard let p = pending else { return "" }
        if let cur = store.champ.current, cur.year != p.champ.year { return tx.chAskNew(p.champ.year, cur.year) }
        return tx.chAskSame(p.champ.year)
    }

    private func fetch() {
        store.champ.url = url.trimmingCharacters(in: .whitespaces)
        store.saveChamp()
        guard url.lowercased().hasPrefix("http"), let u = ChampParser.directURL(url) else { message = tx.chNeedUrl; return }
        busy = true
        Task {
            defer { busy = false }
            do {
                var req = URLRequest(url: u); req.cachePolicy = .reloadIgnoringLocalCacheData
                let (data, resp) = try await URLSession.shared.data(for: req)
                if let h = resp as? HTTPURLResponse, !(200..<300).contains(h.statusCode) { message = tx.chFetchFail + " (HTTP \(h.statusCode))"; return }
                loaded(data)
            } catch { message = tx.chFetchFail }
        }
    }

    private func loaded(_ data: Data) {
        let text = String(data: data, encoding: .utf8) ?? String(data: data, encoding: .isoLatin1) ?? ""
        do {
            let r = try ChampParser.parse(text, tx: tx)
            if store.champ.current == nil { apply(r) } else { pending = r }
        } catch ChampParser.Failure.noYear { message = tx.chNoYear }
        catch ChampParser.Failure.needColumns { message = tx.chNeedCols }
        catch ChampParser.Failure.noRows { message = tx.chNoRows }
        catch { message = tx.chBadFile }
    }

    private func apply(_ r: ChampParser.Result) {
        store.applyChamp(r.champ)
        warnings = r.warnings
        pending = nil
        message = tx.chLoaded(r.champ.items.count, r.champ.year) + (r.warnings.isEmpty ? "" : " · " + tx.chWarnN(r.warnings.count))
    }
}
