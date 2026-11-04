// Creates or updates the native Android / iOS project from the web app.
// Usage: node scripts/prepare-native.mjs android|ios
// Optional environment: BUILD_NUMBER (integer), APP_VERSION (e.g. 1.0.42)
//
// The native projects (android/, ios/) are generated on the build machine and
// are not kept in the repository; everything that differs from Capacitor's
// defaults is applied here, so this file is the single place to change them.
import { execSync } from "node:child_process";
import { existsSync, readFileSync, writeFileSync } from "node:fs";

const platform = process.argv[2];
if (!["android", "ios"].includes(platform)) {
  console.error("Usage: node scripts/prepare-native.mjs android|ios");
  process.exit(1);
}
const run = (cmd) => { console.log(`> ${cmd}`); execSync(cmd, { stdio: "inherit" }); };
const build = Number(process.env.BUILD_NUMBER || 1);
const version = process.env.APP_VERSION || `1.0.${build}`;

if (!existsSync(platform)) run(`npx cap add ${platform}`);

// App icon and splash screen from assets/logo.png (target on club green).
try {
  run(`npx @capacitor/assets generate --${platform} --iconBackgroundColor "#2c5a38" --iconBackgroundColorDark "#2c5a38" --splashBackgroundColor "#eef0ec" --splashBackgroundColorDark "#111512"`);
} catch {
  console.warn("Icon generation failed; keeping the default icons.");
}

function patch(file, fn) {
  const before = readFileSync(file, "utf8"), after = fn(before);
  if (after !== before) { writeFileSync(file, after); console.log(`patched ${file}`); }
}

if (platform === "android") {
  patch("android/app/build.gradle", (s) => {
    s = s.replace(/versionCode \d+/, `versionCode ${build}`).replace(/versionName "[^"]*"/, `versionName "${version}"`);
    if (!s.includes("signingConfigs {")) {
      // Release signing with the key stored in the repository's GitHub secrets.
      s = s.replace(/android \{\n/, `android {
    signingConfigs {
        release {
            if (System.getenv("ANDROID_KEYSTORE_FILE")) {
                storeFile file(System.getenv("ANDROID_KEYSTORE_FILE"))
                storePassword System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias System.getenv("ANDROID_KEY_ALIAS")
                keyPassword System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }
`);
      s = s.replace(/buildTypes \{\n(\s*)release \{\n/, (m, ind) => `${m}${ind}    if (System.getenv("ANDROID_KEYSTORE_FILE")) { signingConfig signingConfigs.release }\n`);
    }
    return s;
  });
}

if (platform === "ios") {
  patch("ios/App/App/Info.plist", (s) => {
    const keys = {
      NSCameraUsageDescription: "MySchiessbuch braucht die Kamera, um deine Scheiben zu fotografieren. / MySchiessbuch uses the camera to photograph your targets.",
      NSPhotoLibraryUsageDescription: "MySchiessbuch braucht Zugriff auf Fotos, um Scheibenbilder hinzuzufügen. / MySchiessbuch uses your photos to add target pictures.",
      NSPhotoLibraryAddUsageDescription: "MySchiessbuch kann Scheibenbilder in deinen Fotos speichern. / MySchiessbuch can save target pictures to your photos.",
    };
    let add = "";
    for (const [k, v] of Object.entries(keys)) if (!s.includes(`<key>${k}</key>`)) add += `\t<key>${k}</key>\n\t<string>${v}</string>\n`;
    if (!s.includes("<key>ITSAppUsesNonExemptEncryption</key>")) add += `\t<key>ITSAppUsesNonExemptEncryption</key>\n\t<false/>\n`;
    return add ? s.replace(/<\/dict>\s*<\/plist>\s*$/, `${add}</dict>\n</plist>\n`) : s;
  });
}

run(`npx cap sync ${platform}`);
console.log(`${platform} project ready, version ${version} (${build})`);
