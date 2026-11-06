// Runs reader.js on grey images (raw .gray files: 8-byte header w,h as uint32 + pixels) and writes scan-lab JSON.
//   node tools/reader/js-check.js MODELDIR out.json img1.gray ...
const fs = require("fs"), path = require("path");
const R = require("../../reader.js");
const md = process.argv[2];
const meta = JSON.parse(fs.readFileSync(path.join(md, "reader.json"), "utf8"));
const bin = fs.readFileSync(path.join(md, "reader.bin"));
const reader = R.create(meta, bin.buffer.slice(bin.byteOffset, bin.byteOffset + bin.length));
const pages = [];
for (const f of process.argv.slice(4)) {
  const b = fs.readFileSync(f), w = b.readUInt32LE(0), h = b.readUInt32LE(4);
  const t0 = Date.now();
  const words = reader.read(new Uint8Array(b.buffer, b.byteOffset + 8, w * h), w, h);
  console.log(path.basename(f), words.length, `${Date.now() - t0} ms`, words.slice(0, 30).map((x) => x.text).join(" "));
  pages.push({ file: path.basename(f).replace(".gray", ".jpg"), words: words.map((x) => ({ t: x.text, x: x.x, y: x.y, w: x.w, h: x.h })) });
}
fs.writeFileSync(process.argv[3], JSON.stringify(pages));
