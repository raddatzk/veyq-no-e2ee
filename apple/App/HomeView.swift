import SwiftUI
import VeyqKit

/// The unlocked state. Login + unlock is the whole of cr-0015, so this is intentionally a thin landing
/// screen — content (Memory / Chapters) is a later slice. It confirms the operator-blind result: a session
/// is held and the vault is open on-device, with a sign-out that revokes the session and re-locks.
struct HomeView: View {
    @EnvironmentObject var app: AppModel
    @State private var busy = false

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
}
