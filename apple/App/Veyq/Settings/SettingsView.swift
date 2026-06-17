import SwiftUI

/// Settings sheet — account, the connected instance, and disconnect. Theme/language are shown to match the
/// reference; full theming + i18n are their own later passes.
struct SettingsView: View {
    @EnvironmentObject var app: AppState
    @Environment(\.dismiss) private var dismiss
    @AppStorage("veyq.theme") private var theme = "system"
    @AppStorage("veyq.locale") private var locale = "de"
    @AppStorage("veyq.privacy.mapTiles") private var mapTiles = false

    var body: some View {
        NavigationStack {
            List {
                Section(T("settings.langAppearance")) {
                    Picker(T("settings.appearance"), selection: $theme) {
                        Text(T("settings.system")).tag("system")
                        Text(T("settings.light")).tag("light")
                        Text(T("settings.dark")).tag("dark")
                    }
                    Picker(T("settings.language"), selection: $locale) {
                        Text("Deutsch").tag("de")
                        Text("English").tag("en")
                    }
                }
                Section {
                    Toggle(T("settings.mapTiles"), isOn: $mapTiles)
                } header: {
                    Text(T("settings.privacy"))
                } footer: {
                    Text(T("settings.mapTilesHint"))
                }
                Section(T("settings.account")) {
                    if let user = app.session?.user {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(user.displayName).foregroundStyle(VeyqStyle.text)
                            Text(user.email).font(.caption).foregroundStyle(VeyqStyle.muted)
                        }
                    }
                }
                if DeviceUnlock.isEnabled {
                    Section(T("settings.deviceUnlock")) {
                        Button(T("settings.forgetDevice"), role: .destructive) { DeviceUnlock.forget() }
                    }
                }
                Section(T("settings.instance")) {
                    Text(app.apiBase).foregroundStyle(VeyqStyle.text)
                }
                Section {
                    Button(T("settings.disconnect"), role: .destructive) { app.signOut(); dismiss() }
                }
            }
            .scrollContentBackground(.hidden)
            .background(VeyqStyle.bg)
            .navigationTitle(T("settings.title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button(T("common.done")) { dismiss() } } }
        }
        .tint(VeyqStyle.accent)
    }
}
