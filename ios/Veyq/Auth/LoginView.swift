import SwiftUI
import UniformTypeIdentifiers
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
    @State private var showImporter = false
    @State private var showScanner = false

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
                Button { showImporter = true } label: { Text("📄 \(T("kit.import"))").frame(maxWidth: .infinity) }
                    .buttonStyle(.bordered).tint(VeyqStyle.accent)
                Button { showScanner = true } label: { Text("📷 \(T("kit.scan"))").frame(maxWidth: .infinity) }
                    .buttonStyle(.bordered).tint(VeyqStyle.accent)
                Button(T("login.otherInstance")) { app.changeInstance() }
                    .font(.system(size: 13)).foregroundStyle(VeyqStyle.muted)
                if !error.isEmpty { MessageBox(text: error) }
            }
        }
        // Import a kit file (PDF / .veyqkit) → prefill. Read as Latin-1 so the ASCII token survives binary PDF bytes.
        .fileImporter(isPresented: $showImporter, allowedContentTypes: [.pdf, UTType(filenameExtension: "veyqkit") ?? .data, .data]) { result in
            guard case let .success(url) = result else { return }
            let ok = url.startAccessingSecurityScopedResource()
            defer { if ok { url.stopAccessingSecurityScopedResource() } }
            if let data = try? Data(contentsOf: url), let text = String(data: data, encoding: .isoLatin1) { applyKit(text) }
        }
        .fullScreenCover(isPresented: $showScanner) {
            QrScannerScreen(onDecoded: { showScanner = false; applyKit($0) }, onClose: { showScanner = false })
        }
    }

    /// Pull email + Secret Key out of a scanned QR / imported kit file and pre-fill the form.
    private func applyKit(_ text: String) {
        guard let k = EmergencyKit.parse(text) else { error = T("kit.importFailed"); return }
        email = k.email
        secretKey = k.secretKey
        error = ""
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
