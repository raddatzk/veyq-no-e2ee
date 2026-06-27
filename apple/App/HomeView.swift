import SwiftUI
import VeyqKit

/// The unlocked state. Login + unlock is the whole of cr-0015, so this is intentionally a thin landing
/// screen — content (Memory / Chapters) is a later slice. It confirms the operator-blind result: a session
/// is held and the vault is open on-device, with a sign-out that revokes the session and re-locks.
struct HomeView: View {
    @EnvironmentObject var app: AppModel
    @State private var busy = false
    @State private var untrusting = false

    var body: some View {
        ZStack {
            VeyqStyle.bg.ignoresSafeArea()
            VStack(spacing: 18) {
                Image(systemName: "lock.open.fill")
                    .font(.system(size: 44))
                    .foregroundStyle(VeyqStyle.success)
                    .frame(width: 84, height: 84)
                    .background(VeyqStyle.success.opacity(0.12), in: Circle())

                VStack(spacing: 4) {
                    Text("Unlocked").font(.title2.weight(.bold)).foregroundStyle(VeyqStyle.text)
                    Text(app.email).font(.callout).foregroundStyle(VeyqStyle.muted).textSelection(.enabled)
                    Text(app.apiBase).font(.footnote).foregroundStyle(VeyqStyle.muted).textSelection(.enabled)
                }

                MessageBox(text: "Your keys are open on this device only. The server holds ciphertext — your content stays end-to-end encrypted.",
                           error: false)
                    .frame(maxWidth: 360)

                if app.deviceTrustFailed {
                    MessageBox(text: "Couldn’t trust this device — you’ll use your Secret Key next time.")
                        .frame(maxWidth: 360)
                }

                if app.deviceTrusted {
                    VStack(spacing: 8) {
                        MessageBox(text: "This device is trusted — next time you’ll unlock with just your password.",
                                   error: false)
                        Button(untrusting ? "…" : "Stop trusting this device") { untrust() }
                            .font(.system(size: 13, weight: .medium))
                            .foregroundStyle(VeyqStyle.danger)
                            .buttonStyle(.plain)
                            .disabled(untrusting)
                            .accessibilityIdentifier("Stop trusting this device")
                    }
                    .frame(maxWidth: 360)
                }

                PrimaryButton(title: "Sign out", busy: busy) { signOut() }
                    .frame(maxWidth: 220)
            }
            .padding(28)
            .frame(maxWidth: 440)
        }
    }

    private func signOut() {
        busy = true
        Task {
            await app.signOut()
            busy = false
        }
    }

    private func untrust() {
        untrusting = true
        Task {
            await app.untrust()
            untrusting = false
        }
    }
}
