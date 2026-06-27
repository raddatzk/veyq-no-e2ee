import SwiftUI
import UniformTypeIdentifiers
import LocalAuthentication
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
    @State private var importingKit = false
    @State private var scanningKit = false
    /// The device unlock level the user picks on this floor (no default — they must choose; ADR 0005 §10).
    @State private var level: DeviceUnlockLevel? = nil

    private var canSubmit: Bool { !secretKey.isEmpty && level != nil }

    /// Face ID / Touch ID enrolled on this device → the L2 row is offered.
    private var biometricAvailable: Bool {
        LAContext().canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: nil)
    }

    /// A device passcode / lock screen is set → the L3 ("open straight in") row is offered. `.deviceOwner`
    /// authentication is available exactly when a passcode (or biometric) exists, which is what L3 relies on.
    private var passcodeSet: Bool {
        LAContext().canEvaluatePolicy(.deviceOwnerAuthentication, error: nil)
    }

    var body: some View {
        content.onAppear(perform: prefill)
    }

    private var content: some View {
        AuthCard(title: "Unlock your vault", subtitle: app.email) {
            VStack(alignment: .leading, spacing: 14) {
                VeyqField(label: "Secret Key", text: $secretKey, secure: true,
                          hint: "From your Emergency Kit. Used with your password to unlock — never sent.")
                KitImportBar(label: "Import your Emergency Kit to fill the Secret Key",
                             onUpload: { importingKit = true }, onScan: { scanningKit = true })
                Divider().overlay(VeyqStyle.line)
                DeviceSecurityChooser(selection: $level, biometricAvailable: biometricAvailable, passcodeSet: passcodeSet)
                PrimaryButton(title: "Unlock", busy: busy, disabled: !canSubmit) { submit() }
                Button("Use a different instance") { app.changeInstance() }
                    .font(.system(size: 13)).foregroundStyle(VeyqStyle.muted).buttonStyle(.plain)
                if !error.isEmpty { MessageBox(text: error) }
            }
            .fileImporter(isPresented: $importingKit, allowedContentTypes: [.pdf, .plainText, .text]) { result in
                if case .success(let url) = result { applyKit(fromFile: url) }
            }
            .sheet(isPresented: $scanningKit) {
                KitScanner(onResult: { kit in scanningKit = false; secretKey = kit.secretKey },
                           onCancel: { scanningKit = false })
            }
        }
    }

    /// Import a kit from an uploaded file: fill the Secret Key. On-device only — nothing is sent.
    private func applyKit(fromFile url: URL) {
        error = ""
        let access = url.startAccessingSecurityScopedResource()
        defer { if access { url.stopAccessingSecurityScopedResource() } }
        guard let data = try? Data(contentsOf: url), let kit = importKitFromData(data) else {
            error = "Couldn't read an Emergency Kit from that file."
            return
        }
        secretKey = kit.secretKey
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
        guard let level else { return }
        busy = true; error = ""
        let secretKey = self.secretKey
        Task {
            defer { busy = false }
            do {
                try await app.unlock(secretKey: secretKey, level: level)
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
