# veyq v1 crypto — reference implementation

A small Node reference implementation of the **v1 crypto suite**
([crypto-spec](../../docs/wiki/crosscutting/crypto-spec.md)). Its only job is to **generate the shared
conformance test vectors** and prove the spec is implementable. It is **not** production code.

## Regenerate the vectors
```bash
cd tools/crypto-ref
npm install
npm run generate          # self-tests, then writes ../../docs/api/crypto-vectors/v1.json
```

## What the clients do with it
The web (TS), Apple (Swift/CryptoKit) and Android (Kotlin/BouncyCastle) crypto layers each load
`docs/api/crypto-vectors/v1.json` and assert their implementation reproduces every vector byte-for-byte
(plus internal encap→decap / wrap→unwrap round-trips for the randomized KEM). A divergence is a **spec
bug**, not a client bug.

Notes: bytes are lowercase hex. Argon2 vectors use **light params (m = 8 MiB)** for fast conformance;
production uses **64 MiB**. The KEM is conformance-tested via a deterministic **Decap KAT** (ML-KEM/X25519
encapsulation is randomized in practice). HKDF is cross-checked against the **RFC 5869** vector.
