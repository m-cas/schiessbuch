package io.github.mcas.schiessbuch

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * App texts in German, English, French and Italian; the language is chosen in Settings, like the web app.
 * French and Italian come from assets/i18n.json (translated from the English text, see I18n).
 */
class Tx(lang0: String) {
    val lang = if (lang0 in listOf("de", "fr", "it")) lang0 else "en"
    val de = lang == "de"
    val fr = lang == "fr"
    val itLang = lang == "it"
    private fun p(d: String, e: String) = if (de) d else if (fr || itLang) I18n.tr(e, lang) else e
    /** Texts with plural logic, written out per language. */
    private fun q(d: String, e: String, f: String, i: String) = if (de) d else if (fr) f else if (itLang) i else e
    val locale: Locale = when (lang) { "de" -> Locale.forLanguageTag("de-CH"); "fr" -> Locale.forLanguageTag("fr-CH"); "it" -> Locale.forLanguageTag("it-CH"); else -> Locale.UK }

    val appName = "CHSchiessbuch"
    val sessions get() = p("Übungen", "Sessions")
    val newSession get() = p("Neue Übung", "New Session")
    val select get() = p("Auswählen", "Select")
    val done get() = p("Fertig", "Done")
    val all get() = p("Alle", "All")
    val avgSeries get() = p("Ø Serie", "Avg series")
    val bestSeries get() = p("Beste Serie", "Best series")
    val normCaption get() = p("Serienresultate umgerechnet auf 10 Schüsse A10 (0–100).", "Series scores converted to 10 shots on A10 (0–100).")
    val trendYears get() = p("Resultat nach Jahr", "Score by year")
    val keySeries get() = p("Einzelne Serien", "Single series")
    val emptyTitle get() = p("Noch keine Übungen", "No sessions yet")
    val emptyText get() = p("Erfasse deine erste Übung: Disziplin wählen, jeden Schuss eintippen und die Scheibe fotografieren.", "Record your first session: pick the discipline, tap in each shot, and photograph the target.")
    val noMatch get() = p("Keine Übungen für diese Auswahl.", "No sessions for this selection.")
    val discipline get() = p("Disziplin", "Discipline")
    val category get() = p("Kategorie", "Category")
    fun sessionsN(n: Int) = "$n " + if (n == 1) q("Übung", "session", "exercice", "esercizio") else q("Übungen", "sessions", "exercices", "esercizi")
    fun seriesN(n: Int) = "$n " + if (n == 1) q("Serie", "series", "série", "serie") else q("Serien", "series", "séries", "serie")
    fun shotsN(n: Int) = "$n " + if (n == 1) q("Schuss", "shot", "coup", "colpo") else q("Schüsse", "shots", "coups", "colpi")
    fun photosN(n: Int) = "$n " + if (n == 1) q("Foto", "photo", "photo", "foto") else q("Fotos", "photos", "photos", "foto")
    fun selected(n: Int) = "$n " + q("ausgewählt", "selected", if (n == 1) "sélectionné" else "sélectionnés", if (n == 1) "selezionato" else "selezionati")
    fun yearAvg(a: String, n: Int) = "Ø $a · " + sessionsN(n)

