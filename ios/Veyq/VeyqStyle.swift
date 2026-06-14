import SwiftUI

/// veyq design tokens (dark) — mirrors the web CSS variables so all platforms share one look.
enum VeyqStyle {
    static let bg = Color(hex: 0x101418)
    static let surface = Color(hex: 0x171C22)
    static let surfaceMuted = Color(hex: 0x20262E)
    static let text = Color(hex: 0xEEF3F8)
    static let muted = Color(hex: 0x9AA7B5)
    static let line = Color(hex: 0x333D49)
    static let accent = Color(hex: 0x9B8CFF)
    static let danger = Color(hex: 0xF87171)
    static let radius: CGFloat = 12
    static let radiusSm: CGFloat = 10
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
}

/// The branded auth card (wordmark + title + slotted content) on the app background.
struct AuthCard<Content: View>: View {
    let title: String
    var subtitle: String? = nil
    @ViewBuilder var content: Content

    var body: some View {
        ZStack {
            VeyqStyle.bg.ignoresSafeArea()
            VStack(alignment: .leading, spacing: 14) {
                Text("veyq").font(.system(size: 26, weight: .heavy)).foregroundStyle(VeyqStyle.text)
                Text(title).font(.system(size: 19, weight: .bold)).foregroundStyle(VeyqStyle.text)
                if let subtitle { Text(subtitle).font(.footnote).foregroundStyle(VeyqStyle.muted) }
                content
            }
            .padding(22)
            .background(VeyqStyle.surface, in: RoundedRectangle(cornerRadius: VeyqStyle.radius))
            .overlay(RoundedRectangle(cornerRadius: VeyqStyle.radius).strokeBorder(VeyqStyle.line))
            .frame(maxWidth: 420)
            .padding(20)
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
            .background(VeyqStyle.surfaceMuted, in: RoundedRectangle(cornerRadius: VeyqStyle.radiusSm))
            .overlay(RoundedRectangle(cornerRadius: VeyqStyle.radiusSm).strokeBorder(VeyqStyle.line))
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
                .background(VeyqStyle.accent, in: RoundedRectangle(cornerRadius: VeyqStyle.radiusSm))
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
            .background((error ? VeyqStyle.danger.opacity(0.14) : VeyqStyle.surfaceMuted), in: RoundedRectangle(cornerRadius: VeyqStyle.radiusSm))
    }
}
