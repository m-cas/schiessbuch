import SwiftUI
import PhotosUI

struct SessionEditor: View {
    let original: Session
    let isNew: Bool
    @Environment(Store.self) private var store
    @Environment(\.lang) private var lang
    @Environment(\.dismiss) private var dismiss

    @State private var draft: Session
    @State private var active = 0
    @State private var added: [String] = []
    @State private var removed: [String] = []
    @State private var pickerItems: [PhotosPickerItem] = []
    @State private var showCamera = false
    @State private var confirmDiscard = false
    @State private var alert: String? = nil
    @State private var tapCount = 0
    @State private var fullCount = 0
    @FocusState private var numberField: NumberField?

    init(original: Session, isNew: Bool) {
        self.original = original
        self.isNew = isNew
        _draft = State(initialValue: original)
    }

    private var tx: Tx { Tx(lang) }
    private var dirty: Bool { draft != original || !added.isEmpty || !removed.isEmpty }

    var body: some View {
        NavigationStack {
            Form {
                detailsSection
                ForEach(draft.series.indices, id: \.self) { i in
                    SeriesEditorSection(series: $draft.series[i], index: i, discipline: draft.discipline, isActive: i == active, canRemove: draft.series.count > 1, tx: tx, focus: $numberField,
                                        onActivate: { active = i },
                                        onRemove: { removeSeries(i) })
                }
                Section {
                    Button { addSeries() } label: { Label(tx.addSeries, systemImage: "plus.circle") }
                }
                photosSection
                Section(tx.notes) {
                    TextField(tx.notesPh, text: text(\.notes), axis: .vertical).lineLimit(3...8)
                }
            }
            .scrollDismissesKeyboard(.interactively)
            .safeAreaInset(edge: .bottom) {
                // The keypad steps aside while a total or Tiefschuss is typed, and for series scored by total only.
                if draft.series.indices.contains(active), numberField == nil, !draft.series[active].stats.manual {
                    ShotKeypad(series: draft.series[active], index: active, discipline: draft.discipline, tx: tx,
                               onValue: addShot, onBack: removeLast)
                }
            }
            .navigationTitle(isNew ? tx.newSessionTitle : tx.editSessionTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(tx.cancel) { if dirty { confirmDiscard = true } else { cancel() } }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(tx.save) { save() }.fontWeight(.semibold)
                }
                if numberField != nil {
                    ToolbarItemGroup(placement: .keyboard) {
                        Spacer()
                        Button(tx.done) { numberField = nil }.fontWeight(.semibold)
                    }
                }
            }
            .interactiveDismissDisabled(dirty)
            .confirmationDialog(tx.discardChanges, isPresented: $confirmDiscard, titleVisibility: .visible) {
                Button(tx.discard, role: .destructive) { cancel() }
                Button(tx.keep, role: .cancel) {}
            }
            .alert(alert ?? "", isPresented: Binding(get: { alert != nil }, set: { if !$0 { alert = nil } })) {
                Button(tx.ok, role: .cancel) {}
            }
            .fullScreenCover(isPresented: $showCamera) {
                CameraPicker { img in if let id = store.addPhoto(img) { draft.photos.append(id); added.append(id) } }
                    .ignoresSafeArea()
            }
            .onChange(of: pickerItems) { _, items in
                guard !items.isEmpty else { return }
                Task {
                    for item in items {
                        if let data = try? await item.loadTransferable(type: Data.self), let img = UIImage(data: data), let id = store.addPhoto(img) {
                            draft.photos.append(id); added.append(id)
                        }
                    }
                    pickerItems = []
                }
            }
            .sensoryFeedback(.impact(weight: .light), trigger: tapCount)
            .sensoryFeedback(.warning, trigger: fullCount)
        }
    }

    // MARK: sections

    private var detailsSection: some View {
        Section(tx.details) {
            DatePicker(tx.date, selection: Binding(get: { draft.day }, set: { draft.date = Session.dayString($0) }), displayedComponents: .date)
            Picker(tx.discipline, selection: Binding(get: { draft.discipline }, set: { changeDiscipline($0) })) {
                ForEach(Discipline.allCases) { d in Text(tx.disciplineName(d)).tag(d) }
            }
            Picker(tx.category, selection: Binding(get: { draft.categoryValue }, set: { draft.category = $0.rawValue })) {
                ForEach(Category.allCases) { c in Text(tx.categoryName(c)).tag(c) }
            }
            .pickerStyle(.segmented)
            TextField(tx.description, text: text(\.desc), prompt: Text(tx.descriptionPh))
            TextField(tx.range, text: text(\.location), prompt: Text(tx.rangePh))
            TextField(tx.weapon, text: text(\.weapon), prompt: Text(tx.weaponPh))
            TextField(tx.conditions, text: text(\.conditions), prompt: Text(tx.conditionsPh))
        }
    }

    private var photosSection: some View {
        Section(tx.targets) {
            if !draft.photos.isEmpty {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 96), spacing: 8)], spacing: 8) {
                    ForEach(draft.photos, id: \.self) { pid in
                        PhotoThumb(id: pid).aspectRatio(3 / 4, contentMode: .fit)
                            .overlay(alignment: .topTrailing) {
                                Button { removePhoto(pid) } label: {
                                    Image(systemName: "xmark.circle.fill").symbolRenderingMode(.palette)
                                        .foregroundStyle(.white, .black.opacity(0.6)).font(.title3)
                                }
                                .buttonStyle(.plain).padding(4)
                                .accessibilityLabel(tx.delete)
                            }
                    }
                }
                .padding(.vertical, 6)
            }
            if CameraPicker.isAvailable {
                Button { showCamera = true } label: { Label(tx.takePhoto, systemImage: "camera") }
            }
            PhotosPicker(selection: $pickerItems, maxSelectionCount: 10, matching: .images) {
                Label(tx.fromLibrary, systemImage: "photo.on.rectangle")
            }
        }
    }

    private func text(_ key: WritableKeyPath<Session, String?>) -> Binding<String> {
        Binding(get: { draft[keyPath: key] ?? "" }, set: { draft[keyPath: key] = $0 })
    }

    // MARK: shots

    private func addShot(_ v: Shot) {
        guard draft.series.indices.contains(active) else { return }
        var s = draft.series[active]
        guard !s.stats.manual else { return }
        let slots = s.stats.slots
        guard s.shots.count < slots else { fullCount += 1; return }
        s.shots.append(v)
        s.total = nil
        draft.series[active] = s
        tapCount += 1
        if s.shots.count >= slots && active < draft.series.count - 1 { active += 1 }
    }
    private func removeLast() {
        guard draft.series.indices.contains(active) else { return }
        if draft.series[active].shots.isEmpty, active > 0 { active -= 1 }
        if !draft.series[active].shots.isEmpty { draft.series[active].shots.removeLast(); tapCount += 1 }
    }
    private func addSeries() {
        draft.series.append(Series(format: draft.discipline.defaultFormat))
        active = draft.series.count - 1
    }
    private func removeSeries(_ i: Int) {
        guard draft.series.count > 1, draft.series.indices.contains(i) else { return }
        draft.series.remove(at: i)
        active = min(active, draft.series.count - 1)
    }

    private func changeDiscipline(_ d: Discipline) {
        draft.preset = d.rawValue
        if draft.series.allSatisfy({ !$0.hasResult }) {
            draft.series = draft.series.map { _ in Series(format: d.defaultFormat) }
            active = 0
            return
        }
        draft.series = draft.series.map { orig in
            var s = orig
            if !d.formats.contains(s.format) {
                let st = s.stats
                s.type = SeriesFormat.custom.rawValue
                s.practice = st.practiceCount
                s.planned = max(st.counted, s.shots.count - st.practiceCount)
            }
            if !d.hasTargetChoice { s.target = Target.a10.rawValue }
            SeriesEditorSection.clamp(&s, discipline: d)
            return s
        }
    }

    // MARK: photos and saving

    private func removePhoto(_ id: String) {
        draft.photos.removeAll { $0 == id }
        if let i = added.firstIndex(of: id) { added.remove(at: i); store.deletePhoto(id) } else { removed.append(id) }
    }
    private func cancel() {
        added.forEach(store.deletePhoto)
        dismiss()
    }
    private func save() {
        for (i, s) in draft.series.enumerated() {
            if let t = s.tief, !(0...100).contains(t) { active = i; alert = tx.tiefError(i + 1); return }
        }
        draft.series = draft.series.map { orig in
            var s = orig
            let st = s.stats
            if s.format == .custom { s.type = SeriesFormat.custom.rawValue; s.planned = st.counted; s.practice = st.practiceCount }
            else { s.planned = st.slots; s.practice = 0; s.target = Target.a10.rawValue }
            return s
        }
        draft.desc = (draft.desc ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        store.save(draft)
        removed.forEach(store.deletePhoto)
        dismiss()
    }
}

