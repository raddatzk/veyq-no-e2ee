import SwiftUI
import VeyqCrypto

/// Join a shared group from a pasted invite link. The link's secrets are parsed locally; a confirmation
/// preview (tenant + role) is fetched from public metadata, then acceptMemberInvite adds the grant.
struct JoinGroupView: View {
    @ObservedObject var store: HomeStore
    @Environment(\.dismiss) private var dismiss

    @State private var link = ""
    @State private var info: (name: String, role: String)?
    @State private var busy = false
    @State private var passphrase = ""

    private var parts: InviteLink.Parts? { InviteLink.parse(link) }

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 16) {
                Text(T("accept.paste")).font(.subheadline).foregroundStyle(VeyqStyle.muted)
                TextField("https://…/#invite=…", text: $link, axis: .vertical)
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
                    .lineLimit(2 ... 4)
                    .padding(10)
                    .background(VeyqStyle.surface)
                    .clipShape(RoundedRectangle(cornerRadius: VeyqStyle.radius))
                    .overlay(RoundedRectangle(cornerRadius: VeyqStyle.radius).strokeBorder(VeyqStyle.line))

                if let info {
                    let role = info.role == "Owner" ? T("invite.owner") : T("invite.member")
                    Text("\(info.name) — \(T("accept.as")) \(role)").font(.system(size: 15, weight: .semibold)).foregroundStyle(VeyqStyle.text)
                }

                if parts?.isProtected == true {
                    Text(T("accept.passphrase")).font(.subheadline).foregroundStyle(VeyqStyle.muted)
                    SecureField(T("accept.passphraseHint"), text: $passphrase)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                        .padding(10)
                        .background(VeyqStyle.surface)
                        .clipShape(RoundedRectangle(cornerRadius: VeyqStyle.radius))
                        .overlay(RoundedRectangle(cornerRadius: VeyqStyle.radius).strokeBorder(VeyqStyle.line))
                }

                Button {
                    guard let p = parts else { return }
                    busy = true
                    Task { if await store.joinGroup(p, passphrase: passphrase) { dismiss() }; busy = false }
                } label: {
                    Text(busy ? T("accept.joining") : T("accept.join")).frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent).tint(VeyqStyle.accent).disabled(parts == nil || busy || (parts?.isProtected == true && passphrase.isEmpty))

                Spacer()
            }
            .padding(16)
            .background(VeyqStyle.bg)
            .navigationTitle(T("groups.join"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button(T("common.close")) { dismiss() } } }
            .onChange(of: link) { _, _ in
                info = nil
                if let p = parts { Task { info = await store.inviteInfo(p.inviteId) } }
            }
        }
        .tint(VeyqStyle.accent)
    }
}
