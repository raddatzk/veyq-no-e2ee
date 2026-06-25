// swift-tools-version: 6.0
import PackageDescription

// Greenfield Apple core. The SwiftUI multiplatform app (iOS/iPadOS/macOS) lives in an Xcode
// project that depends on this package; the package holds the shared crypto + slice logic so it
// is testable headlessly via `swift test`.
let package = Package(
    name: "VeyqCrypto",
    platforms: [.macOS("26.0"), .iOS("26.0")], // CryptoKit ML-KEM-768 requires the 26 SDKs
    products: [
        .library(name: "VeyqCrypto", targets: ["VeyqCrypto"]),
        // Spike: API DTOs generated from docs/api/*.openapi.yaml (scripts/gen_mobile_models.py).
        .library(name: "VeyqApiModels", targets: ["VeyqApiModels"])
    ],
    dependencies: [
        // Argon2id is not in CryptoKit; libsodium (ARM-native) provides it via crypto_pwhash.
        .package(url: "https://github.com/jedisct1/swift-sodium", from: "0.9.1"),
        // y-crdt / YSwift — Yrs (Rust) Swift bindings: the SAME Yjs-compatible CRDT as the web, so iOS
        // syncs the live journal with web clients over the operator-blind relay (wire-compatible).
        .package(url: "https://github.com/y-crdt/yswift", from: "0.2.0")
    ],
    targets: [
        .target(
            name: "VeyqCrypto",
            dependencies: [.product(name: "Sodium", package: "swift-sodium"), .product(name: "YSwift", package: "yswift")]
        ),
        .testTarget(name: "VeyqCryptoTests", dependencies: ["VeyqCrypto"]),
        // Spec-generated, pure-Codable model structs (Foundation only). The hand-written transport +
        // crypto layer in VeyqCrypto can adopt these instead of re-declaring the wire shape by hand.
        .target(name: "VeyqApiModels")
    ]
)
