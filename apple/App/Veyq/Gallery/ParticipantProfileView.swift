import SwiftUI
import VeyqCrypto

/// A participant's profile within the active space: their display name + every Memory/Chapter they appear in.
/// No separate user-profile endpoint exists (the server is blind to participants), so this scans the scope
/// client-side (Participants.listNodeHits) and matches by linkedUserId.
struct ParticipantProfileView: View {
    let apiBase: String
    let session: Login.UnlockedSession
    let scopeId: String
    let contentKey: Data
    let linkedUserId: String
    let fallbackName: String

    @State private var hits: [Participants.NodeHit] = []
    @State private var loading = true
    @State private var displayName = ""

    private var title: String { displayName.isEmpty ? fallbackName : displayName }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                HStack(spacing: 12) {
                    Text(String(title.prefix(1)).uppercased())
                        .font(.title2.weight(.bold)).foregroundStyle(.white)
                        .frame(width: 48, height: 48).background(VeyqStyle.accent, in: Circle())
                    Text(title).font(.title2.weight(.semibold)).foregroundStyle(VeyqStyle.text)
                }
                Text(T("participant.sharedMemories")).font(.caption.weight(.semibold)).foregroundStyle(VeyqStyle.muted)
                if loading {
                    ProgressView().tint(VeyqStyle.accent)
                } else if hits.isEmpty {
                    Text(T("participant.noMemories")).foregroundStyle(VeyqStyle.muted)
                } else {
                    ForEach(hits) { hit in
                        NavigationLink {
                            MemoryDetailView(apiBase: apiBase, session: session, scopeId: scopeId, contentKey: contentKey, node: hit.node, name: hit.name)
                        } label: {
                            HStack(spacing: 10) {
                                Text(hit.type == "CHAPTER" ? "📖" : hit.type == "FOLDER" ? "🗂" : "🖼")
                                Text(hit.name).foregroundStyle(VeyqStyle.text).lineLimit(1)
                                Spacer()
                                Image(systemName: "chevron.right").foregroundStyle(VeyqStyle.muted).font(.caption)
                            }
                            .padding(10)
                            .background(VeyqStyle.elevatedSurface)
                            .clipShape(RoundedRectangle(cornerRadius: VeyqStyle.radiusSm, style: .continuous))
                            .overlay(RoundedRectangle(cornerRadius: VeyqStyle.radiusSm, style: .continuous).strokeBorder(VeyqStyle.line))
                        }
                    }
                }
            }
            .frame(maxWidth: 720)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(16)
        }
        .background(VeyqScreenBackground())
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .task {
            let h = (try? await Participants.listNodeHits(apiBase: apiBase, session: session, scopeId: scopeId, contentKey: contentKey, linkedUserId: linkedUserId)) ?? []
            hits = h
            displayName = h.first?.participant.displayName ?? fallbackName
            loading = false
        }
    }
}
