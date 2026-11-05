#!/bin/bash
# Builds the MySchiessbuch iPhone app with this Mac's Xcode and uploads it to TestFlight.
# Start in Terminal with:  bash ~/Documents/Development/Schiessbuch/build-ios.sh
# A build that was already uploaded is not built again; to build it anyway:  bash build-ios.sh --force
set -e
VERSION="2.0.16"   # shown in TestFlight
BUILD="16"         # must be higher than the last TestFlight build
HERE="$(cd "$(dirname "$0")" && pwd)"
FORCE=0; [ "$1" = "--force" ] && FORCE=1

# Builds already sent to TestFlight (from this Mac and from GitHub), one "version (build)" per line.
LOG="$HERE/uploaded-builds.txt"
touch "$LOG"
if grep -qx "$VERSION ($BUILD)" "$LOG" && [ "$FORCE" = 0 ]; then
  echo "Version $VERSION ($BUILD) ist schon hochgeladen ($(basename "$LOG"))."
  echo "Für eine neue Version VERSION und BUILD oben in diesem Skript erhöhen, oder mit --force trotzdem bauen."
  exit 0
fi
if awk -v b="$BUILD" -F'[()]' '$2+0 >= b+0 {found=1} END {exit !found}' "$LOG" && [ "$FORCE" = 0 ]; then
  echo "Build $BUILD ist nicht höher als ein bereits hochgeladener Build:"; sed 's/^/  /' "$LOG"
  echo "Apple nimmt nur höhere Build-Nummern an. BUILD oben im Skript erhöhen, oder mit --force trotzdem bauen."
  exit 1
fi
cd "$HERE/schiessbuch"   # the clone of this repository next to the script

echo "== 1/5 Neueste Version holen"
git checkout -q main
git pull -q --ff-only

echo "== 2/5 Werkzeuge prüfen"
if ! command -v xcodegen >/dev/null; then
  if ! command -v brew >/dev/null; then echo "Homebrew fehlt: zuerst Homebrew installieren (brew.sh)."; exit 1; fi
  brew install xcodegen
fi
xcodebuild -version | head -1
MAJOR=$(xcodebuild -version | head -1 | sed -E 's/Xcode ([0-9]+).*/\1/')
if [ "$MAJOR" -lt 26 ]; then echo "Xcode 26 oder neuer nötig (App Store)."; exit 1; fi

echo "== 3/5 Team für die Signierung"
TEAM=$(security find-certificate -a -c "Apple Development" -p 2>/dev/null | openssl x509 -noout -subject 2>/dev/null | sed -nE 's/.*OU ?= ?([A-Z0-9]{10}).*/\1/p' | head -1)
if [ -z "$TEAM" ]; then
  echo "Team-ID nicht gefunden. Sie steht auf developer.apple.com unter Account → Membership (10 Zeichen)."
  read -r -p "Team-ID: " TEAM
fi
echo "Team: $TEAM"

echo "== 4/5 Projekt erstellen und archivieren (einige Minuten)"
cd ios-native
xcodegen generate --quiet
OUT="$HERE/build"
mkdir -p "$OUT"
xcodebuild -project MySchiessbuch.xcodeproj -scheme MySchiessbuch -configuration Release \
  -destination 'generic/platform=iOS' -archivePath "$OUT/MySchiessbuch.xcarchive" \
  DEVELOPMENT_TEAM="$TEAM" CODE_SIGN_STYLE=Automatic CODE_SIGNING_ALLOWED=NO \
  MARKETING_VERSION="$VERSION" CURRENT_PROJECT_VERSION="$BUILD" \
  clean archive -quiet
# The archive is unsigned (a development profile would need a registered iPhone);
# the upload step below signs it for the App Store, which needs no device.

echo "== 5/5 Hochladen zu TestFlight"
cat > "$OUT/ExportOptions.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>method</key><string>app-store-connect</string>
  <key>destination</key><string>upload</string>
  <key>teamID</key><string>$TEAM</string>
  <key>signingStyle</key><string>automatic</string>
</dict></plist>
PLIST
xcodebuild -exportArchive -archivePath "$OUT/MySchiessbuch.xcarchive" -exportOptionsPlist "$OUT/ExportOptions.plist" \
  -exportPath "$OUT/export" -allowProvisioningUpdates -quiet

grep -qx "$VERSION ($BUILD)" "$LOG" || echo "$VERSION ($BUILD)" >> "$LOG"
echo ""
echo "Fertig: Version $VERSION ($BUILD) ist hochgeladen. Sie erscheint nach 5–30 Minuten in TestFlight."
