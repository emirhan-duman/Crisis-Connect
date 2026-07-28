// swift-tools-version: 6.0

// Vendored LibSignalClient (signalapp/libsignal v0.86.5, AGPL-3.0-only).
// Swift sources are copied verbatim from swift/Sources/LibSignalClient; SignalFfi.xcframework is
// the Rust core built locally for aarch64-apple-ios + aarch64-apple-ios-sim with
// `cargo build --release -p libsignal-ffi --features libsignal-bridge-testing` (the testing
// feature bakes in the signal_ffi_testing.h symbols that LibSignalClient's fake-connection test
// helpers reference, so one static library links everything).
// To upgrade: bump the tag, rebuild both slices, re-run xcodebuild -create-xcframework, re-copy
// the Swift sources. Keep the version aligned with Android's org.signal:libsignal-android.

import PackageDescription

let package = Package(
    name: "LibSignalClient",
    platforms: [
        .iOS(.v15), .macOS(.v10_15),
    ],
    products: [
        .library(name: "LibSignalClient", targets: ["LibSignalClient"])
    ],
    targets: [
        .binaryTarget(name: "SignalFfi", path: "SignalFfi.xcframework"),
        .target(
            name: "LibSignalClient",
            dependencies: ["SignalFfi"],
            // Vendored code stays in Swift 5 language mode: under the Swift 6 mode that a
            // tools-6.0 package defaults to, newer compilers escalate upstream sendability
            // diagnostics (e.g. TokioAsyncContext's FFI closures) into hard errors we don't
            // want to patch in third-party sources.
            swiftSettings: [
                .swiftLanguageMode(.v5),
                .enableExperimentalFeature("StrictConcurrency"),
            ],
            linkerSettings: [
                .linkedLibrary("z")
            ]
        ),
    ]
)
