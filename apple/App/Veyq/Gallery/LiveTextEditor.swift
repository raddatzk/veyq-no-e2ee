import SwiftUI
import UIKit
import VeyqCrypto

/// A UITextView-backed editor that can draw OTHER editors' live carets (a coloured line + name tag) at their
/// reported char index — SwiftUI's TextEditor exposes no caret geometry, so we drop to UIKit. Local text +
/// cursor changes are reported back up; remote peers are overlaid.
struct LiveTextEditor: UIViewRepresentable {
    @Binding var text: String
    var peers: [JournalLiveProvider.Peer]
    var onTextChange: (String) -> Void
    var onCursorChange: (Int) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIView(context: Context) -> UITextView {
        let tv = UITextView()
        tv.delegate = context.coordinator
        tv.font = .monospacedSystemFont(ofSize: 15, weight: .regular)
        tv.backgroundColor = .clear
        tv.textColor = UIColor(VeyqStyle.text)
        tv.autocorrectionType = .yes
        tv.autocapitalizationType = .sentences
        return tv
    }

    func updateUIView(_ tv: UITextView, context: Context) {
        if tv.text != text {
            let sel = tv.selectedRange
            tv.text = text
            // keep the caret in range after a remote merge (best-effort)
            tv.selectedRange = NSRange(location: min(sel.location, (text as NSString).length), length: 0)
        }
        context.coordinator.renderCarets(on: tv, peers: peers)
    }

    final class Coordinator: NSObject, UITextViewDelegate {
        private let parent: LiveTextEditor
        private var caretViews: [UIView] = []
        init(_ parent: LiveTextEditor) { self.parent = parent }

        func textViewDidChange(_ tv: UITextView) {
            parent.onTextChange(tv.text)
            parent.onCursorChange(tv.selectedRange.location)
        }
        func textViewDidChangeSelection(_ tv: UITextView) {
            parent.onCursorChange(tv.selectedRange.location)
        }

        /// Overlay each peer's caret (a 2pt coloured bar + a small name chip) at its char index.
        func renderCarets(on tv: UITextView, peers: [JournalLiveProvider.Peer]) {
            caretViews.forEach { $0.removeFromSuperview() }
            caretViews.removeAll()
            let len = (tv.text as NSString).length
            for peer in peers {
                guard let idx = peer.cursor, idx >= 0, idx <= len,
                      let pos = tv.position(from: tv.beginningOfDocument, offset: idx) else { continue }
                let rect = tv.caretRect(for: pos)
                guard rect.origin.x.isFinite, rect.origin.y.isFinite else { continue }
                let color = UIColor(hex: peer.color) ?? .systemBlue
                let bar = UIView(frame: CGRect(x: rect.minX, y: rect.minY, width: 2, height: rect.height))
                bar.backgroundColor = color
                tv.addSubview(bar); caretViews.append(bar)
                let label = PaddedLabel()
                label.text = peer.name
                label.font = .systemFont(ofSize: 10, weight: .semibold)
                label.textColor = .white
                label.backgroundColor = color
                label.layer.cornerRadius = 3
                label.layer.masksToBounds = true
                label.sizeToFit()
                label.frame = CGRect(x: rect.minX, y: max(0, rect.minY - 14), width: label.frame.width + 8, height: 13)
                tv.addSubview(label); caretViews.append(label)
            }
        }
    }
}

private final class PaddedLabel: UILabel {
    override func drawText(in rect: CGRect) { super.drawText(in: rect.insetBy(dx: 4, dy: 0)) }
}

private extension UIColor {
    convenience init?(hex: String) {
        var s = hex.trimmingCharacters(in: CharacterSet(charactersIn: "#"))
        guard s.count == 6, let v = UInt32(s, radix: 16) else { return nil }
        self.init(red: CGFloat((v >> 16) & 0xff) / 255, green: CGFloat((v >> 8) & 0xff) / 255, blue: CGFloat(v & 0xff) / 255, alpha: 1)
    }
}
