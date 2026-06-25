import SwiftUI
import VeyqCrypto

/// Owner-side member invite for a tenant: pick a role + protection mode, then either email the invite or
/// share the link. Pragmatic = the temp key rides the link in the clear; passphrase = it's encrypted under
/// an out-of-band passphrase (operator-blind, even against the email relay), so the link alone can't join.
struct InviteMemberView: View {
    @ObservedObject var store: HomeStore
    let space: SwitchActiveSpace.SpaceEntry
    @Environment(\.dismiss) private var dismiss

    @State private var role = "Member"
    @State private var passphraseMode = false
    @State private var passphrase = ""
    @State private var email = ""
    @State private var busy = false
    @State private var sent = false
    @State private var shareItem: ShareItem?

    private var tenantId: String { space.tenantId ?? "" }
    private var passOrNil: String? { passphraseMode ? passphrase : nil }
    private var passMissing: Bool { passphraseMode && passphrase.trimmingCharacters(in: .whitespaces).isEmpty }

    var body: some View {
        NavigationStack {
            Form {
                Section(T("invite.role")) {
                    Picker(T("invite.role"), selection: $role) {
                        Text(T("invite.member")).tag("Member")
                        Text(T("invite.owner")).tag("Owner")
                    }.pickerStyle(.segmented)
                }
                Section(T("invite.protection")) {
                    Picker(T("invite.protection"), selection: $passphraseMode) {
                        Text(T("invite.protLink")).tag(false)
                        Text(T("invite.protPassphrase")).tag(true)
                    }.pickerStyle(.segmented)
                    if passphraseMode {
                        SecureField(T("invite.passphrasePlaceholder"), text: $passphrase)
                            .textInputAutocapitalization(.never).autocorrectionDisabled()
                        Text(T("invite.passphraseOob")).font(.footnote).foregroundStyle(VeyqStyle.muted)
                    }
                }
                Section(T("invite.emailHint")) {
                    TextField(T("invite.emailPlaceholder"), text: $email)
                        .textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.emailAddress)
                    Button {
                        busy = true
                        Task {
                            sent = await store.emailMemberInvite(tenantId: tenantId, role: role, email: email, passphrase: passOrNil)
                            busy = false
                            if sent { email = "" }
                        }
                    } label: { Text(sent ? T("invite.emailSent") : T("invite.emailSend")) }
                        .disabled(busy || email.trimmingCharacters(in: .whitespaces).isEmpty || passMissing)
                }
                Section {
                    Button {
                        busy = true
                        Task {
                            if let link = await store.buildMemberInviteLink(tenantId: tenantId, role: role, passphrase: passOrNil),
                               let url = URL(string: link) { shareItem = ShareItem(url: url) }
                            busy = false
                        }
                    } label: { Label(T("invite.shareLink"), systemImage: "square.and.arrow.up") }
                        .disabled(busy || passMissing)
                }
                if let err = store.error { Section { MessageBox(text: err) } }
            }
            .listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
            .background(VeyqScreenBackground())
            .navigationTitle(T("invite.title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button(T("common.close")) { dismiss() } } }
            .sheet(item: $shareItem) { ActivityView(url: $0.url) }
        }
        .tint(VeyqStyle.accent)
    }
}
