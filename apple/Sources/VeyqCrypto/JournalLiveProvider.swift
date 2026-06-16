import Foundation
import YSwift

/// Operator-blind live-journal client for iOS, wire-compatible with the web (Yjs). It runs a Yrs document
/// (via YSwift), speaks the SAME y-websocket sync framing the web uses, and AEAD-seals every message under
/// the scope content key before it hits the relay — so iOS and web edit the SAME CRDT through the dumb,
/// ciphertext-only relay. The editor binds via plain strings: `onText` pushes remote changes up;
/// `applyLocalText` diffs the editor's new value into minimal CRDT ops and broadcasts them.
public final class JournalLiveProvider: NSObject, URLSessionWebSocketDelegate {
    private let doc = YDocument()
    private let text: YText
    private let proto: YProtocol
    private let contentKey: Data
    private let url: URL
    private var task: URLSessionWebSocketTask?
    private var lastSentSV: [UInt8] = []
    private var closed = false

    // Awareness (presence + cursor) — the y-protocols/awareness wire format, so co-editors and their cursor
    // positions sync across web/iOS/Android. The cursor is a plain UTF-16 char index (`cursorIndex`); the web's
    // yCollab uses Yjs relative positions, so cross-family shows presence (the `user` field) and same-family
    // shows exact carets.
    public struct Peer: Equatable, Sendable { public let clientId: UInt; public let name: String; public let color: String; public let cursor: Int? }
    private let localClientId: UInt = UInt(UInt32.random(in: 1 ... UInt32.max))
    private var localClock: UInt = 0
    private var localUser: [String: String] = [:]
    private var localCursor: Int?
    private var peers: [UInt: Peer] = [:]
    private var peerClocks: [UInt: UInt] = [:]

    // Operator-blind persistence (the CRDT store: sealed snapshot + sealed update log). Load reconstructs the
    // doc; flush appends local edits since the last persist; compact writes a fresh snapshot + Markdown projection.
    private var persistApiBase: String?
    private var persistSession: Login.UnlockedSession?
    private var persistScopeId = ""
    private var persistNodeId = ""
    private var lastPersistedSV: [UInt8] = []
    public private(set) var persistMaxSeq = 0

    public var onText: ((String) -> Void)?
    public var onStatus: ((Bool) -> Void)?
    public var onPeers: (([Peer]) -> Void)?

    public init(url: URL, contentKey: Data) {
        self.url = url
        self.contentKey = contentKey
        self.text = doc.getOrCreateText(named: "journal")
        self.proto = YProtocol(document: doc)
        super.init()
        connect()
    }

    public var currentText: String { doc.transactSync { self.text.getString(in: $0) } }

    /// Apply the editor's new value as minimal insert/remove ops (UTF-8 offsets, as YText expects) and
    /// broadcast the resulting CRDT update. No-op if unchanged.
    public func applyLocalText(_ newText: String) {
        let oldChars = Array(currentText), newChars = Array(newText)
        if oldChars == newChars { return }
        var p = 0
        while p < oldChars.count, p < newChars.count, oldChars[p] == newChars[p] { p += 1 }
        var s = 0
        while s < (oldChars.count - p), s < (newChars.count - p), oldChars[oldChars.count - 1 - s] == newChars[newChars.count - 1 - s] { s += 1 }
        let prefixBytes = String(oldChars[0 ..< p]).utf8.count
        let removedBytes = String(oldChars[p ..< (oldChars.count - s)]).utf8.count
        let inserted = String(newChars[p ..< (newChars.count - s)])
        doc.transactSync { txn in
            if removedBytes > 0 { self.text.removeRange(start: UInt32(prefixBytes), length: UInt32(removedBytes), in: txn) }
            if !inserted.isEmpty { self.text.insert(inserted, at: UInt32(prefixBytes), in: txn) }
        }
        broadcastUpdate()
    }

    /// Seed an empty shared doc from the sealed journal (call once after a short sync settle, like the web).
    public func seedIfEmpty(_ markdown: String) {
        if currentText.isEmpty, !markdown.isEmpty { applyLocalText(markdown) }
    }

    /// Publish this editor's identity (name + colour) to co-editors.
    public func setUser(name: String, color: String) {
        localUser = ["name": name, "color": color, "colorLight": color + "33"]
        bumpAwareness()
    }
    /// Publish this editor's caret position (char index) so others can draw it.
    public func setCursor(_ index: Int?) {
        if localCursor == index { return }
        localCursor = index
        bumpAwareness()
    }

