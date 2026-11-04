import SwiftUI

/// App texts in German and English. The language is chosen in Settings (like the web app),
/// independent of the phone's language.
struct Tx {
    let de: Bool
    init(_ lang: String) { de = lang == "de" }
    private func p(_ d: String, _ e: String) -> String { de ? d : e }

    var locale: Locale { Locale(identifier: de ? "de_CH" : "en_GB") }
    var appName: String { "MySchiessbuch" }

    // main
    var sessions: String { p("Übungen", "Sessions") }
    var newSession: String { p("Neue Übung", "New Session") }
    var select: String { p("Auswählen", "Select") }
    var done: String { p("Fertig", "Done") }
    var all: String { p("Alle", "All") }
    var none: String { p("Keine", "None") }
    var avgSeries: String { p("Ø Serie", "Avg series") }
    var bestSeries: String { p("Beste Serie", "Best series") }
    var normCaption: String { p("Serienresultate umgerechnet auf 10 Schüsse A10 (0–100).", "Series scores converted to 10 shots on A10 (0–100).") }
    var trendYears: String { p("Resultat nach Jahr", "Score by year") }
    var keySession: String { p("Ø der Übung", "Session average") }
    var keySeries: String { p("Einzelne Serien", "Single series") }
    var emptyTitle: String { p("Noch keine Übungen", "No sessions yet") }
    var emptyText: String { p("Erfasse deine erste Übung: Disziplin wählen, jeden Schuss eintippen und die Scheibe fotografieren.", "Record your first session: pick the discipline, tap in each shot, and photograph the target.") }
    var noMatch: String { p("Keine Übungen für diese Auswahl.", "No sessions for this selection.") }
    var discipline: String { p("Disziplin", "Discipline") }
    var category: String { p("Kategorie", "Category") }
    var filter: String { p("Filter", "Filter") }
    func sessionsN(_ n: Int) -> String { de ? "\(n) \(n == 1 ? "Übung" : "Übungen")" : "\(n) \(n == 1 ? "session" : "sessions")" }
    func seriesN(_ n: Int) -> String { de ? "\(n) \(n == 1 ? "Serie" : "Serien")" : "\(n) series" }
    func shotsN(_ n: Int) -> String { de ? "\(n) \(n == 1 ? "Schuss" : "Schüsse")" : "\(n) \(n == 1 ? "shot" : "shots")" }
    func photosN(_ n: Int) -> String { de ? "\(n) \(n == 1 ? "Foto" : "Fotos")" : "\(n) \(n == 1 ? "photo" : "photos")" }
    func selected(_ n: Int) -> String { de ? "\(n) ausgewählt" : "\(n) selected" }
    func yearAvg(_ a: String, _ n: Int) -> String { "Ø \(a) · " + sessionsN(n) }

    func disciplineName(_ d: Discipline) -> String {
        switch d {
        case .r300: return p("Gewehr 300m", "Rifle 300m")
        case .p25: return p("Pistole 25m", "Pistol 25m")
        case .p50: return p("Pistole 50m", "Pistol 50m")
        case .ap10: return p("Luftpistole 10m", "Air Pistol 10m")
        case .other: return p("Andere", "Other")
        }
    }
    func disciplineShort(_ d: Discipline) -> String {
        switch d { case .r300: "G300"; case .p25: "P25"; case .p50: "P50"; case .ap10: "LP10"; case .other: p("Andere", "Other") }
    }
    func categoryName(_ c: Category) -> String {
        switch c { case .training: "Training"; case .championship: p("Meisterschaft", "Championship"); case .other: p("Andere", "Other") }
    }
    func formatName(_ f: SeriesFormat, discipline: Discipline) -> String {
        switch f {
        case .s10: return p("10 Schüsse · A10", "10 shots · A10")
        case .p2s6r4: return p("12 Schüsse · 2 Probe, 6 Einzel, 4 Serie · A10", "12 shots · 2 practice, 6 single, 4 in series · A10")
        case .p2s5r23: return p("12 Schüsse · 2 Probe, 5 Einzel, Serien zu 2 und 3 · A10", "12 shots · 2 practice, 5 single, series of 2 and 3 · A10")
        case .op300: return p("OP · 20 Schüsse A5/B4", "OP · 20 shots A5/B4")
        case .fs300: return p("Feldschiessen Gewehr · 18 Schüsse B4", "Feldschiessen rifle · 18 shots B4")
        case .pstd: return "Standard · 5/5/5"
        case .pop: return p("OP · 5 Einzel + 5/5/5", "OP · 5 single + 5/5/5")
        case .pfs: return p("Feldschiessen · 3 Einzel + 5/5/5", "Feldschiessen · 3 single + 5/5/5")
        case .ap10s: return p("10 Schüsse", "10 shots")
        case .ap20s: return p("20 Schüsse", "20 shots")
        case .custom: return discipline.hasTargetChoice ? p("Eigene · Schüsse und Scheibe wählen", "Custom · choose shots and target") : p("Eigene · Anzahl Schüsse wählen", "Custom · choose number of shots")
        }
    }
    func seriesLabel(_ s: Series, discipline: Discipline) -> String {
        guard s.format == .custom else { return formatName(s.format, discipline: discipline) }
        let st = s.stats
        let pr = st.practiceCount > 0 ? (de ? "\(st.practiceCount) Probe + " : "\(st.practiceCount) practice + ") : ""
        return p("Eigene", "Custom") + " · " + pr + shotsN(st.counted) + " · " + s.targetValue.rawValue
    }
    func segmentName(_ k: SegmentKind, count: Int) -> String {
        switch k {
        case .practice: return p("Probe · zählt nicht", "Practice · not counted")
        case .single: return p("Einzelschüsse", "Single shots")
        case .series: return p("Serie zu \(count)", "Series of \(count)")
        }
    }