    fun disciplineName(d: Discipline) = when (d) {
        Discipline.R300 -> p("Gewehr 300m", "Rifle 300m")
        Discipline.P25 -> p("Pistole 25m", "Pistol 25m")
        Discipline.P50 -> p("Pistole 50m", "Pistol 50m")
        Discipline.AP10 -> p("Luftpistole 10m", "Air Pistol 10m")
        Discipline.AG10 -> p("Luftgewehr 10m", "Air Rifle 10m")
        Discipline.OTHER -> p("Andere", "Other")
    }
    fun disciplineShort(d: Discipline) = when (d) {
        Discipline.R300 -> "G300"; Discipline.P25 -> "P25"; Discipline.P50 -> "P50"; Discipline.AP10 -> "LP10"; Discipline.AG10 -> "LG10"; Discipline.OTHER -> p("Andere", "Other")
    }
    fun categoryName(c: Category) = when (c) {
        Category.TRAINING -> "Training"; Category.CHAMPIONSHIP -> p("Meisterschaft", "Championship"); Category.OTHER -> p("Andere", "Other")
    }
    fun formatName(f: SeriesFormat, d: Discipline) = when (f) {
        SeriesFormat.S10 -> p("10 Schüsse · A10", "10 shots · A10")
        SeriesFormat.S2X10 -> p("2×10 Einzelschüsse · A10", "2×10 single shots · A10")
        SeriesFormat.S6R4 -> p("10 Schüsse · 6 Einzel, 4 Serie · A10", "10 shots · 6 single, 4 in series · A10")
        SeriesFormat.P2S6R4 -> p("12 Schüsse · 2 Probe, 6 Einzel, 4 Serie · A10", "12 shots · 2 practice, 6 single, 4 in series · A10")
        SeriesFormat.P2S5R23 -> p("12 Schüsse · 2 Probe, 5 Einzel, Serien zu 2 und 3 · A10", "12 shots · 2 practice, 5 single, series of 2 and 3 · A10")
        SeriesFormat.OP300 -> p("OP · 20 Schüsse A5/B4", "OP · 20 shots A5/B4")
        SeriesFormat.FS300 -> p("Feldschiessen Gewehr · 18 Schüsse B4", "Feldschiessen rifle · 18 shots B4")
        SeriesFormat.PSTD -> "Standard · 5/5/5"
        SeriesFormat.POP -> p("OP · 5 Einzel + 5/5/5", "OP · 5 single + 5/5/5")
        SeriesFormat.PFS -> p("Feldschiessen · 3 Einzel + 5/5/5", "Feldschiessen · 3 single + 5/5/5")
        SeriesFormat.AP10S -> p("10 Schüsse", "10 shots")
        SeriesFormat.AP20S -> p("20 Schüsse", "20 shots")
        SeriesFormat.CUSTOM -> if (d.hasTargetChoice) p("Eigene · Schüsse und Scheibe wählen", "Custom · choose shots and target") else p("Eigene · Anzahl Schüsse wählen", "Custom · choose number of shots")
    }
    fun seriesLabel(s: Series, d: Discipline): String {
        if (s.isComposite) return groupingText(s.parts!!, ::chPrac)
        if (s.format != SeriesFormat.CUSTOM) return formatName(s.format, d)
        val st = s.stats
        val pr = if (st.practiceCount > 0) "${st.practiceCount} " + q("Probe", "practice", "d'essai", "di prova") + " + " else ""
        return p("Eigene", "Custom") + " · " + pr + shotsN(st.counted) + " · " + s.targetValue.key
    }
    fun segmentName(k: SegmentKind, count: Int) = when (k) {
        SegmentKind.PRACTICE -> p("Probe · zählt nicht", "Practice · not counted")
        SegmentKind.SINGLE -> p("Einzelschüsse", "Single shots")
        SegmentKind.SERIES -> p("Serie zu $count", "Series of $count")
    }

