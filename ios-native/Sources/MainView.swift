import SwiftUI

struct MainView: View {
    @Environment(Store.self) private var store
    @Environment(\.lang) private var lang
    @AppStorage("clubLogo") private var clubLogo = false
    @AppStorage("filterDiscipline") private var filterRaw = "all"
    @AppStorage("filterCategories") private var categoriesRaw = "training,championship,other"

    @State private var openYears: Set<String>? = nil
    @State private var editMode: EditMode = .inactive
    @State private var selection = Set<String>()
    @State private var showSettings = false
    @State private var showChamp = false
    @State private var editing: Session? = nil
    @State private var mail: OutgoingMail? = nil
    @State private var confirmDelete = false
    @State private var backupTooBig = false
    @State private var path: [String] = []

    private var tx: Tx { Tx(lang) }
    private var categories: Set<Category> { Set(categoriesRaw.split(separator: ",").compactMap { Category(rawValue: String($0)) }) }
    private var rows: [Session] {
        store.sessions.filter { s in
            (filterRaw == "all" || s.discipline.rawValue == filterRaw) && categories.contains(s.categoryValue)
        }
    }
    private var years: [(String, [Session])] {
        Dictionary(grouping: rows, by: \.year).sorted { $0.key > $1.key }.map { ($0.key, $0.value) }
    }
    private var isSelecting: Bool { editMode.isEditing }

    var body: some View {
        NavigationStack(path: $path) {
            // A list with a selection only while selecting: with a selection binding, a tap on a row
            // selects it instead of opening the session.
            Group {
                if isSelecting {
                    List(selection: $selection) { listContent }
                } else {
                    List { listContent }
                }
            }
            .listStyle(.insetGrouped)
            .environment(\.editMode, $editMode)
            .navigationTitle(tx.appName)
            .navigationDestination(for: String.self) { id in SessionDetailView(id: id) }
            .toolbar { toolbar }
            .sheet(isPresented: $showSettings, onDismiss: refreshStorage) { SettingsView() }
            .sheet(isPresented: $showChamp) { ChampionshipView() }
            .sheet(item: $editing) { s in SessionEditor(original: s, isNew: store.session(s.id) == nil) }
            .mailOrShare($mail)
            .confirmationDialog(tx.confirmDelete(selection.count, photos: photoCount(selection)), isPresented: $confirmDelete, titleVisibility: .visible) {
                Button(tx.delete, role: .destructive) {
                    store.delete(selection); selection = []; editMode = .inactive
                }
                Button(tx.keep, role: .cancel) {}
            }
            .task(id: store.sessions.count) { refreshStorage() }
            .onAppear(perform: openScreenForScreenshots)
        }
    }

    @ViewBuilder private var listContent: some View {
        if store.sessions.isEmpty {
            ContentUnavailableView {
                Label(tx.emptyTitle, systemImage: "target")
            } description: {
                Text(tx.emptyText)
            } actions: {
                Button(tx.newSession) { editing = newSession() }.buttonStyle(.borderedProminent)
            }
            .listRowBackground(Color.clear)
        } else {
            Section { filters }
            Section {
                stats
                if !rows.isEmpty { TrendChart(sessions: rows, tx: tx).padding(.vertical, 6) }
            } footer: { Text(tx.normCaption) }

            if rows.isEmpty {
                Text(tx.noMatch).foregroundStyle(.secondary)
            }
            ForEach(years, id: \.0) { year, list in
                let open = (openYears ?? Set(years.prefix(1).map(\.0))).contains(year)
                Section {
                    if open {
                        ForEach(list) { s in
                            NavigationLink(value: s.id) { SessionRow(session: s, tx: tx) }
                                .tag(s.id)
                        }
                    }
                } header: {
                    yearHeader(year, count: list.count, open: open, picked: list.filter { selection.contains($0.id) }.count)
                }
            }
        }
    }

    // MARK: parts