    // session detail / editor
    var date: String { p("Datum", "Date") }
    var description: String { p("Beschreibung", "Description") }
    var descriptionPh: String { p("z. B. kniend, Vereinsstich, neues Visier", "e.g. kneeling, club match, new sight") }
    var range: String { p("Schiessstand", "Range") }
    var rangePh: String { p("Schiessanlage", "Shooting range") }
    var weapon: String { p("Waffe", "Weapon") }
    var weaponPh: String { p("z. B. Stgw 90", "e.g. Stgw 90") }
    var conditions: String { p("Bedingungen", "Conditions") }
    var conditionsPh: String { p("Licht, Wind, Temperatur", "Light, wind, temperature") }
    var notes: String { p("Notizen", "Notes") }
    var notesPh: String { p("Visierkorrekturen, was gut lief, woran arbeiten", "Sight adjustments, what went well, what to work on") }
    var details: String { p("Angaben", "Details") }
    var series: String { p("Serien", "Series") }
    func seriesTitle(_ n: Int) -> String { p("Serie \(n)", "Series \(n)") }
    var format: String { p("Format", "Format") }
    var target: String { p("Scheibe", "Target") }
    var practiceShots: String { p("Probe", "Practice") }
    var countedShots: String { p("Schüsse", "Shots") }
    var orTotal: String { p("oder Total", "or total") }
    var totalLock: String { p("Total eingegeben. Feld leeren, um Einzelschüsse einzutippen.", "Total entered. Clear it to tap in single shots.") }
    var tief: String { "Tiefschuss" }
    var tiefShort: String { "TS" }
    func practiceSum(_ n: Int) -> String { p("Probe: \(n) (nicht im Total)", "Practice: \(n) (not in total)") }
    var addSeries: String { p("Serie hinzufügen", "Add series") }
    var removeSeries: String { p("Serie entfernen", "Remove series") }
    var targets: String { p("Scheiben", "Targets") }
    var takePhoto: String { p("Foto aufnehmen", "Take photo") }
    var fromLibrary: String { p("Aus Mediathek", "From library") }
    var noPhotos: String { p("Keine Fotos.", "No photos.") }
    var save: String { p("Sichern", "Save") }
    var cancel: String { p("Abbrechen", "Cancel") }
    var edit: String { p("Bearbeiten", "Edit") }
    var delete: String { p("Löschen", "Delete") }
    var keep: String { p("Behalten", "Keep") }
    var email: String { p("E-Mail", "Email") }
    var share: String { p("Teilen", "Share") }
    var newSessionTitle: String { p("Neue Übung", "New Session") }
    var editSessionTitle: String { p("Übung bearbeiten", "Edit Session") }
    var discardChanges: String { p("Änderungen verwerfen?", "Discard changes?") }
    var discard: String { p("Verwerfen", "Discard") }
    func confirmDelete(_ n: Int, photos: Int) -> String {
        de ? "\(sessionsN(n))\(photos > 0 ? " und \(photosN(photos))" : "") löschen? Das kann nicht rückgängig gemacht werden."
           : "Delete \(sessionsN(n))\(photos > 0 ? " and \(photosN(photos))" : "")? This can't be undone."
    }
    var shot: String { p("Schuss", "Shot") }
    var practiceShot: String { p("Probeschuss", "Practice shot") }
    var complete: String { p("vollständig", "complete") }
    var add: String { p("Hinzufügen", "Add") }
    var removeLast: String { p("Letzten Schuss entfernen", "Remove last shot") }
    func typeValue(_ m: Int) -> String { p("0–\(m) eingeben", "Enter 0–\(m)") }
    func tiefError(_ n: Int) -> String { p("Der Tiefschuss in Serie \(n) muss eine ganze Zahl von 0 bis 100 sein.", "Tiefschuss in series \(n) must be a whole number from 0 to 100.") }
    var seriesFull: String { p("Diese Serie ist vollständig.", "This series is complete.") }