    fun groupN(n: Int) = p("Gruppe $n", "Group $n")
    val date get() = p("Datum", "Date")
    val description get() = p("Beschreibung", "Description")
    val descriptionPh get() = p("z. B. kniend, Vereinsstich, neues Visier", "e.g. kneeling, club match, new sight")
    val range get() = p("Schiessstand", "Range")
    val rangePh get() = p("Schiessanlage", "Shooting range")
    val backupTooBigShort get() = p("Backup zu gross für E-Mail", "Backup too large for email")
    val weapon get() = p("Waffe", "Weapon")
    val weaponPh get() = p("z. B. Stgw 90", "e.g. Stgw 90")
    val conditions get() = p("Bedingungen", "Conditions")
    val conditionsPh get() = p("Licht, Wind, Temperatur", "Light, wind, temperature")
    val notes get() = p("Notizen", "Notes")
    val notesPh get() = p("Visierkorrekturen, was gut lief, woran arbeiten", "Sight adjustments, what went well, what to work on")
    val details get() = p("Angaben", "Details")
    val series get() = p("Serien", "Series")
    fun seriesTitle(n: Int) = p("Serie $n", "Series $n")
    val format get() = p("Format", "Format")
    val target get() = p("Scheibe", "Target")
    val practiceShots get() = p("Probe", "Practice")
    val countedShots get() = p("Schüsse", "Shots")
    val orTotal get() = p("oder Total", "or total")
    val totalLock get() = p("Total eingegeben. Feld leeren, um Einzelschüsse einzutippen.", "Total entered. Clear it to tap in single shots.")
    val tief = "Tiefschuss"
    val tiefShort = "TS"
    fun practiceSum(n: Int) = p("Probe: $n (nicht im Total)", "Practice: $n (not in total)")
    val addSeries get() = p("Serie hinzufügen", "Add series")
    val removeSeries get() = p("Serie entfernen", "Remove series")
    val targets get() = p("Scheiben", "Targets")
    val takePhoto get() = p("Foto aufnehmen", "Take photo")
    val fromLibrary get() = p("Aus Mediathek", "From gallery")
    val noPhotos get() = p("Keine Fotos.", "No photos.")
    val save get() = p("Sichern", "Save")
    val cancel get() = p("Abbrechen", "Cancel")
    val edit get() = p("Bearbeiten", "Edit")
    val delete get() = p("Löschen", "Delete")
    val keep get() = p("Behalten", "Keep")
    val email get() = p("E-Mail", "Email")
    val back get() = p("Zurück", "Back")
    val more get() = p("Mehr", "More")
    val newSessionTitle get() = p("Neue Übung", "New Session")
    val editSessionTitle get() = p("Übung bearbeiten", "Edit Session")
    val discardChanges get() = p("Änderungen verwerfen?", "Discard changes?")
    val discard get() = p("Verwerfen", "Discard")
    fun confirmDelete(n: Int, photos: Int): String {
        val ph = if (photos > 0) " " + q("und", "and", "et", "e") + " " + photosN(photos) else ""
        return q("${sessionsN(n)}$ph löschen? Das kann nicht rückgängig gemacht werden.", "Delete ${sessionsN(n)}$ph? This can't be undone.",
            "Supprimer ${sessionsN(n)}$ph ? Cette action est définitive.", "Eliminare ${sessionsN(n)}$ph? L'operazione non può essere annullata.")
    }
    val shot get() = p("Schuss", "Shot")
    val practiceShot get() = p("Probeschuss", "Practice shot")
    val complete get() = p("vollständig", "complete")
    val add get() = p("Hinzufügen", "Add")
    val removeLast get() = p("Letzten Schuss entfernen", "Remove last shot")
    fun typeValue(m: Int) = p("0–$m eingeben", "Enter 0–$m")
    fun tiefError(n: Int) = p("Der Tiefschuss in Serie $n muss eine ganze Zahl von 0 bis 100 sein.", "Tiefschuss in series $n must be a whole number from 0 to 100.")
    val seriesFull get() = p("Diese Serie ist vollständig.", "This series is complete.")

    val helpH get() = p("Hilfe", "Help")
    val helpOpen get() = p("Anleitung zur App", "How to use the app")
    val settings get() = p("Einstellungen", "Settings")
    val exitSettings get() = p("Einstellungen verlassen", "Exit settings")
    val language get() = p("Sprache", "Language")
    val logo = "Logo"
    val clubLogo get() = p("SG-Muttenz-Logo anzeigen", "Show SG Muttenz logo")
    val clubLogoNote get() = p("Ersetzt das App-Symbol oben links.", "Replaces the app icon at the top left.")
    val export = "Export"
    val exportCsv get() = p("Als CSV exportieren", "Export as CSV")
    val exportNote get() = p("Eine Zeile pro Serie, für Excel oder Google Sheets.", "One row per series, for Excel or Google Sheets.")
    val backupData get() = p("Backup und Daten", "Backup and data")
    val saveBackup get() = p("Backup speichern", "Save backup")
    val emailBackup get() = p("Backup per E-Mail", "Email backup")
    val restoreBackup get() = p("Backup wiederherstellen", "Restore backup")
    val dataOnPhone get() = p("Daten auf diesem Telefon", "Data on this phone")
    val dataOnPhoneText get() = p("Übungen und Fotos werden nur auf diesem Gerät gespeichert. Nichts wird hochgeladen. Speichere ab und zu ein Backup, und vor einem Telefonwechsel.", "Sessions and photos are stored only on this device. Nothing is uploaded. Save a backup now and then, and before changing phones.")
    fun storageUsed(used: String, free: String) = p("Speicher belegt: $used · $free frei", "Storage used: $used · $free free")
    fun backupSize(n: Int, size: String) = photosN(n) + p(" · Backup ca. $size", " · backup about $size")
    fun backupTooBig(size: String) = p("Dieses Backup ist ca. $size gross, zu gross für die meisten E-Mail-Dienste (ca. 20–25 MB). Speichere es mit «Backup speichern» z. B. in Google Drive oder Dropbox.", "This backup is about $size, too large for most email services (about 20–25 MB). Use Save backup and keep it in e.g. Google Drive or Dropbox.")
    fun restored(n: Int) = p("${sessionsN(n)} wiederhergestellt", "${sessionsN(n)} restored")
    val saved get() = p("Gespeichert", "Saved")
    val badBackup get() = p("Diese Datei ist kein CHSchiessbuch-Backup.", "This file isn't a CHSchiessbuch backup.")
    val restoreFailed get() = p("Das Backup konnte nicht wiederhergestellt werden.", "The backup couldn't be restored.")
    val failed get() = p("Das hat nicht geklappt.", "That didn't work.")
    val version get() = p("Version", "Version")
    val ok = "OK"

