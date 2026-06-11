import SwiftUI

struct SettingsView: View {
    @EnvironmentObject private var store: VeyqStore
    @Environment(\.dismiss) private var dismiss
    @AppStorage(AppThemePreference.storageKey) private var themePreferenceRawValue = AppThemePreference.system.rawValue
    @AppStorage(AppLanguagePreference.storageKey) private var languagePreferenceRawValue = AppLanguagePreference.system.rawValue

    var body: some View {
        NavigationStack {
            List {
                Section {
                    CompactPreferenceMenu(
                        title: L10n.string("Appearance"),
                        selectedTitle: AppThemePreference.resolve(themePreferenceRawValue).localizedTitle,
                        symbolName: "circle.lefthalf.filled",
                        accessibilityIdentifier: "settings-theme-picker"
                    ) {
                        ForEach(AppThemePreference.allCases) { preference in
                            Button {
                                themePreferenceRawValue = preference.rawValue
                            } label: {
                                PreferenceMenuItem(
                                    title: preference.localizedTitle,
                                    selected: preference == AppThemePreference.resolve(themePreferenceRawValue)
                                )
                            }
                        }
                    }

                    CompactPreferenceMenu(
                        title: L10n.string("Language"),
                        selectedTitle: AppLanguagePreference.resolve(languagePreferenceRawValue).localizedTitle,
                        symbolName: "globe",
                        accessibilityIdentifier: "settings-language-picker"
                    ) {
                        ForEach(AppLanguagePreference.allCases) { preference in
                            Button {
                                languagePreferenceRawValue = preference.rawValue
                            } label: {
                                PreferenceMenuItem(
                                    title: preference.localizedTitle,
                                    selected: preference == AppLanguagePreference.resolve(languagePreferenceRawValue)
                                )
                            }
                        }
                    }
                } header: {
                    Text(L10n.string("Language and appearance"))
                }

                Section {
                    if let user = store.user {
                        NavigationLink {
                            AccountSettingsView()
                                .environmentObject(store)
                        } label: {
                            SettingsSummaryRow(
                                symbolName: "person.crop.circle",
                                title: user.displayName,
                                value: user.email
                            )
                        }
                        .accessibilityIdentifier("settings-account-link")
                    }
                } header: {
                    Text(L10n.string("Account"))
                }

                if !store.instances.isEmpty {
                    Section {
                        ForEach(store.instances, id: \.instanceURL) { session in
                            SettingsSummaryRow(
                                symbolName: store.connectedInstanceURL == session.instanceURL ? "checkmark.circle.fill" : "server.rack",
                                title: instanceTitle(session),
                                value: session.user.displayName
                            )
                            .accessibilityIdentifier("signed-in-instance-\(session.instanceURL.absoluteString)")
                        }
                    } header: {
                        Text(L10n.string("Signed-in Instances"))
                    } footer: {
                        Text(L10n.string("Tenants are selected as workspace context; instances are shown here as connected sessions."))
                    }
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

    private func instanceTitle(_ session: StoredNativeSession) -> String {
        session.instanceURL.host ?? session.instanceURL.absoluteString
    }
}

private struct AccountSettingsView: View {
    @EnvironmentObject private var store: VeyqStore
    @State private var displayName = ""
    @State private var permalink = ""
    @State private var email = ""
    @State private var saving = false
    @State private var message: String?
    @State private var errorMessage: String?

    private var hasChanges: Bool {
        guard let user = store.user else { return false }
        return displayName != user.displayName ||
            permalink != user.permalink ||
            email != user.email
    }

    private var canSave: Bool {
        !saving &&
            hasChanges &&
            !displayName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
            !permalink.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
            !email.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    var body: some View {
        List {
            if let user = store.user {
                Section {
                    HStack(spacing: 14) {
                        ProfileAvatar(user: user)
                        VStack(alignment: .leading, spacing: 4) {
                            Text(user.displayName)
                                .font(.headline)
                            Text("~\(user.permalink)")
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                        }
                    }
                    .padding(.vertical, 4)
                }
            }

            Section {
                TextField(L10n.string("Display Name"), text: $displayName)
                    .textContentType(.name)
                    .accessibilityIdentifier("account-display-name-field")
                TextField(L10n.string("Permalink"), text: $permalink)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .accessibilityIdentifier("account-permalink-field")
                TextField(L10n.string("Email"), text: $email)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .keyboardType(.emailAddress)
                    .textContentType(.emailAddress)
                    .accessibilityIdentifier("account-email-field")
            } header: {
                Text(L10n.string("Profile"))
            } footer: {
                if let pendingEmail = store.user?.pendingEmail, pendingEmail != store.user?.email {
                    Text(L10n.format("Confirm %@ from the email we sent before it becomes your login address.", pendingEmail))
                }
            }

            Section {
                SettingsSummaryRow(
                    symbolName: store.user?.hasPassword == true ? "key.fill" : "key.slash",
                    title: L10n.string("Password"),
                    value: store.user?.hasPassword == true ? L10n.string("Enabled") : L10n.string("Not set")
                )
                SettingsSummaryRow(
                    symbolName: "person.badge.key",
                    title: L10n.string("Passkeys"),
                    value: L10n.format("%d configured", store.user?.passkeyCount ?? 0)
                )
                if store.user?.totpEnabled == true {
                    SettingsSummaryRow(
                        symbolName: "checkmark.shield.fill",
                        title: L10n.string("Two-Factor Authentication"),
                        value: L10n.string("Enabled")
                    )
                }
            } header: {
                Text(L10n.string("Security"))
            } footer: {
                Text(L10n.string("Password, passkey and two-factor changes stay in the browser login flow for now."))
            }

            if let message {
                Section {
                    Text(message)
                        .foregroundStyle(VeyqColor.success)
                        .accessibilityIdentifier("account-success-message")
                }
            }

            if let errorMessage {
                Section {
                    Text(errorMessage)
                        .foregroundStyle(VeyqColor.danger)
                        .accessibilityIdentifier("account-error-message")
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle(L10n.string("Account"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    Task { await save() }
                } label: {
                    if saving {
                        ProgressView()
                    } else {
                        Text(L10n.string("Save"))
                    }
                }
                .disabled(!canSave)
                .accessibilityIdentifier("account-save-button")
            }
        }
        .onAppear(perform: applyUser)
        .onChange(of: store.user) { _, _ in
            if !saving {
                applyUser()
            }
        }
        .accessibilityIdentifier("account-settings-view")
    }

    private func applyUser() {
        guard let user = store.user else { return }
        displayName = user.displayName
        permalink = user.permalink
        email = user.email
    }

    @MainActor
    private func save() async {
        saving = true
        message = nil
        errorMessage = nil
        defer { saving = false }

        do {
            let nextUser = try await store.updateMySettings(
                displayName: displayName.trimmingCharacters(in: .whitespacesAndNewlines),
                permalink: permalink.trimmingCharacters(in: .whitespacesAndNewlines),
                email: email.trimmingCharacters(in: .whitespacesAndNewlines)
            )
            applyUser()
            if let pendingEmail = nextUser.pendingEmail, pendingEmail != nextUser.email {
                message = L10n.string("Profile saved. Confirm the new email address before using it to log in.")
            } else {
                message = L10n.string("Profile saved.")
            }
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

private struct ProfileAvatar: View {
    let user: VeyqUser

    private var initials: String {
        let words = user.displayName
            .split(separator: " ")
            .prefix(2)
            .compactMap(\.first)
        let value = String(words).uppercased()
        return value.isEmpty ? "V" : value
    }

    var body: some View {
        ZStack {
            Circle()
                .fill(VeyqColor.accent.opacity(0.14))
            Text(initials)
                .font(.headline.weight(.bold))
                .foregroundStyle(VeyqColor.accent)
        }
        .frame(width: 48, height: 48)
        .accessibilityHidden(true)
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

private struct CompactPreferenceMenu<Content: View>: View {
    let title: String
    let selectedTitle: String
    let symbolName: String
    let accessibilityIdentifier: String
    @ViewBuilder let content: () -> Content

    var body: some View {
        Menu {
            content()
        } label: {
            HStack(spacing: 12) {
                Image(systemName: symbolName)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(VeyqColor.accent)
                    .frame(width: 30, height: 30)
                    .background(VeyqColor.accent.opacity(0.12), in: Circle())
                    .accessibilityHidden(true)

                Text(title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.primary)

                Spacer(minLength: 12)

                Text(selectedTitle)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)

                Image(systemName: "chevron.up.chevron.down")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.tertiary)
                    .accessibilityHidden(true)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier(accessibilityIdentifier)
    }
}

private struct PreferenceMenuItem: View {
    let title: String
    let selected: Bool

    var body: some View {
        HStack {
            Text(title)
            if selected {
                Image(systemName: "checkmark")
            }
        }
    }
}
