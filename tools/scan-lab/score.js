// Runs the printout parser (scan.js) on words recognized by ML Kit or Vision (scan-lab output) and
// compares the result with the values printed on the photos (expected.json).
//   node tools/scan-lab/score.js lab/vision.json [-v]
const fs = require("fs");
const path = require("path");
const P = require("../../scan.js");
const pages = JSON.parse(fs.readFileSync(process.argv[2], "utf8"));
const verbose = process.argv.includes("-v");
const expected = JSON.parse(fs.readFileSync(path.join(__dirname, "expected.json"), "utf8"));
let good = 0, total = 0;
const byVariant = {};
for (const pg of pages) {
  const m = /^(s\d+)(?:_(v\d+))?\.jpg$/.exec(pg.file);
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
    const ok = r && JSON.stringify(shots) === JSON.stringify(e.shots) && P.total(r) === e.total;
    if (ok) good++;
    byVariant[variant][1]++; if (ok) byVariant[variant][0]++;
    console.log(`${ok ? "OK  " : "FAIL"} ${pg.file} ${e.target}: ${r ? `${shots.join(",")} = ${P.total(r)}` : "not found"}${ok ? "" : `   (expected ${e.shots.join(",")} = ${e.total})`}`);
  }
  if (verbose) { cols.forEach((c, i) => console.log(`   --- column ${i + 1}\n` + c.map((l) => "   | " + l).join("\n"))); }
}
console.log(`\n${good} of ${total} printouts read correctly`);
for (const [v, [g, t]] of Object.entries(byVariant).sort()) console.log(`  ${v}: ${g} of ${t}`);