    private var filters: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(tx.discipline).font(.caption.weight(.semibold)).foregroundStyle(.secondary).textCase(.uppercase)
            Picker(tx.discipline, selection: $filterRaw) {
                Text(tx.all).tag("all")
                ForEach(Discipline.allCases) { d in Text(tx.disciplineShort(d)).tag(d.rawValue) }
            }
            .pickerStyle(.segmented)
            .labelsHidden()
            Divider().padding(.vertical, 4)
            Text(tx.category).font(.caption.weight(.semibold)).foregroundStyle(.secondary).textCase(.uppercase)
            HStack(spacing: 8) {
                ForEach(Category.allCases) { c in
                    Toggle(tx.categoryName(c), isOn: Binding(
                        get: { categories.contains(c) },
                        set: { on in
                            var set = categories
                            if on { set.insert(c) } else if set.count > 1 { set.remove(c) }
                            categoriesRaw = Category.allCases.filter(set.contains).map(\.rawValue).joined(separator: ",")
                        }))
                    .toggleStyle(.button)
                    .buttonStyle(.bordered)
                    .controlSize(.small)
                }
            }
        }
    }

    private var stats: some View {
        let values = rows.flatMap { $0.series.compactMap(\.normalized) }
        let avg = values.isEmpty ? nil : values.reduce(0, +) / Double(values.count)
        return HStack(alignment: .top) {
            statTile(tx.sessions, "\(rows.count)")
            Divider()
            statTile(tx.avgSeries, avg.map { tx.score($0) } ?? "–")
            Divider()
            statTile(tx.bestSeries, values.max().map { tx.score($0) } ?? "–")
        }
    }
    private func statTile(_ label: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label).font(.caption).foregroundStyle(.secondary).textCase(.uppercase)
            Text(value).font(.system(.title, design: .rounded).weight(.bold)).monospacedDigit()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func yearHeader(_ year: String, count: Int, open: Bool, picked: Int) -> some View {
        Button {
            var set = openYears ?? Set(years.prefix(1).map(\.0))
            if set.contains(year) { set.remove(year) } else { set.insert(year) }
            withAnimation { openYears = set }
        } label: {
            HStack(spacing: 8) {
                Text(year).font(.title3.weight(.bold)).monospacedDigit().foregroundStyle(.primary)
                Text(isSelecting && picked > 0 ? tx.sessionsN(count) + " · " + tx.selected(picked) : tx.sessionsN(count))
                    .font(.footnote.weight(.semibold))
                    .padding(.horizontal, 8).padding(.vertical, 2)
                    .background(.quaternary, in: Capsule())
                Spacer()
                Image(systemName: "chevron.down").rotationEffect(.degrees(open ? 180 : 0)).foregroundStyle(.secondary)
            }
            .textCase(nil)
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(.isHeader)
    }

    @ToolbarContentBuilder private var toolbar: some ToolbarContent {
        ToolbarItem(placement: .topBarLeading) {
            Button { showSettings = true } label: {
                Image(clubLogo ? "ClubLogo" : "AppLogo")
                    .resizable().scaledToFit().frame(width: 34, height: 34)
                    .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                    .overlay(alignment: .topTrailing) {
                        if backupTooBig { Circle().fill(.red).frame(width: 10, height: 10).offset(x: 3, y: -3) }
                    }
            }
            .accessibilityLabel(tx.settings)
        }
        ToolbarItem(placement: .topBarLeading) {
            Button { showChamp = true } label: { Image(systemName: "trophy") }
                .accessibilityLabel(tx.chTitle)
        }
        if !store.sessions.isEmpty {
            ToolbarItem(placement: .topBarTrailing) {
                Button(isSelecting ? tx.done : tx.select) {
                    withAnimation { editMode = isSelecting ? .inactive : .active; selection = [] }
                }
            }
        }
        if !isSelecting {
            ToolbarItem(placement: .topBarTrailing) {
                Button { editing = newSession() } label: { Text(tx.newSession).fontWeight(.semibold) }
                    .buttonStyle(.borderedProminent)
                    .buttonBorderShape(.capsule)
            }
        }
        if isSelecting {
            ToolbarItemGroup(placement: .bottomBar) {
                Button(role: .destructive) { confirmDelete = true } label: { Image(systemName: "trash") }
                    .disabled(selection.isEmpty)
                    .accessibilityLabel(tx.delete)
                Spacer()
                Text(tx.selected(selection.count)).font(.footnote).foregroundStyle(.secondary)
                Spacer()
                Button { mail = outgoing(for: selection) } label: { Image(systemName: "envelope") }
                    .disabled(selection.isEmpty)
                    .accessibilityLabel(tx.email)
            }
        }
    }

    // MARK: actions

    private func newSession() -> Session {
        var s = Session()
        if let last = store.sessions.first {
            s.preset = last.discipline.rawValue
            s.category = last.categoryValue.rawValue
            s.location = last.location
            s.weapon = last.weapon
        }
        s.series = [Series(format: s.discipline.defaultFormat)]
        return s
    }
    private func photoCount(_ ids: Set<String>) -> Int { store.sessions.filter { ids.contains($0.id) }.reduce(0) { $0 + $1.photos.count } }
    private func outgoing(for ids: Set<String>) -> OutgoingMail {
        let list = store.sessions.filter { ids.contains($0.id) }
        return SessionMail.make(list, store: store, tx: tx)
    }
    /// Test screenshots start the app with "-screen editor|detail|settings".
    private func openScreenForScreenshots() {
        switch UserDefaults.standard.string(forKey: "screen") {
        case "settings": showSettings = true
        case "champ": showChamp = true
        case "detail": if let s = store.sessions.first { path = [s.id] }
        case "editor":
            var s = newSession()
            s.desc = "Training"
            s.series = [Series(format: .p2s6r4), Series(format: .s10)]
            s.series[0].shots = [.value(8), .value(9), .value(10), .value(9), .value(9)]
            editing = s
        default: break
        }
    }
    private func refreshStorage() { backupTooBig = store.storageInfo().backupBytes > Store.mailLimit }
}

struct SessionRow: View {
    let session: Session
    let tx: Tx
    var body: some View {
        HStack(spacing: 12) {
            Text(tx.shortDate(session.day)).monospacedDigit().foregroundStyle(.secondary)
            Text(session.descText.isEmpty ? tx.categoryName(session.categoryValue) : "\(tx.categoryName(session.categoryValue)) · \(session.descText)")
                .fontWeight(.semibold).lineLimit(1)
        }
    }
}

enum SessionMail {
    static func make(_ list: [Session], store: Store, tx: Tx) -> OutgoingMail {
        let text = store.mailText(list, tx)
        var attachments: [MailComposer.Attachment] = []
        var urls: [Any] = []
        for s in list.sorted(by: { $0.date < $1.date }) {
            for (n, id) in s.photos.enumerated() {
                let url = store.photoURL(id)
                if let d = try? Data(contentsOf: url) {
                    attachments.append(.init(data: d, mime: "image/jpeg", name: "\(s.date)-\(n + 1).jpg"))
                    urls.append(url)
                }
            }
        }
        return OutgoingMail(subject: text.subject, body: text.body, attachments: attachments, shareItems: urls)
    }
}