// MARK: - One series in the editor

struct SeriesEditorSection: View {
    @Binding var series: Series
    let index: Int
    let discipline: Discipline
    let isActive: Bool
    let canRemove: Bool
    let tx: Tx
    var focus: FocusState<NumberField?>.Binding
    let onActivate: () -> Void
    let onRemove: () -> Void

    var body: some View {
        let st = series.stats
        Section {
            if discipline.formats.count > 1 {
                Picker(tx.format, selection: Binding(get: { series.format }, set: { changeFormat($0) })) {
                    ForEach(discipline.formats) { f in Text(f == .custom && series.isComposite ? tx.seriesLabel(series, discipline: discipline) : tx.formatName(f, discipline: discipline)).tag(f) }
                }
            }
            if series.format == .custom && discipline.hasTargetChoice && !series.isComposite {
                Picker(tx.target, selection: Binding(get: { series.targetValue }, set: { series.target = $0.rawValue; Self.clamp(&series, discipline: discipline) })) {
                    ForEach(Target.allCases) { t in Text("\(t.rawValue) · 0–\(t.max)").tag(t) }
                }
            }
            if st.manual {
                Text(tx.totalLock).font(.footnote).foregroundStyle(.secondary)
            } else {
                shotsGrid
            }
            if st.practiceCount > 0 && series.shots.indices.contains(0) {
                Text(tx.practiceSum(st.practice)).font(.footnote).foregroundStyle(.secondary)
            }
            if series.format == .custom && !series.isComposite {
                Stepper(value: Binding(get: { st.practiceCount }, set: { series.practice = max(0, min(20, $0)) }), in: 0...20) {
                    LabeledContent(tx.practiceShots, value: "\(st.practiceCount)")
                }
                Stepper(value: Binding(get: { st.counted }, set: { setCounted($0) }), in: 1...200) {
                    LabeledContent(tx.countedShots, value: "\(st.counted)")
                }
            }
            LabeledContent(tx.orTotal) {
                TextField("—", value: $series.total, format: .number).keyboardType(.decimalPad).multilineTextAlignment(.trailing)
                    .focused(focus, equals: .total(index))
                    .disabled(!series.shots.isEmpty)
            }
            LabeledContent(tx.tief) {
                TextField("—", value: $series.tief, format: .number).keyboardType(.numberPad).multilineTextAlignment(.trailing)
                    .focused(focus, equals: .tief(index))
            }
            if canRemove {
                Button(role: .destructive) { onRemove() } label: { Label(tx.removeSeries, systemImage: "minus.circle") }
            }
        } header: {
            HStack {
                if isActive { Image(systemName: "pencil.circle.fill").foregroundStyle(Color.accentColor) }
                Text(tx.seriesTitle(index + 1))
                Spacer()
                Text("\(tx.score(st.total)) / \(st.max)").monospacedDigit().fontWeight(.semibold)
            }
        }
    }

