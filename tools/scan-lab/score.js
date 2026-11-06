// Runs the printout parser (scan.js) on words recognized by ML Kit or Vision (scan-lab output) and
// compares the result with the values printed on the photos (expected.json).
//   node tools/scan-lab/score.js lab/vision.json [-v]
const fs = require("fs");
const path = require("path");
const P = require("../../scan.js");
let pages = JSON.parse(fs.readFileSync(process.argv[2], "utf8"));
// row crops (sNN_rKK.jpg): put their words back on one page, row K at height 1 - (K + 0.5) / rows
const rowPages = {};
for (const pg of pages) { const m = /^(s\d+)_r(\d+)\.jpg$/.exec(pg.file); if (m) (rowPages[m[1]] = rowPages[m[1]] || []).push([+m[2], pg]); }
for (const [s, list] of Object.entries(rowPages)) {
  const n = Math.max(...list.map((x) => x[0])) + 1, words = [];
  for (const [k, pg] of list) for (const w of pg.words || []) words.push({ t: w.t, x: w.x, w: w.w, y: 1 - (k + 0.7) / n, h: 0.4 / n });
  pages.push({ file: `${s}_rows.jpg`, words });
}
pages = pages.filter((p) => !/_r\d+\.jpg$/.test(p.file));
const verbose = process.argv.includes("-v");
const expected = JSON.parse(fs.readFileSync(path.join(__dirname, "expected.json"), "utf8"));
let good = 0, total = 0;
const byVariant = {};
for (const pg of pages) {
  const m = /^(s\d+)(?:_(v\d+|rows))?\.jpg$/.exec(pg.file);
  const exp = expected[m ? m[1] + ".jpg" : pg.file] || [];
  const variant = (m && m[2]) || "v0";
  byVariant[variant] = byVariant[variant] || [0, 0];
  const words = (pg.words || []).map((w) => ({ text: w.t, x: w.x, y: w.y, w: w.w, h: w.h }));
  const cols = P.columns(words);
  const res = P.mergeCopies(cols.flatMap((c) => P.parseAll(c)));
  for (const e of exp) {
    total++;
    const r = res.find((x) => x.targetLabel === e.target) || (e.target == null ? res[0] : null);
    const shots = r ? P.shots(r).map((s) => s.value) : [];
    const sight = r ? r.sighters.map((s) => s.value) : [];
    const ok = r && JSON.stringify(shots) === JSON.stringify(e.shots) && P.total(r) === e.total && (!e.sighters || JSON.stringify(sight) === JSON.stringify(e.sighters));
    if (ok) good++;
    byVariant[variant][1]++; if (ok) byVariant[variant][0]++;
    console.log(`${ok ? "OK  " : "FAIL"} ${pg.file} ${e.target}: ${r ? `${shots.join(",")} = ${P.total(r)}` : "not found"}${ok ? "" : `   (expected ${e.shots.join(",")} = ${e.total})`}`);
  }
  if (verbose) { cols.forEach((c, i) => console.log(`   --- column ${i + 1}\n` + c.map((l) => "   | " + l).join("\n"))); }
}
console.log(`\n${good} of ${total} printouts read correctly`);
for (const [v, [g, t]] of Object.entries(byVariant).sort()) console.log(`  ${v}: ${g} of ${t}`);
