import SwiftUI
import VeyqKit

/// Sign in with email + password + Secret Key. One submit runs the two distinct data-layer steps —
/// `login` (operator-blind challenge-response → session) then `unlock` (password + Secret Key →
/// in-memory vault) — mirroring the legacy native single-form UX. Operator-blind: the password and Secret
/// Key stay in this view's transient state, are used on-device only, and are dropped after submit; the
/// password is never sent and the Secret Key never leaves the device. Ported from
/// `legacy/iOS/Veyq/Auth/LoginView.swift`.
struct SignInView: View {
    @EnvironmentObject var app: AppModel
    @State private var email = ""
    @State private var password = ""
    @State private var secretKey = ""
    @State private var busy = false
    @State private var error = ""

    private var canSubmit: Bool {
        !email.trimmingCharacters(in: .whitespaces).isEmpty && !password.isEmpty && !secretKey.isEmpty
    }

    var body: some View {
        AuthCard(title: "Sign in", subtitle: app.apiBase) {
            VStack(alignment: .leading, spacing: 14) {
                VeyqField(label: "Email", text: $email, placeholder: "you@example.com", keyboard: .email)
                VeyqField(label: "Password", text: $password, secure: true)
                VeyqField(label: "Secret Key", text: $secretKey, secure: true,
                          hint: "From your Emergency Kit. Used with your password to unlock — never sent.")
                PrimaryButton(title: "Sign in & unlock", busy: busy, disabled: !canSubmit) { submit() }
                Button("Use a different instance") { app.changeInstance() }
                    .font(.system(size: 13)).foregroundStyle(VeyqStyle.muted).buttonStyle(.plain)
                if !error.isEmpty { MessageBox(text: error) }
            }
        }
    }

    private func submit() {
        guard let client = app.client else { return }
        busy = true; error = ""
        let email = self.email.trimmingCharacters(in: .whitespaces)
        let password = self.password
        let secretKey = self.secretKey
        Task {
            defer { busy = false }
            do {
                try await client.login(email: email, password: password)
                try await client.unlock(password: password, secretKey: secretKey)
                // Drop the secrets from view state the moment they're no longer needed.
                self.password = ""
                self.secretKey = ""
                app.unlocked(email: email)
            } catch VeyqError.unlockUnavailable {
                error = "This account has no password unlock configured."
            } catch {
                // Wrong password / Secret Key / unknown account all surface as one generic failure
                // (non-enumerable, operator-blind).
                self.error = "Sign-in failed. Check your email, password, and Secret Key."
            }
        }
    }
}
