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
    @State private var passphrase = ""
    @State private var kit: String?
    @State private var busy = false
    @State private var error = ""
    @State private var shareItem: ShareItem?
    @State private var breachCount = 0

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
                    if breachCount > 0 {
                        Text(T("password.pwnedWarning", breachCount))
                            .font(.system(size: 12)).foregroundStyle(.orange)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    if let hint = PasswordPolicy.hintKey(password) {
                        Text(T(hint)).font(.system(size: 12)).foregroundStyle(VeyqStyle.muted)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    if parts.isProtected {
                        VeyqField(label: T("accept.passphrase"), text: $passphrase, secure: true)
                    }
                    PrimaryButton(title: T("accept.register"), busy: busy) { register() }
                        .disabled(password.count < PasswordPolicy.minLength)
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
        // Debounced HIBP breach check (soft, non-blocking): .task(id:) auto-cancels when the password changes.
        .task(id: password) {
            breachCount = 0
            guard !password.isEmpty else { return }
            try? await Task.sleep(nanoseconds: 600_000_000)
            if !Task.isCancelled { breachCount = await Pwned.count(password) }
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
                    let tempKey = parts.isProtected
                        ? try InviteMembers.unprotectTempKey(encTempKey: parts.encTempKey!, pwSalt: parts.pwSalt!, passphrase: passphrase)
                        : (parts.tempPrivateKey ?? "")
                    res = try await InviteMembers.registerViaMemberInvite(
                        apiBase: app.apiBase, inviteId: parts.inviteId, bootstrapSecret: parts.bootstrapSecret,
                        tempPrivateKeyB64: tempKey, email: email, displayName: displayName, password: password)
                }
                kit = res.emergencyKit.secretKey
            } catch {
                self.error = parts.isProtected ? T("accept.wrongPassphrase") : T("accept.registerFailed")
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