    // settings
    var settings: String { p("Einstellungen", "Settings") }
    var exitSettings: String { p("Einstellungen verlassen", "Exit settings") }
    var language: String { p("Sprache", "Language") }
    var logo: String { "Logo" }
    var clubLogo: String { p("SG-Muttenz-Logo anzeigen", "Show SG Muttenz logo") }
    var clubLogoNote: String { p("Ersetzt das App-Symbol oben links.", "Replaces the app icon at the top left.") }
    var export: String { "Export" }
    var exportCsv: String { p("Als CSV exportieren", "Export as CSV") }
    var exportNote: String { p("Eine Zeile pro Serie, für Excel oder Numbers.", "One row per series, for Excel or Numbers.") }
    var backupData: String { p("Backup und Daten", "Backup and data") }
    var saveBackup: String { p("Backup speichern", "Save backup") }
    var emailBackup: String { p("Backup per E-Mail", "Email backup") }
    var restoreBackup: String { p("Backup wiederherstellen", "Restore backup") }
    var dataOnPhone: String { p("Daten auf diesem Telefon", "Data on this phone") }
    var dataOnPhoneText: String { p("Übungen und Fotos werden nur auf diesem Gerät gespeichert. Nichts wird hochgeladen. Speichere ab und zu ein Backup, und vor einem Telefonwechsel.", "Sessions and photos are stored only on this device. Nothing is uploaded. Save a backup now and then, and before changing phones.") }
    func storageUsed(_ used: String, _ free: String) -> String { p("Speicher belegt: \(used) · \(free) frei", "Storage used: \(used) · \(free) free") }
    func backupSize(_ n: Int, _ size: String) -> String { photosN(n) + p(" · Backup ca. \(size)", " · backup about \(size)") }
    func backupTooBig(_ size: String) -> String { p("Dieses Backup ist ca. \(size) gross, zu gross für die meisten E-Mail-Dienste (ca. 20–25 MB). Speichere es mit «Backup speichern» in Dropbox oder «Dateien».", "This backup is about \(size), too large for most email services (about 20–25 MB). Use Save backup and keep it in Dropbox or Files.") }
    var backupTooBigShort: String { p("Backup zu gross für E-Mail", "Backup too large for email") }
    func restored(_ n: Int) -> String { p("\(sessionsN(n)) wiederhergestellt", "\(sessionsN(n)) restored") }
    var badBackup: String { p("Diese Datei ist kein MySchiessbuch-Backup.", "This file isn't a MySchiessbuch backup.") }
    var restoreFailed: String { p("Das Backup konnte nicht wiederhergestellt werden.", "The backup couldn't be restored.") }
    var version: String { p("Version", "Version") }
    var ok: String { "OK" }

    // email
    var mailGreeting: String { p("Hallo\n\nHier sind meine Schiessresultate.", "Hello,\n\nhere are my shooting results.") }
    func mailSubject(_ d: String) -> String { p("Schiessresultate \(d)", "Shooting results \(d)") }
    func mailSubjectN(_ n: Int, _ a: String, _ b: String) -> String { p("Schiessresultate: \(n) Übungen, \(a) – \(b)", "Shooting results: \(n) sessions, \(a) – \(b)") }
    var mScore: String { p("Resultat", "Score") }
    var totalOnly: String { p("nur Total", "total only") }
    func mPractice(_ n: Int) -> String { p("Probe \(n), zählt nicht", "practice \(n), not counted") }
    var targetPhotos: String { p("Scheibenfotos", "Target photos") }
    func backupMailSubject(_ d: String) -> String { p("MySchiessbuch-Backup \(d)", "MySchiessbuch backup \(d)") }
    var backupMailText: String { p("Backup von MySchiessbuch mit allen Übungen und Scheibenfotos. Zum Laden: MySchiessbuch öffnen, «Backup wiederherstellen» antippen und diese Datei wählen.", "Backup of MySchiessbuch with all sessions and target photos. To load it: open MySchiessbuch, tap Restore backup and choose this file.") }
    var backupFile: String { "myschiessbuch-backup" }
    var csvFile: String { "myschiessbuch" }
    var csvHead: [String] {
        de ? ["datum", "disziplin", "kategorie", "beschreibung", "schiessstand", "waffe", "bedingungen", "serie", "format", "resultat", "maximum", "resultat_10_schuss_a10", "innenzehner", "tiefschuss", "schuesse", "notizen"]
           : ["date", "discipline", "category", "description", "range", "weapon", "conditions", "series", "format", "score", "max", "score_10_shots_A10", "inner_tens", "tiefschuss", "shots", "notes"]
    }

    // dates and numbers
    func longDate(_ d: Date) -> String { d.formatted(Date.FormatStyle(date: .abbreviated, time: .omitted).weekday(.abbreviated).locale(locale)) }
    func shortDate(_ d: Date) -> String { d.formatted(Date.FormatStyle().day(.twoDigits).month(.twoDigits).year().locale(locale)) }
    func score(_ v: Double) -> String { v.formatted(.number.precision(.fractionLength(0...1)).locale(locale)) }
}

private struct LangKey: EnvironmentKey { static let defaultValue = "de" }
extension EnvironmentValues {
    var lang: String { get { self[LangKey.self] } set { self[LangKey.self] = newValue } }
}
