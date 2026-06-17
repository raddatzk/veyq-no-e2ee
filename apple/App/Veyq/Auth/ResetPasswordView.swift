import SwiftUI
import VeyqCrypto

/// Forgot-password recovery: request a reset email, then paste the link from it and enter the Secret Key +
/// a new password. Recovery needs the Secret Key (crypto factor) AND email access (the link is the email
/// factor). On success the user is signed in with the new password.
struct ResetPasswordView: View {
    let apiBase: String
    let prefillEmail: String
    let onSignedIn: (Login.UnlockedSession) -> Void
    @Environment(\.dismiss) private var dismiss

    @State private var email = ""
    @State private var sent = false
    @State private var link = ""
    @State private var secretKey = ""
    @State private var newPassword = ""
    @State private var busy = false
    @State private var error = ""
    @State private var breachCount = 0

    private var token: String? { ResetPassword.parseToken(link) }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    // Step 1 — request the email
                    Text(T("reset.requestHint")).font(.subheadline).foregroundStyle(VeyqStyle.muted)
                    VeyqField(label: T("common.email"), text: $email, keyboard: .emailAddress)
                    if sent { Text(T("reset.sent")).font(.footnote).foregroundStyle(VeyqStyle.accent) }
                    PrimaryButton(title: sent ? T("reset.sendAgain") : T("reset.sendLink"), busy: busy) { sendRequest() }

                    Divider().padding(.vertical, 4)

                    // Step 2 — paste the link + Secret Key + new password
                    Text(T("reset.confirmHint")).font(.subheadline).foregroundStyle(VeyqStyle.muted)
                    VeyqField(label: T("reset.pasteLink"), text: $link)
                    VeyqField(label: T("common.secretKey"), text: $secretKey, secure: true, hint: T("login.secretHint"))
                    VeyqField(label: T("reset.newPassword"), text: $newPassword, secure: true)
                    if breachCount > 0 {
                        Text(T("password.pwnedWarning", breachCount)).font(.system(size: 12)).foregroundStyle(.orange)
                    }
                    if let hint = PasswordPolicy.hintKey(newPassword) {
                        Text(T(hint)).font(.system(size: 12)).foregroundStyle(VeyqStyle.muted)
                    }
                    PrimaryButton(title: T("reset.setPassword"), busy: busy) { confirm() }
                        .disabled(token == nil || secretKey.isEmpty || newPassword.count < PasswordPolicy.minLength)

                    if !error.isEmpty { MessageBox(text: error) }
                }
                .padding(16)
            }
            .background(VeyqStyle.bg)
            .navigationTitle(T("reset.title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button(T("common.close")) { dismiss() } } }
        }
        .tint(VeyqStyle.accent)
        .onAppear { if email.isEmpty { email = prefillEmail } }
        // Debounced HIBP breach check (soft, non-blocking); .task(id:) auto-cancels when the password changes.
        .task(id: newPassword) {
            breachCount = 0
            guard !newPassword.isEmpty else { return }
            try? await Task.sleep(nanoseconds: 600_000_000)
            if !Task.isCancelled { breachCount = await Pwned.count(newPassword) }
        }
    }

    private func sendRequest() {
        busy = true; error = ""
        Task {
            defer { busy = false }
            do { try await ResetPassword.requestReset(apiBase: apiBase, email: email); sent = true }
            catch { self.error = T("reset.requestFailed") }
        }
    }

    private func confirm() {
        guard let token else { return }
        busy = true; error = ""
        Task {
            defer { busy = false }
            do {
                let addr = try await ResetPassword.confirmReset(apiBase: apiBase, token: token, secretKeyB64: secretKey, newPassword: newPassword)
                let session = try await Login.login(apiBase: apiBase, email: addr, password: newPassword, secretKeyB64: secretKey)
                onSignedIn(session)
                dismiss()
            } catch {
                self.error = T("reset.failed")
            }
        }
    }
}