    public func destroy() {
        closed = true
        task?.cancel(with: .normalClosure, reason: nil)
    }

    // MARK: - websocket

    private func connect() {
        let session = URLSession(configuration: .default, delegate: self, delegateQueue: nil)
        let t = session.webSocketTask(with: url)
        task = t
        t.resume()
        receive()
    }

    public func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask, didOpenWithProtocol proto: String?) {
        onStatus?(true)
        let s1 = self.proto.handleConnectionStarted() // STEP_1 (our state vector) → peers send what we miss
        send(encode(sync: 0, payload: s1.buffer))
        lastSentSV = stateVector()
        if !localUser.isEmpty { send(encodeAwareness()) } // announce presence/cursor
    }

    public func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask, didCloseWith closeCode: URLSessionWebSocketTask.CloseCode, reason: Data?) {
        onStatus?(false)
        if !closed { DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { [weak self] in self?.connect() } }
    }

    private func receive() {
        task?.receive { [weak self] result in
            guard let self else { return }
            if case let .success(message) = result {
                if case let .data(data) = message { self.handle(data) }
                self.receive()
            }
        }
    }

    private func handle(_ data: Data) {
        guard let plain = open(data) else { return }
        let bytes = [UInt8](plain)
        var pos = 0
        let msgType = readVarUint(bytes, &pos)
        if msgType == 1 { // awareness (presence + cursor)
            if let upd = readVarUint8Array(bytes, &pos) { applyAwareness(upd) }
            return
        }
        guard msgType == 0 else { return } // 0 = sync
        let syncKind = readVarUint(bytes, &pos)
        guard let payload = readVarUint8Array(bytes, &pos) else { return }
        switch syncKind {
        case 0: // step1 → reply with step2 (the update covering what the peer is missing)
            let reply = proto.handleStep1(payload)
            send(encode(sync: 1, payload: reply.buffer))
        case 1, 2: // step2 / update → apply, then push the merged text to the editor
            proto.handleStep2(payload) {}
            lastSentSV = stateVector()
            onText?(currentText)
        default: break
        }
    }

    // MARK: - operator-blind persistence

    public func configurePersistence(apiBase: String, session: Login.UnlockedSession, scopeId: String, nodeId: String) {
        persistApiBase = apiBase; persistSession = session; persistScopeId = scopeId; persistNodeId = nodeId
    }

    /// Reconstruct the doc from the store (snapshot + update log); returns the max seq applied.
    @discardableResult
    public func loadPersisted() async -> Int {
        guard let api = persistApiBase, let s = persistSession else { return 0 }
        persistMaxSeq = (try? await JournalPersistence.loadJournalDoc(apiBase: api, session: s, scopeId: persistScopeId, nodeId: persistNodeId, contentKey: contentKey, doc: doc)) ?? 0
        lastPersistedSV = stateVector()
        onText?(currentText)
        return persistMaxSeq
    }

    /// Append the edits made since the last persist as one sealed update (local + any relay-applied; idempotent).
    public func flushPersist() async {
        guard let api = persistApiBase, let s = persistSession else { return }
        let diff = doc.transactSync { (try? $0.transactionEncodeStateAsUpdateFromSv(stateVector: self.lastPersistedSV)) ?? [] }
        guard !diff.isEmpty else { return }
        if let seq = try? await JournalPersistence.appendJournalUpdate(apiBase: api, session: s, scopeId: persistScopeId, nodeId: persistNodeId, contentKey: contentKey, update: diff) {
            persistMaxSeq = max(persistMaxSeq, seq)
            lastPersistedSV = stateVector()
        }
    }

    /// Compact: write a fresh snapshot + the Markdown reader projection and prune the folded-in updates.
    public func compactPersisted() async {
        guard let api = persistApiBase, let s = persistSession else { return }
        try? await JournalPersistence.compactJournal(apiBase: api, session: s, scopeId: persistScopeId, nodeId: persistNodeId, contentKey: contentKey, doc: doc, markdown: currentText, coveredSeq: persistMaxSeq)
    }

    private func broadcastUpdate() {
        let update = doc.transactSync { txn in self.doc.diff(txn: txn, from: self.lastSentSV) }
        if !update.isEmpty { send(encode(sync: 2, payload: update)); lastSentSV = stateVector() }
    }

    private func stateVector() -> [UInt8] { doc.transactSync { $0.transactionStateVector() } }

    private func send(_ data: Data) { task?.send(.data(data)) { _ in } }

    // MARK: - framing + AEAD (matches the web EncryptedRelayProvider)

    /// nonce(12) || AEAD(contentKey, inner) — the outer seal every relay message uses.
    private func seal(_ inner: [UInt8]) -> Data {
        let nonce = V1.randomBytes(12)
        return nonce + ((try? V1.aeadSeal(key: contentKey, nonce: nonce, plaintext: Data(inner), aad: Data())) ?? Data())
    }
    /// [varuint 0=sync][varuint syncKind][varUint8Array payload] → sealed.
    private func encode(sync syncKind: UInt, payload: [UInt8]) -> Data {
        var enc: [UInt8] = []
        writeVarUint(&enc, 0); writeVarUint(&enc, syncKind); writeVarUint8Array(&enc, payload)
        return seal(enc)
    }
    /// [varuint 1=awareness][varUint8Array update], where update = [count][clientId][clock][varString(json)].
    private func encodeAwareness() -> Data {
        var state: [String: Any] = ["user": localUser]
        if let c = localCursor { state["cursorIndex"] = c }
        let json = (try? JSONSerialization.data(withJSONObject: state)) ?? Data("{}".utf8)
        var aw: [UInt8] = []
        writeVarUint(&aw, 1); writeVarUint(&aw, localClientId); writeVarUint(&aw, localClock)
        writeVarUint8Array(&aw, [UInt8](json))
        var enc: [UInt8] = []
        writeVarUint(&enc, 1); writeVarUint8Array(&enc, aw)
        return seal(enc)
    }
    private func bumpAwareness() { localClock += 1; if task != nil { send(encodeAwareness()) } }
    private func applyAwareness(_ update: [UInt8]) {
        var p = 0
        let count = readVarUint(update, &p)
        var i: UInt = 0
        while i < count {
            i += 1
            let clientId = readVarUint(update, &p)
            let clock = readVarUint(update, &p)
            guard let jsonBytes = readVarUint8Array(update, &p) else { break }
            if clientId == localClientId { continue }
            if let prev = peerClocks[clientId], prev >= clock { continue }
            peerClocks[clientId] = clock
            if let obj = try? JSONSerialization.jsonObject(with: Data(jsonBytes)) as? [String: Any], let user = obj["user"] as? [String: Any] {
                peers[clientId] = Peer(clientId: clientId, name: (user["name"] as? String) ?? "?", color: (user["color"] as? String) ?? "#888888", cursor: obj["cursorIndex"] as? Int)
            } else {
                peers.removeValue(forKey: clientId)
            }
        }
        onPeers?(Array(peers.values))
    }
    private func open(_ data: Data) -> Data? {
        guard data.count > 12 else { return nil }
        return try? V1.aeadOpen(key: contentKey, nonce: data.prefix(12), aead: data.dropFirst(12), aad: Data())
    }

    private func writeVarUint(_ out: inout [UInt8], _ value: UInt) {
        var v = value
        while v > 0x7F { out.append(UInt8((v & 0x7F) | 0x80)); v >>= 7 }
        out.append(UInt8(v & 0x7F))
    }
    private func writeVarUint8Array(_ out: inout [UInt8], _ bytes: [UInt8]) {
        writeVarUint(&out, UInt(bytes.count)); out.append(contentsOf: bytes)
    }
    private func readVarUint(_ bytes: [UInt8], _ pos: inout Int) -> UInt {
        var num: UInt = 0, shift: UInt = 0
        while pos < bytes.count {
            let b = bytes[pos]; pos += 1
            num |= UInt(b & 0x7F) << shift
            if b < 0x80 { break }
            shift += 7
        }
        return num
    }
    private func readVarUint8Array(_ bytes: [UInt8], _ pos: inout Int) -> [UInt8]? {
        let len = Int(readVarUint(bytes, &pos))
        guard pos + len <= bytes.count else { return nil }
        defer { pos += len }
        return Array(bytes[pos ..< pos + len])
    }
}
