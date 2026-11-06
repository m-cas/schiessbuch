// Checks scan.js with the printouts transcribed in the ShootingResultScanner tests.   node tools/scan-test.js
const P = require("../scan.js");
let fail = 0;
const eq = (name, a, b) => { const x = JSON.stringify(a), y = JSON.stringify(b); if (x !== y) { fail++; console.log("FAIL", name, x, "≠", y); } };
const one = (lines) => P.parseAll(lines)[0];

let r = one(["Pg 59   21/0002", "1 ↗ 4", "2 ↑ 4", "3 + 4", "4 ↙ 4", "5 ↖ 4", "6 ↓ 4 24", "7 → 4", "8 → 4", "9 ↘ 4 12",
  "10 ↓ 4", "11 + 4", "12 ↘ 4 12", "13 ↓ 4", "14 ↓ 4", "15 ↓ 4", "16 + 4", "17 ↑ 4", "18 + 4 24", "72 72", "T/P 8 92", "T/P11 98", "T/P16 97"]);
eq("pg59 program", [r.program, r.targetLabel, r.scoringMode], ["59", "21/0002", "integer"]);
eq("pg59 counts", r.series.map((s) => s.shots.length), [6, 3, 3, 6]);
eq("pg59 printed", r.series.map((s) => s.printedTotal), [24, 12, 12, 24]);
eq("pg59 total", P.total(r), 72);
eq("pg59 deep", r.deepShots, [{ shot: 8, value: 92 }, { shot: 11, value: 98 }, { shot: 16, value: 97 }]);
eq("pg59 verified", [P.verified(r), r.warnings], [true, []]);

r = one(["Pg 32 23/0001", "1 K 7", "2 k 9", "J 4 9", "] t 7", "5 R 9", "6 ÷ 8 49", "7 + 10", "8 k 10", "9 R 10", "10 + 7 37", "8 6 86", "T/P 7 98"]);
eq("pg32 shots", P.shots(r).map((s) => s.value), [7, 9, 9, 7, 9, 8, 10, 10, 10, 7]);
eq("pg32 printed", r.series.map((s) => s.printedTotal), [49, 37]);
eq("pg32 total", r.printedTotal, 86);
eq("pg32 verified", [P.verified(r), r.warnings], [true, []]);

r = one(["Pg191 23/0001", "1 ↓ 6", "2 ↙ 9", "3 ↓ 9", "4 ↘ 8", "5 ↖ 10", "6 ← 10", "7 ↖ 9", "8 ↗ 9", "9 ↘ 8", "10 + 10 88", "88 88"]);
eq("pg191", [r.program, r.series.length, P.total(r), P.verified(r)], ["191", 1, 88, true]);

r = one(["Pg 32 23/0003", "1 ↙ 9", "2 ↗ 9", "3 ↘ 9", "5 ↗ 9", "6 + 10 56"]);
eq("missing", r.warnings.map((w) => w.type), ["missing", "seriesTotal"]);
eq("missing nums", r.warnings[0].numbers, [4]);

r = one(["Scheibe 3/14 K", "19.9.2026 11:27 K", "C1 A10 Benutzerprogra", "0-34251(33) ???? ?", "1 ↓ 9 87P K", "2 ↖ 10 93P K",
  "1 ↙ 9 90 K", "2 ↖ 9 86 K", "3 ↓ 8 79 K", "4 ↓ 8 75 K", "5 ↙ 9 85 K", "Subtota 43 415 K", "6 + 9 88 K", "7 → 9 83 K", "Subtota 18 171 K", "",
  "8 ↓ 10 95 K", "9 ↙ 8 73 K", "10 ↙ 10 94 K", "Subtota 28 262 K", "Total 89 848 K"]);
eq("scheibe meta", [r.targetLabel, r.date, r.time], ["3/14", "19.9.2026", "11:27"]);
eq("scheibe sighters", r.sighters.map((s) => s.value), [9, 10]);
eq("scheibe printed", r.series.map((s) => s.printedTotal), [43, 18, 28]);
eq("scheibe printedH", r.series.map((s) => s.printedHundredTotal), [415, 171, 262]);
eq("scheibe totals", [P.total(r), P.hundredTotal(r), P.verified(r)], [89, 848, true]);

r = one(["TS: 9999/ 17", "STICH NR: 0", "", "1 ↑ P 9 88", "2 → P 9 82", "", "1 ↗ 9 84", "2 ↖ 9 84", "3 ↙ 9 83", "4 ↙ 10 92", "5 ↘ 10 95", "",
  "6 ↓ 8 73", "7 ↙ 8 74", "( 7 ↙ 74 74", "", "8 ↙ 10 93", "9 ↘ 8 77", "10 ↙ 8 77", "( 8 ↙ 93 93", "", "TOTAL 89 832"]);
eq("older", [r.sighters.length, r.series.map((s) => s.shots.length), r.printedTotal, r.printedHundredTotal, P.verified(r)], [2, [5, 2, 3], 89, 832, true]);

const two = P.parseAll(["Scheibe 3/6", "19.9.2026 08:38", "A10 Benutzerprogra", "1 ↗ 7 62P", "2 ↗ 10✱ 96P", "1 ↗ 7 69", "2 ↗ 9 87", "3 ↖ 10 91",
  "4 ↖ 10 94", "5 ↙ 9 87", "", "6 ↙ 9 90", "7 ↖ 7 70", "( 6 ↙ 9 90)", "8 ↖ 9 90", "9 ← 8 78", "10 ↘ 10 91", "( 10 ↘ 10 91)", "Total 88 847", "",
  "Scheibe 3/7", "19.9.2026 08:43", "A10 Benutzerprogra", "1 ↙ 82", "2 ↓ 89", "Total 171"]);
