#!/usr/bin/env bash
# CI build for the Apple apps (runs on a macOS runner with Xcode 16+).
#   1. swift test            – TippliCore unit tests (engine-cases.json etc.)
#   2. xcodegen generate     – Tippli.xcodeproj from project.yml
#   3. xcodebuild iOS        – app + keyboard extension for the simulator, unsigned
#   4. xcodebuild macOS      – menu-bar app, unsigned, then ad-hoc signed and zipped
#                              to apple/build/TippliMac.zip
set -euo pipefail

APPLE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD_DIR="$APPLE_DIR/build"
DERIVED="$BUILD_DIR/DerivedData"

section() {
  echo
  echo "=================================================================="
  echo "== $*"
  echo "=================================================================="
}

cd "$APPLE_DIR"
mkdir -p "$BUILD_DIR"

section "Toolchain"
xcodebuild -version
swift --version

section "TippliCore: swift test"
(cd "$APPLE_DIR/TippliCore" && swift test)

section "XcodeGen: generate Tippli.xcodeproj"
if ! command -v xcodegen >/dev/null 2>&1; then
  echo "xcodegen not found – installing via Homebrew"
  brew install xcodegen
fi
xcodegen generate --spec "$APPLE_DIR/project.yml"

XCPRETTY=(cat)
if command -v xcbeautify >/dev/null 2>&1; then
  XCPRETTY=(xcbeautify)
fi

section "iOS: build Tippli + TippliKeyboard (iOS Simulator, no signing)"
xcodebuild \
  -project "$APPLE_DIR/Tippli.xcodeproj" \
  -scheme Tippli \
  -configuration Debug \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath "$DERIVED" \
  CODE_SIGNING_ALLOWED=NO \
  build | "${XCPRETTY[@]}"

section "macOS: build TippliMac (Release, no signing)"
xcodebuild \
  -project "$APPLE_DIR/Tippli.xcodeproj" \
  -scheme TippliMac \
  -configuration Release \
  -destination 'generic/platform=macOS' \
  -derivedDataPath "$DERIVED" \
  CODE_SIGNING_ALLOWED=NO \
  build | "${XCPRETTY[@]}"

APP="$DERIVED/Build/Products/Release/TippliMac.app"
if [ ! -d "$APP" ]; then
  echo "error: $APP not found" >&2
  exit 1
fi

section "macOS: ad-hoc sign and zip"
codesign --force --deep -s - "$APP"
codesign --verify --verbose "$APP"
rm -f "$BUILD_DIR/TippliMac.zip"
ditto -c -k --sequesterRsrc --keepParent "$APP" "$BUILD_DIR/TippliMac.zip"
ls -lh "$BUILD_DIR/TippliMac.zip"

section "Done"
