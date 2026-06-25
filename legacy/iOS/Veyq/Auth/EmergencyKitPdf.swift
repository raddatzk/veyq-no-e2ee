import UIKit
import CoreImage.CIFilterBuiltins
import VeyqCrypto

/// Build the Emergency Kit PDF on iOS: human-readable rows + a QR encoding the cross-platform `veyqkit:`
/// token, so the Secret Key born during mobile registration can be re-imported by scanning it on any
/// device. Mirrors the web kit (downloadKitPdf) and the Android EmergencyKitPdf; shared via ActivityView.
enum EmergencyKitPdf {
    static func build(_ data: EmergencyKit.KitData, displayName: String) -> URL {
        let payload = EmergencyKit.payload(data)
        let qr = qrImage(payload)
        let bounds = CGRect(x: 0, y: 0, width: 595, height: 842) // A4 @ 72dpi
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("veyq-emergency-kit-\(data.email).pdf")
        let renderer = UIGraphicsPDFRenderer(bounds: bounds)
        let dark = UIColor(white: 0.08, alpha: 1)
        let mutedColor = UIColor(white: 0.35, alpha: 1)
        try? renderer.writePDF(to: url) { ctx in
            ctx.beginPage()
            draw("veyq — Emergency Kit", CGPoint(x: 56, y: 54), .boldSystemFont(ofSize: 24), dark)
            draw("Keep this safe. With your Secret Key + your password you can always sign in again.", CGPoint(x: 56, y: 90), .systemFont(ofSize: 12), mutedColor)
            var y: CGFloat = 136
            for (k, v) in [("Name", displayName), ("Instance", data.instance.isEmpty ? "(same as the website)" : data.instance), ("Email", data.email)] {
                draw(k, CGPoint(x: 56, y: y), .boldSystemFont(ofSize: 13), dark)
                draw(v, CGPoint(x: 170, y: y), .systemFont(ofSize: 13), dark)
                y += 26
            }
            draw("Secret Key", CGPoint(x: 56, y: y), .boldSystemFont(ofSize: 13), dark)
            let mono = UIFont.monospacedSystemFont(ofSize: 12, weight: .regular)
            for chunk in data.secretKey.kitChunks(48) { draw(chunk, CGPoint(x: 170, y: y), mono, dark); y += 17 }
            y += 24
            qr.draw(in: CGRect(x: 56, y: y, width: 150, height: 150))
            draw("Scan this code on another device", CGPoint(x: 220, y: y + 56), .systemFont(ofSize: 12), mutedColor)
            draw("to fill in your email + Secret Key.", CGPoint(x: 220, y: y + 74), .systemFont(ofSize: 12), mutedColor)
        }
        return url
    }

    private static func draw(_ s: String, _ p: CGPoint, _ font: UIFont, _ color: UIColor) {
        (s as NSString).draw(at: p, withAttributes: [.font: font, .foregroundColor: color])
    }

    static func qrImage(_ s: String) -> UIImage {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(s.utf8)
        filter.correctionLevel = "M"
        guard let out = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 10, y: 10)),
              let cg = CIContext().createCGImage(out, from: out.extent) else { return UIImage() }
        return UIImage(cgImage: cg)
    }
}

private extension String {
    /// Split into fixed-width chunks so a long Secret Key wraps cleanly on the page.
    func kitChunks(_ n: Int) -> [String] {
        var res: [String] = []
        var i = startIndex
        while i < endIndex {
            let j = index(i, offsetBy: n, limitedBy: endIndex) ?? endIndex
            res.append(String(self[i ..< j]))
            i = j
        }
        return res
    }
}
