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
        AuthCard(title: "Mit einer Instanz verbinden", subtitle: "Gib die Adresse deiner veyq-Instanz ein.") {
            VStack(spacing: 14) {
                VeyqField(label: "Instanz-URL", text: $url, placeholder: "https://memories.example.com", keyboard: .URL)
                if setupNeeded {
                    MessageBox(text: "Diese Instanz ist noch nicht eingerichtet — bitte im Web einrichten.", error: false)
                }
                PrimaryButton(title: "Verbinden", busy: busy) { connect() }
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
                if status.setupRequired { setupNeeded = true } else { app.connected(apiBase: base) }
            } catch {
                self.error = "Konnte die Instanz nicht erreichen. Prüfe die Adresse."
            }
        }
    }
}
