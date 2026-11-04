// Copies the web app (the files GitHub Pages serves from the repository root)
// into www/, which Capacitor packs into the Android and iOS apps.
import { cpSync, mkdirSync, rmSync, existsSync } from "node:fs";

const FILES = [
  "index.html",
  "manifest.webmanifest",
  "logo.png",
  "icon-192.png",
  "icon-512.png",
  "icon-maskable-512.png",
  "apple-touch-icon.png",
];

rmSync("www", { recursive: true, force: true });
mkdirSync("www");
for (const f of FILES) {
  if (!existsSync(f)) throw new Error(`Missing web file: ${f}`);
  cpSync(f, `www/${f}`);
}
// sw.js is left out on purpose: the native apps load their files from the app
// package and need no offline cache.
console.log(`Copied ${FILES.length} files to www/`);
