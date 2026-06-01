import SwiftUI

struct SettingsView: View {
    let onDisconnect: () -> Void
    @EnvironmentObject private var store: VeyqStore
    @Environment(\.dismiss) private var dismiss
    @AppStorage(AppThemePreference.storageKey) private var themePreferenceRawValue = AppThemePreference.system.rawValue
    @AppStorage(AppLanguagePreference.storageKey) private var languagePreferenceRawValue = AppLanguagePreference.system.rawValue

    private var themePreference: Binding<AppThemePreference> {
        Binding {
            AppThemePreference.resolve(themePreferenceRawValue)
        } set: { preference in
            themePreferenceRawValue = preference.rawValue
        }
    }

    private var languagePreference: Binding<AppLanguagePreference> {
        Binding {
            AppLanguagePreference.resolve(languagePreferenceRawValue)
        } set: { preference in
            languagePreferenceRawValue = preference.rawValue
        }
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Picker(L10n.string("Appearance"), selection: themePreference) {
                        ForEach(AppThemePreference.allCases) { preference in
                            Text(preference.localizedTitle)
                                .tag(preference)
                        }
                    }
                    .accessibilityIdentifier("settings-theme-picker")
                    .pickerStyle(.inline)
                } header: {
                    Text(L10n.string("Appearance"))
                }

                Section {
                    Picker(L10n.string("Language"), selection: languagePreference) {
                        ForEach(AppLanguagePreference.allCases) { preference in
                            Text(preference.localizedTitle)
                                .tag(preference)
                        }
                    }
                    .accessibilityIdentifier("settings-language-picker")
                    .pickerStyle(.inline)
                } header: {
                    Text(L10n.string("Language"))
                }

                Section {
                    if let connectedInstanceURL = store.connectedInstanceURL {
                        SettingsSummaryRow(
                            symbolName: "server.rack",
                            title: L10n.string("Instance"),
                            value: connectedInstanceURL.host ?? connectedInstanceURL.absoluteString
                        )
                    }

                    Button(role: .destructive) {
                        onDisconnect()
                        dismiss()
                    } label: {
                        Label(L10n.string("Disconnect"), systemImage: "rectangle.portrait.and.arrow.right")
                    }
                    .accessibilityIdentifier("settings-disconnect-button")
                } header: {
                    Text(L10n.string("Instance"))
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle(L10n.string("Settings"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(L10n.string("Done")) {
                        dismiss()
                    }
                }
            }
            .accessibilityIdentifier("settings-view")
        }
    }
}

private struct SettingsSummaryRow: View {
    let symbolName: String
    let title: String
    let value: String

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: symbolName)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(VeyqColor.accent)
                .frame(width: 30, height: 30)
                .background(VeyqColor.accent.opacity(0.12), in: Circle())

            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.subheadline.weight(.semibold))
                Text(value)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
    }
}
