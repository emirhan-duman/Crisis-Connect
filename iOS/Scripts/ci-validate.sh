#!/bin/bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IOS_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
PROJECT="$IOS_ROOT/Crisis Connect.xcodeproj"
SCHEME="$PROJECT/xcshareddata/xcschemes/Crisis Connect.xcscheme"
TEST_PLAN="$IOS_ROOT/Crisis Connect.xctestplan"
CACHE_ROOT="${TMPDIR:-/tmp}/crisis-connect-ios-ci"

mkdir -p "$CACHE_ROOT/clang-module-cache" "$CACHE_ROOT/swiftpm-module-cache"
export CLANG_MODULE_CACHE_PATH="$CACHE_ROOT/clang-module-cache"
export SWIFTPM_MODULECACHE_OVERRIDE="$CACHE_ROOT/swiftpm-module-cache"

plutil -lint "$PROJECT/project.pbxproj"
xmllint --noout "$SCHEME"
python3 -m json.tool "$TEST_PLAN" >/dev/null

swift package --package-path "$IOS_ROOT/Packages/LibSignalClient" dump-package >/dev/null
swift package --package-path "$IOS_ROOT/Packages/LiteRT-LM" dump-package >/dev/null

while IFS= read -r -d '' source_file; do
    xcrun swiftc -frontend -parse "$source_file"
done < <(
    find \
        "$IOS_ROOT/Crisis Connect" \
        "$IOS_ROOT/Crisis ConnectTests" \
        "$IOS_ROOT/Crisis ConnectUITests" \
        "$IOS_ROOT/BroadcastExtension" \
        "$IOS_ROOT/WidgetExtension" \
        -type f -name '*.swift' -print0
)

if grep -q 'FIRAAppCheckDebugToken' "$SCHEME"; then
    echo "App Check debug tokens must not be committed in the shared Xcode scheme." >&2
    exit 1
fi

echo "iOS project metadata, package manifests, and Swift syntax are valid."