eq("two count", two.length, 2);
eq("two first", [two[0].targetLabel, two[0].sighters[1].inner, two[0].series.map((s) => s.shots.length), P.total(two[0]), P.hundredTotal(two[0]), P.verified(two[0])], ["3/6", true, [5, 2, 3], 88, 847, true]);
eq("two second", [two[1].scoringMode, P.shots(two[1]).map((s) => s.value), P.total(two[1]), P.verified(two[1])], ["hundred", [82, 89], 171, true]);
P.applyHeader({ awardThreshold: 82, payoutThreshold: 148 }, two);
eq("thresholds", [two[0].awardThreshold, two[1].payoutThreshold], [82, 148]);

const h = P.parseHeader(["Schützengesellschaft Ziefen", "16. Föiflibertalschiessen", "AHLT", "Castelli Massimo 1965 V", "Muttenz, Schützengesellschaft",
  "Sturmgewehr 90", "Gruppenstich", "Einzelschütze", "Auszeichnung ab: 82", "Kranzkarte"]);
eq("header", [h.shooterName, h.birthYear, h.category, h.organizer, h.event, h.club, h.weapon, h.competition, h.awardThreshold],
  ["Castelli Massimo", 1965, "V", "Schützengesellschaft Ziefen", "16. Föiflibertalschiessen", "Muttenz, Schützengesellschaft", "Sturmgewehr 90", "Gruppenstich", 82]);

// two copies side by side
const rows = [[["Pg", .20], ["32", .24], ["23/0003", .32]], [["1", .21], ["↙", .24], ["9", .30]], [["2", .21], ["↗", .24], ["9", .30]], [["3", .21], ["↘", .24], ["9", .30]],
  [["4", .21], ["↑", .24], ["10", .30]], [["5", .21], ["↗", .24], ["9", .30]], [["6", .21], ["+", .24], ["10", .30], ["56", .42]], [["7", .21], ["→", .24], ["10", .30]],
  [["8", .21], ["↗", .24], ["9", .30]], [["9", .21], ["↘", .24], ["8", .30]], [["10", .21], ["+", .24], ["10", .30], ["37", .42]], [["93", .30], ["93", .42]], [["T/P10", .21], ["99", .30]]];
const words = [];
rows.forEach((row, i) => { const y = 0.9 - i * 0.03; for (const [t, x] of row) for (const off of [0, 0.35]) words.push({ text: t, x: x + off, y, w: 0.015 * t.length, h: 0.02 }); });
const cols = P.columns(words);
eq("columns", [cols.length, cols[0].join("|") === cols[1].join("|"), cols[0][0], cols[0][6]], [2, true, "Pg 32 23/0003", "6 + 10 56"]);
const merged = P.mergeCopies(cols.flatMap((c) => P.parseAll(c)));
eq("merged", [merged.length, merged[0].copiesRead, merged[0].copiesAgree, P.total(merged[0]), P.verified(merged[0])], [1, 2, true, 93, true]);

// two copies, header of the left one misread ("Pg1i91", "21/00044"): found by the repeating rows,
// the left copy's misread total is outvoted by the right copy
{
  const rows2 = [[["Pg1i91", .20], ["21/00044", .30]], [["1", .21], [">", .24], ["9", .30]], [["2", .21], [">", .24], ["7", .30]], [["3", .21], [">", .24], ["8", .30]],
    [["4", .21], [">", .24], ["9", .30]], [["5", .21], [">", .24], ["9", .30]], [["6", .21], [">", .24], ["10", .30]], [["7", .21], [">", .24], ["9", .30]],
    [["8", .21], [">", .24], ["9", .30]], [["9", .21], [">", .24], ["9", .30]], [["10", .20], ["+", .24], ["10", .30], ["89", .40]], [["89", .30], ["89", .40]]];
  const w2 = [];
  rows2.forEach((row, i) => { const y = 0.9 - i * 0.03; for (const [t, x] of row) for (const off of [0, 0.33]) w2.push({ text: off && t === "Pg1i91" ? "Pg191" : off && t === "21/00044" ? "21/0004" : t, x: x + off, y, w: 0.015 * t.length, h: 0.02 }); });
  const c2 = P.columns(w2);
  const m2 = P.mergeCopies(c2.flatMap((c) => P.parseAll(c)));
  eq("copies without header", [c2.length, m2.length, m2[0].targetLabel, P.shots(m2[0]).length, P.total(m2[0]), P.verified(m2[0])], [2, 1, "21/0004", 10, 89, true]);
  // a single copy where a value equals its shot number is not split
  const w3 = [];
  [[["1", .21], [">", .24], ["1", .30]], [["2", .21], [">", .24], ["9", .30]], [["3", .21], [">", .24], ["3", .30]], [["4", .21], [">", .24], ["9", .30]],
   [["5", .21], [">", .24], ["9", .30]], [["6", .21], [">", .24], ["9", .30]], [["7", .21], [">", .24], ["9", .30]], [["8", .21], [">", .24], ["8", .30]],
   [["9", .21], [">", .24], ["9", .30]], [["10", .20], [">", .24], ["10", .30]]].forEach((row, i) => { for (const [t, x] of row) w3.push({ text: t, x, y: 0.9 - i * 0.03, w: 0.015 * t.length, h: 0.02 }); });
  eq("single copy stays one", P.columns(w3).length, 1);
}

// generic sheet
r = one(["Serie 1  10.5 10.2 9.8 10.1 10.4 9.9 10.0 10.3 10.6 9.7 = 101.5"]);
eq("generic", [r.scoringMode, P.shots(r).length, r.series[0].printedTotal, P.verified(r)], ["decimal", 10, 101.5, true]);

console.log(fail ? `${fail} failed` : "all scan tests passed");
process.exit(fail ? 1 : 0);
