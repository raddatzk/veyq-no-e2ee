import SwiftUI
import VeyqKit

/// Connect to a chosen instance. Native never bootstraps (first-run setup is browser-only), so if the
/// instance still needs setup we say so rather than offering a setup form; otherwise advance to sign-in.
/// Ported from `legacy/iOS/Veyq/Auth/ConnectView.swift`.
struct ConnectView: View {
    @EnvironmentObject var app: AppModel
    @State private var url = ""
    @State private var busy = false
    @State private var error = ""
    @State private var setupNeeded = false

    var body: some View {
        AuthCard(title: "Connect to your instance",
                 subtitle: "Enter the address of your veyq server.") {
            VStack(alignment: .leading, spacing: 14) {
                VeyqField(label: "Instance URL", text: $url,
                          placeholder: "https://memories.example.com", keyboard: .url)
                if setupNeeded {
                    MessageBox(text: "This instance still needs first-run setup. Finish it in a web browser, then connect here.",
                               error: false)
                }
                PrimaryButton(title: "Connect", busy: busy,
                              disabled: url.trimmingCharacters(in: .whitespaces).isEmpty) { connect() }
                if !error.isEmpty { MessageBox(text: error) }
            }
        }
    }

    private func connect() {
        busy = true; error = ""; setupNeeded = false
        Task {
            defer { busy = false }
            guard let baseURL = normalizeBaseURL(url) else {
                error = "That doesn't look like a valid address."
                return
            }
            let client = VeyqClient(instance: VeyqInstance(baseURL: baseURL))
            do {
                if try await client.setupRequired() {
                    setupNeeded = true
                } else {
                    app.connected(baseURL: baseURL, client: client)
                }
            } catch {
                self.error = "Couldn't reach that instance. Check the address and try again."
            }
        }
    }
}
