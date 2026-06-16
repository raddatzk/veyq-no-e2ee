import SwiftUI
import VeyqCrypto

/// Live, operator-blind collaborative journal editing on iOS — the SAME Yjs CRDT as the web (via YSwift/Yrs)
/// over the encrypted relay. A plain TextEditor is diffed into minimal CRDT ops; remote merges push back in.
@MainActor
final class LiveJournalModel: ObservableObject {
    @Published var text = ""
    @Published var connected = false
    @Published var peers: [JournalLiveProvider.Peer] = []
    private var provider: JournalLiveProvider?
    private var ready = false
    private var flushWork: DispatchWorkItem?

    private static let colors = ["#5b4df5", "#0ea5e9", "#10b981", "#f59e0b", "#ef4444", "#ec4899", "#8b5cf6"]

    func start(store: GalleryStore, initial: String) {
        text = initial
        let wsBase = store.apiBase.replacingOccurrences(of: "http", with: "ws")
        guard let url = URL(string: "\(wsBase)/ws/journal/\(store.scopeId)/\(store.nodeId)?token=\(store.session.sessionToken)") else { return }
        let p = JournalLiveProvider(url: url, contentKey: store.contentKey)
        p.onStatus = { [weak self] c in Task { @MainActor in self?.connected = c } }
        p.onText = { [weak self] s in Task { @MainActor in guard let self else { return }; if self.text != s { self.text = s } } }
        p.onPeers = { [weak self] list in Task { @MainActor in self?.peers = list } }
        p.setUser(name: store.session.user.displayName, color: Self.colors.randomElement() ?? "#5b4df5")
        p.configurePersistence(apiBase: store.apiBase, session: store.session, scopeId: store.scopeId, nodeId: store.nodeId)
        provider = p
        // Reconstruct from the operator-blind store, then bootstrap a legacy Markdown-only journal into the CRDT.
        Task { @MainActor in
            await p.loadPersisted()
            if p.currentText.isEmpty, !initial.isEmpty { p.applyLocalText(initial); self.scheduleFlush() }
            self.text = p.currentText
            self.ready = true
        }
    }

    func onUserEdit(_ s: String) {
        guard ready else { return } // ignore edits until the initial load has settled
        provider?.applyLocalText(s)
        scheduleFlush()
    }
    func onCursor(_ index: Int) { provider?.setCursor(index) }

    /// Coalesce local edits into a debounced append to the store (the live relay broadcast is immediate).
    private func scheduleFlush() {
        flushWork?.cancel()
        let w = DispatchWorkItem { [weak self] in Task { await self?.provider?.flushPersist() } }
        flushWork = w
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.2, execute: w)
    }

    func stop(save: @escaping (String) -> Void) {
        flushWork?.cancel()
        let p = provider
        provider = nil
        Task { @MainActor in
            await p?.flushPersist()
            await p?.compactPersisted() // refresh snapshot + Markdown projection, prune the folded updates
            let final = p?.currentText ?? self.text
            p?.destroy()
            save(final)
        }
    }
}

struct LiveJournalView: View {
    @ObservedObject var store: GalleryStore
    let onClose: (String) -> Void
    @StateObject private var model = LiveJournalModel()

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 6) {
                Circle().fill(model.connected ? Color.green : VeyqStyle.muted).frame(width: 8, height: 8)
                Text(model.connected ? T("journal.live") : T("journal.connecting")).font(.footnote).foregroundStyle(VeyqStyle.muted)
                ForEach(model.peers, id: \.clientId) { peer in
                    HStack(spacing: 3) {
                        Circle().fill(Color(hex: UInt(peer.color.drop { $0 == "#" }, radix: 16) ?? 0x888888)).frame(width: 7, height: 7)
                        Text(peer.name).font(.system(size: 11)).foregroundStyle(VeyqStyle.muted)
                    }
                }
                Spacer()
                Button(T("journal.done")) { model.stop { onClose($0) } }.tint(VeyqStyle.accent)
            }
            LiveTextEditor(
                text: Binding(get: { model.text }, set: { model.text = $0 }),
                peers: model.peers,
                onTextChange: { v in model.text = v; model.onUserEdit(v) },
                onCursorChange: { model.onCursor($0) },
            )
            .frame(minHeight: 280)
            .padding(8)
            .background(VeyqStyle.surface)
            .clipShape(RoundedRectangle(cornerRadius: VeyqStyle.radius))
            .overlay(RoundedRectangle(cornerRadius: VeyqStyle.radius).strokeBorder(VeyqStyle.line))
        }
        .onAppear { model.start(store: store, initial: store.journalMarkdown) }
    }
}
