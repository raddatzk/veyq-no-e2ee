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
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text(T("accept.paste")).font(.subheadline).foregroundStyle(VeyqStyle.muted)
                    TextField("https://…/#invite=…", text: $link, axis: .vertical)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                        .lineLimit(2 ... 4)
                        .padding(10)
                        .background(VeyqStyle.elevatedSurface)
                        .clipShape(RoundedRectangle(cornerRadius: VeyqStyle.radiusSm, style: .continuous))
                        .overlay(RoundedRectangle(cornerRadius: VeyqStyle.radiusSm, style: .continuous).strokeBorder(VeyqStyle.line))

                    if let info {
                        let role = info.role == "Owner" ? T("invite.owner") : T("invite.member")
                        Label("\(info.name) — \(T("accept.as")) \(role)", systemImage: "person.2.badge.key")
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundStyle(VeyqStyle.text)
                    }

                    if parts?.isProtected == true {
                        Text(T("accept.passphrase")).font(.subheadline).foregroundStyle(VeyqStyle.muted)
                        SecureField(T("accept.passphraseHint"), text: $passphrase)
                            .textInputAutocapitalization(.never).autocorrectionDisabled()
                            .padding(10)
                            .background(VeyqStyle.elevatedSurface)
                            .clipShape(RoundedRectangle(cornerRadius: VeyqStyle.radiusSm, style: .continuous))
                            .overlay(RoundedRectangle(cornerRadius: VeyqStyle.radiusSm, style: .continuous).strokeBorder(VeyqStyle.line))
                    }

                    PrimaryButton(title: busy ? T("accept.joining") : T("accept.join"), busy: busy) {
                        guard let p = parts else { return }
                        busy = true
                        Task { if await store.joinGroup(p, passphrase: passphrase) { dismiss() }; busy = false }
                    }
                    .disabled(parts == nil || busy || (parts?.isProtected == true && passphrase.isEmpty))

                    Spacer(minLength: 0)
                }
                .padding(18)
                .background(.thinMaterial, in: RoundedRectangle(cornerRadius: VeyqStyle.radius, style: .continuous))
                .overlay {
                    RoundedRectangle(cornerRadius: VeyqStyle.radius, style: .continuous)
                        .stroke(VeyqStyle.line)
                }
                .shadow(color: VeyqStyle.cardShadow, radius: 18, y: 10)
                .frame(maxWidth: 560, alignment: .leading)
                .padding(22)
                .frame(maxWidth: .infinity)
            }
            .background(VeyqScreenBackground())
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
