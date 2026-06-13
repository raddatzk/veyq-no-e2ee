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
    dependencies: [
        // Argon2id is not in CryptoKit; libsodium (ARM-native) provides it via crypto_pwhash.
        .package(url: "https://github.com/jedisct1/swift-sodium", from: "0.9.1")
    ],
    targets: [
        .target(
            name: "VeyqCrypto",
            dependencies: [.product(name: "Sodium", package: "swift-sodium")]
        ),
        .testTarget(name: "VeyqCryptoTests", dependencies: ["VeyqCrypto"])
    ]
)
