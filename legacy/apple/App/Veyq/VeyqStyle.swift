import SwiftUI

/// veyq native design tokens. The legacy iOS app is the native UI reference; these values intentionally
/// mirror its palette and compact rhythm while staying adaptive for light/dark mode.
enum VeyqStyle {
    static let bg = Color(light: 0xF7F8FB, dark: 0x101418)
    static let surface = Color(light: 0xFFFFFF, dark: 0x171C22)
    static let surfaceMuted = Color(light: 0xF4F6FB, dark: 0x20262E)
    static let elevatedSurface = Color(light: 0xF4F6FB, dark: 0x20262E)
    static let text = Color(light: 0x111827, dark: 0xEEF3F8)
    static let muted = Color(light: 0x667085, dark: 0x9AA7B5)
    static let line = Color(light: 0xDCE2EC, dark: 0x333D49)
    static let accent = Color(light: 0x5B4DF5, dark: 0x9B8CFF)
    static let success = Color(light: 0x0F766E, dark: 0x2DD4BF)
    static let warning = Color(light: 0xFACC15, dark: 0xFACC15)
    static let info = Color(light: 0x2563EB, dark: 0x67E8F9)
    static let rose = Color(light: 0xC8335D, dark: 0xFB7185)
    static let danger = Color(light: 0xB42318, dark: 0xF87171)
    static let dangerSoft = Color(light: 0xFEF2F2, dark: 0x3B1D24)
    static let dangerLine = Color(light: 0xFECACA, dark: 0x7F1D1D)
    static let onStrong = Color.white
    static let mediaBackdrop = Color(light: 0x0F172A, dark: 0x0B0F14)
    static let cardShadow = Color(light: 0x000000, dark: 0x000000).opacity(0.08)
    static let dockShadow = Color(light: 0x000000, dark: 0x000000).opacity(0.12)
    static let radius: CGFloat = 10
    static let radiusSm: CGFloat = 8
    static let floatingDockContentPadding: CGFloat = 82
}

struct VeyqScreenBackground: View {
    var body: some View {
        LinearGradient(
            colors: [
                VeyqStyle.accent.opacity(0.12),
                VeyqStyle.success.opacity(0.06),
                VeyqStyle.bg
            ],
            startPoint: .topLeading,
            endPoint: .center
        )
        .ignoresSafeArea()
    }
}

struct VeyqEmptyState: View {
    let symbolName: String
    let title: String
    let message: String
    var tint: Color = VeyqStyle.accent

    var body: some View {
        VStack(spacing: 10) {
            Image(systemName: symbolName)
                .font(.title2.weight(.semibold))
                .foregroundStyle(tint)
                .frame(width: 44, height: 44)
                .background(tint.opacity(0.12), in: Circle())

            VStack(spacing: 4) {
                Text(title)
                    .font(.headline)
                    .foregroundStyle(VeyqStyle.text)
                    .multilineTextAlignment(.center)
                Text(message)
                    .font(.subheadline)
                    .foregroundStyle(VeyqStyle.muted)
                    .multilineTextAlignment(.center)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(18)
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: VeyqStyle.radius, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: VeyqStyle.radius, style: .continuous)
                .stroke(VeyqStyle.line)
        }
    }
}

extension Color {
    init(hex: UInt) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
            opacity: 1,
        )
    }

    /// An adaptive color that resolves to `light` or `dark` based on the active interface style.
    init(light: UInt, dark: UInt) {
        self.init(UIColor { $0.userInterfaceStyle == .dark ? UIColor(hex: dark) : UIColor(hex: light) })
    }
}

private extension UIColor {
    convenience init(hex: UInt) {
        self.init(
            red: CGFloat((hex >> 16) & 0xFF) / 255,
            green: CGFloat((hex >> 8) & 0xFF) / 255,
            blue: CGFloat(hex & 0xFF) / 255,
            alpha: 1,
        )
    }
}

