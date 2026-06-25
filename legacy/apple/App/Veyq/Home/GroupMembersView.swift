import SwiftUI
import VeyqCrypto

/// Manage a group's members (GroupOwner): list, add a tenant member, change role, remove, delete the group.
/// The crypto (re-wrap the group key to a member's identity) lives in VeyqCrypto.Groups.
struct GroupMembersView: View {
    @ObservedObject var store: HomeStore
    let space: SwitchActiveSpace.SpaceEntry
    let onDeleted: () -> Void
    @Environment(\.dismiss) private var dismiss

    @State private var members: [Groups.GroupMember] = []
    @State private var candidates: [InviteMembers.TenantMemberDTO] = []
    @State private var toAdd = ""
    @State private var error = ""
    @State private var busy = false
    @State private var confirmDelete = false

    private var tid: String { space.tenantId ?? "" }

    var body: some View {
        NavigationStack {
            List {
                Section(T("groups.members")) {
                    ForEach(members) { m in
                        HStack(spacing: 8) {
                            Text(m.displayName).foregroundStyle(VeyqStyle.text)
                            Spacer()
                            Picker("", selection: roleBinding(m)) {
                                Text(T("groups.roleOwner")).tag("GroupOwner")
                                Text(T("groups.roleMember")).tag("GroupMember")
                            }.labelsHidden().disabled(busy)
                            Button(role: .destructive) { Task { await remove(m) } } label: { Image(systemName: "minus.circle") }
                                .buttonStyle(.borderless).disabled(busy)
                        }
                    }
                }
                if !candidates.isEmpty {
                    Section(T("groups.addMember")) {
                        Picker(T("groups.addMember"), selection: $toAdd) {
                            ForEach(candidates, id: \.userId) { Text($0.displayName).tag($0.userId) }
                        }
                        Button(T("groups.add")) { Task { await add() } }.disabled(toAdd.isEmpty || busy)
                    }
                }
                Section {
                    Button(T("delete.group"), role: .destructive) { confirmDelete = true }
                }
                if !error.isEmpty { Text(error).font(.footnote).foregroundStyle(VeyqStyle.danger) }
            }
            .listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
            .background(VeyqScreenBackground())
            .navigationTitle(store.displayName(for: space))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button(T("common.close")) { dismiss() } } }
            .task { await reload() }
            .sheet(isPresented: $confirmDelete) {
                ConfirmDeleteView(name: store.displayName(for: space)) {
                    Task { await store.deleteGroup(space); onDeleted(); dismiss() }
                }
            }
        }
        .tint(VeyqStyle.accent)
    }

    private func roleBinding(_ m: Groups.GroupMember) -> Binding<String> {
        Binding(get: { m.role }, set: { newRole in if newRole != m.role { Task { await setRole(m, newRole) } } })
    }

    private func reload() async {
        do {
            members = try await Groups.listGroupMembers(apiBase: store.apiBase, session: store.session, tenantId: tid, scopeId: space.scopeId)
            let inGroup = Set(members.map { $0.userId })
            let tm = try await InviteMembers.listTenantMembers(apiBase: store.apiBase, token: store.session.sessionToken, tenantId: tid)
            candidates = tm.filter { $0.status == "ACTIVE" && !inGroup.contains($0.userId) }
            toAdd = candidates.first?.userId ?? ""
        } catch { self.error = error.localizedDescription }
    }

    private func add() async {
        guard let c = candidates.first(where: { $0.userId == toAdd }) else { return }
        busy = true; error = ""
        do {
            _ = try await Groups.addGroupMember(apiBase: store.apiBase, session: store.session, tenantId: tid, scopeId: space.scopeId, userId: c.userId, targetKemPublicKeyB64: c.identityPublicKey.kemPublicKey)
            await reload()
        } catch { self.error = error.localizedDescription }
        busy = false
    }

    private func setRole(_ m: Groups.GroupMember, _ role: String) async {
        busy = true; error = ""
        do { _ = try await Groups.setGroupMemberRole(apiBase: store.apiBase, session: store.session, tenantId: tid, scopeId: space.scopeId, userId: m.userId, role: role); await reload() }
        catch { self.error = error.localizedDescription }
        busy = false
    }

    private func remove(_ m: Groups.GroupMember) async {
        busy = true; error = ""
        do { try await Groups.removeGroupMember(apiBase: store.apiBase, session: store.session, tenantId: tid, scopeId: space.scopeId, userId: m.userId); await reload() }
        catch { self.error = error.localizedDescription }
        busy = false
    }
}
