import SwiftUI
import VeyqCrypto

/// Password + Secret Key login (the unlock model). Slice 1 asks for the Secret Key each time; remembering
/// it on-device (Keychain) is its own later unlock slice.
struct LoginView: View {
    @EnvironmentObject var app: AppState
    @State private var email = ""
    @State private var password = ""
    @State private var secretKey = ""
    @State private var remember = false
    @State private var busy = false
    @State private var error = ""

    var body: some View {
        AuthCard(title: T("login.title"), subtitle: app.apiBase) {
            VStack(spacing: 14) {
                if DeviceUnlock.isEnabled {
                    PrimaryButton(title: "🔓 \(T("login.unlock"))", busy: busy) { unlock() }
                }
                VeyqField(label: T("common.email"), text: $email, keyboard: .emailAddress)
                VeyqField(label: T("common.password"), text: $password, secure: true)
                VeyqField(label: T("common.secretKey"), text: $secretKey, secure: true, hint: T("login.secretHint"))
                if DeviceUnlock.available {
                    Toggle(T("login.remember"), isOn: $remember).font(.system(size: 14)).tint(VeyqStyle.accent)
                }
                PrimaryButton(title: T("login.submit"), busy: busy) { submit() }
                Button(T("login.otherInstance")) { app.changeInstance() }
                    .font(.system(size: 13)).foregroundStyle(VeyqStyle.muted)
                if !error.isEmpty { MessageBox(text: error) }
            }
        }
    }

    private func submit() {
        busy = true; error = ""
        Task {
            defer { busy = false }
            do {
                let session = try await Login.login(apiBase: app.apiBase, email: email, password: password, secretKeyB64: secretKey)
                if remember { DeviceUnlock.save(DeviceUnlock.Creds(apiBase: app.apiBase, email: email, password: password, secretKey: secretKey)) }
                app.loggedIn(session)
            } catch {
                self.error = T("login.error")
            }
        }
    }

    private func unlock() {
        busy = true; error = ""
        Task {
            defer { busy = false }
            guard let creds = await DeviceUnlock.load() else { error = T("login.unlockFailed"); return }
            do {
                let session = try await Login.login(apiBase: creds.apiBase, email: creds.email, password: creds.password, secretKeyB64: creds.secretKey)
                app.loggedIn(session)
            } catch {
                self.error = T("login.unlockFailed")
            }
        }
    }
}
