import SwiftUI

/// A destructive-action gate: the user types the resource's exact name to confirm. Used for every
/// irreversible delete (folder/memory, group, tenant) so a slip can't wipe content.
struct ConfirmDeleteView: View {
    let name: String
    let onConfirm: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var typed = ""

    var body: some View {
        NavigationStack {
            Form {
                Text(T("delete.warning")).font(.footnote).foregroundStyle(VeyqStyle.muted)
                TextField(String(format: T("delete.confirmPrompt"), name), text: $typed)
                    .autocorrectionDisabled().textInputAutocapitalization(.never)
            }
            .navigationTitle(T("delete.title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(T("common.cancel")) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(T("delete.confirm"), role: .destructive) { onConfirm(); dismiss() }
                        .disabled(typed != name)
                }
            }
        }
    }
}
