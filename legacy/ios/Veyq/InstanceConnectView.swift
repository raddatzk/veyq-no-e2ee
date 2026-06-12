import SwiftUI

struct InstanceConnectView: View {
    @State private var instanceURL: String
    @State private var errorMessage: String?
    @State private var loading = false

    let onConnect: (String) async -> String?

    init(initialURL: String, onConnect: @escaping (String) async -> String?) {
        self._instanceURL = State(initialValue: initialURL)
        self.onConnect = onConnect
    }

    var body: some View {
        ZStack {
            VeyqScreenBackground()

            ScrollView {
                VStack(alignment: .leading, spacing: 26) {
                    VStack(alignment: .leading, spacing: 14) {
                        Image(systemName: "sparkles.rectangle.stack")
                            .font(.system(size: 34, weight: .semibold))
                            .foregroundStyle(VeyqColor.accent)
                            .frame(width: 62, height: 62)
                            .background(.thinMaterial, in: RoundedRectangle(cornerRadius: VeyqLayout.cardRadius, style: .continuous))
                            .overlay {
                                RoundedRectangle(cornerRadius: VeyqLayout.cardRadius, style: .continuous)
                                    .stroke(VeyqColor.hairline)
                            }
                            .accessibilityHidden(true)

                        VStack(alignment: .leading, spacing: 8) {
                            Text("veyq")
                                .font(.largeTitle.bold())
                            Text("Connect your private Memory archive.")
                                .font(.title3.weight(.semibold))
                                .foregroundStyle(.secondary)
                        }
                    }

                    VStack(alignment: .leading, spacing: 14) {
                        Text("Instance")
                            .font(.headline)

                        VStack(alignment: .leading, spacing: 10) {
                            TextField("https://memories.example.com", text: $instanceURL)
                                .textInputAutocapitalization(.never)
                                .autocorrectionDisabled()
                                .keyboardType(.URL)
                                .textContentType(.URL)
                                .font(.body)
                                .padding(13)
                                .background(VeyqColor.surface, in: RoundedRectangle(cornerRadius: VeyqLayout.controlRadius, style: .continuous))
                                .overlay {
                                    RoundedRectangle(cornerRadius: VeyqLayout.controlRadius, style: .continuous)
                                        .stroke(errorMessage == nil ? VeyqColor.hairline : VeyqColor.danger.opacity(0.45))
                                }
                                .accessibilityIdentifier("instance-url-field")

                            Text(errorMessage ?? L10n.string("Use the base URL of your veyq instance."))
                                .font(.footnote)
                                .foregroundStyle(errorMessage == nil ? .secondary : VeyqColor.danger)
                                .accessibilityIdentifier("instance-url-error")
                        }
                    }

                    Button {
                        Task {
                            loading = true
                            errorMessage = await onConnect(instanceURL)
                            loading = false
                        }
                    } label: {
                        HStack {
                            if loading {
                                ProgressView()
                                    .tint(VeyqColor.onStrong)
                            } else {
                                Image(systemName: "link")
                                    .font(.headline.weight(.semibold))
                                Text("Connect")
                                    .font(.headline.weight(.semibold))
                            }
                        }
                        .foregroundStyle(VeyqColor.onStrong)
                        .frame(maxWidth: .infinity, minHeight: 50)
                        .background(VeyqColor.accent, in: RoundedRectangle(cornerRadius: VeyqLayout.controlRadius, style: .continuous))
                    }
                    .buttonStyle(.plain)
                    .disabled(loading)
                    .accessibilityIdentifier("connect-button")

                    VeyqEmptyState(
                        symbolName: "lock.shield",
                        title: "Self-hosted by default",
                        message: "Your Memories stay on the instance you choose.",
                        tint: VeyqColor.success
                    )
                }
                .padding(22)
                .frame(maxWidth: 520, alignment: .leading)
            }
        }
        .navigationTitle("veyq")
        .navigationBarTitleDisplayMode(.inline)
    }
}
