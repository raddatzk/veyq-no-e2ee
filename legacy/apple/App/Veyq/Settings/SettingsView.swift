import SwiftUI
import VeyqCrypto

/// Settings sheet — account, the connected instance, and disconnect. Theme/language are shown to match the
/// reference; full theming + i18n are their own later passes.
struct SettingsView: View {
    @EnvironmentObject var app: AppState
    @Environment(\.dismiss) private var dismiss
    @AppStorage("veyq.theme") private var theme = "system"
    @AppStorage("veyq.locale") private var locale = "de"
    @AppStorage("veyq.privacy.mapTiles") private var mapTiles = false
    @State private var rotating = false
    @State private var showRotateConfirm = false
    @State private var rotateError: String?
    @State private var newEmail = ""
    @State private var emailBusy = false
    @State private var emailMessage: String?
    @State private var emailError: String?
    @State private var supportUrl: String?
    @State private var serverVersion: String?
    @AppStorage("veyq.support.dismissedVersion") private var supportDismissed = ""

    private var supportVisible: Bool { supportUrl != nil && supportDismissed != (serverVersion ?? "unversioned") }

    /// Request an email change: a confirmation token is emailed to the NEW address (committed via that link).
    private func changeEmail() {
        guard let session = app.session, !emailBusy else { return }
        let target = newEmail.trimmingCharacters(in: .whitespaces)
        guard !target.isEmpty else { return }
        emailBusy = true; emailError = nil; emailMessage = nil
        Task {
            do {
                try await EmailChange.requestEmailChange(apiBase: app.apiBase, sessionToken: session.sessionToken, newEmail: target)
                emailMessage = T("emailChange.sent", target)
                newEmail = ""
            } catch let e as NSError {
                emailError = e.code == 409 ? T("emailChange.taken") : T("emailChange.failed")
            }
            emailBusy = false
        }
    }

    /// Load operator metadata (support URL + version) for the dismissible support banner.
    private func loadSupport() {
        Task {
            if let status = try? await Instance.getSetupStatus(apiBase: app.apiBase) {
                serverVersion = status.serverVersion
                supportUrl = status.supportUrl
            }
        }
    }

    private var themeTitle: String {
        switch theme {
        case "light": T("settings.light")
        case "dark": T("settings.dark")
        default: T("settings.system")
        }
    }

    private var localeTitle: String {
        locale == "en" ? "English" : "Deutsch"
    }

    /// Re-key every grant to a fresh identity (auth unchanged). The in-memory session's identity goes stale,
    /// so on success we sign out — the next login re-derives the new identity from the unchanged master key.
    private func rotateIdentity() {
        guard let session = app.session, !rotating else { return }
        rotating = true
        Task {
            do {
                _ = try await RotateIdentity.rotateIdentity(apiBase: app.apiBase, session: session)
                app.signOut(); dismiss()
            } catch {
                rotateError = error.localizedDescription
                rotating = false
            }
        }
    }

