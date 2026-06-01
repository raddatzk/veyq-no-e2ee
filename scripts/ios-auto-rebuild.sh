#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT="$ROOT_DIR/ios/Veyq.xcodeproj"
SCHEME="Veyq"
DERIVED_DATA="$ROOT_DIR/.derivedData"
BUNDLE_ID="com.veyq.ios"
MODE="${1:-build}"

case "$MODE" in
  build)
    DESTINATION="generic/platform=iOS"
    PRODUCT_DIR="Debug-iphoneos"
    ;;
  simulator)
    DESTINATION="generic/platform=iOS Simulator"
    PRODUCT_DIR="Debug-iphonesimulator"
    ;;
  *)
    echo "Usage: $0 [build|simulator]"
    exit 2
    ;;
esac

fingerprint() {
  find "$ROOT_DIR/ios" \
    -type f \
    \( -name "*.swift" -o -name "*.plist" -o -name "*.xcassets" -o -name "project.pbxproj" \) \
    -print0 \
    | xargs -0 stat -f "%m %z %N" \
    | shasum
}

build_app() {
  echo
  echo "[$(date '+%H:%M:%S')] Rebuilding iOS app..."
  "$ROOT_DIR/scripts/ios-preflight.sh"
  xcodebuild \
    -project "$PROJECT" \
    -scheme "$SCHEME" \
    -destination "$DESTINATION" \
    -derivedDataPath "$DERIVED_DATA" \
    CODE_SIGNING_ALLOWED=NO \
    build

  if [[ "$MODE" == "simulator" ]] && xcrun simctl list devices booted | grep -q "(Booted)"; then
    local app_path="$DERIVED_DATA/Build/Products/$PRODUCT_DIR/Veyq.app"
    xcrun simctl install booted "$app_path"
    xcrun simctl launch booted "$BUNDLE_ID" >/dev/null
  fi
}

echo "Watching iOS sources. Press Ctrl-C to stop."
echo "Mode: $MODE"

last_fingerprint=""
while true; do
  current_fingerprint="$(fingerprint)"
  if [[ "$current_fingerprint" != "$last_fingerprint" ]]; then
    last_fingerprint="$current_fingerprint"
    build_app || true
  fi
  sleep 1
done
