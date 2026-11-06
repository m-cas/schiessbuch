package io.github.mcas.schiessbuch.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Printouts transcribed from real strips (same data as the ShootingResultScanner tests). */
class ScanParserTest {
    private fun one(lines: List<String>) = Scan.parseAll(lines).first()

    @Test fun pg59FourSeriesAndDeepShots() {
        val r = one(listOf("Pg 59   21/0002", "1 ↗ 4", "2 ↑ 4", "3 + 4", "4 ↙ 4", "5 ↖ 4", "6 ↓ 4 24", "7 → 4", "8 → 4", "9 ↘ 4 12",
            "10 ↓ 4", "11 + 4", "12 ↘ 4 12", "13 ↓ 4", "14 ↓ 4", "15 ↓ 4", "16 + 4", "17 ↑ 4", "18 + 4 24", "72 72", "T/P 8 92", "T/P11 98", "T/P16 97"))
        assertEquals("59", r.program); assertEquals("21/0002", r.targetLabel)
        assertEquals(listOf(6, 3, 3, 6), r.series.map { it.shots.size })
        assertEquals(listOf(24.0, 12.0, 12.0, 24.0), r.series.map { it.printedTotal })
        assertEquals(72.0, r.total, 0.0)
        assertEquals(listOf(DeepShot(8, 92), DeepShot(11, 98), DeepShot(16, 97)), r.deepShots)
        assertTrue(r.warnings.toString(), r.isVerified)
    }

    @Test fun pg32TypicalOcrNoise() {
        val r = one(listOf("Pg 32 23/0001", "1 K 7", "2 k 9", "J 4 9", "] t 7", "5 R 9", "6 ÷ 8 49", "7 + 10", "8 k 10", "9 R 10", "10 + 7 37", "8 6 86", "T/P 7 98"))
        assertEquals(listOf(7.0, 9.0, 9.0, 7.0, 9.0, 8.0, 10.0, 10.0, 10.0, 7.0), r.shots.map { it.value })
        assertEquals(listOf(49.0, 37.0), r.series.map { it.printedTotal })
        assertEquals(86.0, r.printedTotal)
        assertTrue(r.warnings.toString(), r.isVerified)
    }

    @Test fun missingLineIsReported() {
        val r = one(listOf("Pg 32 23/0003", "1 ↙ 9", "2 ↗ 9", "3 ↘ 9", "5 ↗ 9", "6 + 10 56"))
        assertTrue(r.warnings.contains(ScanWarning.Missing(listOf(4))))
        assertTrue(r.warnings.contains(ScanWarning.SeriesTotal(1, 56.0, 46.0)))
    }

    @Test fun scheibeStrip() {
        val r = one(listOf("Scheibe 3/14 K", "19.9.2026 11:27 K", "C1 A10 Benutzerprogra", "0-34251(33) ???? ?", "1 ↓ 9 87P K", "2 ↖ 10 93P K",
            "1 ↙ 9 90 K", "2 ↖ 9 86 K", "3 ↓ 8 79 K", "4 ↓ 8 75 K", "5 ↙ 9 85 K", "Subtota 43 415 K", "6 + 9 88 K", "7 → 9 83 K", "Subtota 18 171 K", "",
            "8 ↓ 10 95 K", "9 ↙ 8 73 K", "10 ↙ 10 94 K", "Subtota 28 262 K", "Total 89 848 K"))
        assertEquals("3/14", r.targetLabel); assertEquals("19.9.2026", r.date); assertEquals("11:27", r.time)
        assertEquals(listOf(9.0, 10.0), r.sighters.map { it.value })
        assertEquals(listOf(43.0, 18.0, 28.0), r.series.map { it.printedTotal })
        assertEquals(listOf(415, 171, 262), r.series.map { it.printedHundredTotal })
        assertEquals(89.0, r.total, 0.0); assertEquals(848, r.hundredTotal)
        assertTrue(r.warnings.toString(), r.isVerified)
    }

