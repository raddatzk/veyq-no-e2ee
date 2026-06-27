import Foundation

/// The last instance + account this device used at a **passwordless** level (L2/L3) — so the app can
/// auto-restore on launch (it must know *which* account's level to resume; the `DeviceSecurityStore` is
/// keyed by email). Neither value is a secret (a URL + an email), so plain `UserDefaults` is fine; the
/// actual secrets (the key K, the refresh token) live in the Keychain. Set when a passwordless level is
/// chosen, cleared when the device drops to L0/L1.
enum LaunchPrefs {
    private static let kInstance = "veyq.lastInstance"
    private static let kAccount = "veyq.lastAccount"

    static var instance: String? { UserDefaults.standard.string(forKey: kInstance) }
    static var account: String? { UserDefaults.standard.string(forKey: kAccount) }

    static func remember(instance: String, account: String) {
        UserDefaults.standard.set(instance, forKey: kInstance)
        UserDefaults.standard.set(account, forKey: kAccount)
    }

    static func clear() {
        UserDefaults.standard.removeObject(forKey: kInstance)
        UserDefaults.standard.removeObject(forKey: kAccount)
    }
}
