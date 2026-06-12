import SwiftUI
import UIKit

enum VeyqColor {
    static let accent = Color(uiColor: .veyqDynamic(light: .veyqRGB(0x5B4DF5), dark: .veyqRGB(0x9B8CFF)))
    static let success = Color(uiColor: .veyqDynamic(light: .veyqRGB(0x0F766E), dark: .veyqRGB(0x2DD4BF)))
    static let warning = Color(uiColor: .veyqDynamic(light: .veyqRGB(0xFACC15), dark: .veyqRGB(0xFACC15)))
    static let danger = Color(uiColor: .veyqDynamic(light: .veyqRGB(0xB42318), dark: .veyqRGB(0xF87171)))
    static let info = Color(uiColor: .veyqDynamic(light: .veyqRGB(0x2563EB), dark: .veyqRGB(0x67E8F9)))
    static let rose = Color(uiColor: .veyqDynamic(light: .veyqRGB(0xC8335D), dark: .veyqRGB(0xFB7185)))
    static let ink = Color(uiColor: .veyqDynamic(light: .veyqRGB(0x111827), dark: .veyqRGB(0xEEF3F8)))
    static let background = Color(uiColor: .veyqDynamic(light: .veyqRGB(0xF7F8FB), dark: .veyqRGB(0x101418)))
    static let surface = Color(uiColor: .veyqDynamic(light: .veyqRGB(0xFFFFFF), dark: .veyqRGB(0x171C22)))
    static let elevatedSurface = Color(uiColor: .veyqDynamic(light: .veyqRGB(0xF4F6FB), dark: .veyqRGB(0x20262E)))
    static let onStrong = Color(uiColor: .veyqRGB(0xFFFFFF))
    static let mediaBackdrop = Color(uiColor: .veyqDynamic(light: .veyqRGB(0x0F172A), dark: .veyqRGB(0x0B0F14)))
    static let softControl = Color.primary.opacity(0.06)
    static let cardShadow = Color(uiColor: .veyqDynamic(light: .black.withAlphaComponent(0.06), dark: .black.withAlphaComponent(0.30)))
    static let dockShadow = Color(uiColor: .veyqDynamic(light: .black.withAlphaComponent(0.08), dark: .black.withAlphaComponent(0.34)))
    static let textSecondary = Color.secondary
    static let hairline = Color(uiColor: .veyqDynamic(light: .veyqRGB(0xDCE2EC), dark: .veyqRGB(0x333D49)))
}

enum VeyqLayout {
    static let floatingDockContentPadding: CGFloat = 82
    static let cardRadius: CGFloat = 10
    static let controlRadius: CGFloat = 8
}

struct VeyqScreenBackground: View {
    var body: some View {
        LinearGradient(
            colors: [
                VeyqColor.accent.opacity(0.12),
                VeyqColor.success.opacity(0.06),
                VeyqColor.background
            ],
            startPoint: .topLeading,
            endPoint: .center
        )
        .ignoresSafeArea()
    }
}

struct VeyqMetadataPill: View {
    let symbolName: String
    let text: String
    var tint: Color = VeyqColor.accent

    var body: some View {
        HStack(spacing: 7) {
            Image(systemName: symbolName)
                .imageScale(.medium)
            Text(text)
                .lineLimit(1)
                .minimumScaleFactor(0.86)
        }
            .font(.caption.weight(.semibold))
            .foregroundStyle(tint)
            .padding(.horizontal, 9)
            .padding(.vertical, 6)
            .background(tint.opacity(0.11), in: Capsule())
            .accessibilityElement(children: .combine)
    }
}

struct VeyqEmptyState: View {
    let symbolName: String
    let title: LocalizedStringKey
    let message: Text
    var tint: Color = VeyqColor.accent

    init(symbolName: String, title: LocalizedStringKey, message: LocalizedStringKey, tint: Color = VeyqColor.accent) {
        self.symbolName = symbolName
        self.title = title
        self.message = Text(message)
        self.tint = tint
    }

    init(symbolName: String, title: LocalizedStringKey, rawMessage: String, tint: Color = VeyqColor.accent) {
        self.symbolName = symbolName
        self.title = title
        self.message = Text(rawMessage)
        self.tint = tint
    }

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
                    .multilineTextAlignment(.center)
                message
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(18)
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: VeyqLayout.cardRadius, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: VeyqLayout.cardRadius, style: .continuous)
                .stroke(VeyqColor.hairline)
        }
    }
}

private extension UIColor {
    static func veyqDynamic(light: UIColor, dark: UIColor) -> UIColor {
        UIColor { traitCollection in
            traitCollection.userInterfaceStyle == .dark ? dark : light
        }
    }

    static func veyqRGB(_ value: Int, alpha: CGFloat = 1) -> UIColor {
        UIColor(
            red: CGFloat((value >> 16) & 0xFF) / 255,
            green: CGFloat((value >> 8) & 0xFF) / 255,
            blue: CGFloat(value & 0xFF) / 255,
            alpha: alpha
        )
    }
}
