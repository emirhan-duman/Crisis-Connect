#!/usr/bin/env bash
#
# patch-firebase-async-let.sh
#
# Workaround for Swift 6.1 async let teardown ordering bug that crashes
# release builds calling Firebase Cloud Functions on iOS 26 / Xcode 26.
#
# Root cause: Firebase's FunctionsContext.swift declares three `async let`
# bindings whose SIL/LLVM lowering hits a compiler bug where
# swift_task_dealloc gets reordered across suspension boundaries, causing
# `freed pointer was not the last allocation` in
# `asyncLet_finish_after_task_completion`.
#
# References:
#   https://github.com/firebase/firebase-ios-sdk/issues/15994
#   https://github.com/swiftlang/swift/issues/81771
#   https://forums.swift.org/t/fix-for-async-let-teardown-ordering-crash/85049
#
# This script replaces the `async let` block with sequential `await`s,
# which compile to a different code path that doesn't trigger the bug.
#
# Runs as an Xcode "Run Script" Build Phase BEFORE "Compile Sources".
# Idempotent — safe to run on every build.
#
# REMOVE THIS SCRIPT when either:
#   1. Firebase iOS SDK releases a fix for issue #15994, OR
#   2. Xcode / Swift releases a toolchain with PR #87571 merged
#

set -eu

echo "==> Checking Firebase FunctionsContext.swift for async let bug workaround"

# The Swift Package Manager checkouts directory lives next to the Build
# directory inside DerivedData. Both regular Xcode builds and Xcode Cloud
# use this layout.
BUILD_ROOT_VAL="${BUILD_ROOT:-}"
DERIVED_FILE_DIR_VAL="${DERIVED_FILE_DIR:-}"
SRCROOT_VAL="${SRCROOT:-}"

TARGET_FILE=""

# Try primary path if BUILD_ROOT is set
if [ -n "$BUILD_ROOT_VAL" ]; then
    SPM_CHECKOUTS="${BUILD_ROOT_VAL%/Build/*}/SourcePackages/checkouts"
    TARGET_FILE="${SPM_CHECKOUTS}/firebase-ios-sdk/FirebaseFunctions/Sources/Internal/FunctionsContext.swift"
fi

# Fallback: try locating relative to DERIVED_FILE_DIR
if [ -z "$TARGET_FILE" ] || [ ! -f "$TARGET_FILE" ]; then
    if [ -n "$DERIVED_FILE_DIR_VAL" ]; then
        TARGET_FILE=$(/usr/bin/find "${DERIVED_FILE_DIR_VAL%/Build/*}/SourcePackages/checkouts/firebase-ios-sdk/FirebaseFunctions/Sources/Internal" -name "FunctionsContext.swift" 2>/dev/null | head -n 1 || true)
    fi
fi

# Last resort: search in likely roots and standard DerivedData folder
if [ -z "$TARGET_FILE" ] || [ ! -f "$TARGET_FILE" ]; then
    for root in "${BUILD_ROOT_VAL%/Build/*}" "${DERIVED_FILE_DIR_VAL%/Build/*}" "${SRCROOT_VAL}/.." "$HOME/Library/Developer/Xcode/DerivedData"; do
        if [ -z "$root" ] || [ "$root" = "/.." ]; then
            continue
        fi
        candidate=$(/usr/bin/find "$root" -maxdepth 8 -type f -name "FunctionsContext.swift" -path "*firebase-ios-sdk*" 2>/dev/null | head -n 1 || true)
        if [ -n "$candidate" ]; then
            TARGET_FILE="$candidate"
            break
        fi
    done
fi

if [ -z "$TARGET_FILE" ] || [ ! -f "$TARGET_FILE" ]; then
    echo "warning: FunctionsContext.swift not found — Firebase may not be resolved yet. Skipping patch."
    exit 0
fi

echo "==> Target: $TARGET_FILE"

# Idempotency: check for our marker
if /usr/bin/grep -q "Workaround for Swift 6.1 async let teardown" "$TARGET_FILE"; then
    echo "==> Already patched, skipping"
    exit 0
fi

# Sanity check: confirm the original async let block is still there
if ! /usr/bin/grep -q "async let authToken = auth?.getToken" "$TARGET_FILE"; then
    echo "warning: Expected async let pattern not found. Firebase SDK may have changed — please review manually."
    exit 0
fi

# SPM checkouts are often read-only — make writable first
/bin/chmod u+w "$TARGET_FILE" 2>/dev/null || true

# Apply patch via Python for reliable multiline replacement
/usr/bin/env python3 - "$TARGET_FILE" <<'PY'
import sys

path = sys.argv[1]
with open(path, 'r') as f:
    content = f.read()

old = """  func context(options: HTTPSCallableOptions?) async throws -> FunctionsContext {
    async let authToken = auth?.getToken(forcingRefresh: false)
    async let appCheckToken = getAppCheckToken(options: options)
    async let limitedUseAppCheckToken = getLimitedUseAppCheckToken(options: options)

    // Only `authToken` is throwing, but the formatter script removes the `try`
    // from `try authToken` and puts it in front of the initializer call.
    return try await FunctionsContext(
      authToken: authToken,
      fcmToken: messaging?.fcmToken,
      appCheckToken: appCheckToken,
      limitedUseAppCheckToken: limitedUseAppCheckToken
    )
  }"""

new = """  func context(options: HTTPSCallableOptions?) async throws -> FunctionsContext {
    // Workaround for Swift 6.1 async let teardown ordering bug (firebase-ios-sdk#15994):
    // Using sequential `await` instead of `async let` to avoid
    // `asyncLet_finish_after_task_completion` crash triggered by the LLVM
    // SimplifyCFG + ArgMemOnly annotation issue. Applied by
    // Scripts/patch-firebase-async-let.sh on every build.
    let authToken = try await auth?.getToken(forcingRefresh: false)
    let appCheckToken = await getAppCheckToken(options: options)
    let limitedUseAppCheckToken = await getLimitedUseAppCheckToken(options: options)

    return FunctionsContext(
      authToken: authToken,
      fcmToken: messaging?.fcmToken,
      appCheckToken: appCheckToken,
      limitedUseAppCheckToken: limitedUseAppCheckToken
    )
  }"""

if old not in content:
    sys.stderr.write("error: exact async let block not matched — Firebase SDK layout changed. Please review patch script.\n")
    sys.exit(1)

with open(path, 'w') as f:
    f.write(content.replace(old, new))

print("==> Patch applied successfully")
PY

echo "==> Done"
