import SwiftUI
import UniformTypeIdentifiers

/// The championship page: load the club's CSV file, activate the items one by one, keep earlier years in a history.
struct ChampionshipView: View {
    @Environment(Store.self) private var store
    @Environment(\.lang) private var lang
    @Environment(\.dismiss) private var dismiss

    @State private var tab = 0
    @State private var url = ""
    @State private var busy = false
    @State private var pending: ChampParser.Result? = nil
    @State private var warnings: [String] = []
    @State private var message: String? = nil
    @State private var importing = false
    @State private var resetItem: ChampItem? = nil
    @State private var dropYear: Int? = nil
    @State private var editing: Session? = nil
    @State private var path: [String] = []

    private var tx: Tx { Tx(lang) }

    var body: some View {
        NavigationStack(path: $path) {
            List {
                Section {
                    Picker("", selection: $tab) {
                        Text(tx.chCurrent).tag(0)
                        Text(store.champ.history.isEmpty ? tx.chHistory : "\(tx.chHistory) (\(store.champ.history.count))").tag(1)
                    }
                    .pickerStyle(.segmented)
                    .listRowBackground(Color.clear)
                    .listRowInsets(EdgeInsets())
                }
                if tab == 0 { currentTab } else { historyTab }
            }
            .listStyle(.insetGrouped)
            .navigationTitle(tx.chTitle)
            .navigationBarTitleDisplayMode(.inline)
            .navigationDestination(for: String.self) { id in SessionDetailView(id: id) }
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button(tx.done) { dismiss() } }
            }
            .sheet(item: $editing) { s in SessionEditor(original: s, isNew: false) }
            .fileImporter(isPresented: $importing, allowedContentTypes: [.commaSeparatedText, .plainText, .text, .data]) { result in
                guard case .success(let u) = result else { return }
                let access = u.startAccessingSecurityScopedResource()
                defer { if access { u.stopAccessingSecurityScopedResource() } }
                if let d = try? Data(contentsOf: u) { loaded(d) } else { message = tx.chBadFile }
            }
            .confirmationDialog(pendingQuestion, isPresented: Binding(get: { pending != nil }, set: { if !$0 { pending = nil } }), titleVisibility: .visible) {
                Button(tx.chLoad) { if let p = pending { apply(p) } }
                Button(tx.cancel, role: .cancel) { pending = nil }
            }
            .confirmationDialog(tx.chResetAsk, isPresented: Binding(get: { resetItem != nil }, set: { if !$0 { resetItem = nil } }), titleVisibility: .visible) {
                Button(tx.chKeep) { if let i = resetItem { store.resetChamp(i.id, deleteSession: false) }; resetItem = nil }
                Button(tx.chDelete, role: .destructive) { if let i = resetItem { store.resetChamp(i.id, deleteSession: true) }; resetItem = nil }
                Button(tx.cancel, role: .cancel) { resetItem = nil }
            }
            .confirmationDialog(tx.chHistDel, isPresented: Binding(get: { dropYear != nil }, set: { if !$0 { dropYear = nil } }), titleVisibility: .visible) {
                Button(tx.chHistDel, role: .destructive) { if let y = dropYear { store.dropHistory(y) }; dropYear = nil }
                Button(tx.cancel, role: .cancel) { dropYear = nil }
            }
            .alert(message ?? "", isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })) {
                Button(tx.ok, role: .cancel) {}
            }
            .onAppear { url = store.champ.url }
        }
    }

    // MARK: tabs

    @ViewBuilder private var currentTab: some View {
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
        } header: { Text(tx.chUrl) }

        if let cur = store.champ.current {
            let done = cur.items.filter { store.champSession($0) != nil }.count
            Section {
                VStack(alignment: .leading, spacing: 8) {
                    Text("\(tx.chTitle) \(String(cur.year))").font(.caption).foregroundStyle(.secondary).textCase(.uppercase)
                    Text(cur.name).font(.title3.weight(.bold))
                    ProgressView(value: Double(done), total: Double(max(cur.items.count, 1)))
                    Text(tx.chProgress(done, cur.items.count)).font(.footnote).foregroundStyle(.secondary).monospacedDigit()
                }
                .padding(.vertical, 4)
            }
            Section {
                ForEach(cur.items) { item in row(item, live: true) }
            }
        } else {
            Section {
                VStack(alignment: .leading, spacing: 8) {
                    Text(tx.chEmptyH).font(.headline)
                    Text(tx.chEmptyP).font(.subheadline).foregroundStyle(.secondary)
                    Text("id;jahr;titel;disziplin;serien;datum;schiessstand;notizen").font(.caption.monospaced()).textSelection(.enabled)
                }
                .padding(.vertical, 4)
            }
        }
    }

    @ViewBuilder private var historyTab: some View {
        if store.champ.history.isEmpty {
            Section { Text(tx.chNoHistory).foregroundStyle(.secondary) }
        }
        ForEach(store.champ.history, id: \.year) { h in
            let done = h.items.filter { store.champSession($0) != nil }.count
            Section {
                DisclosureGroup {
                    ForEach(h.items) { item in row(item, live: false) }
                    Button(role: .destructive) { dropYear = h.year } label: { Label(tx.chHistDel, systemImage: "trash") }
                } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("\(String(h.year)) · \(h.name)").font(.headline)
                        Text(tx.chProgress(done, h.items.count)).font(.footnote).foregroundStyle(.secondary)
                    }
                }
            }
        }
    }

    private func row(_ item: ChampItem, live: Bool) -> some View {
        let s = store.champSession(item)
        let active = s != nil
        let what = tx.disciplineName(item.discipline) + " · " + item.series.map { c in
            let f = SeriesFormat(rawValue: c.type) ?? .custom
            return f == .custom ? tx.chCustom(c.planned ?? 10, c.target ?? "A10", c.practice ?? 0) : tx.formatName(f, discipline: item.discipline)
        }.joined(separator: " + ")
        let meta = [item.day.map { tx.longDate($0) }, item.location].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · ")
        return HStack(alignment: .top, spacing: 12) {
            Image(systemName: active ? "checkmark.circle.fill" : "circle")
                .font(.title3).foregroundStyle(active ? Color.accentColor : Color.secondary)
            VStack(alignment: .leading, spacing: 3) {
                Text(item.title).fontWeight(.semibold)
                Text(what).font(.caption).foregroundStyle(.secondary)
                if !meta.isEmpty { Text(meta).font(.caption).foregroundStyle(.secondary) }
                if let n = item.notes, !n.isEmpty { Text(n).font(.caption).foregroundStyle(.secondary) }
                if let s {
                    Button { path.append(s.id) } label: {
                        Text("\(result(s)) · \(tx.shortDate(s.day)) ›").font(.subheadline.weight(.semibold)).monospacedDigit()
                    }
                    .buttonStyle(.borderless)
                } else if !live {
                    Text(tx.chNotShot).font(.caption).foregroundStyle(.secondary)
                }
                if live {
                    HStack {
                        Spacer()
                        if active {
                            Button(tx.chReset) { resetItem = item }.buttonStyle(.bordered)
                        } else {
                            Button(tx.chActivate) { if let s = store.activate(item.id) { editing = s } }.buttonStyle(.borderedProminent)
                        }
                    }
                    .padding(.top, 4)
                }
            }
            .opacity(active && live ? 0.6 : 1)
        }
        .padding(.vertical, 4)
    }

    // MARK: loading

    private var pendingQuestion: String {
        guard let p = pending else { return "" }
        if let cur = store.champ.current, cur.year != p.champ.year { return tx.chAskNew(p.champ.year, cur.year) }
        return tx.chAskSame(p.champ.year)
    }

    private func result(_ s: Session) -> String {
        let done = s.series.filter(\.hasResult)
        if done.isEmpty { return tx.chNoResult }
        return done.map { "\(tx.score($0.stats.total))/\($0.stats.max)" }.joined(separator: " · ")
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
        tab = 0
        message = tx.chLoaded(r.champ.items.count, r.champ.year) + (r.warnings.isEmpty ? "" : " · " + tx.chWarnN(r.warnings.count))
    }
}
