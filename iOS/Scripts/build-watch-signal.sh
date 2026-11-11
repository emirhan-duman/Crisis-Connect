#!/bin/bash
# Rebuild only the extra watch slices. Existing iOS slices stay byte-for-byte unchanged.
# Requires rustup targets aarch64-apple-watchos and aarch64-apple-watchos-sim, cmake, protoc, Xcode.
set -euo pipefail
cd "$(dirname "$0")/.."
work="$PWD/build/watch-signal"
source="$work/source"
expected="b39e93f1a5e6531044dfcdf5876585cbcf08f884"
if [ ! -d "$source/.git" ]; then
  mkdir -p "$work"
  git clone --branch v0.86.5 --depth 1 https://github.com/signalapp/libsignal.git "$source"
fi
actual="$(git -C "$source" rev-parse HEAD)"
[ "$actual" = "$expected" ] || { echo "Unexpected libsignal source revision" >&2; exit 1; }
for target in aarch64-apple-watchos aarch64-apple-watchos-sim; do
  (cd "$source"; WATCHOS_DEPLOYMENT_TARGET=10.0 rustup run stable cargo build --locked --release -p libsignal-ffi --features libsignal-bridge-testing --target "$target" -j 2)
  mkdir -p "$work/libraries/$target"
  cp "$source/target/$target/release/libsignal_ffi.a" "$work/libraries/$target/libSignalFfi.a"
done
output="$work/SignalFfi-$(date +%s).xcframework"
existing="$PWD/Packages/LibSignalClient/SignalFfi.xcframework"
xcodebuild -create-xcframework \
 -library "$existing/ios-arm64/libSignalFfi.a" -headers "$existing/ios-arm64/Headers" \
 -library "$existing/ios-arm64_x86_64-simulator/libSignalFfi.a" -headers "$existing/ios-arm64_x86_64-simulator/Headers" \
 -library "$work/libraries/aarch64-apple-watchos/libSignalFfi.a" -headers "$existing/ios-arm64/Headers" \
 -library "$work/libraries/aarch64-apple-watchos-sim/libSignalFfi.a" -headers "$existing/ios-arm64/Headers" \
 -output "$output"
echo "Prepared $output. Review watch platform slices before copying into the package."
