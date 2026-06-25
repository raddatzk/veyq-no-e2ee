import SwiftUI
import AVFoundation

/// A full-screen camera QR scanner used to read an Emergency Kit QR at login. Emits the decoded string
/// once, then the caller dismisses it. Decoding (EmergencyKit.parse) happens in the caller.
struct QrScannerScreen: View {
    let onDecoded: (String) -> Void
    let onClose: () -> Void

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            QrScannerView(onDecoded: onDecoded).ignoresSafeArea()
            VeyqStyle.mediaBackdrop.opacity(0.22).ignoresSafeArea()

            RoundedRectangle(cornerRadius: VeyqStyle.radius, style: .continuous)
                .strokeBorder(VeyqStyle.accent, lineWidth: 2)
                .frame(width: 260, height: 260)
                .shadow(color: VeyqStyle.accent.opacity(0.24), radius: 18, y: 8)

            VStack {
                Spacer()
                VStack(spacing: 12) {
                    Label(T("kit.scanHint"), systemImage: "qrcode.viewfinder")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(VeyqStyle.onStrong)
                        .multilineTextAlignment(.center)
                    Button(T("common.cancel")) { onClose() }
                        .buttonStyle(.borderedProminent)
                        .tint(VeyqStyle.accent)
                }
                .padding(16)
                .frame(maxWidth: 360)
                .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: VeyqStyle.radius, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: VeyqStyle.radius, style: .continuous).strokeBorder(.white.opacity(0.24)))
                .padding(.bottom, 32)
            }
            .padding()
        }
    }
}

/// Bridges an AVFoundation capture session that recognises QR codes into SwiftUI.
struct QrScannerView: UIViewControllerRepresentable {
    let onDecoded: (String) -> Void

    func makeUIViewController(context: Context) -> ScannerViewController {
        let vc = ScannerViewController()
        vc.onDecoded = onDecoded
        return vc
    }

    func updateUIViewController(_ vc: ScannerViewController, context: Context) {}
}

final class ScannerViewController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    var onDecoded: ((String) -> Void)?
    private let session = AVCaptureSession()
    private var preview: AVCaptureVideoPreviewLayer?
    private var handled = false

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        guard let device = AVCaptureDevice.default(for: .video),
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input) else { return }
        session.addInput(input)
        let output = AVCaptureMetadataOutput()
        guard session.canAddOutput(output) else { return }
        session.addOutput(output)
        output.setMetadataObjectsDelegate(self, queue: .main)
        output.metadataObjectTypes = [.qr]
        let layer = AVCaptureVideoPreviewLayer(session: session)
        layer.videoGravity = .resizeAspectFill
        view.layer.addSublayer(layer)
        preview = layer
        // startRunning() blocks — run it off the main thread. AVCaptureSession is internally thread-safe.
        nonisolated(unsafe) let s = session
        DispatchQueue.global(qos: .userInitiated).async { s.startRunning() }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        preview?.frame = view.bounds
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        nonisolated(unsafe) let s = session
        DispatchQueue.global(qos: .userInitiated).async { s.stopRunning() }
    }

    // The delegate protocol isn't main-actor-isolated, so this must be nonisolated; hop back to the main
    // actor to touch UI state. (The output delegate queue is already .main, but Swift 6 requires this.)
    nonisolated func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject], from connection: AVCaptureConnection) {
        guard let obj = metadataObjects.first as? AVMetadataMachineReadableCodeObject, let value = obj.stringValue else { return }
        Task { @MainActor in
            guard !self.handled else { return }
            self.handled = true
            self.onDecoded?(value)
        }
    }
}
