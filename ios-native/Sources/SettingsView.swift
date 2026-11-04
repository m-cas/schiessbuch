import SwiftUI
import UniformTypeIdentifiers

struct SettingsView: View {
    @Environment(Store.self) private var store
    @Environment(\.dismiss) private var dismiss
    @AppStorage("lang") private var lang: String = "de"
    @AppStorage("clubLogo") private var clubLogo = false

    @State private var shared: SharedFile? = nil
    @State private var mail: OutgoingMail? = nil
    @State private var importing = false
    @State private var message: String? = nil
    @State private var busy = false
    @State private var info: Store.StorageInfo? = nil

    private var tx: Tx { Tx(lang) }
    private var tooBig: Bool { (info?.backupBytes ?? 0) > Store.mailLimit && !store.sessions.isEmpty }

    var body: some View {
        NavigationStack {
            Form {
                Section(tx.language) {
                    Picker(tx.language, selection: $lang) {
                        Text("Deutsch").tag("de")
                        Text("English").tag("en")
                    }
                    .pickerStyle(.segmented)
                    .labelsHidden()
                }

                Section {
                    Toggle(isOn: $clubLogo) {
                        HStack(spacing: 12) {
                            Image("ClubLogo").resizable().scaledToFit().frame(width: 36, height: 36)
                                .clipShape(RoundedRectangle(cornerRadius: 7, style: .continuous))
                            VStack(alignment: .leading, spacing: 2) {
                                Text(tx.clubLogo)
                                Text(tx.clubLogoNote).font(.footnote).foregroundStyle(.secondary)
                            }
                        }
                    }
                } header: { Text(tx.logo) }

                Section {
                    Button { run { shared = SharedFile(url: try store.csvFile(tx)) } } label: {
                        Label(tx.exportCsv, systemImage: "tablecells")
                    }
                    .disabled(store.sessions.isEmpty)
                } header: { Text(tx.export) } footer: { Text(tx.exportNote) }

                Section {
                    Button { run { shared = SharedFile(url: try backupURL()) } } label: {
                        Label(tx.saveBackup, systemImage: "square.and.arrow.down")
                    }
                    .disabled(store.sessions.isEmpty)
                    Button { run { mail = try backupMail() } } label: {
                        Label(tx.emailBackup, systemImage: "envelope")
                    }
                    .disabled(store.sessions.isEmpty)
                    Button { importing = true } label: {
                        Label(tx.restoreBackup, systemImage: "arrow.counterclockwise")
                    }
                    if tooBig, let info {
                        Label(tx.backupTooBig(bytes(info.backupBytes)), systemImage: "exclamationmark.triangle.fill")
                            .font(.footnote).foregroundStyle(.red)
                    }
                    DisclosureGroup {
                        Text(tx.dataOnPhoneText).font(.footnote).foregroundStyle(.secondary)
                        if let info {
                            Text(tx.storageUsed(bytes(info.photoBytes + info.dataBytes), info.free.map(bytes) ?? "–")).font(.footnote).monospacedDigit()
                            if !store.sessions.isEmpty { Text(tx.backupSize(info.photoCount, bytes(info.backupBytes))).font(.footnote).monospacedDigit() }
                        }
                    } label: {
                        HStack {
                            Label(tx.dataOnPhone, systemImage: "lock")
                            if tooBig { Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.red).accessibilityLabel(tx.backupTooBigShort) }
                        }
                    }
                } header: { Text(tx.backupData) }

                Section {
                    LabeledContent(tx.version, value: appVersion)
                }
            }
            .disabled(busy)
            .overlay { if busy { ProgressView().controlSize(.large) } }
            .navigationTitle(tx.settings)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button(tx.exitSettings) { dismiss() } }
            }
            .sheet(item: $shared) { f in ShareSheet(items: [f.url]).presentationDetents([.medium, .large]) }
            .mailOrShare($mail)
            .fileImporter(isPresented: $importing, allowedContentTypes: [.json, .plainText, .data]) { result in
                switch result {
                case .success(let url): run { message = tx.restored(try store.restore(from: url)); info = store.storageInfo() }
                case .failure: break
                }
            }
            .alert(message ?? "", isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })) {
                Button(tx.ok, role: .cancel) {}
            }
            .task { info = store.storageInfo() }
        }
        .environment(\.lang, lang)
        .environment(\.locale, tx.locale)
    }

    private var appVersion: String {
        let v = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "–"
        let b = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "–"
        return "\(v) (\(b))"
    }
    private func bytes(_ n: Int64) -> String { ByteCountFormatter.string(fromByteCount: n, countStyle: .file) }
    private func backupURL() throws -> URL { try store.backupFile(name: "\(tx.backupFile)-\(Session.dayString(Date()))") }
    private func backupMail() throws -> OutgoingMail {
        let url = try backupURL()
        let data = try Data(contentsOf: url)
        return OutgoingMail(subject: tx.backupMailSubject(tx.longDate(Date())), body: tx.backupMailText,
                            attachments: [.init(data: data, mime: "application/json", name: url.lastPathComponent)], shareItems: [url])
    }
    /// Runs work that may take a moment (large backups) and reports failures.
    private func run(_ work: @escaping () throws -> Void) {
        busy = true
        Task { @MainActor in
            await Task.yield()
            do { try work() }
            catch Store.RestoreError.notABackup { message = tx.badBackup }
            catch { message = tx.restoreFailed + "\n" + error.localizedDescription }
            busy = false
        }
    }
}