    @Test fun olderStripWithBestShotLines() {
        val r = one(listOf("TS: 9999/ 17", "STICH NR: 0", "", "1 ↑ P 9 88", "2 → P 9 82", "", "1 ↗ 9 84", "2 ↖ 9 84", "3 ↙ 9 83", "4 ↙ 10 92", "5 ↘ 10 95", "",
            "6 ↓ 8 73", "7 ↙ 8 74", "( 7 ↙ 74 74", "", "8 ↙ 10 93", "9 ↘ 8 77", "10 ↙ 8 77", "( 8 ↙ 93 93", "", "TOTAL 89 832"))
        assertEquals(2, r.sighters.size)
        assertEquals(listOf(5, 2, 3), r.series.map { it.shots.size })
        assertEquals(89.0, r.printedTotal); assertEquals(832, r.printedHundredTotal)
        assertTrue(r.warnings.toString(), r.isVerified)
    }

    @Test fun twoProgramsIncludingHundredOnly() {
        val res = Scan.parseAll(listOf("Scheibe 3/6", "19.9.2026 08:38", "A10 Benutzerprogra", "1 ↗ 7 62P", "2 ↗ 10✱ 96P", "1 ↗ 7 69", "2 ↗ 9 87", "3 ↖ 10 91",
            "4 ↖ 10 94", "5 ↙ 9 87", "", "6 ↙ 9 90", "7 ↖ 7 70", "( 6 ↙ 9 90)", "8 ↖ 9 90", "9 ← 8 78", "10 ↘ 10 91", "( 10 ↘ 10 91)", "Total 88 847", "",
            "Scheibe 3/7", "19.9.2026 08:43", "A10 Benutzerprogra", "1 ↙ 82", "2 ↓ 89", "Total 171"))
        assertEquals(2, res.size)
        assertTrue(res[0].sighters[1].inner)
        assertEquals(listOf(5, 2, 3), res[0].series.map { it.shots.size })
        assertEquals(88.0, res[0].total, 0.0); assertEquals(847, res[0].hundredTotal); assertTrue(res[0].isVerified)
        assertEquals(ScoringMode.HUNDRED, res[1].scoringMode)
        assertEquals(171.0, res[1].total, 0.0); assertTrue(res[1].isVerified)
        Scan.applyHeader(HeaderInfo().apply { awardThreshold = 82; payoutThreshold = 148 }, res)
        assertEquals(82, res[0].awardThreshold); assertEquals(148, res[1].payoutThreshold)
    }

    @Test fun sidewaysHeader() {
        val h = Scan.parseHeader(listOf("Schützengesellschaft Ziefen", "16. Föiflibertalschiessen", "AHLT", "Castelli Massimo 1965 V",
            "Muttenz, Schützengesellschaft", "Sturmgewehr 90", "Gruppenstich", "Einzelschütze", "Auszeichnung ab: 82", "Kranzkarte"))
        assertEquals("Castelli Massimo", h.shooterName); assertEquals(1965, h.birthYear); assertEquals("V", h.category)
        assertEquals("Schützengesellschaft Ziefen", h.organizer); assertEquals("16. Föiflibertalschiessen", h.event)
        assertEquals("Muttenz, Schützengesellschaft", h.club); assertEquals("Sturmgewehr 90", h.weapon)
        assertEquals("Gruppenstich", h.competition); assertEquals(82, h.awardThreshold)
    }

    @Test fun sideBySideCopies() {
        val rows = listOf(listOf("Pg" to .20, "32" to .24, "23/0003" to .32), listOf("1" to .21, "↙" to .24, "9" to .30), listOf("2" to .21, "↗" to .24, "9" to .30),
            listOf("3" to .21, "↘" to .24, "9" to .30), listOf("4" to .21, "↑" to .24, "10" to .30), listOf("5" to .21, "↗" to .24, "9" to .30),
            listOf("6" to .21, "+" to .24, "10" to .30, "56" to .42), listOf("7" to .21, "→" to .24, "10" to .30), listOf("8" to .21, "↗" to .24, "9" to .30),
            listOf("9" to .21, "↘" to .24, "8" to .30), listOf("10" to .21, "+" to .24, "10" to .30, "37" to .42), listOf("93" to .30, "93" to .42), listOf("T/P10" to .21, "99" to .30))
        val words = rows.flatMapIndexed { i, row -> row.flatMap { (t, x) -> listOf(0.0, 0.35).map { off -> Word(t, x + off, 0.9 - i * 0.03, 0.015 * t.length, 0.02) } } }
        val cols = Scan.columns(words)
        assertEquals(2, cols.size); assertEquals(cols[0], cols[1])
        assertEquals("Pg 32 23/0003", cols[0][0]); assertEquals("6 + 10 56", cols[0][6])
        val merged = Scan.mergeCopies(cols.flatMap { Scan.parseAll(it) })
        assertEquals(1, merged.size); assertEquals(2, merged[0].copiesRead); assertEquals(true, merged[0].copiesAgree)
        assertEquals(93.0, merged[0].total, 0.0); assertTrue(merged[0].isVerified)
    }