    var body: some View {
        NavigationStack {
            List {
                if supportVisible, let urlString = supportUrl, let link = URL(string: urlString) {
                    Section {
                        VStack(alignment: .leading, spacing: 8) {
                            Text(T("support.noticeTitle")).font(.subheadline.weight(.semibold)).foregroundStyle(VeyqStyle.text)
                            Text(T("support.noticeCopy")).font(.caption).foregroundStyle(VeyqStyle.muted)
                            HStack {
                                Link(T("support.action"), destination: link)
                                Spacer()
                                Button(T("support.dismiss")) { supportDismissed = serverVersion ?? "unversioned" }
                                    .font(.caption).foregroundStyle(VeyqStyle.muted)
                            }
                        }
                    }
                }
                Section(T("settings.langAppearance")) {
                    CompactPreferenceMenu(
                        title: T("settings.appearance"),
                        selectedTitle: themeTitle,
                        symbolName: "circle.lefthalf.filled",
                        accessibilityIdentifier: "settings-theme-picker"
                    ) {
                        PreferenceMenuItem(title: T("settings.system"), selected: theme == "system") { theme = "system" }
                        PreferenceMenuItem(title: T("settings.light"), selected: theme == "light") { theme = "light" }
                        PreferenceMenuItem(title: T("settings.dark"), selected: theme == "dark") { theme = "dark" }
                    }

                    CompactPreferenceMenu(
                        title: T("settings.language"),
                        selectedTitle: localeTitle,
                        symbolName: "globe",
                        accessibilityIdentifier: "settings-language-picker"
                    ) {
                        PreferenceMenuItem(title: "Deutsch", selected: locale == "de") { locale = "de" }
                        PreferenceMenuItem(title: "English", selected: locale == "en") { locale = "en" }
                    }
                }
                Section {
                    Toggle(isOn: $mapTiles) {
                        SettingsSummaryLabel(symbolName: "map", title: T("settings.mapTiles"), value: T("settings.mapTilesHint"))
                    }
                } header: {
                    Text(T("settings.privacy"))
                } footer: {
                    Text(T("settings.mapTilesHint"))
                }
                Section {
                    if let user = app.session?.user {
                        SettingsSummaryLabel(symbolName: "person.crop.circle", title: user.displayName, value: user.email)
                    }
                    TextField(T("emailChange.newEmail"), text: $newEmail)
                        .textContentType(.emailAddress)
                        .keyboardType(.emailAddress)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled(true)
                    Button(emailBusy ? T("emailChange.sending") : T("emailChange.change")) { changeEmail() }
                        .disabled(emailBusy || newEmail.trimmingCharacters(in: .whitespaces).isEmpty)
                    if let m = emailMessage { Text(m).font(.caption).foregroundStyle(VeyqStyle.muted) }
                    if let e = emailError { Text(e).font(.caption).foregroundStyle(.red) }
                } header: {
                    Text(T("settings.account"))
                } footer: {
                    Text(T("emailChange.hint"))
                }
                if DeviceUnlock.isEnabled {
                    Section(T("settings.deviceUnlock")) {
                        Button(T("settings.forgetDevice"), role: .destructive) { DeviceUnlock.forget() }
                    }
                }
                Section {
                    Button(rotating ? T("settings.rotating") : T("settings.rotateIdentity")) { showRotateConfirm = true }
                        .disabled(rotating)
                } header: {
                    Text(T("settings.security"))
                } footer: {
                    Text(T("settings.rotateIdentityHint"))
                }
                Section(T("settings.instance")) {
                    SettingsSummaryLabel(symbolName: "server.rack", title: T("settings.instance"), value: app.apiBase)
                }
                Section {
                    Button(T("settings.disconnect"), role: .destructive) { app.signOut(); dismiss() }
                }
            }
            .listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
            .background(VeyqScreenBackground())
            .task { loadSupport() }
            .navigationTitle(T("settings.title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button(T("common.done")) { dismiss() } } }
            .confirmationDialog(T("settings.rotateConfirm"), isPresented: $showRotateConfirm, titleVisibility: .visible) {
                Button(T("settings.rotateIdentity"), role: .destructive) { rotateIdentity() }
                Button(T("common.cancel"), role: .cancel) {}
            }
            .alert(T("common.error"), isPresented: .constant(rotateError != nil)) {
                Button(T("common.close")) { rotateError = nil }
            } message: { Text(rotateError ?? "") }
        }
        .tint(VeyqStyle.accent)
    }
}

private struct SettingsSummaryLabel: View {
    let symbolName: String
    let title: String
    let value: String

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: symbolName)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(VeyqStyle.accent)
                .frame(width: 30, height: 30)
                .background(VeyqStyle.accent.opacity(0.12), in: Circle())
                .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(VeyqStyle.text)
                Text(value)
                    .font(.caption)
                    .foregroundStyle(VeyqStyle.muted)
                    .lineLimit(2)
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
                    .foregroundStyle(VeyqStyle.accent)
                    .frame(width: 30, height: 30)
                    .background(VeyqStyle.accent.opacity(0.12), in: Circle())
                    .accessibilityHidden(true)

                Text(title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(VeyqStyle.text)

                Spacer(minLength: 12)

                Text(selectedTitle)
                    .font(.subheadline)
                    .foregroundStyle(VeyqStyle.muted)

                Image(systemName: "chevron.up.chevron.down")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(VeyqStyle.muted)
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
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack {
                Text(title)
                if selected {
                    Image(systemName: "checkmark")
                }
            }
        }
    }
}
