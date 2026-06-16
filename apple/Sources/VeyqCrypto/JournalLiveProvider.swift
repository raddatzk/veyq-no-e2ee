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

    public var onText: ((String) -> Void)?
    public var onStatus: ((Bool) -> Void)?

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
        guard msgType == 0 else { return } // 0 = sync; 1 = awareness (cursors) — ignored on iOS for now
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

    private func broadcastUpdate() {
        let update = doc.transactSync { txn in self.doc.diff(txn: txn, from: self.lastSentSV) }
        if !update.isEmpty { send(encode(sync: 2, payload: update)); lastSentSV = stateVector() }
    }

    private func stateVector() -> [UInt8] { doc.transactSync { $0.transactionStateVector() } }

    private func send(_ data: Data) { task?.send(.data(data)) { _ in } }

    // MARK: - framing + AEAD (matches the web EncryptedRelayProvider)

    /// [varuint 0=sync][varuint syncKind][varUint8Array payload] → AEAD-sealed under the content key.
    private func encode(sync syncKind: UInt, payload: [UInt8]) -> Data {
        var enc: [UInt8] = []
        writeVarUint(&enc, 0)
        writeVarUint(&enc, syncKind)
        writeVarUint8Array(&enc, payload)
        let nonce = V1.randomBytes(12)
        let sealed = (try? V1.aeadSeal(key: contentKey, nonce: nonce, plaintext: Data(enc), aad: Data())) ?? Data()
        return nonce + sealed
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
