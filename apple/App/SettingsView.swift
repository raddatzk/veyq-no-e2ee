import SwiftUI

/// Settings (cr-0035) — the native parity of the web ProfileMenu: appearance (theme) + language + account +
/// sign-out, reached from the shell's profile bar. Reuses the first iOS settings layout
/// (`legacy/iOS/Veyq/Settings/SettingsView.swift`). Theme persists via `@AppStorage`; language via the shared
/// `L10n` (both apply live).
struct SettingsView: View {
    @EnvironmentObject var app: AppModel
    @EnvironmentObject var l10n: L10n
    @Environment(\.dismiss) private var dismiss
    @AppStorage("veyq.theme") private var theme = "system"

    var body: some View {
        NavigationStack {
            List {
                Section(l10n.t("settings.langAppearance")) {
                    Picker(l10n.t("settings.appearance"), selection: $theme) {
                        Text(l10n.t("settings.system")).tag("system")
                        Text(l10n.t("settings.light")).tag("light")
                        Text(l10n.t("settings.dark")).tag("dark")
                    }
                    Picker(l10n.t("settings.language"), selection: $l10n.locale) {
                        Text("English").tag("en")
                        Text("Deutsch").tag("de")
                    }
                }
                Section(l10n.t("settings.account")) {
                    Text(app.email).foregroundStyle(VeyqStyle.text).textSelection(.enabled)
                }
                Section(l10n.t("settings.instance")) {
                    Text(app.apiBase).font(.footnote).foregroundStyle(VeyqStyle.muted).textSelection(.enabled)
                }
                Section {
                    Button(l10n.t("home.signOut"), role: .destructive) {
                        dismiss()
                        Task { await app.signOut() }
                    }
                    .accessibilityIdentifier("Sign out")
                }
            }
            .scrollContentBackground(.hidden)
            .background(VeyqStyle.bg)
            .navigationTitle(l10n.t("settings.title"))
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(l10n.t("common.done")) { dismiss() }
                }
            }
        }
        .tint(VeyqStyle.accent)
    }
}
