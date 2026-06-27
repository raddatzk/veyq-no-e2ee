import SwiftUI

/// The per-device unlock-level choice (uc.account.set-device-security; ADR 0005 §8–13). Shown on the floor
/// at unlock time with **no pre-selection** — the user must actively decide how this device unlocks next
/// time. cr-0019 ships the first two notches; **biometric** (L2) and **no login** (L3) arrive with cr-0020
/// and appear here as disabled "coming soon" rows so the full slider is an extension, not a rewrite.
enum DeviceUnlockLevel: CaseIterable, Identifiable {
    case secretKeyEachTime   // L0 — store nothing; the floor stays the only path
    case onlyPassword        // L1 — DEVICE wrap (this CR)
    case biometric           // L2 — cr-0020
    case noLogin             // L3 — cr-0020

    var id: Self { self }

    /// cr-0019 implements L0 + L1; the passwordless notches are previewed but not selectable yet.
    var available: Bool { self == .secretKeyEachTime || self == .onlyPassword }

    var title: String {
        switch self {
        case .secretKeyEachTime: return "Secret Key each time"
        case .onlyPassword: return "Only my password"
        case .biometric: return "Face ID / Touch ID"
        case .noLogin: return "Open straight in"
        }
    }

    var subtitle: String {
        switch self {
        case .secretKeyEachTime: return "Most private — enter your password and Secret Key every time."
        case .onlyPassword: return "Trust this device: next time just your password, no Secret Key."
        case .biometric: return "Unlock with a glance or a touch — no password."
        case .noLogin: return "No prompt at all — relies only on your device’s lock screen."
        }
    }

    var icon: String {
        switch self {
        case .secretKeyEachTime: return "key.horizontal"
        case .onlyPassword: return "lock"
        case .biometric: return "faceid"
        case .noLogin: return "bolt"
        }
    }
}

/// A radio-style list of the device unlock levels. `selection` starts `nil` (no default — the user must
/// choose); disabled rows can't be selected.
struct DeviceSecurityChooser: View {
    @Binding var selection: DeviceUnlockLevel?

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("How should this device unlock next time?")
                .font(.system(size: 14, weight: .semibold)).foregroundStyle(VeyqStyle.text)
            ForEach(DeviceUnlockLevel.allCases) { level in row(level) }
        }
    }

    private func row(_ level: DeviceUnlockLevel) -> some View {
        let selected = selection == level
        return Button {
            if level.available { selection = level }
        } label: {
            HStack(spacing: 11) {
                Image(systemName: level.icon)
                    .font(.system(size: 16))
                    .foregroundStyle(level.available ? VeyqStyle.accent : VeyqStyle.muted)
                    .frame(width: 22)
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 6) {
                        Text(level.title)
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundStyle(level.available ? VeyqStyle.text : VeyqStyle.muted)
                        if !level.available {
                            Text("Coming soon")
                                .font(.system(size: 10, weight: .bold))
                                .foregroundStyle(VeyqStyle.muted)
                                .padding(.horizontal, 6).padding(.vertical, 2)
                                .background(VeyqStyle.surfaceMuted, in: Capsule())
                        }
                    }
                    Text(level.subtitle).font(.system(size: 12)).foregroundStyle(VeyqStyle.muted)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 6)
                Image(systemName: selected ? "checkmark.circle.fill" : "circle")
                    .font(.system(size: 18))
                    .foregroundStyle(selected ? VeyqStyle.accent : VeyqStyle.line)
            }
            .padding(11)
            .background(VeyqStyle.surfaceMuted, in: RoundedRectangle(cornerRadius: VeyqStyle.radiusSm, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: VeyqStyle.radiusSm, style: .continuous)
                    .strokeBorder(selected ? VeyqStyle.accent : VeyqStyle.line, lineWidth: selected ? 2 : 1)
            )
        }
        .buttonStyle(.plain)
        .disabled(!level.available)
        .accessibilityIdentifier("level.\(level)")
    }
}
