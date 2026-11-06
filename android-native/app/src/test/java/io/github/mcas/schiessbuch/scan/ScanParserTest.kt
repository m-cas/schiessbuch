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
}
