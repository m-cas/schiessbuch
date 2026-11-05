import SwiftUI

struct MainView: View {
    @Environment(Store.self) private var store
    @Environment(\.lang) private var lang
    @AppStorage("clubLogo") private var clubLogo = false
    @AppStorage("chartOpen") private var chartOpen = true
    @AppStorage("filterDiscipline") private var filterRaw = "all"
    @AppStorage("filterCategories") private var categoriesRaw = "training,championship,other"

    @State private var openYears: Set<String>? = nil
    @State private var expanded = Set<String>()
    @Environment(\.verticalSizeClass) private var vSize
    /// iPhone turned sideways: rows show discipline and results too.
    private var wide: Bool { vSize == .compact }
    @State private var editMode: EditMode = .inactive
    @State private var selection = Set<String>()
    @State private var showSettings = false
    @State private var showChamp = false
    @State private var editing: Session? = nil
    @State private var mail: OutgoingMail? = nil
    @State private var confirmDelete = false
    @State private var askPrintPhotos = false
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
            // own header like the web version (logo + title, three buttons) instead of the system bar
            .alert(tx.printAsk(photoCount(selection)), isPresented: $askPrintPhotos) {
                Button(tx.printPhotos) { printSelection(photos: true) }
                Button(tx.printNoPhotos) { printSelection(photos: false) }
                Button(tx.cancel, role: .cancel) {}
            }
            .toolbar(.hidden, for: .navigationBar)
            .safeAreaInset(edge: .top, spacing: 0) { header }
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
                if !rows.isEmpty {
                    // the chart can be folded away; the choice is remembered
                    Button { withAnimation { chartOpen.toggle() } } label: {
                        HStack {
                            Text(tx.trendYears).font(.caption).foregroundStyle(.secondary).textCase(.uppercase)
                            Spacer()
                            Image(systemName: "chevron.down").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                                .rotationEffect(.degrees(chartOpen ? 180 : 0))
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(tx.chartToggle)
                    if chartOpen { TrendChart(sessions: rows, tx: tx, showTitle: false).padding(.bottom, 6) }
                }
            } footer: { Text(tx.normCaption) }

            if rows.isEmpty {
                Text(tx.noMatch).foregroundStyle(.secondary)
            }
            ForEach(years, id: \.0) { year, list in
                let open = (openYears ?? Set(years.prefix(1).map(\.0))).contains(year)
                Section {
                    if open {
                        ForEach(list) { s in
                            if isSelecting {
                                NavigationLink(value: s.id) { SessionRow(session: s, tx: tx, wide: wide) }
                                    .tag(s.id)
                            } else {
                                // tap on the row opens the session; the chevron shows a compact card with photo
                                HStack(spacing: 4) {
                                    Button { path.append(s.id) } label: {
                                        SessionRow(session: s, tx: tx, wide: wide)
                                            .frame(maxWidth: .infinity, alignment: .leading)
                                            .contentShape(Rectangle())
                                    }
                                    .buttonStyle(.plain)
                                    Button {
                                        withAnimation { if expanded.contains(s.id) { expanded.remove(s.id) } else { expanded.insert(s.id) } }
                                    } label: {
                                        Image(systemName: "chevron.down")
                                            .rotationEffect(.degrees(expanded.contains(s.id) ? 180 : 0))
                                            .foregroundStyle(.secondary)
                                            .frame(width: 36, height: 32)
                                            .contentShape(Rectangle())
                                    }
                                    .buttonStyle(.borderless)
                                    .accessibilityLabel(tx.details)
                                }
                                if expanded.contains(s.id) {
                                    Button { path.append(s.id) } label: { SessionCard(session: s, tx: tx) }
                                        .buttonStyle(.plain)
                                }
                            }
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
            DisciplineBar(selection: $filterRaw, items: [("all", tx.all)] + Discipline.allCases.map { ($0.rawValue, tx.disciplineShort($0)) })
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

        if isSelecting {
            ToolbarItemGroup(placement: .bottomBar) {
                Button(role: .destructive) { confirmDelete = true } label: { Image(systemName: "trash") }
                    .disabled(selection.isEmpty)
                    .accessibilityLabel(tx.delete)
                Spacer()
                Text(tx.selected(selection.count)).font(.footnote).foregroundStyle(.secondary)
                Spacer()
                Button { if photoCount(selection) > 0 { askPrintPhotos = true } else { printSelection(photos: false) } } label: { Image(systemName: "printer") }
                    .disabled(selection.isEmpty)
                    .accessibilityLabel(tx.print)
                Spacer().frame(width: 18)
                Button { mail = outgoing(for: selection) } label: { Image(systemName: "envelope") }
                    .disabled(selection.isEmpty)
                    .accessibilityLabel(tx.email)
            }
        }
    }

    /// Three equal buttons under the title, as in the web version. Each shows its full label at 15 pt when it fits,
    /// otherwise the short one ("Meistersch." / "Champ", "Neue Üb." / "New Sess.").
    private var header: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 12) {
                // tap: settings; hold for 10 seconds: switch between the app logo and the SG Muttenz logo (app icon too)
                Image(clubLogo ? "ClubLogo" : "AppLogo")
                    .resizable().scaledToFit().frame(width: 40, height: 40)
                    .clipShape(RoundedRectangle(cornerRadius: 9, style: .continuous))
                    .overlay(alignment: .topTrailing) {
                        if backupTooBig { Circle().fill(.red).frame(width: 10, height: 10).offset(x: 3, y: -3) }
                    }
                    .contentShape(Rectangle())
                    .onTapGesture { showSettings = true }
                    .onLongPressGesture(minimumDuration: 10) { toggleClubLogo() }
                    .accessibilityElement()
                    .accessibilityLabel(tx.settings)
                    .accessibilityAddTraits(.isButton)
                    .accessibilityAction { showSettings = true }
                Text(isSelecting ? tx.selected(selection.count) : tx.appName)
                    .font(.title2.weight(.bold)).lineLimit(1).minimumScaleFactor(0.8)
                Spacer(minLength: 0)
            }
            actionButtons
        }
        .padding(.horizontal, 16)
        .padding(.top, 6)
        .padding(.bottom, 10)
        .background(.bar)
    }

    private var actionButtons: some View {
        HStack(spacing: 8) {
            Button { showChamp = true } label: { FitLabel(full: tx.chTitle, short: tx.chTitleShort) }
                .buttonStyle(TopButtonStyle(prominent: false))
            Button {
                withAnimation { editMode = isSelecting ? .inactive : .active; selection = [] }
            } label: { FitLabel(full: isSelecting ? tx.done : tx.select, short: isSelecting ? tx.done : tx.select) }
                .buttonStyle(TopButtonStyle(prominent: false))
                .disabled(store.sessions.isEmpty)
            Button { editing = newSession() } label: { FitLabel(full: tx.newSession, short: tx.newSessionShort) }
                .buttonStyle(TopButtonStyle(prominent: true))
                .disabled(isSelecting)
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
    private func printSelection(photos: Bool) {
        SessionPrinter.shared.print(store.sessions.filter { selection.contains($0.id) }, store: store, tx: tx, photos: photos)
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
    /// Switches the in-app logo and the home-screen icon (alternate icon "AppIconClub") together.
    private func toggleClubLogo() {
        clubLogo.toggle()
        UINotificationFeedbackGenerator().notificationOccurred(.success)
        let name: String? = clubLogo ? "AppIconClub" : nil
        if UIApplication.shared.supportsAlternateIcons, UIApplication.shared.alternateIconName != name {
            UIApplication.shared.setAlternateIconName(name) { _ in }
        }
    }
    private func refreshStorage() { backupTooBig = store.storageInfo().backupBytes > Store.mailLimit }
}

struct SessionRow: View {
    let session: Session
    let tx: Tx
    var wide = false
    private var results: String {
        session.series.filter { $0.hasResult }.map { r -> String in
            let st = r.stats
            return tx.score(st.total) + "/" + String(st.max)
        }.joined(separator: " · ")
    }
    var body: some View {
        HStack(spacing: 12) {
            Text(tx.shortDate(session.day)).monospacedDigit().foregroundStyle(.secondary)
            Text(session.descText.isEmpty ? tx.categoryName(session.categoryValue) : "\(tx.categoryName(session.categoryValue)) · \(session.descText)")
                .fontWeight(.semibold).lineLimit(1)
            if wide {
                Text(tx.disciplineShort(session.discipline)).foregroundStyle(.secondary)
                Spacer(minLength: 8)
                Text(results)
                    .fontWeight(.semibold).monospacedDigit().lineLimit(1)
            }
        }
    }
}

/// The compact card shown under a row when its chevron is opened: photo, details and results.
struct SessionCard: View {
    let session: Session
    let tx: Tx

    private var whereLine: String {
        var parts = [tx.longDate(session.day), tx.disciplineShort(session.discipline)]
        if let loc = session.location, !loc.isEmpty { parts.append(loc) }
        return parts.joined(separator: " · ")
    }
    private var countLine: String {
        var parts = [tx.seriesN(session.series.count), tx.shotsN(session.countedShots)]
        if !session.photos.isEmpty { parts.append(tx.photosN(session.photos.count)) }
        return parts.joined(separator: " · ")
    }
    private func scoreText(_ r: Series, big: Bool) -> Text {
        let st = r.stats
        let value = Text(tx.score(st.total)).font(big ? .title2.weight(.bold) : .callout.weight(.semibold)).monospacedDigit()
        let max = Text(" / " + String(st.max)).font(.caption).foregroundStyle(.secondary)
        return value + max
    }
    var body: some View {
        let done = session.series.filter(\.hasResult)
        HStack(alignment: .center, spacing: 12) {
            Group {
                if let pid = session.photos.first { PhotoThumb(id: pid) }
                else {
                    RoundedRectangle(cornerRadius: 10, style: .continuous).fill(Color(.secondarySystemFill))
                        .overlay { Image(systemName: "target").foregroundStyle(.secondary) }
                }
            }
            .frame(width: 60, height: 60)
            VStack(alignment: .leading, spacing: 3) {
                Text(whereLine).font(.footnote).foregroundStyle(.secondary).lineLimit(2)
                Text(countLine).font(.footnote).foregroundStyle(.secondary)
            }
            Spacer(minLength: 8)
            VStack(alignment: .trailing, spacing: 2) {
                if done.isEmpty { Text("–").foregroundStyle(.secondary) }
                ForEach(Array(done.prefix(4).enumerated()), id: \.offset) { _, r in scoreText(r, big: done.count == 1) }
            }
        }
        .padding(.vertical, 4)
        .contentShape(Rectangle())
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

/// Full label at 15 pt semibold when it fits, otherwise the short label (shrinking a little only as a last resort).
struct FitLabel: View {
    let full: String
    let short: String
    var body: some View {
        ViewThatFits(in: .horizontal) {
            Text(full).lineLimit(1).fixedSize()
            Text(short).lineLimit(1).fixedSize()
            Text(short).lineLimit(1).minimumScaleFactor(0.8)
        }
        .font(.system(size: 15, weight: .semibold))
        .frame(maxWidth: .infinity)
    }
}

/// Rounded button like the web version: 42 pt high, outlined, or filled in the accent colour.
struct TopButtonStyle: ButtonStyle {
    let prominent: Bool
    @Environment(\.isEnabled) private var enabled
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .padding(.horizontal, 8)
            .frame(maxWidth: .infinity, minHeight: 42)
            .foregroundStyle(prominent ? Color(.systemBackground) : Color.primary)   // readable on the green in light and dark mode
            .background(RoundedRectangle(cornerRadius: 10, style: .continuous)
                .fill(prominent ? Color.accentColor : Color(.secondarySystemGroupedBackground)))
            .overlay(RoundedRectangle(cornerRadius: 10, style: .continuous)
                .strokeBorder(prominent ? Color.clear : Color(.separator), lineWidth: 1))
            .opacity(enabled ? (configuration.isPressed ? 0.7 : 1) : 0.45)
    }
}

/// One joined bar with a segment per discipline; exactly one is chosen. Labels shrink slightly rather than being cut off.
struct DisciplineBar: View {
    @Binding var selection: String
    let items: [(String, String)]
    var body: some View {
        HStack(spacing: 0) {
            ForEach(Array(items.enumerated()), id: \.offset) { i, item in
                let on = selection == item.0
                Button { selection = item.0 } label: {
                    Text(item.1)
                        .font(.system(size: 14, weight: on ? .bold : .medium))
                        .lineLimit(1).minimumScaleFactor(0.7)
                        .frame(maxWidth: .infinity, minHeight: 34)
                        .foregroundStyle(on ? Color(.systemBackground) : Color.primary)
                        .background(on ? Color.primary : Color.clear)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(on ? .isSelected : [])
                if i < items.count - 1 { Divider().frame(height: 34) }
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 9, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 9, style: .continuous).strokeBorder(Color(.separator), lineWidth: 1))
    }
}