    private var shotsGrid: some View {
        VStack(alignment: .leading, spacing: 8) {
            let segs = series.segments
            ForEach(Array(segs.enumerated()), id: \.offset) { gi, g in
                VStack(alignment: .leading, spacing: 4) {
                    if segs.count > 1 {
                        Text(series.group(of: gi).map { gr in "\(tx.groupN(gr.n))\(g.kind == .series ? " · " + tx.segmentName(g.kind, count: g.count) : "")\(g.target.map { " · " + $0.rawValue } ?? "") · \(gr.shots > 0 ? String(gr.sum) : "–") / \(gr.max)" }
                             ?? (tx.segmentName(g.kind, count: g.count) + (g.target.map { " · " + $0.rawValue } ?? "")))
                            .font(.caption2.weight(.semibold)).foregroundStyle(.secondary).textCase(.uppercase)
                    }
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 38), spacing: 6)], alignment: .leading, spacing: 6) {
                        ForEach(g.start..<(g.start + g.count), id: \.self) { j in chip(j, practice: g.kind == .practice) }
                    }
                }
            }
        }
        .padding(.vertical, 4)
        .contentShape(Rectangle())
        .onTapGesture { onActivate() }
    }

    @ViewBuilder private func chip(_ j: Int, practice: Bool) -> some View {
        if j < series.shots.count {
            let v = series.shots[j]
            Button { series.shots.remove(at: j); onActivate() } label: {
                Text(v.label).font(.system(.callout, design: .rounded).weight(.semibold)).monospacedDigit()
                    .frame(width: 36, height: 36)
                    .background(Circle().fill(!practice && v == .x ? Color.primary : Color.clear))
                    .foregroundStyle(!practice && v == .x ? Color(.systemBackground) : (practice ? Color.secondary : (v.points == series.maxValue(at: j) ? Color.orange : Color.primary)))
                    .overlay(Circle().strokeBorder(practice ? Color.secondary : (v.points == series.maxValue(at: j) ? Color.orange : Color.primary), style: StrokeStyle(lineWidth: 1.5, dash: practice ? [2, 2] : [])))
            }
            .buttonStyle(.plain)
            .accessibilityLabel("\(practice ? tx.practiceShot : tx.shot) \(v.label)")
        } else {
            let next = isActive && j == series.shots.count
            Text(practice ? "P" : "\(j + 1 - series.stats.practiceCount)")
                .font(.caption).monospacedDigit()
                .frame(width: 36, height: 36)
                .foregroundStyle(next ? Color.accentColor : Color.secondary)
                .overlay(Circle().strokeBorder(next ? Color.accentColor : Color(.separator), style: StrokeStyle(lineWidth: 1.2, dash: [3, 3])))
                .accessibilityHidden(true)
        }
    }

    private func changeFormat(_ f: SeriesFormat) {
        if f == .custom && series.isComposite { return }
        let prev = series.stats
        series.parts = nil
        series.type = f.rawValue
        if let parts = f.parts {
            let n = parts.reduce(0) { $0 + $1.count }
            series.planned = n; series.practice = 0
            if !(f == .custom) { series.target = Target.a10.rawValue }
            if series.shots.count > n { series.shots = Array(series.shots.prefix(n)) }
        } else {
            series.practice = prev.practiceCount
            series.planned = max(prev.counted, series.shots.count - prev.practiceCount)
        }
        Self.clamp(&series, discipline: discipline)
        onActivate()
    }
    private func setCounted(_ n: Int) {
        let p = series.stats.practiceCount
        series.planned = max(1, n)
        if series.shots.count > p + series.planned! { series.shots = Array(series.shots.prefix(p + series.planned!)) }
        onActivate()
    }

    /// Keeps every shot within the range of its target; X only where it is allowed.
    static func clamp(_ s: inout Series, discipline: Discipline) {
        let copy = s
        s.shots = copy.shots.enumerated().map { j, v in
            let m = copy.maxValue(at: j)
            if v == .x { return discipline.allowsX && m == 10 && copy.segment(at: j)?.target == nil ? .x : .value(min(10, m)) }
            return .value(min(v.points, m))
        }
    }
}

