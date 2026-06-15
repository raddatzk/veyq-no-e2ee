import SwiftUI
import VeyqCrypto

/// Connect to a chosen instance. If it still needs first-run setup (web-only), say so rather than
/// offering a setup form; otherwise advance to login.
struct ConnectView: View {
    @EnvironmentObject var app: AppState
    @State private var url = ""
    @State private var busy = false
    @State private var error = ""
    @State private var setupNeeded = false

    var body: some View {
        AuthCard(title: T("connect.title"), subtitle: T("connect.sub")) {
            VStack(spacing: 14) {
                VeyqField(label: T("connect.url"), text: $url, placeholder: "https://memories.example.com", keyboard: .URL)
                if setupNeeded {
                    MessageBox(text: T("connect.setupNeeded"), error: false)
                }
                PrimaryButton(title: T("connect.connect"), busy: busy) { connect() }
                if !error.isEmpty { MessageBox(text: error) }
            }
        }
        .onAppear { if url.isEmpty { url = app.apiBase } }
    }

    private func connect() {
        busy = true; error = ""; setupNeeded = false
        Task {
            defer { busy = false }
            do {
                let base = normalizeApiBase(url)
                let status = try await Instance.getSetupStatus(apiBase: base)
                if status.setupRequired { setupNeeded = true } else { app.connected(apiBase: base, appBaseUrl: status.appBaseUrl) }
            } catch {
                self.error = T("connect.error")
            }
        }
    }
}