    @Test fun copiesFoundWithoutHeader() {
        val rows = listOf(listOf("Pg1i91" to .20, "21/00044" to .30), listOf("1" to .21, ">" to .24, "9" to .30), listOf("2" to .21, ">" to .24, "7" to .30),
            listOf("3" to .21, ">" to .24, "8" to .30), listOf("4" to .21, ">" to .24, "9" to .30), listOf("5" to .21, ">" to .24, "9" to .30),
            listOf("6" to .21, ">" to .24, "10" to .30), listOf("7" to .21, ">" to .24, "9" to .30), listOf("8" to .21, ">" to .24, "9" to .30),
            listOf("9" to .21, ">" to .24, "9" to .30), listOf("10" to .20, "+" to .24, "10" to .30, "89" to .40), listOf("89" to .30, "89" to .40))
        val words = rows.flatMapIndexed { i, row -> row.flatMap { (t, x) -> listOf(0.0, 0.33).map { off ->
            val text = if (off > 0 && t == "Pg1i91") "Pg191" else if (off > 0 && t == "21/00044") "21/0004" else t
            Word(text, x + off, 0.9 - i * 0.03, 0.015 * t.length, 0.02) } } }
        val cols = Scan.columns(words)
        assertEquals(2, cols.size)
        val merged = Scan.mergeCopies(cols.flatMap { Scan.parseAll(it) })
        assertEquals(1, merged.size); assertEquals("21/0004", merged[0].targetLabel)
        assertEquals(10, merged[0].shots.size); assertEquals(89.0, merged[0].total, 0.0); assertTrue(merged[0].isVerified)
    }

    @Test fun sightersWhenNumberingRestarts() {
        val r = one(listOf("Pg211 23/0003", "1 ↗ 9", "2 ↗ 10 19", "1 + 10", "2 ↓ 10", "3 ↓ 9", "4 ↙ 8", "5 → 10", "6 ↘ 8 55", "7 ↘ 10", "8 → 8", "9 ↘ 9", "10 ↘ 9 36", "91 91", "T/P 7 94"))
        assertEquals(listOf(9.0, 10.0), r.sighters.map { it.value }); assertEquals(listOf(6, 4), r.series.map { it.shots.size })
        assertEquals(91.0, r.total, 0.0); assertTrue(r.warnings.toString(), r.isVerified)
    }

    @Test fun hundredSeriesTotalOnLastRow() {
        val r = one(listOf("Pg 57 23/0051", "1 ← 94", "2 + 97", "3 → 89", "4 ↑ 78", "5 ↖ 89", "6 ↓ 91", "7 → 80", "8 ↑ 87", "9 ↗ 94", "10 ↗ 75 874", "874 874"))
        assertEquals(ScoringMode.HUNDRED, r.scoringMode); assertEquals(10, r.shots.size); assertEquals(874.0, r.total, 0.0); assertTrue(r.warnings.toString(), r.isVerified)
    }

    @Test fun thermalSubtotalAfterParentheses() {
        val r = one(listOf("Scheibe 10/6 K", "18.7.2026 09:05 K", "[1] A10 Benutzerpr", "1-34251(49) Castel", "1 ↗ 8 72 K", "2 ↖ 9 86 K", "3 ↘ 9 89 K", "4 ↙ 10# 96 K", "5 ↙ 8 73 K",
            "6 ← 8 77 K", "Subtota 52 493 K", "7 ↘ 8 74 K", "8 ↘ 9 85 K", "9 ↙ 7 67 K", "10 ↙ 10 92 K", "( 10 ↙ 10 92) K", "Subtota 34 318 K", "Total 86 811 K"))
        assertEquals(listOf(52.0, 34.0), r.series.map { it.printedTotal }); assertEquals(811, r.hundredTotal)
        assertEquals(1, r.shots.count { it.inner }); assertTrue(r.warnings.toString(), r.isVerified)
    }

