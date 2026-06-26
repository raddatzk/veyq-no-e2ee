import Foundation

// Emergency Kit import — the Apple port of web `emergencyKit.ts`'s parse path. The kit is machine-readable:
// `kitPayload` (web/setup) writes `veyqkit:<base64url(JSON{v:1,email,sk})>` as a QR **and** uncompressed in
// the kit PDF's bytes. Recovering it is purely on-device (operator-blind): the email + Secret Key fill local
// fields, nothing is sent. The kit deliberately carries **no password** (that stays in the user's head).

/// The data an Emergency Kit carries: the login email + the recovery Secret Key (never the password).
public struct KitData: Equatable, Sendable {
    public let email: String
    public let secretKey: String

    public init(email: String, secretKey: String) {
        self.email = email
        self.secretKey = secretKey
    }
}

/// Parse a kit token (`veyqkit:<base64url(JSON{v:1,email,sk})>`) out of any text — a scanned QR string or
/// the kit PDF read as text. Mirrors web `parseKit`: find the token, base64url-decode, validate `v == 1`
/// and non-empty email + sk. Returns nil if no valid kit token is present.
public func parseKit(_ text: String) -> KitData? {
    guard let marker = text.range(of: "veyqkit:") else { return nil }
    // The token is the run of base64url characters immediately after the prefix.
    let token = String(text[marker.upperBound...].prefix { c in
        c.isASCII && (c.isLetter || c.isNumber || c == "_" || c == "-")
    })
    guard !token.isEmpty, let json = decodeBase64url(token) else { return nil }
    guard let obj = try? JSONSerialization.jsonObject(with: json) as? [String: Any],
          let v = obj["v"] as? Int, v == 1,
          let email = obj["email"] as? String, !email.isEmpty,
          let sk = obj["sk"] as? String, !sk.isEmpty
    else { return nil }
    return KitData(email: email, secretKey: sk)
}

/// Recover a kit from a file's raw bytes read as UTF-8 text (the kit PDF embeds the token uncompressed, or
/// any token-carrying text). No PDF library — exactly like web `importKitFromFile`'s text path. Returns nil
/// if no kit token is found.
public func importKitFromData(_ data: Data) -> KitData? {
    parseKit(String(decoding: data, as: UTF8.self))
}

/// base64url → Data, restoring the `=` padding web's `b64url` strips (Foundation requires it).
private func decodeBase64url(_ s: String) -> Data? {
    var b = s.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
    let remainder = b.count % 4
    if remainder != 0 { b += String(repeating: "=", count: 4 - remainder) }
    return Data(base64Encoded: b)
}
