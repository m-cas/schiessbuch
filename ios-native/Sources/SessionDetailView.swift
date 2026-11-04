import SwiftUI

struct SessionDetailView: View {
    let id: String
    @Environment(Store.self) private var store
    @Environment(\.lang) private var lang
    @Environment(\.dismiss) private var dismiss
    @State private var editing: Session? = nil
    @State private var mail: OutgoingMail? = nil
    @State private var confirmDelete = false
    @State private var viewer: PhotoIndex? = nil

    private var tx: Tx { Tx(lang) }

    var body: some View {
        if let s = store.session(id) {
            List {
                Section {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(tx.longDate(s.day)).font(.subheadline).foregroundStyle(.secondary)
                        Text("\(tx.categoryName(s.categoryValue)) · \(tx.disciplineName(s.discipline))").font(.title3.weight(.bold))
                        if !s.descText.isEmpty { Text(s.descText) }
                    }
                    .padding(.vertical, 4)
                    if let v = s.location, !v.isEmpty { LabeledContent(tx.range, value: v) }
                    if let v = s.weapon, !v.isEmpty { LabeledContent(tx.weapon, value: v) }
                    if let v = s.conditions, !v.isEmpty { LabeledContent(tx.conditions, value: v) }
                }

                Section(tx.series) {
                    ForEach(Array(s.series.enumerated()), id: \.offset) { i, r in
                        SeriesResultRow(index: i, series: r, discipline: s.discipline, tx: tx)
                    }
                }

                if let n = s.notes, !n.isEmpty {
                    Section(tx.notes) { Text(n) }
                }

                Section(tx.targets) {
                    if s.photos.isEmpty {
                        Text(tx.noPhotos).foregroundStyle(.secondary)
                    } else {
                        LazyVGrid(columns: [GridItem(.adaptive(minimum: 96), spacing: 8)], spacing: 8) {
                            ForEach(Array(s.photos.enumerated()), id: \.element) { i, pid in
                                Button { viewer = PhotoIndex(ids: s.photos, index: i) } label: {
                                    PhotoThumb(id: pid).aspectRatio(3 / 4, contentMode: .fit)
                                }
                                .buttonStyle(.plain)
                            }
                        }
                        .padding(.vertical, 6)
                    }
                }
            }
            .navigationTitle(tx.shortDate(s.day))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Button { mail = SessionMail.make([s], store: store, tx: tx) } label: { Label(tx.email, systemImage: "envelope") }
                        Button(role: .destructive) { confirmDelete = true } label: { Label(tx.delete, systemImage: "trash") }
                    } label: { Image(systemName: "ellipsis.circle") }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button(tx.edit) { editing = s }
                }
            }
            .sheet(item: $editing) { e in SessionEditor(original: e, isNew: false) }
            .mailOrShare($mail)
            .fullScreenCover(item: $viewer) { v in PhotoViewer(ids: v.ids, start: v.index) }
            .confirmationDialog(tx.confirmDelete(1, photos: s.photos.count), isPresented: $confirmDelete, titleVisibility: .visible) {
                Button(tx.delete, role: .destructive) { store.delete([s.id]); dismiss() }
                Button(tx.keep, role: .cancel) {}
            }
        } else {
            ContentUnavailableView(tx.noMatch, systemImage: "target")
        }
    }
}

struct SeriesResultRow: View {
    let index: Int
    let series: Series
    let discipline: Discipline
    let tx: Tx
    var body: some View {
        let st = series.stats
        VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .firstTextBaseline) {
                Text(tx.seriesTitle(index + 1)).font(.headline)
                Spacer()
                if series.hasResult {
                    Text(tx.score(st.total)).font(.title3.weight(.bold)).monospacedDigit()
                    + Text(" / \(st.max)").font(.subheadline).foregroundStyle(.secondary)
                } else {
                    Text("–").foregroundStyle(.secondary)
                }
            }
            Text(tx.seriesLabel(series, discipline: discipline)).font(.caption).foregroundStyle(.secondary)
            if !series.shots.isEmpty {
                Text(series.shotsText).font(.callout.monospacedDigit())
            } else if st.manual {
                Text(tx.totalOnly).font(.callout).foregroundStyle(.secondary)
            }
            HStack(spacing: 12) {
                if let t = series.tief { Text("\(tx.tief) \(t)") }
                if st.x > 0 { Text("\(st.x) X") }
                if st.practiceCount > 0 && !series.shots.isEmpty { Text(tx.practiceSum(st.practice)) }
            }
            .font(.caption).foregroundStyle(.secondary)
        }
        .padding(.vertical, 2)
    }
}

struct PhotoIndex: Identifiable { let id = UUID(); let ids: [String]; let index: Int }

struct PhotoThumb: View {
    let id: String
    @Environment(Store.self) private var store
    var body: some View {
        Color(.secondarySystemFill)
            .overlay {
                if let img = store.thumbnail(id) {
                    Image(uiImage: img).resizable().scaledToFill()
                } else {
                    Image(systemName: "photo").foregroundStyle(.secondary)
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
    }
}

struct PhotoViewer: View {
    let ids: [String]
    let start: Int
    @Environment(Store.self) private var store
    @Environment(\.dismiss) private var dismiss
    @State private var page = 0
    var body: some View {
        NavigationStack {
            TabView(selection: $page) {
                ForEach(Array(ids.enumerated()), id: \.offset) { i, pid in
                    ZoomableImage(image: store.image(pid)).tag(i)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: ids.count > 1 ? .always : .never))
            .background(Color.black)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { dismiss() } label: { Image(systemName: "xmark.circle.fill").font(.title2) }
                        .tint(.white)
                }
            }
            .toolbarBackground(.hidden, for: .navigationBar)
        }
        .onAppear { page = start }
    }
}

/// Pinch to zoom and pan, double tap to reset.
struct ZoomableImage: View {
    let image: UIImage?
    @State private var scale: CGFloat = 1
    @State private var lastScale: CGFloat = 1
    @State private var offset: CGSize = .zero
    @State private var lastOffset: CGSize = .zero
    var body: some View {
        GeometryReader { geo in
            if let image {
                Image(uiImage: image).resizable().scaledToFit()
                    .frame(width: geo.size.width, height: geo.size.height)
                    .scaleEffect(scale).offset(offset)
                    .gesture(MagnificationGesture()
                        .onChanged { scale = max(1, lastScale * $0) }
                        .onEnded { _ in lastScale = scale; if scale == 1 { offset = .zero; lastOffset = .zero } })
                    .simultaneousGesture(DragGesture()
                        .onChanged { v in if scale > 1 { offset = CGSize(width: lastOffset.width + v.translation.width, height: lastOffset.height + v.translation.height) } }
                        .onEnded { _ in lastOffset = offset })
                    .onTapGesture(count: 2) { withAnimation { scale = 1; lastScale = 1; offset = .zero; lastOffset = .zero } }
            }
        }
        .ignoresSafeArea()
    }
}