    val mailGreeting get() = p("Hallo\n\nHier sind meine Schiessresultate.", "Hello,\n\nhere are my shooting results.")
    fun mailSubject(d: String) = p("Schiessresultate $d", "Shooting results $d")
    fun mailSubjectN(n: Int, a: String, b: String) = p("Schiessresultate: $n Übungen, $a – $b", "Shooting results: $n sessions, $a – $b")
    val totalOnly get() = p("nur Total", "total only")
    fun mPractice(n: Int) = p("Probe $n, zählt nicht", "practice $n, not counted")
    val targetPhotos get() = p("Scheibenfotos", "Target photos")
    fun backupMailSubject(d: String) = p("CHSchiessbuch-Backup $d", "CHSchiessbuch backup $d")
    val backupMailText get() = p("Backup von CHSchiessbuch mit allen Übungen und Scheibenfotos. Zum Laden: CHSchiessbuch öffnen, «Backup wiederherstellen» antippen und diese Datei wählen.", "Backup of CHSchiessbuch with all sessions and target photos. To load it: open CHSchiessbuch, tap Restore backup and choose this file.")
    val backupFile = "chschiessbuch-backup"
    val csvFile = "chschiessbuch"
    val csvHead: List<String>
        get() = if (de) listOf("datum", "disziplin", "kategorie", "beschreibung", "schiessstand", "waffe", "bedingungen", "serie", "format", "resultat", "maximum", "resultat_10_schuss_a10", "innenzehner", "tiefschuss", "schuesse", "notizen")
        else listOf("date", "discipline", "category", "description", "range", "weapon", "conditions", "series", "format", "score", "max", "score_10_shots_A10", "inner_tens", "tiefschuss", "shots", "notes")

