import AVFoundation
import SwiftUI
import VeyqKit

/// A live QR scanner over **AVFoundation** — universal (iOS / iPadOS + the macOS camera). Decodes a
/// `veyqkit:` QR and calls `onResult` with the parsed kit; non-kit QR codes are ignored (keep scanning).
/// Permission-denied / no-camera surface a message and let the user fall back to upload/paste. Operator-
/// blind: the decode is on-device; only the Secret Key (and email) leave this sheet, into local fields.
struct KitScanner: View {
    let onResult: (KitData) -> Void
    let onCancel: () -> Void

    @StateObject private var camera = CameraScanController()

    var body: some View {
        ZStack {
            VeyqStyle.bg.ignoresSafeArea()
            VStack(spacing: 16) {
                Text("Scan your Emergency Kit")
                    .font(.system(size: 19, weight: .bold)).foregroundStyle(VeyqStyle.text)

                switch camera.status {
                case .scanning:
                    CameraPreview(controller: camera)
                        .frame(maxWidth: 360)
                        .aspectRatio(1, contentMode: .fit)
                        .clipShape(RoundedRectangle(cornerRadius: VeyqStyle.radius, style: .continuous))
                        .overlay(RoundedRectangle(cornerRadius: VeyqStyle.radius, style: .continuous)
                            .strokeBorder(VeyqStyle.line))
                    Text("Point the camera at the QR code on your kit.")
                        .font(.system(size: 13)).foregroundStyle(VeyqStyle.muted)
                case .denied:
                    MessageBox(text: "Camera access is off. Allow it in Settings, or upload the kit instead.")
                        .frame(maxWidth: 360)
                case .unavailable:
                    MessageBox(text: "No camera available here. Upload the kit instead.")
                        .frame(maxWidth: 360)
                }

                Button("Cancel") { onCancel() }
                    .font(.system(size: 14, weight: .medium)).foregroundStyle(VeyqStyle.accent)
                    .buttonStyle(.plain).accessibilityIdentifier("Cancel")
            }
            .padding(24)
            .frame(maxWidth: 440)
        }
        .onAppear { camera.onFound = handle; camera.start() }
        .onDisappear { camera.stop() }
    }

    /// A decoded QR string — fill only if it's a real kit; otherwise keep scanning (ignore foreign codes).
    private func handle(_ text: String) {
        guard let kit = parseKit(text) else { return }
        camera.stop()
        onResult(kit)
    }
}

/// Owns the `AVCaptureSession`: requests camera permission, wires a `.qr` metadata output, and reports the
/// first decoded string. `@MainActor` for the `@Published` status; the session runs on a background queue.
@MainActor
final class CameraScanController: NSObject, ObservableObject, AVCaptureMetadataOutputObjectsDelegate {
    enum Status { case scanning, denied, unavailable }

    @Published var status: Status = .scanning
    // AVCaptureSession is thread-safe for configuration / start / stop but not `Sendable`; the session
    // queue serialises start/stop off the main actor, so opt this reference out of isolation checking.
    nonisolated(unsafe) let session = AVCaptureSession()
    private let sessionQueue = DispatchQueue(label: "me.veyq.kitscanner.session")
    var onFound: ((String) -> Void)?
    private var delivered = false

    /// Begin scanning, requesting camera access first if needed.
    func start() {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            configure()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { granted in
                Task { @MainActor in granted ? self.configure() : (self.status = .denied) }
            }
        default:
            status = .denied
        }
    }

    func stop() {
        sessionQueue.async { [session] in if session.isRunning { session.stopRunning() } }
    }

    private func configure() {
        guard let device = AVCaptureDevice.default(for: .video),
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input)
        else { status = .unavailable; return }
        session.addInput(input)

        let output = AVCaptureMetadataOutput()
        guard session.canAddOutput(output) else { status = .unavailable; return }
        session.addOutput(output)
        output.setMetadataObjectsDelegate(self, queue: .main)
        output.metadataObjectTypes = [.qr]

        status = .scanning
        sessionQueue.async { [session] in session.startRunning() }
    }

    nonisolated func metadataOutput(
        _ output: AVCaptureMetadataOutput,
        didOutput metadataObjects: [AVMetadataObject],
        from connection: AVCaptureConnection
    ) {
        guard let obj = metadataObjects.first as? AVMetadataMachineReadableCodeObject,
              obj.type == .qr, let value = obj.stringValue
        else { return }
        Task { @MainActor in
            guard !self.delivered else { return }
            self.delivered = true
            self.onFound?(value)
        }
    }
}

#if os(macOS)
import AppKit

/// Hosts the camera preview layer (macOS).
struct CameraPreview: NSViewRepresentable {
    let controller: CameraScanController

    func makeNSView(context: Context) -> NSView {
        let view = NSView()
        view.wantsLayer = true
        let container = CALayer()
        view.layer = container
        let preview = AVCaptureVideoPreviewLayer(session: controller.session)
        preview.videoGravity = .resizeAspectFill
        preview.frame = view.bounds
        preview.autoresizingMask = [.layerWidthSizable, .layerHeightSizable]
        container.addSublayer(preview)
        return view
    }

    func updateNSView(_ nsView: NSView, context: Context) {
        if let preview = nsView.layer?.sublayers?.first {
            preview.frame = nsView.bounds
        }
    }
}
#else
import UIKit

/// Hosts the camera preview layer (iOS / iPadOS).
struct CameraPreview: UIViewRepresentable {
    let controller: CameraScanController

    func makeUIView(context: Context) -> PreviewView {
        let view = PreviewView()
        view.videoPreviewLayer.session = controller.session
        view.videoPreviewLayer.videoGravity = .resizeAspectFill
        return view
    }

    func updateUIView(_ uiView: PreviewView, context: Context) {}

    final class PreviewView: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var videoPreviewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    }
}
#endif
