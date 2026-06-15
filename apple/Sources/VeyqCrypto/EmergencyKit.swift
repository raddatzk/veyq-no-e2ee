import Foundation

/// The Emergency Kit — the portable token carrying everything needed to sign in again EXCEPT the
/// password (which stays in the user's head). It is generated on the web after registration; on
/// mobile we only ever *consume* it (scan a QR / import the kit file) to pre-fill the login form.
///
/// Token format is identical across all platforms so a kit produced on the web parses here:
///   veyqkit:<base64url(JSON {v:1, instance, email, sk})>
public enum EmergencyKit {
    public struct KitData: Equatable {
        public let instance: String
        public let email: String
        public let secretKey: String
        public init(instance: String, email: String, secretKey: String) {
            self.instance = instance
            self.email = email
            self.secretKey = secretKey
        }
    }

    static let prefix = "veyqkit:"

    static func b64url(_ d: Data) -> String { SetupServer.b64url(d) }
    static func b64urlDecode(_ s: String) -> Data { Login.b64urlDecode(s) }

    /// The compact, machine-readable token (what a QR encodes / a kit file carries).
    public static func payload(_ data: KitData) -> String {
        let obj: [String: Any] = ["v": 1, "instance": data.instance, "email": data.email, "sk": data.secretKey]
        let json = try! JSONSerialization.data(withJSONObject: obj)
        return prefix + b64url(json)
    }

    /// Extract a kit out of any text — a scanned QR string, a `.veyqkit` file, or the bytes of the kit PDF.
    public static func parse(_ text: String) -> KitData? {
        guard let range = text.range(of: prefix + "[A-Za-z0-9_-]+", options: .regularExpression) else { return nil }
        let token = String(text[range]).dropFirst(prefix.count)
        guard let obj = try? JSONSerialization.jsonObject(with: b64urlDecode(String(token))) as? [String: Any],
              (obj["v"] as? Int) == 1,
              let email = obj["email"] as? String, !email.isEmpty,
              let sk = obj["sk"] as? String, !sk.isEmpty
        else { return nil }
        return KitData(instance: (obj["instance"] as? String) ?? "", email: email, secretKey: sk)
    }
}
