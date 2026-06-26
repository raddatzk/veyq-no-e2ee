import SwiftUI
import VeyqKit

/// Step 2 of sign-in — the **Secret-Key floor**: the carried password (from login) + the Secret Key →
/// `unlock()` (derive the Unlock KEK on-device, open the Master Key + identity into the in-memory vault).
/// Mirrors the web unlock floor. Operator-blind: the Secret Key stays in this view's transient state, is
/// used on-device only, and is dropped after submit; nothing is sent.
///
/// The field pre-fills from `app.pendingSecretKey` when a **login-screen kit import** parked one (cr-0017
/// phase 2); the floor's own upload/scan affordances are added in phases 2–3.
struct UnlockView: View {
    @EnvironmentObject var app: AppModel
    @State private var secretKey = ""
    @State private var busy = false
    @State private var error = ""

    private var canSubmit: Bool { !secretKey.isEmpty }

    var body: some View {
        content.onAppear(perform: prefill)
    }

    private var content: some View {
        AuthCard(title: "Unlock your vault", subtitle: app.email) {
            VStack(alignment: .leading, spacing: 14) {
                VeyqField(label: "Secret Key", text: $secretKey, secure: true,
                          hint: "From your Emergency Kit. Used with your password to unlock — never sent.")
                PrimaryButton(title: "Unlock", busy: busy, disabled: !canSubmit) { submit() }
                Button("Use a different instance") { app.changeInstance() }
                    .font(.system(size: 13)).foregroundStyle(VeyqStyle.muted).buttonStyle(.plain)
                if !error.isEmpty { MessageBox(text: error) }
            }
        }
    }

    /// Consume a Secret Key parked by a login-screen kit import; otherwise the DEBUG UI-test seam.
    private func prefill() {
        if !app.pendingSecretKey.isEmpty {
            secretKey = app.pendingSecretKey
            return
        }
        #if DEBUG
        let env = ProcessInfo.processInfo.environment
        if env["UITEST_EMAIL"] != nil { secretKey = env["UITEST_SECRET"] ?? "" }
        #endif
    }

    private func submit() {
        busy = true; error = ""
        let secretKey = self.secretKey
        Task {
            defer { busy = false }
            do {
                try await app.unlock(secretKey: secretKey)
                self.secretKey = ""
            } catch VeyqError.unlockUnavailable {
                error = "This account has no password unlock configured."
            } catch {
                // Wrong password / Secret Key both surface as one generic failure (non-enumerable).
                self.error = "Unlock failed. Check your Secret Key."
            }
        }
    }
}
