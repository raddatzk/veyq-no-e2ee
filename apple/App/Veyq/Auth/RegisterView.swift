import SwiftUI
import VeyqCrypto

/// Brand-new invitee registering on mobile (reached via the veyq:// deep link from the browser banner):
/// create the account from the invite, show + share the Emergency Kit, then sign in and land in the group.
struct RegisterView: View {
    @EnvironmentObject var app: AppState
    let parts: InviteLink.Parts

    @State private var tenantName = ""
    @State private var role = "Member"
    @State private var displayName = ""
    @State private var email = ""
    @State private var password = ""
    @State private var kit: String?
    @State private var busy = false
    @State private var error = ""
    @State private var shareItem: ShareItem?

    private var subtitle: String {
        guard !tenantName.isEmpty else { return app.apiBase }
        return String(format: T("accept.joinAs"), tenantName, role == "Owner" ? T("invite.owner") : T("invite.member"))
    }

    var body: some View {
        AuthCard(title: T("accept.createAccount"), subtitle: subtitle) {
            VStack(spacing: 14) {
                if let secret = kit {
                    Text(T("setup.kitTitle")).font(.system(size: 17, weight: .bold)).foregroundStyle(VeyqStyle.text)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    Text(T("setup.kitSub")).font(.system(size: 13)).foregroundStyle(VeyqStyle.muted)
                    Text(secret).font(.system(size: 13, design: .monospaced)).foregroundStyle(VeyqStyle.text)
                        .frame(maxWidth: .infinity, alignment: .leading).padding(10)
                        .background(VeyqStyle.surfaceMuted, in: RoundedRectangle(cornerRadius: VeyqStyle.radiusSm))
                    Button {
                        shareItem = ShareItem(url: EmergencyKitPdf.build(EmergencyKit.KitData(instance: app.apiBase, email: email, secretKey: secret), displayName: displayName))
                    } label: {
                        Text("📄 \(T("kit.download"))").frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered).tint(VeyqStyle.accent)
                    PrimaryButton(title: T("setup.kitProceed"), busy: busy) { proceed(secret) }
                } else {
                    VeyqField(label: T("setup.displayName"), text: $displayName)
                    VeyqField(label: T("common.email"), text: $email, keyboard: .emailAddress)
                    VeyqField(label: T("common.password"), text: $password, secure: true)
                    PrimaryButton(title: T("accept.register"), busy: busy) { register() }
                    Button(T("common.cancel")) { app.cancelRegister() }
                        .font(.system(size: 13)).foregroundStyle(VeyqStyle.muted)
                }
                if !error.isEmpty { MessageBox(text: error) }
            }
        }
        .task {
            if let m = try? await RegisterViaInvitation.getInviteMetadata(apiBase: app.apiBase, inviteId: parts.inviteId) {
                tenantName = m.tenant.displayName
                role = m.role
            }
        }
        .sheet(item: $shareItem) { ActivityView(url: $0.url) }
    }

    private func register() {
        busy = true; error = ""
        Task {
            defer { busy = false }
            do {
                let res: RegisterViaInvitation.RegisterResult
                if role == "Owner" {
                    res = try await RegisterViaInvitation.registerViaInvitation(
                        apiBase: app.apiBase, inviteId: parts.inviteId, bootstrapSecret: parts.bootstrapSecret,
                        email: email, displayName: displayName, password: password)
                } else {
                    res = try await InviteMembers.registerViaMemberInvite(
                        apiBase: app.apiBase, inviteId: parts.inviteId, bootstrapSecret: parts.bootstrapSecret,
                        tempPrivateKeyB64: parts.tempPrivateKey, email: email, displayName: displayName, password: password)
                }
                kit = res.emergencyKit.secretKey
            } catch {
                self.error = T("accept.registerFailed")
            }
        }
    }

    private func proceed(_ secret: String) {
        busy = true; error = ""
        Task {
            defer { busy = false }
            do {
                let session = try await Login.login(apiBase: app.apiBase, email: email, password: password, secretKeyB64: secret)
                app.loggedIn(session)
            } catch {
                self.error = T("login.error")
            }
        }
    }
}