/// The branded auth card (wordmark + title + slotted content) on the app background.
struct AuthCard<Content: View>: View {
    let title: String
    var subtitle: String? = nil
    @ViewBuilder var content: Content

    var body: some View {
        ZStack {
            VeyqScreenBackground()
            ScrollView {
                VStack(alignment: .leading, spacing: 22) {
                    VStack(alignment: .leading, spacing: 10) {
                        Image(systemName: "sparkles.rectangle.stack")
                            .font(.title3.weight(.semibold))
                            .foregroundStyle(VeyqStyle.accent)
                            .frame(width: 42, height: 42)
                            .background(VeyqStyle.accent.opacity(0.12), in: Circle())

                        VStack(alignment: .leading, spacing: 4) {
                            Text("veyq")
                                .font(.system(size: 32, weight: .heavy))
                                .foregroundStyle(VeyqStyle.text)
                            Text(title)
                                .font(.system(size: 20, weight: .bold))
                                .foregroundStyle(VeyqStyle.text)
                            if let subtitle {
                                Text(subtitle)
                                    .font(.footnote)
                                    .foregroundStyle(VeyqStyle.muted)
                                    .textSelection(.enabled)
                            }
                        }
                    }

                    VStack(alignment: .leading, spacing: 14) {
                        content
                    }
                    .padding(18)
                    .background(.thinMaterial, in: RoundedRectangle(cornerRadius: VeyqStyle.radius, style: .continuous))
                    .overlay {
                        RoundedRectangle(cornerRadius: VeyqStyle.radius, style: .continuous)
                            .stroke(VeyqStyle.line)
                    }
                    .shadow(color: VeyqStyle.cardShadow, radius: 18, y: 10)
                }
                .frame(maxWidth: 520, alignment: .leading)
                .padding(22)
                .frame(maxWidth: .infinity, minHeight: 620)
            }
        }
    }
}

/// A labeled secure/plain text field matching the web form inputs.
struct VeyqField: View {
    let label: String
    @Binding var text: String
    var placeholder = ""
    var secure = false
    var keyboard: UIKeyboardType = .default
    var hint: String? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label).font(.system(size: 14, weight: .semibold)).foregroundStyle(VeyqStyle.text)
            Group {
                if secure { SecureField(placeholder, text: $text) }
                else { TextField(placeholder, text: $text) }
            }
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .keyboardType(keyboard)
            .foregroundStyle(VeyqStyle.text)
            .padding(.horizontal, 11).padding(.vertical, 10)
            .background(VeyqStyle.elevatedSurface, in: RoundedRectangle(cornerRadius: VeyqStyle.radiusSm, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: VeyqStyle.radiusSm, style: .continuous)
                    .stroke(VeyqStyle.line)
            }
            if let hint { Text(hint).font(.system(size: 12)).foregroundStyle(VeyqStyle.muted) }
        }
    }
}

/// Primary (accent) full-width button.
struct PrimaryButton: View {
    let title: String
    var busy = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(busy ? "…" : title)
                .font(.system(size: 16, weight: .semibold))
                .frame(maxWidth: .infinity).padding(.vertical, 11)
                .foregroundStyle(.white)
                .background(VeyqStyle.accent, in: RoundedRectangle(cornerRadius: VeyqStyle.radiusSm, style: .continuous))
        }
        .buttonStyle(.plain)
    }
}

/// Inline error / info message box.
struct MessageBox: View {
    let text: String
    var error = true
    var body: some View {
        Text(text)
            .font(.system(size: 13))
            .foregroundStyle(error ? VeyqStyle.danger : VeyqStyle.muted)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(10)
            .background((error ? VeyqStyle.danger.opacity(0.14) : VeyqStyle.surfaceMuted), in: RoundedRectangle(cornerRadius: VeyqStyle.radiusSm, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: VeyqStyle.radiusSm, style: .continuous)
                    .stroke(error ? VeyqStyle.dangerLine : VeyqStyle.line)
            }
    }
}
