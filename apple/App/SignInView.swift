import SwiftUI
import VeyqKit

/// Step 1 of sign-in: **email + password → `login()`** (operator-blind challenge-response → session).
/// Mirrors the web two-step flow; the Secret-Key floor (`UnlockView`) is the second step. Operator-blind:
/// the password stays in this view's transient state, is used on-device only (the data layer signs a nonce
/// with a password-derived key — the password itself is never sent), and is dropped after submit.
/// (cr-0017 phase 2 adds the in-screen Emergency-Kit import: fill email + park the Secret Key.)
struct SignInView: View {
    @EnvironmentObject var app: AppModel
    @State private var email = ""
    @State private var password = ""
    @State private var busy = false
    @State private var error = ""

    private var canSubmit: Bool {
        !email.trimmingCharacters(in: .whitespaces).isEmpty && !password.isEmpty
    }

    var body: some View {
        content.onAppear(perform: prefillForUITestIfNeeded)
    }

    private var content: some View {
        AuthCard(title: "Sign in", subtitle: app.apiBase) {
            VStack(alignment: .leading, spacing: 14) {
                VeyqField(label: "Email", text: $email, placeholder: "you@example.com", keyboard: .email)
                VeyqField(label: "Password", text: $password, secure: true)
                PrimaryButton(title: "Sign in", busy: busy, disabled: !canSubmit) { submit() }
                Button("Use a different instance") { app.changeInstance() }
                    .font(.system(size: 13)).foregroundStyle(VeyqStyle.muted).buttonStyle(.plain)
                if !error.isEmpty { MessageBox(text: error) }
            }
        }
    }

    /// DEBUG-only UI-test seam: SwiftUI `SecureField`s drop characters under XCUITest `typeText`, so the
    /// round-trip UI test injects the credentials via launch environment instead of typing them. Compiled
    /// out of release builds entirely, and inert unless the launch env carries `UITEST_EMAIL`.
    private func prefillForUITestIfNeeded() {
        #if DEBUG
        let env = ProcessInfo.processInfo.environment
        guard let e = env["UITEST_EMAIL"] else { return }
        email = e
        password = env["UITEST_PASSWORD"] ?? ""
        #endif
    }

    private func submit() {
        busy = true; error = ""
        let email = self.email.trimmingCharacters(in: .whitespaces)
        let password = self.password
        Task {
            defer { busy = false }
            do {
                try await app.login(email: email, password: password)
                // Drop the password from view state; the model carries it to the unlock step.
                self.password = ""
            } catch {
                // Wrong password / unknown account both surface as one generic failure (non-enumerable).
                self.error = "Sign-in failed. Check your email and password."
            }
        }
    }
}