    // championship
    val chTitle get() = p("Meisterschaft", "Championship")
    val chTitleShort get() = p("Meistersch.", "Champ")
    val newSessionShort get() = p("Neue Üb.", "New Sess.")
    val chCurrent get() = p("Aktuell", "Current")
    val chHistory get() = p("Verlauf", "History")
    val chUrl get() = p("Adresse der CSV-Datei", "Address of the CSV file")
    val chLoad get() = p("Laden", "Load")
    val chReload get() = p("Neu laden", "Reload")
    val chFromFile get() = p("Datei wählen", "Choose file")
    val chActivate get() = p("Aktivieren", "Activate")
    val chSettings get() = p("Meisterschaft einrichten", "Championship settings")
    fun chSoon(d: Int) = if (d == 0) p("Heute", "Today") else q("In $d ${if (d == 1) "Tag" else "Tagen"}", "In $d ${if (d == 1) "day" else "days"}", "Dans $d ${if (d == 1) "jour" else "jours"}", "Tra $d ${if (d == 1) "giorno" else "giorni"}")
    val chPast get() = p("Überfällig", "Overdue")
    val chLateAsk get() = p("Diese Übung ist überfällig und wird mit einem Abzug bestraft. Möchtest du fortfahren?", "This activity is overdue and will incur a penalty. Do you want to continue?")
    val chLateGo get() = p("Fortfahren", "Continue")
    val chEmptyCog get() = p("Lade die Datei des Vereins unter «Meisterschaft einrichten» (Zahnrad oben links).", "Load the club's file under Championship settings (cog at the top left).")
    val chOnlyOne get() = p("Es wird nur eine Meisterschaft gespeichert. Eine Datei eines anderen Jahres ersetzt sie; erfasste Übungen bleiben.", "Only one championship is kept. A file for another year replaces it; recorded sessions stay.")
    fun chLoadedInfo(y: Int, n: Int) = p("Geladen: Meisterschaft $y mit $n Einträgen.", "Loaded: championship $y with $n items.")
    // printing
    val print get() = p("Drucken", "Print")
    val printTitle get() = p("Schiessresultate", "Shooting results")
    val printTotal = "Total"
    // new session from a photo of the range printout
    val close get() = p("Schliessen", "Close")
    val newManual get() = p("Manuell erfassen", "Enter manually")
    val newTakePhoto get() = p("Foto aufnehmen", "Take picture")
    val newLoadPhoto get() = p("Foto laden", "Load picture")
    val scanReading get() = p("Ausdruck wird gelesen…", "Reading the printout…")
    val scanNone get() = p("Keine Schüsse gefunden. Streifen flach hinlegen, Spiegelungen vermeiden und den Druck möglichst bildfüllend fotografieren.", "No shots found. Lay the strip flat, avoid glare and let the printed area fill the photo.")
    val scanFail get() = p("Das Foto konnte nicht gelesen werden.", "The photo could not be read.")
    val scanCheck get() = p("Bitte die gelesenen Werte prüfen:", "Please check the values read from the photo:")
    val scanOk get() = p("Aus dem Foto gelesen: alle gedruckten Totale stimmen. Bitte vor dem Speichern kontrollieren.", "Read from the photo: all printed totals match. Please check before saving.")
    fun scanNote(x: String) = p("Automatisch aus dem angehängten Foto gelesen", "Read automatically from the attached photo") + (if (x.isEmpty()) "" else " ($x)") + "."
    val scanTarget get() = p("Scheibe", "Target")
    fun scanWSeries(s: Int, pr: String, c: String) = p("Serie $s: gedruckt $pr, die Schüsse ergeben $c.", "Series $s: printed $pr, shots add up to $c.")
    fun scanWSeriesH(s: Int, pr: Int, c: Int) = p("Serie $s: gedrucktes 100er-Total $pr, die Schüsse ergeben $c.", "Series $s: printed 100-total $pr, shots add up to $c.")
    fun scanWTotal(pr: String, c: String) = p("Gedrucktes Total $pr, die Serien ergeben $c.", "Printed total $pr, series add up to $c.")
    fun scanWTotalH(pr: Int, c: Int) = p("Gedrucktes 100er-Total $pr, die Schüsse ergeben $c.", "Printed 100-total $pr, shots add up to $c.")
    fun scanWShot(n: Int, v: String, h: Int) = p("Schuss $n: Ring $v passt nicht zum 100er-Wert $h.", "Shot $n: ring $v does not fit the 100-value $h.")
    fun scanWMissing(x: String) = p("Schuss $x konnte nicht gelesen werden.", "Shot(s) $x could not be read.")
    val scanWCopies get() = p("Die Kopien auf dem Streifen wurden unterschiedlich gelesen.", "The copies on the strip were read differently.")
    fun printAsk(n: Int) = p("Mit den Scheibenfotos ($n) drucken?", "Print with the target photos ($n)?")
    val printPhotos get() = p("Mit Fotos", "With photos")
    val printNoPhotos get() = p("Ohne Fotos", "Without photos")
    val seriesFormat get() = p("Serienformat", "Series format")
    val shotsH get() = p("Schüsse", "Shots")
    val scoreH get() = p("Resultat", "Score")
    // club logo (hold the logo for 10 seconds)
    val logoClubOn get() = p("SG-Muttenz-Logo und App-Symbol aktiv", "SG Muttenz logo and app icon on")
    val logoClubOff get() = p("Standard-Logo und App-Symbol aktiv", "Standard logo and app icon on")
    val chartToggle get() = p("Grafik ein- oder ausblenden", "Show or hide the chart")
    val chReset get() = p("Zurücksetzen", "Reset")
    val chResetAsk get() = p("Eintrag wieder freigeben. Die bereits erfasste Übung behalten oder löschen?", "Make this item available again. Keep the session already recorded, or delete it?")
    val chKeep get() = p("Übung behalten", "Keep session")
    val chDelete get() = p("Übung löschen", "Delete session")
    fun chProgress(d: Int, n: Int) = p("$d von $n erledigt", "$d of $n done")
    val chNoResult get() = p("noch kein Resultat", "no result yet")
    val chNotShot get() = p("nicht geschossen", "not shot")
    fun chAskSame(y: Int) = p("Meisterschaft $y neu laden? Aktivierte Einträge und ihre Übungen bleiben erhalten.", "Reload championship $y? Activated items and their sessions stay as they are.")
    fun chAskNew(y: Int, old: Int) = p("Meisterschaft $y laden? Sie ersetzt $old. Alle erfassten Übungen bleiben.", "Load championship $y? It replaces $old. All recorded sessions stay.")
    fun chLoaded(n: Int, y: Int) = p("$n Einträge für $y geladen", "$n items loaded for $y")
    fun chWarnN(n: Int) = q("$n ${if (n == 1) "Zeile" else "Zeilen"} mit Problemen", "$n ${if (n == 1) "line" else "lines"} with problems", "$n ${if (n == 1) "ligne" else "lignes"} avec des problèmes", "$n ${if (n == 1) "riga" else "righe"} con problemi")
    val chNoRows get() = p("Die Datei enthält keine Einträge.", "The file contains no items.")
    val chNeedCols get() = p("Die Datei braucht mindestens die Spalten titel und disziplin.", "The file needs at least the columns titel and disziplin.")
    val chNoYear get() = p("Die Datei braucht ein Jahr (Spalte jahr).", "The file needs a year (column jahr).")
    fun chBadDisc(l: Int, v: String) = p("Zeile $l: unbekannte Disziplin «$v», übersprungen", "Line $l: unknown discipline \"$v\", skipped")
    fun chBadSeries(l: Int, v: String) = p("Zeile $l: unbekannte Serie «$v», ignoriert", "Line $l: unknown series \"$v\", ignored")
    val chNeedUrl get() = p("Zuerst die Adresse der CSV-Datei eingeben.", "Enter the address of the CSV file first.")
    val chFetchFail get() = p("Die Datei konnte nicht geladen werden. Adresse prüfen oder «Datei wählen» nutzen.", "The file couldn't be loaded. Check the address or use Choose file.")
    val chBadFile get() = p("Diese Datei konnte nicht gelesen werden.", "This file couldn't be read.")
    fun chDefaultName(y: Int) = p("Meisterschaft $y", "Championship $y")
    val chEmptyH get() = p("Keine Meisterschaft geladen", "No championship loaded")
    val chEmptyP get() = p("Lade die CSV-Datei des Vereins mit den zu schiessenden Übungen. Eine Zeile pro Übung:", "Load the club's CSV file with the sessions to shoot. One line per session:")
    val chNoHistory get() = p("Noch keine früheren Meisterschaften.", "No earlier championships yet.")
    val chHistDel get() = p("Aus dem Verlauf entfernen", "Remove from history")
    fun chPrac(n: Int) = p("$n Probe", "$n practice")
    fun chCustom(n: Int, tg: String, pr: Int) = p("Eigene · ", "Custom · ") + (if (pr > 0) p("$pr Probe + ", "$pr practice + ") else "") + shotsN(n) + " · " + tg

    fun longDate(d: LocalDate): String = d.format(DateTimeFormatter.ofPattern(if (de) "EEE, d. MMM yyyy" else "EEE d MMM yyyy", locale))
    fun shortDate(d: LocalDate): String = d.format(DateTimeFormatter.ofPattern(if (de || fr || itLang) "dd.MM.yyyy" else "dd/MM/yyyy", locale))
    fun score(v: Double): String {
        val r = (v * 10).roundToInt() / 10.0
        return if (r == r.toLong().toDouble()) r.toLong().toString() else String.format(locale, "%.1f", r)
    }
}
