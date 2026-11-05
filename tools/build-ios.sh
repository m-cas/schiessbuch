#!/bin/bash
# Builds the MySchiessbuch iPhone app with this Mac's Xcode and uploads it to TestFlight.
# Start in Terminal with:  bash ~/Documents/Development/Schiessbuch/build-ios.sh
# The build number is picked automatically: one higher than the last build in uploaded-builds.txt,
# version 2.0.<build>. When the iPhone app has not changed since the last upload, nothing is built;
# to build anyway (with the next number):  bash build-ios.sh --force
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
FORCE=0; [ "$1" = "--force" ] && FORCE=1

# Builds already sent to TestFlight, one "version (build) commit" per line (older lines have no commit).
LOG="$HERE/uploaded-builds.txt"
touch "$LOG"
cd "$HERE/schiessbuch"

echo "== 1/5 Getting the latest code"
git checkout -q main
git pull -q --ff-only
HEAD_COMMIT=$(git rev-parse --short HEAD)

# Last uploaded build and the commit it was made from
LAST=$(awk -F'[()]' '$2+0 > m {m = $2+0} END {print m+0}' "$LOG")
LAST_COMMIT=$(awk -F'[()]' -v b="$LAST" '$2+0 == b {split($3, a, " "); c = a[1]} END {print c}' "$LOG")
if [ "$LAST" -gt 0 ] && [ -n "$LAST_COMMIT" ] && [ "$FORCE" = 0 ] \
   && git cat-file -e "$LAST_COMMIT^{commit}" 2>/dev/null && git diff --quiet "$LAST_COMMIT" HEAD -- ios-native; then
  echo "The iPhone app has not changed since version 2.0.$LAST ($LAST) was uploaded. Nothing to build."
  echo "To build it anyway with the next number:  bash $(basename "$0") --force"
  exit 0
fi
BUILD=$((LAST + 1))
VERSION="2.0.$BUILD"
echo "Last uploaded build: ${LAST}. Building version $VERSION ($BUILD) from commit $HEAD_COMMIT."

echo "== 2/5 Checking tools"
if ! command -v xcodegen >/dev/null; then
  if ! command -v brew >/dev/null; then echo "Homebrew is missing: install it first (brew.sh)."; exit 1; fi
  brew install xcodegen
fi
xcodebuild -version | head -1
MAJOR=$(xcodebuild -version | head -1 | sed -E 's/Xcode ([0-9]+).*/\1/')
if [ "$MAJOR" -lt 26 ]; then echo "Xcode 26 or newer is required (Mac App Store)."; exit 1; fi

echo "== 3/5 Signing team"
TEAM=$(security find-certificate -a -c "Apple Development" -p 2>/dev/null | openssl x509 -noout -subject 2>/dev/null | sed -nE 's/.*OU ?= ?([A-Z0-9]{10}).*/\1/p' | head -1)
if [ -z "$TEAM" ]; then
  echo "Team ID not found. It is on developer.apple.com under Account → Membership (10 characters)."
  read -r -p "Team ID: " TEAM
fi
echo "Team: $TEAM"

echo "== 4/5 Generating the project and archiving (a few minutes)"
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

echo "== 5/5 Uploading to TestFlight"
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

echo "$VERSION ($BUILD) $HEAD_COMMIT" >> "$LOG"
echo ""
echo "Done: version $VERSION ($BUILD) is uploaded. It appears in TestFlight after 5–30 minutes."
