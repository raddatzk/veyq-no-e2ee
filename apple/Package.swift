// swift-tools-version: 6.0
import PackageDescription

// Greenfield Apple core. The SwiftUI multiplatform app (iOS/iPadOS/macOS) lives in an Xcode
// project that depends on this package; the package holds the shared crypto + slice logic so it
// is testable headlessly via `swift test`.
let package = Package(
    name: "VeyqCrypto",
    platforms: [.macOS("26.0"), .iOS("26.0")], // CryptoKit ML-KEM-768 requires the 26 SDKs
    products: [
        .library(name: "VeyqCrypto", targets: ["VeyqCrypto"])
    ],
    targets: [
        .target(name: "VeyqCrypto"),
        .testTarget(name: "VeyqCryptoTests", dependencies: ["VeyqCrypto"])
    ]
)
