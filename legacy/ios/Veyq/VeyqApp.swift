import SwiftUI

@main
struct VeyqApp: App {
    @StateObject private var store: VeyqStore

    init() {
        let uiTesting = CommandLine.arguments.contains("--ui-testing")
        if uiTesting {
            UserDefaults.standard.removeObject(forKey: "veyq.instance.url")
            UserDefaults.standard.removeObject(forKey: AppThemePreference.storageKey)
            UserDefaults.standard.set(AppLanguagePreference.en.rawValue, forKey: AppLanguagePreference.storageKey)
        }
        _store = StateObject(wrappedValue: VeyqStore(mockMode: uiTesting))
    }

    var body: some Scene {
        WindowGroup {
            AppRootView()
                .environmentObject(store)
        }
    }
}
