#!/usr/bin/env bash
# Regenerate the native Yrs (y-crdt) artifacts for the live journal: the Android .so per ABI, a host dylib
# for JVM tests, and the uniffi Kotlin binding. Run from anywhere; needs Rust + the Android NDK.
#
# Committed outputs (so app/test builds need NO Rust toolchain):
#   android/app/src/main/jniLibs/{arm64-v8a,x86_64}/libuniffi_yniffi.so   (Android runtime)
#   android/core/native/libuniffi_yniffi.dylib                            (JVM test runtime, macOS)
#   android/core/src/main/kotlin/uniffi/yniffi/yniffi.kt                  (generated binding)
#
# The crate is yswift's `lib/` (yrs 0.18.2 + uniffi 0.27.1) — the SAME version the iOS xcframework uses, so
# the wire format is identical across web (yjs) / iOS (YSwift) / Android. Pin yswift in apple/Package.swift.
set -euo pipefail

ANDROID_DIR="$(cd "$(dirname "$0")/.." && pwd)"
REPO="$(cd "$ANDROID_DIR/.." && pwd)"
SRC="$REPO/apple/.build/checkouts/yswift/lib"   # resolved by `swift package resolve` in apple/
WORK="$ANDROID_DIR/yrs-android"
: "${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to your NDK (e.g. \$ANDROID_HOME/ndk/29.x)}"

command -v cargo >/dev/null || { echo "install Rust (https://rustup.rs)"; exit 1; }
[ -d "$SRC" ] || { echo "run 'swift package resolve' in apple/ first (fetches yswift)"; exit 1; }

rustup target add aarch64-linux-android x86_64-linux-android armv7-linux-androideabi >/dev/null
cargo install cargo-ndk >/dev/null 2>&1 || true

rm -rf "$WORK"; mkdir -p "$WORK"; cp -R "$SRC/"* "$WORK/"
cd "$WORK"
# cdylib so cargo-ndk emits a loadable .so (iOS uses the staticlib slice)
sed -i '' 's/crate-type = \["staticlib"\]/crate-type = ["staticlib", "cdylib"]/' Cargo.toml

cargo ndk -t arm64-v8a -t x86_64 -o "$ANDROID_DIR/app/src/main/jniLibs" build --release
cargo build --release   # host dylib for JVM tests
mkdir -p "$ANDROID_DIR/core/native"
cp target/release/libuniffi_yniffi.dylib "$ANDROID_DIR/core/native/"
cargo run --features uniffi/cli --bin uniffi-bindgen -- generate src/yniffi.udl --language kotlin \
  --out-dir "$ANDROID_DIR/core/src/main/kotlin"

echo "done — committed binaries + binding refreshed."
