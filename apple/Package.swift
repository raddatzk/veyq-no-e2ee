// swift-tools-version: 6.0
import PackageDescription

// veyq Apple client (cr-0015). Shared, platform-agnostic SPM libraries for the universal
// macOS/iOS/iPadOS app — and built for tvOS too (libs-only; the tvOS UI head is a later
// increment, TV sign-in being passkey/QR handoff, see cr-0015 + ADR 0010).
//
// Layers (ADR 0010 — shared contracts, not shared code):
//   VeyqCrypto                          native `v1` suite, vector-gated (cr-0014, ADR 0009).
//   SetupAPI / AuthAPI / AccountAPI     generated from contracts/api/*.openapi.yaml via the
//                                       swift-openapi-generator build plugin — never hand-written.
//   VeyqApi                             thin facade: re-exports the generated clients + a
//                                       URLSession transport.
//   VeyqKit                             session + vault + login/unlock orchestration (phases 2–4).
let package = Package(
    name: "VeyqClient",
    platforms: [.macOS("26.0"), .iOS("26.0"), .tvOS("26.0")],
    products: [
        .library(name: "VeyqCrypto", targets: ["VeyqCrypto"]),
        .library(name: "VeyqApi", targets: ["VeyqApi"]),
        .library(name: "VeyqKit", targets: ["VeyqKit"]),
    ],
    dependencies: [
        .package(url: "https://github.com/apple/swift-crypto.git", from: "3.0.0"),
        .package(url: "https://github.com/jedisct1/swift-sodium.git", from: "0.9.1"),
        .package(url: "https://github.com/apple/swift-openapi-generator.git", from: "1.5.0"),
        .package(url: "https://github.com/apple/swift-openapi-runtime.git", from: "1.5.0"),
        .package(url: "https://github.com/apple/swift-openapi-urlsession.git", from: "1.0.2"),
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

        // Generated clients — one module per contract document (the specs are independent
        // documents with colliding component names, so they cannot share a module).
        .target(
            name: "SetupAPI",
            dependencies: [
                .product(name: "OpenAPIRuntime", package: "swift-openapi-runtime"),
            ],
            plugins: [.plugin(name: "OpenAPIGenerator", package: "swift-openapi-generator")],
        ),
        .target(
            name: "AuthAPI",
            dependencies: [
                .product(name: "OpenAPIRuntime", package: "swift-openapi-runtime"),
            ],
            plugins: [.plugin(name: "OpenAPIGenerator", package: "swift-openapi-generator")],
        ),
        .target(
            name: "AccountAPI",
            dependencies: [
                .product(name: "OpenAPIRuntime", package: "swift-openapi-runtime"),
            ],
            plugins: [.plugin(name: "OpenAPIGenerator", package: "swift-openapi-generator")],
        ),
        .target(
            name: "ScopesAPI",
            dependencies: [
                .product(name: "OpenAPIRuntime", package: "swift-openapi-runtime"),
            ],
            plugins: [.plugin(name: "OpenAPIGenerator", package: "swift-openapi-generator")],
        ),
        .target(
            name: "ContentAPI",
            dependencies: [
                .product(name: "OpenAPIRuntime", package: "swift-openapi-runtime"),
            ],
            plugins: [.plugin(name: "OpenAPIGenerator", package: "swift-openapi-generator")],
        ),

        .target(
            name: "VeyqApi",
            dependencies: [
                "SetupAPI", "AuthAPI", "AccountAPI", "ScopesAPI", "ContentAPI",
                .product(name: "OpenAPIRuntime", package: "swift-openapi-runtime"),
                .product(name: "OpenAPIURLSession", package: "swift-openapi-urlsession"),
            ],
        ),

        .target(name: "VeyqKit", dependencies: ["VeyqCrypto", "VeyqApi"]),
        .testTarget(name: "VeyqKitTests", dependencies: ["VeyqKit"]),
    ],
)
