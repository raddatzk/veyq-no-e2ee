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
    // Called on EVERY (re)connect so each handshake gets a fresh single-use relay ticket (a baked-in URL would
    // break reconnection once its ticket is consumed). Share connections return a constant URL.
    private let urlFactory: () async -> URL?
    // The one monitor. YSwift's transactSync already serializes doc ops; this lock guards every shared Swift
    // field below (task, lastSentSV, the awareness maps, local* presence state), which are otherwise mutated
    // from the URLSession delegate queue AND the editor/persistence callers concurrently. Compute outgoing
    // bytes under the lock, then send()/invoke callbacks OUTSIDE it (no network/UI while holding the monitor).
    private let lock = NSRecursiveLock()
    private func sync<T>(_ body: () -> T) -> T { lock.lock(); defer { lock.unlock() }; return body() }
    private var task: URLSessionWebSocketTask?
    private var lastSentSV: [UInt8] = []
    private var closed = false
    // Bumped at the start of every connect(); a superseded connect bails after its await, and a superseded
    // socket's delegate callbacks are ignored (task !== self.task), so overlapping retries can't leak a socket.
    private var generation = 0

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

    public init(urlFactory: @escaping () async -> URL?, contentKey: Data) {
        self.urlFactory = urlFactory
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
        // The read-diff-apply must be atomic: a concurrent edit (remote apply) between reading the old text and
        // the mutation would otherwise make the offsets stale and corrupt the text. Hold the lock across all three.
        let changed: Bool = sync {
            let oldChars = Array(self.currentText), newChars = Array(newText)
            if oldChars == newChars { return false }
            var p = 0
            while p < oldChars.count, p < newChars.count, oldChars[p] == newChars[p] { p += 1 }
            var s = 0
            while s < (oldChars.count - p), s < (newChars.count - p), oldChars[oldChars.count - 1 - s] == newChars[newChars.count - 1 - s] { s += 1 }
            let prefixBytes = String(oldChars[0 ..< p]).utf8.count
            let removedBytes = String(oldChars[p ..< (oldChars.count - s)]).utf8.count
            let inserted = String(newChars[p ..< (newChars.count - s)])
            self.doc.transactSync { txn in
                if removedBytes > 0 { self.text.removeRange(start: UInt32(prefixBytes), length: UInt32(removedBytes), in: txn) }
                if !inserted.isEmpty { self.text.insert(inserted, at: UInt32(prefixBytes), in: txn) }
            }
            return true
        }
        if changed { broadcastUpdate() }
    }

    /// Seed an empty shared doc from the sealed journal (call once after a short sync settle, like the web).
    public func seedIfEmpty(_ markdown: String) {
        if currentText.isEmpty, !markdown.isEmpty { applyLocalText(markdown) }
    }

    /// Publish this editor's identity (name + colour) to co-editors.
    public func setUser(name: String, color: String) {
        let data: Data? = sync {
            self.localUser = ["name": name, "color": color, "colorLight": color + "33"]
            return self.bumpAwarenessLocked()
        }
        if let data { send(data) }
    }
    /// Publish this editor's caret position (char index) so others can draw it.
    public func setCursor(_ index: Int?) {
        let data: Data? = sync {
            if self.localCursor == index { return nil }
            self.localCursor = index
            return self.bumpAwarenessLocked()
        }
        if let data { send(data) }
    }

    public func destroy() {
        let t: URLSessionWebSocketTask? = sync { self.closed = true; return self.task }
        t?.cancel(with: .normalClosure, reason: nil)
    }

    // MARK: - websocket

    private func connect() {
        let gen: Int = sync { generation += 1; return generation }
        Task { [weak self] in
            guard let self else { return }
            if self.sync({ self.closed || gen != self.generation }) { return }
            guard let url = await self.urlFactory() else { // ticket fetch failed — retry the whole connect
                self.scheduleReconnect(gen)
                return
            }
            if self.sync({ self.closed || gen != self.generation }) { return }
            let session = URLSession(configuration: .default, delegate: self, delegateQueue: nil)
            let t = session.webSocketTask(with: url)
            self.sync { self.task = t }
            t.resume()
            self.receive()
        }
    }

    /// Reconnect after a delay, but only if this generation is still current and we aren't torn down.
    private func scheduleReconnect(_ gen: Int) {
        if sync({ closed || gen != generation }) { return }
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { [weak self] in
            guard let self, self.sync({ gen == self.generation && !self.closed }) else { return }
            self.connect()
        }
    }

    public func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask, didOpenWithProtocol proto: String?) {
        if sync({ webSocketTask !== self.task || self.closed }) { return } // superseded/closing socket
        onStatus?(true)
        let (s1data, awData): (Data, Data?) = sync {
            let s1 = self.proto.handleConnectionStarted() // STEP_1 (our state vector) → peers send what we miss
            self.lastSentSV = self.stateVector()
            return (self.encode(sync: 0, payload: s1.buffer), self.localUser.isEmpty ? nil : self.encodeAwareness())
        }
        send(s1data)
        if let awData { send(awData) } // announce presence/cursor
    }

    public func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask, didCloseWith closeCode: URLSessionWebSocketTask.CloseCode, reason: Data?) {
        // Only the CURRENT socket closing triggers a reconnect — a superseded socket's close is ignored.
        let gen: Int? = sync { (webSocketTask === self.task && !self.closed) ? self.generation : nil }
        onStatus?(false)
        if let gen { scheduleReconnect(gen) }
    }

    private func receive() {
        let t: URLSessionWebSocketTask? = sync { self.task }
        t?.receive { [weak self] result in
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
            let reply: Data = sync { self.encode(sync: 1, payload: self.proto.handleStep1(payload).buffer) }
            send(reply)
        case 1, 2: // step2 / update → apply, then push the merged text to the editor
            sync {
                self.proto.handleStep2(payload) {}
                self.lastSentSV = self.stateVector()
            }
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
        let seq = (try? await JournalPersistence.loadJournalDoc(apiBase: api, session: s, scopeId: persistScopeId, nodeId: persistNodeId, contentKey: contentKey, doc: doc)) ?? 0
        sync { self.persistMaxSeq = seq; self.lastPersistedSV = self.stateVector() }
        onText?(currentText)
        return seq
    }

    /// Append the edits made since the last persist as one sealed update (local + any relay-applied; idempotent).
    public func flushPersist() async {
        guard let api = persistApiBase, let s = persistSession else { return }
        let diff: [UInt8] = sync { self.doc.transactSync { (try? $0.transactionEncodeStateAsUpdateFromSv(stateVector: self.lastPersistedSV)) ?? [] } }
        guard !diff.isEmpty else { return }
        if let seq = try? await JournalPersistence.appendJournalUpdate(apiBase: api, session: s, scopeId: persistScopeId, nodeId: persistNodeId, contentKey: contentKey, update: diff) {
            sync { self.persistMaxSeq = max(self.persistMaxSeq, seq); self.lastPersistedSV = self.stateVector() }
        }
    }

    /// Compact: write a fresh snapshot + the Markdown reader projection and prune the folded-in updates.
    public func compactPersisted() async {
        guard let api = persistApiBase, let s = persistSession else { return }
        // Reload the full log into the doc first so the snapshot covers every update <= coveredSeq (a seq another
        // editor appended that this doc never folded in would otherwise be pruned but missing from the snapshot).
        _ = await loadPersisted()
        let seq = sync { self.persistMaxSeq }
        try? await JournalPersistence.compactJournal(apiBase: api, session: s, scopeId: persistScopeId, nodeId: persistNodeId, contentKey: contentKey, doc: doc, markdown: currentText, coveredSeq: seq)
    }

    private func broadcastUpdate() {
        let data: Data? = sync {
            let update = self.doc.transactSync { txn in self.doc.diff(txn: txn, from: self.lastSentSV) }
            if update.isEmpty { return nil }
            self.lastSentSV = self.stateVector()
            return self.encode(sync: 2, payload: update)
        }
        if let data { send(data) }
    }

    private func stateVector() -> [UInt8] { doc.transactSync { $0.transactionStateVector() } }

    private func send(_ data: Data) { let t: URLSessionWebSocketTask? = sync { self.task }; t?.send(.data(data)) { _ in } }

    // MARK: - framing + AEAD (matches the web EncryptedRelayProvider)

    /// salt(16) || nonce(12) || AEAD(HKDF(contentKey, salt), inner) — fresh per-message sub-key (see V1.journalSeal).
    private func seal(_ inner: [UInt8]) -> Data { V1.journalSeal(contentKey: contentKey, plaintext: Data(inner)) }
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
    /// Bump the awareness clock and return the encoded presence frame to send (or nil if not connected). Caller
    /// MUST hold the lock (mutates localClock, reads localUser/localCursor/task); send the result outside the lock.
    private func bumpAwarenessLocked() -> Data? { localClock += 1; return task != nil ? encodeAwareness() : nil }
    private func applyAwareness(_ update: [UInt8]) {
        let snapshot: [Peer] = sync {
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
            return Array(peers.values)
        }
        onPeers?(snapshot)
    }
    private func open(_ data: Data) -> Data? { V1.journalOpen(contentKey: contentKey, blob: data) }

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
