import SwiftUI

// veyq design tokens + auth components, ported from the legacy native UI (`legacy/iOS/Veyq`,
// `legacy/apple`) but made genuinely cross-platform: this is a *native* macOS + iOS + iPadOS app (not
// Mac Catalyst), so the legacy UIKit-only bits (`UIColor`, `UIKeyboardType`, `keyboardType`,
// `textInputAutocapitalization`) are replaced with platform-guarded equivalents. The palette mirrors the
// web CSS variables so every surface shares one look (the Windows/Linux heads port from the screenshots).
enum VeyqStyle {
    static let bg = Color(light: 0xF7F8FB, dark: 0x101418)
    static let surface = Color(light: 0xFFFFFF, dark: 0x171C22)
    static let surfaceMuted = Color(light: 0xF4F6FB, dark: 0x20262E)
    static let text = Color(light: 0x111827, dark: 0xEEF3F8)
    static let muted = Color(light: 0x667085, dark: 0x9AA7B5)
    static let line = Color(light: 0xDCE2EC, dark: 0x333D49)
    static let accent = Color(light: 0x5B4DF5, dark: 0x9B8CFF)
    static let success = Color(light: 0x0F766E, dark: 0x2DD4BF)
    static let danger = Color(light: 0xB42318, dark: 0xF87171)
    static let cardShadow = Color.black.opacity(0.08)
    static let radius: CGFloat = 12
    static let radiusSm: CGFloat = 10
}

/// Cross-platform keyboard hint — maps to `UIKeyboardType` on iOS, ignored on macOS.
enum VeyqKeyboard { case `default`, email, url }

// MARK: - adaptive color (native, per platform)

extension Color {
    init(hex: UInt) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
            opacity: 1
        )
    }
}

#if canImport(UIKit)
import UIKit
extension Color {
    /// Light/dark adaptive color via a dynamic `UIColor` (iOS / iPadOS).
    init(light: UInt, dark: UInt) {
        self.init(UIColor { $0.userInterfaceStyle == .dark ? UIColor(hex: dark) : UIColor(hex: light) })
    }
}
private extension UIColor {
    convenience init(hex: UInt) {
        self.init(red: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255,
                  blue: CGFloat(hex & 0xFF) / 255, alpha: 1)
    }
}
#elseif canImport(AppKit)
import AppKit
extension Color {
    /// Light/dark adaptive color via a dynamic `NSColor` (macOS).
    init(light: UInt, dark: UInt) {
        self.init(nsColor: NSColor(name: nil) { appearance in
            let isDark = appearance.bestMatch(from: [.aqua, .darkAqua]) == .darkAqua
            return NSColor(hex: isDark ? dark : light)
        })
    }
}
private extension NSColor {
    convenience init(hex: UInt) {
        self.init(srgbRed: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255,
                  blue: CGFloat(hex & 0xFF) / 255, alpha: 1)
    }
}
#endif

// MARK: - components

/// The branded auth card (wordmark + title + slotted content) centred on the app background. Adaptive
/// across iPhone / iPad / Mac: the card caps at a comfortable reading width instead of stretching.
struct AuthCard<Content: View>: View {
    let title: String
    var subtitle: String? = nil
    @ViewBuilder var content: Content

    var body: some View {
        ZStack {
            VeyqStyle.bg.ignoresSafeArea()
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    VStack(alignment: .leading, spacing: 6) {
                        HStack(spacing: 10) {
                            Image(systemName: "lock.shield")
                                .font(.title3.weight(.semibold))
                                .foregroundStyle(VeyqStyle.accent)
                            Text("veyq").font(.system(size: 28, weight: .heavy)).foregroundStyle(VeyqStyle.text)
                        }
                        Text(title).font(.system(size: 19, weight: .bold)).foregroundStyle(VeyqStyle.text)
                        if let subtitle {
                            Text(subtitle).font(.footnote).foregroundStyle(VeyqStyle.muted).textSelection(.enabled)
                        }
                    }
                    content
                }
                .padding(22)
                .background(VeyqStyle.surface, in: RoundedRectangle(cornerRadius: VeyqStyle.radius, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: VeyqStyle.radius, style: .continuous).strokeBorder(VeyqStyle.line))
                .shadow(color: VeyqStyle.cardShadow, radius: 18, y: 10)
                .frame(maxWidth: 440)
                .frame(maxWidth: .infinity)
                .padding(24)
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
    var keyboard: VeyqKeyboard = .default
    var hint: String? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label).font(.system(size: 14, weight: .semibold)).foregroundStyle(VeyqStyle.text)
            field
                .textFieldStyle(.plain)
                .accessibilityIdentifier(label)
                .autocorrectionDisabled()
                .foregroundStyle(VeyqStyle.text)
                .padding(.horizontal, 11).padding(.vertical, 10)
                .background(VeyqStyle.surfaceMuted, in: RoundedRectangle(cornerRadius: VeyqStyle.radiusSm, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: VeyqStyle.radiusSm, style: .continuous).strokeBorder(VeyqStyle.line))
                .modifier(InputTraits(keyboard: keyboard))
            if let hint { Text(hint).font(.system(size: 12)).foregroundStyle(VeyqStyle.muted) }
        }
    }

    @ViewBuilder private var field: some View {
        if secure { SecureField(placeholder, text: $text) } else { TextField(placeholder, text: $text) }
    }
}

/// iOS keyboard + capitalization traits; inert on macOS (those modifiers are UIKit-only).
private struct InputTraits: ViewModifier {
    let keyboard: VeyqKeyboard
    func body(content: Content) -> some View {
        #if os(iOS)
        content
            .textInputAutocapitalization(.never)
            .keyboardType(uiKeyboard)
        #else
        content
        #endif
    }
    #if os(iOS)
    private var uiKeyboard: UIKeyboardType {
        switch keyboard {
        case .default: return .default
        case .email: return .emailAddress
        case .url: return .URL
        }
    }
    #endif
}

/// Primary (accent) full-width button with a busy state.
struct PrimaryButton: View {
    let title: String
    var busy = false
    var disabled = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(busy ? "…" : title)
                .font(.system(size: 16, weight: .semibold))
                .frame(maxWidth: .infinity).padding(.vertical, 11)
                .foregroundStyle(.white)
                .background((disabled ? VeyqStyle.accent.opacity(0.5) : VeyqStyle.accent),
                            in: RoundedRectangle(cornerRadius: VeyqStyle.radiusSm, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier(title)
        .disabled(busy || disabled)
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
            .background((error ? VeyqStyle.danger.opacity(0.14) : VeyqStyle.surfaceMuted),
                        in: RoundedRectangle(cornerRadius: VeyqStyle.radiusSm, style: .continuous))
    }
}
