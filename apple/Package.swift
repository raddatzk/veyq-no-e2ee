// swift-tools-version: 6.0
import PackageDescription

// veyq Apple client. VeyqCrypto is the native `v1` suite (auth/unlock subset for now, cr-0014) — verified
// against the shared conformance vectors (contracts/crypto/vectors/v1.json, ADR 0009). swift-crypto provides
// Ed25519 / HKDF / AES-GCM / SHA-256; swift-sodium provides Argon2id (libsodium, as in legacy/apple).
// ML-KEM-768 and the content-side primitives arrive with the first scope/content slice.
let package = Package(
    name: "VeyqCrypto",
    platforms: [.macOS(.v13), .iOS(.v16)],
    products: [
        .library(name: "VeyqCrypto", targets: ["VeyqCrypto"]),
    ],
    dependencies: [
        .package(url: "https://github.com/apple/swift-crypto.git", from: "3.0.0"),
        .package(url: "https://github.com/jedisct1/swift-sodium.git", from: "0.9.1"),
    ],
    targets: [
        .target(
            name: "VeyqCrypto",
            dependencies: [
                .product(name: "Crypto", package: "swift-crypto"),
                .product(name: "Sodium", package: "swift-sodium"),
            ],
        ),
        .testTarget(name: "VeyqCryptoTests", dependencies: ["VeyqCrypto"]),
    ],
)
