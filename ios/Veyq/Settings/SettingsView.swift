import SwiftUI

/// Settings sheet — account, the connected instance, and disconnect. Theme/language are shown to match the
/// reference; full theming + i18n are their own later passes.
struct SettingsView: View {
    @EnvironmentObject var app: AppState
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section("Sprache und Darstellung") {
                    settingRow("Darstellung", value: "Dunkel")
                    settingRow("Sprache", value: "Deutsch")
                }
                Section("Account") {
                    if let user = app.session?.user {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(user.displayName).foregroundStyle(VeyqStyle.text)
                            Text(user.email).font(.caption).foregroundStyle(VeyqStyle.muted)
                        }
                    }
                }
                Section("Eingeloggte Instanz") {
                    Text(app.apiBase).foregroundStyle(VeyqStyle.text)
                }
                Section {
                    Button("Trennen", role: .destructive) { app.signOut(); dismiss() }
                }
            }
            .scrollContentBackground(.hidden)
            .background(VeyqStyle.bg)
            .navigationTitle("Einstellungen")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Fertig") { dismiss() } } }
        }
        .tint(VeyqStyle.accent)
    }

    private func settingRow(_ label: String, value: String) -> some View {
        HStack {
            Text(label).foregroundStyle(VeyqStyle.text)
            Spacer()
            Text(value).foregroundStyle(VeyqStyle.muted)
        }
    }
}
