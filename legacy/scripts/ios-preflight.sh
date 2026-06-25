#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT_FILE="$ROOT_DIR/ios/Veyq.xcodeproj/project.pbxproj"
EXTENSION_PLIST="$ROOT_DIR/ios/VeyqShareExtension/Info.plist"

fail() {
  echo "ios-preflight: $*" >&2
  exit 1
}

plist_value() {
  /usr/libexec/PlistBuddy -c "Print :$2" "$1" 2>/dev/null || true
}

require_plist_key() {
  local file="$1"
  local key="$2"
  local value
  value="$(plist_value "$file" "$key")"
  [[ -n "$value" ]] || fail "$file is missing required key $key"
}

require_plist_value() {
  local file="$1"
  local key="$2"
  local expected="$3"
  local actual
  actual="$(plist_value "$file" "$key")"
  [[ "$actual" == "$expected" ]] || fail "$file key $key should be '$expected' but was '$actual'"
}

command -v plutil >/dev/null || fail "plutil is required"
[[ -x /usr/libexec/PlistBuddy ]] || fail "PlistBuddy is required"
[[ -f "$PROJECT_FILE" ]] || fail "missing Xcode project file"
[[ -f "$EXTENSION_PLIST" ]] || fail "missing Share Extension Info.plist"

plutil -lint "$EXTENSION_PLIST" >/dev/null

require_plist_value "$EXTENSION_PLIST" "CFBundleIdentifier" '$(PRODUCT_BUNDLE_IDENTIFIER)'
require_plist_key "$EXTENSION_PLIST" "CFBundleExecutable"
require_plist_key "$EXTENSION_PLIST" "CFBundleName"
require_plist_value "$EXTENSION_PLIST" "CFBundlePackageType" "XPC!"
require_plist_key "$EXTENSION_PLIST" "CFBundleShortVersionString"
require_plist_key "$EXTENSION_PLIST" "CFBundleVersion"
require_plist_value "$EXTENSION_PLIST" "NSExtension:NSExtensionPointIdentifier" "com.apple.share-services"
require_plist_key "$EXTENSION_PLIST" "NSExtension:NSExtensionPrincipalClass"

if [[ -n "$(plist_value "$EXTENSION_PLIST" "NSExtension:NSExtensionMainStoryboard")" ]]; then
  fail "Share Extension should not define NSExtensionMainStoryboard when using NSExtensionPrincipalClass"
fi

app_bundle_id="$(sed -n 's/.*PRODUCT_BUNDLE_IDENTIFIER = \([^;]*\);.*/\1/p' "$PROJECT_FILE" | grep -m1 '^com\.veyq\.ios$' || true)"
extension_bundle_id="$(sed -n 's/.*PRODUCT_BUNDLE_IDENTIFIER = \([^;]*\);.*/\1/p' "$PROJECT_FILE" | grep -m1 '^com\.veyq\.ios\..*' || true)"

[[ -n "$app_bundle_id" ]] || fail "could not find parent app PRODUCT_BUNDLE_IDENTIFIER"
[[ -n "$extension_bundle_id" ]] || fail "could not find extension PRODUCT_BUNDLE_IDENTIFIER"
[[ "$extension_bundle_id" == "$app_bundle_id".* ]] || fail "extension bundle id '$extension_bundle_id' must be prefixed with parent '$app_bundle_id'"

if rg -n "ASWebAuthenticationSession\\(url:.*callbackURLScheme|ASPresentationAnchor\\(\\)" "$ROOT_DIR/ios/Veyq" >/tmp/veyq-ios-preflight-rg.txt; then
  cat /tmp/veyq-ios-preflight-rg.txt >&2
  fail "deprecated authentication initializer pattern found"
fi

echo "ios-preflight: OK"