enum NumberField: Hashable { case total(Int), tief(Int) }

// MARK: - Keypad

struct ShotKeypad: View {
    let series: Series
    let index: Int
    let discipline: Discipline
    let tx: Tx
    let onValue: (Shot) -> Void
    let onBack: () -> Void
    @State private var typed = ""
    @FocusState private var focused: Bool

    var body: some View {
        let st = series.stats
        let next = series.shots.count
        let full = next >= st.slots
        let m = full ? series.targetValue.max : series.maxValue(at: next)
        let seg = series.segment(at: next)
        let allowX = discipline.allowsX && m == 10 && seg?.target == nil && seg?.kind != .practice
        VStack(spacing: 8) {
            HStack {
                Text(tx.seriesTitle(index + 1) + " · " + (full ? tx.complete : (series.isPractice(next) ? "\(tx.practiceShot) \(next + 1)" : "\(tx.shot) \(next + 1 - st.practiceCount)")) + (seg?.target.map { " · " + $0.rawValue } ?? ""))
                    .font(.footnote.weight(.medium)).foregroundStyle(.secondary)
                Spacer()
            }
            if m > 20 {
                HStack(spacing: 8) {
                    TextField(tx.typeValue(m), text: $typed)
                        .keyboardType(.numberPad).focused($focused)
                        .font(.title2.monospacedDigit()).padding(.horizontal, 12).frame(height: 48)
                        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 10))
                        .disabled(full)
                    Button(tx.add) {
                        if let v = Int(typed), (0...m).contains(v) { onValue(.value(v)); typed = "" }
                    }
                    .buttonStyle(.borderedProminent).controlSize(.large)
                    .disabled(full || Int(typed).map { !(0...m).contains($0) } ?? true)
                    Button(action: onBack) { Image(systemName: "delete.left") }
                        .buttonStyle(.bordered).controlSize(.large)
                        .accessibilityLabel(tx.removeLast)
                }
            } else {
                let values: [Shot] = (allowX ? [.x] : []) + (0...m).reversed().map { .value($0) }
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 6), count: 6), spacing: 6) {
                    ForEach(values, id: \.self) { v in
                        Button { onValue(v) } label: {
                            Text(v.label).font(.title2.weight(.semibold)).monospacedDigit().frame(maxWidth: .infinity, minHeight: 34)
                        }
                        .buttonStyle(v == .x ? AnyPrimitiveButtonStyle(BorderedProminentButtonStyle()) : AnyPrimitiveButtonStyle(BorderedButtonStyle()))
                        .tint(v == .x ? Color.primary : nil)
                        .disabled(full)
                    }
                    Button(action: onBack) {
                        Image(systemName: "delete.left").font(.title3).frame(maxWidth: .infinity, minHeight: 34)
                    }
                    .buttonStyle(.bordered)
                    .accessibilityLabel(tx.removeLast)
                }
            }
        }
        .padding(.horizontal, 16).padding(.top, 10).padding(.bottom, 6)
        .background(.bar)
    }
}

/// Lets one button pick between two built-in styles.
struct AnyPrimitiveButtonStyle: PrimitiveButtonStyle {
    private let make: (Configuration) -> AnyView
    init<S: PrimitiveButtonStyle>(_ style: S) { make = { AnyView(style.makeBody(configuration: $0)) } }
    func makeBody(configuration: Configuration) -> some View { make(configuration) }
}
