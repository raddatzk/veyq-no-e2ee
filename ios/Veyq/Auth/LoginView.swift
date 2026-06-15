import SwiftUI
import VeyqCrypto

/// Password + Secret Key login (the unlock model). Slice 1 asks for the Secret Key each time; remembering
/// it on-device (Keychain) is its own later unlock slice.
struct LoginView: View {
    @EnvironmentObject var app: AppState
    @State private var email = ""
    @State private var password = ""
    @State private var secretKey = ""
    @State private var busy = false
    @State private var error = ""

    var body: some View {
        AuthCard(title: T("login.title"), subtitle: app.apiBase) {
            VStack(spacing: 14) {
                VeyqField(label: T("common.email"), text: $email, keyboard: .emailAddress)
                VeyqField(label: T("common.password"), text: $password, secure: true)
                VeyqField(label: T("common.secretKey"), text: $secretKey, secure: true, hint: T("login.secretHint"))
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
                app.loggedIn(session)
            } catch {
                self.error = T("login.error")
            }
        }
    }
}
