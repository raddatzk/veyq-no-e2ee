import Foundation
import LocalAuthentication
import Security

/// uc.account.device-unlock (iOS) — "remember me" behind Face ID / Touch ID. The login credentials are
/// stored in the Keychain with a biometric access control, so the Secure Enclave gates retrieval; next
/// launch, one biometric prompt yields the credentials and we re-login. No app-side crypto — the Keychain
/// is the secure store.
enum DeviceUnlock {
    struct Creds: Codable { let apiBase, email, password, secretKey: String }

    private static let service = "app.veyq.deviceUnlock"
    private static let account = "credentials"
    private static let flag = "veyq.deviceUnlock.enabled"

    /// Whether the device has biometrics set up (so we can offer "remember").
    static var available: Bool {
        var err: NSError?
        return LAContext().canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &err)
    }

    /// Whether credentials are stored (checked via a flag so we don't trigger biometrics just to know).
    static var isEnabled: Bool { UserDefaults.standard.bool(forKey: flag) }

    /// Seal the credentials into the Keychain behind the current biometric set.
    static func save(_ creds: Creds) {
        guard let data = try? JSONEncoder().encode(creds) else { return }
        let base: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: account]
        SecItemDelete(base as CFDictionary)
        var add = base
        add[kSecValueData as String] = data
        if let access = SecAccessControlCreateWithFlags(nil, kSecAttrAccessibleWhenUnlockedThisDeviceOnly, .biometryCurrentSet, nil) {
            add[kSecAttrAccessControl as String] = access
        }
        if SecItemAdd(add as CFDictionary, nil) == errSecSuccess {
            UserDefaults.standard.set(true, forKey: flag)
        }
    }

    /// Retrieve the credentials (prompts Face ID / Touch ID). Runs off the main thread — the call blocks.
    static func load() async -> Creds? {
        await withCheckedContinuation { cont in
            DispatchQueue.global().async {
                let query: [String: Any] = [
                    kSecClass as String: kSecClassGenericPassword,
                    kSecAttrService as String: service,
                    kSecAttrAccount as String: account,
                    kSecReturnData as String: true,
                    kSecUseOperationPrompt as String: T("login.unlock"),
                ]
                var item: CFTypeRef?
                let status = SecItemCopyMatching(query as CFDictionary, &item)
                guard status == errSecSuccess, let data = item as? Data, let creds = try? JSONDecoder().decode(Creds.self, from: data) else {
                    cont.resume(returning: nil); return
                }
                cont.resume(returning: creds)
            }
        }
    }

    /// Forget the stored credentials on this device.
    static func forget() {
        SecItemDelete([kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: account] as CFDictionary)
        UserDefaults.standard.set(false, forKey: flag)
    }
}
