import SwiftUI
import VeyqCrypto

/// Live, operator-blind collaborative journal editing on iOS — the SAME Yjs CRDT as the web (via YSwift/Yrs)
/// over the encrypted relay. A plain TextEditor is diffed into minimal CRDT ops; remote merges push back in.
@MainActor
final class LiveJournalModel: ObservableObject {
    @Published var text = ""
    @Published var connected = false
    private var provider: JournalLiveProvider?
    private var ready = false

    func start(store: GalleryStore, initial: String) {
        text = initial
        let wsBase = store.apiBase.replacingOccurrences(of: "http", with: "ws")
        guard let url = URL(string: "\(wsBase)/ws/journal/\(store.scopeId)/\(store.nodeId)?token=\(store.session.sessionToken)") else { return }
        let p = JournalLiveProvider(url: url, contentKey: store.contentKey)
        p.onStatus = { [weak self] c in Task { @MainActor in self?.connected = c } }
        p.onText = { [weak self] s in Task { @MainActor in guard let self else { return }; if self.text != s { self.text = s } } }
        provider = p
        // Seed the shared doc from the sealed journal once sync with any peers has settled, then mirror back.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) { [weak self] in
            guard let self, let p = self.provider else { return }
            p.seedIfEmpty(initial)
            self.text = p.currentText
            self.ready = true
        }
    }

    func onUserEdit(_ s: String) {
        guard ready else { return } // ignore edits until the initial seed/sync has settled
        provider?.applyLocalText(s)
    }

    func stop(save: @escaping (String) -> Void) {
        let final = provider?.currentText ?? text
        provider?.destroy()
        provider = nil
        save(final)
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
                Spacer()
                Button(T("journal.done")) { model.stop { onClose($0) } }.tint(VeyqStyle.accent)
            }
            TextEditor(text: Binding(get: { model.text }, set: { v in model.text = v; model.onUserEdit(v) }))
                .font(.system(size: 15, design: .monospaced))
                .foregroundStyle(VeyqStyle.text)
                .scrollContentBackground(.hidden)
                .frame(minHeight: 280)
                .padding(8)
                .background(VeyqStyle.surface)
                .clipShape(RoundedRectangle(cornerRadius: VeyqStyle.radius))
                .overlay(RoundedRectangle(cornerRadius: VeyqStyle.radius).strokeBorder(VeyqStyle.line))
        }
        .onAppear { model.start(store: store, initial: store.journalMarkdown) }
    }
}