    @Test fun obligatorischGroupsNumberedFromOne() {
        val r = one(listOf("1 ← 4", "2 ← 4", "3 ← 5", "4 ↖ 5", "5 ← 4 22", "", "1 ↖ 3", "2 ↙ 3", "3 ↖ 2", "4 ← 3", "5 ↖ 3 14", "", "1 ↙ 2", "2 ← 4", "3 → 4", "4 ↑ 4", "5 ↓ 4 18", "",
            "1 ↘ 4", "2 ↗ 4", "3 ↙ 3", "4 ↙ 3", "5 ↓ 4 18", "", "20/ 72"))
        assertEquals(0, r.sighters.size); assertEquals(listOf(22.0, 14.0, 18.0, 18.0), r.series.map { it.printedTotal })
        assertEquals(72.0, r.total, 0.0); assertTrue(r.warnings.toString(), r.isVerified)
    }

    @Test fun frenchCibleWithTotalPart() {
        val rs = Scan.parseAll(listOf("Cible 5/20", "6.9.2025 13:47", "[1] A10 Vignoble", "1 ↑ 8 78", "2 ↖ 9 89", "3 ↘ 10 93", "4 ↓ 9 87", "5 ↙ 9 86", "total part 45 433", "",
            "6 ↗ 7 66", "7 ← 9 84", "( 7 ← 9 84)", "total part 16 150", "", "8 ← 9 84", "9 → 10 91", "10 ← 10 91", "( 9 → 10 91)", "total part 29 266", "Total 90 849", "",
            "Cible 5/22", "6.9.2025 13:53", "[2] A10 Répartition", "1 ← 8 71", "2 ↓ 8 78", "3 ↓ 9 88", "total part 25 237", "", "4 ↖ 10 93", "5 ← 8 72", "6 ← 8 73", "( 4 ↖ 10 93)", "total part 26 238", "Total 51 475"))
        assertEquals(listOf("5/20", "5/22"), rs.map { it.targetLabel }); assertEquals(listOf(90.0, 51.0), rs.map { it.total })
        assertTrue(rs.all { it.isVerified })
    }

    @Test fun runningNumberAfterHundredValue() {
        val r = one(listOf("Scheibe 24/4", "16.8.2025 08:36", "1 ↖ 8 73P 3", "2 ↖ 8 73P 3", "1 ↙ 9 85 37", "2 ← 8 73 37", "3 ← 9 82 38", "4 ↗ 7 63 38", "5 ↓ 8 77 39", "Subtota 41 380",
            "6 ↙ 10 91 39", "7 ↖ 8 76 40", "( 6 ↙ 10 91)", "Subtota 18 167", "", "8 ↙ 7 67 40", "9 → 10 91 40", "10 ↙ 9 83 41", "( 9 → 10 91)", "Subtota 26 241", "Total 85 788"))
        assertEquals(2, r.sighters.size); assertEquals(10, r.shots.size); assertEquals(85.0, r.total, 0.0); assertEquals(788, r.hundredTotal); assertTrue(r.warnings.toString(), r.isVerified)
    }

    @Test fun feldschiessenOnTheFormContinues() {
        val rs = Scan.parseAll(listOf("PgFS1 24/0016", "1 ← 4", "2 ↓ 4", "3 ← 4", "4 ↖ 4", "5 ↗ 4", "6 → 3 23", "", "PgFS2 24/0017", "7 ← 3", "8 ← 3", "9 ↖ 3", "10 ↓ 4", "11 ↖ 3", "12 ↖ 3 19", "",
            "PgFS3 24/0018", "13 ↙ 3", "14 ← 4", "15 ↙ 4", "16 ← 4", "17 ↖ 3", "18 ← 4 22"))
        assertEquals(1, rs.size); assertEquals(18, rs[0].shots.size); assertEquals(64.0, rs[0].total, 0.0); assertTrue(rs[0].warnings.toString(), rs[0].isVerified)
    }
}
