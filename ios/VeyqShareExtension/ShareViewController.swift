import UIKit
import UniformTypeIdentifiers

@objc(ShareViewController)
final class ShareViewController: UIViewController {
    private let appGroupIdentifier = "group.com.veyq.ios"
    private var startedImport = false
    private var stagedBatch: SharePendingImportBatch?
    private let statusLabel = UILabel()
    private let closeButton = UIButton(type: .system)
    private let targetScrollView = UIScrollView()
    private let targetStack = UIStackView()

    override func viewDidLoad() {
        super.viewDidLoad()
        configureStatusView()
        showStatus(Self.localized("Preparing shared media..."))
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        guard !startedImport else { return }
        startedImport = true
        Task {
            await importSharedItems()
        }
    }

    private func importSharedItems() async {
        do {
            let batch = try await stagedImportBatch()
            if batch.fileURLs.isEmpty {
                showStatus(Self.localized("No photos or videos were shared."))
                showCloseButton()
            } else if let snapshot = loadShareImportTargets(), !snapshot.targets.isEmpty {
                stagedBatch = batch
                showMemoryTargets(
                    snapshot.targets,
                    selectedId: snapshot.lastUsedMemoryId,
                    includesOnlyKnownChapters: snapshot.includesOnlyKnownChapters == true
                )
            } else {
                showStatus(Self.localized("Open veyq once, then share again to choose a Memory here."))
                showCloseButton()
            }
        } catch let error as ShareImportError {
            showStatus(error.localizedDescription)
            showCloseButton()
        } catch {
            showStatus(Self.localized("Shared media could not be prepared."))
            showCloseButton()
        }
    }

    private func stagedImportBatch() async throws -> SharePendingImportBatch {
        let batchId = UUID().uuidString
        let batchDirectory = try pendingImportDirectory().appendingPathComponent(batchId, isDirectory: true)
        try FileManager.default.createDirectory(at: batchDirectory, withIntermediateDirectories: true)

        var importedURLs: [URL] = []
        let providers = extensionContext?.inputItems
            .compactMap { $0 as? NSExtensionItem }
            .flatMap { $0.attachments ?? [] } ?? []

        for provider in providers {
            if provider.hasItemConformingToTypeIdentifier(UTType.image.identifier) {
                if let copied = try await copyItem(
                    from: provider,
                    typeIdentifier: UTType.image.identifier,
                    fallbackExtension: "jpg",
                    into: batchDirectory
                ) {
                    importedURLs.append(copied)
                }
            } else if provider.hasItemConformingToTypeIdentifier(UTType.movie.identifier) {
                if let copied = try await copyItem(
                    from: provider,
                    typeIdentifier: UTType.movie.identifier,
                    fallbackExtension: "mov",
                    into: batchDirectory
                ) {
                    importedURLs.append(copied)
                }
            }
        }

        let batch = SharePendingImportBatch(id: batchId, fileURLs: importedURLs, createdAt: Date())
        let data = try JSONEncoder().encode(batch)
        try data.write(to: batchDirectory.appendingPathComponent("batch.json"), options: [.atomic])
        return batch
    }

    private func loadShareImportTargets() -> ShareImportTargetSnapshot? {
        let url = sharedContainerURL()?.appendingPathComponent("share-import-targets.json")
        guard let url, let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(ShareImportTargetSnapshot.self, from: data)
    }

    private func persistSelectedTarget(_ target: ShareImportMemoryTarget) throws -> SharePendingImportBatch {
        guard let stagedBatch else { throw ShareImportError.missingBatch }
        let uploadTarget = ShareUploadTargetMemory(
            tenantName: target.tenantName,
            memoryId: target.id,
            memoryTitle: target.title
        )
        let selectedBatch = SharePendingImportBatch(
            id: stagedBatch.id,
            fileURLs: stagedBatch.fileURLs,
            createdAt: stagedBatch.createdAt,
            targetMemory: uploadTarget
        )
        let data = try JSONEncoder().encode(selectedBatch)
        try data.write(to: pendingImportDirectory().appendingPathComponent(selectedBatch.id, isDirectory: true).appendingPathComponent("batch.json"), options: [.atomic])
        persistLastUsedTarget(target.id)
        return selectedBatch
    }

    private func persistLastUsedTarget(_ targetId: String) {
        guard let snapshot = loadShareImportTargets(),
              let data = try? JSONEncoder().encode(
                ShareImportTargetSnapshot(
                    targets: snapshot.targets,
                    lastUsedMemoryId: targetId,
                    updatedAt: Date(),
                    includesOnlyKnownChapters: snapshot.includesOnlyKnownChapters
                )
              ),
              let url = sharedContainerURL()?.appendingPathComponent("share-import-targets.json")
        else {
            return
        }
        try? data.write(to: url, options: [.atomic])
    }

    private func copyItem(
        from provider: NSItemProvider,
        typeIdentifier: String,
        fallbackExtension: String,
        into directory: URL
    ) async throws -> URL? {
        if let fileURL = try await copyFileRepresentation(
            from: provider,
            typeIdentifier: typeIdentifier,
            fallbackExtension: fallbackExtension,
            into: directory
        ) {
            return fileURL
        }

        return try await copyDataRepresentation(
            from: provider,
            typeIdentifier: typeIdentifier,
            fallbackExtension: fallbackExtension,
            into: directory
        )
    }

    private func copyFileRepresentation(
        from provider: NSItemProvider,
        typeIdentifier: String,
        fallbackExtension: String,
        into directory: URL
    ) async throws -> URL? {
        try await withCheckedThrowingContinuation { continuation in
            provider.loadFileRepresentation(forTypeIdentifier: typeIdentifier) { sourceURL, error in
                if let error {
                    continuation.resume(throwing: error)
                    return
                }
                guard let sourceURL else {
                    continuation.resume(returning: nil)
                    return
                }
                do {
                    let filename = Self.filename(from: sourceURL.lastPathComponent, fallbackExtension: fallbackExtension)
                    let destination = directory.appendingPathComponent(filename)
                    if FileManager.default.fileExists(atPath: destination.path) {
                        try FileManager.default.removeItem(at: destination)
                    }
                    try FileManager.default.copyItem(at: sourceURL, to: destination)
                    continuation.resume(returning: destination)
                } catch {
                    continuation.resume(throwing: error)
                }
            }
        }
    }

    private func copyDataRepresentation(
        from provider: NSItemProvider,
        typeIdentifier: String,
        fallbackExtension: String,
        into directory: URL
    ) async throws -> URL? {
        try await withCheckedThrowingContinuation { continuation in
            provider.loadDataRepresentation(forTypeIdentifier: typeIdentifier) { data, error in
                if let error {
                    continuation.resume(throwing: error)
                    return
                }
                guard let data else {
                    continuation.resume(returning: nil)
                    return
                }
                do {
                    let destination = directory.appendingPathComponent(Self.filename(from: nil, fallbackExtension: fallbackExtension))
                    try data.write(to: destination, options: [.atomic])
                    continuation.resume(returning: destination)
                } catch {
                    continuation.resume(throwing: error)
                }
            }
        }
    }

    private nonisolated static func filename(from rawValue: String?, fallbackExtension: String) -> String {
        guard let rawValue, !rawValue.isEmpty else {
            return "\(UUID().uuidString).\(fallbackExtension)"
        }
        if URL(fileURLWithPath: rawValue).pathExtension.isEmpty {
            return "\(rawValue).\(fallbackExtension)"
        }
        return rawValue
    }

    private func pendingImportDirectory() throws -> URL {
        guard let root = sharedContainerURL() else {
            throw ShareImportError.appGroupUnavailable
        }
        let directory = root.appendingPathComponent("PendingImports", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }

    private func sharedContainerURL() -> URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroupIdentifier)
    }

    private func openHostApp(batchId: String) {
        guard let url = URL(string: "veyq://import?batchId=\(batchId)") else {
            extensionContext?.completeRequest(returningItems: nil)
            return
        }
        extensionContext?.open(url) { [weak self] completed in
            Task { @MainActor [weak self] in
                if completed {
                    self?.extensionContext?.completeRequest(returningItems: nil)
                } else {
                    self?.extensionContext?.completeRequest(returningItems: nil)
                }
            }
        }
    }

    private func configureStatusView() {
        view.backgroundColor = .systemBackground

        targetScrollView.translatesAutoresizingMaskIntoConstraints = false
        targetScrollView.alwaysBounceVertical = true
        targetScrollView.showsVerticalScrollIndicator = false
        targetScrollView.isHidden = true

        targetStack.translatesAutoresizingMaskIntoConstraints = false
        targetStack.axis = .vertical
        targetStack.spacing = 12

        statusLabel.translatesAutoresizingMaskIntoConstraints = false
        statusLabel.font = .preferredFont(forTextStyle: .body)
        statusLabel.textAlignment = .center
        statusLabel.textColor = .label
        statusLabel.numberOfLines = 0

        closeButton.translatesAutoresizingMaskIntoConstraints = false
        closeButton.setTitle(Self.localized("Close"), for: .normal)
        closeButton.isHidden = true
        closeButton.addTarget(self, action: #selector(closeExtension), for: .touchUpInside)

        view.addSubview(statusLabel)
        view.addSubview(targetScrollView)
        targetScrollView.addSubview(targetStack)
        view.addSubview(closeButton)

        NSLayoutConstraint.activate([
            statusLabel.leadingAnchor.constraint(equalTo: view.layoutMarginsGuide.leadingAnchor),
            statusLabel.trailingAnchor.constraint(equalTo: view.layoutMarginsGuide.trailingAnchor),
            statusLabel.centerYAnchor.constraint(equalTo: view.centerYAnchor),

            targetScrollView.leadingAnchor.constraint(equalTo: view.layoutMarginsGuide.leadingAnchor),
            targetScrollView.trailingAnchor.constraint(equalTo: view.layoutMarginsGuide.trailingAnchor),
            targetScrollView.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 20),
            targetScrollView.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -12),

            targetStack.leadingAnchor.constraint(equalTo: targetScrollView.contentLayoutGuide.leadingAnchor),
            targetStack.trailingAnchor.constraint(equalTo: targetScrollView.contentLayoutGuide.trailingAnchor),
            targetStack.topAnchor.constraint(equalTo: targetScrollView.contentLayoutGuide.topAnchor),
            targetStack.bottomAnchor.constraint(equalTo: targetScrollView.contentLayoutGuide.bottomAnchor),
            targetStack.widthAnchor.constraint(equalTo: targetScrollView.frameLayoutGuide.widthAnchor),

            closeButton.topAnchor.constraint(equalTo: statusLabel.bottomAnchor, constant: 20),
            closeButton.centerXAnchor.constraint(equalTo: view.centerXAnchor)
        ])
    }

    private func showStatus(_ message: String) {
        statusLabel.text = message
    }

    private func showCloseButton() {
        closeButton.isHidden = false
    }

    private func showMemoryTargets(
        _ targets: [ShareImportMemoryTarget],
        selectedId: String?,
        includesOnlyKnownChapters: Bool
    ) {
        statusLabel.isHidden = true
        targetScrollView.isHidden = false
        targetStack.arrangedSubviews.forEach { view in
            targetStack.removeArrangedSubview(view)
            view.removeFromSuperview()
        }

        let headerStack = UIStackView()
        headerStack.axis = .vertical
        headerStack.alignment = .leading
        headerStack.spacing = 4

        let title = UILabel()
        title.text = Self.localized("Choose Memory")
        title.font = .preferredFont(forTextStyle: .title2)
        title.adjustsFontForContentSizeCategory = true
        title.textColor = .label

        let subtitle = UILabel()
        subtitle.text = includesOnlyKnownChapters
            ? Self.localized("Some chapters may be missing. Open a Memory in veyq first to make its chapters available here.")
            : Self.localized("Add shared media to a Memory.")
        subtitle.font = .preferredFont(forTextStyle: .subheadline)
        subtitle.adjustsFontForContentSizeCategory = true
        subtitle.textColor = .secondaryLabel
        subtitle.numberOfLines = 0

        headerStack.addArrangedSubview(title)
        headerStack.addArrangedSubview(subtitle)
        targetStack.addArrangedSubview(headerStack)
        targetStack.setCustomSpacing(16, after: headerStack)

        let orderedTargets: [ShareImportMemoryTarget]
        if let selectedId, let selectedTarget = targets.first(where: { $0.id == selectedId }) {
            orderedTargets = [selectedTarget] + targets.filter { $0.id != selectedId }
        } else {
            orderedTargets = targets
        }

        for target in orderedTargets {
            let card = MemoryTargetCardControl(
                target: target,
                isSelected: target.id == selectedId,
                placeholder: Self.placeholderCoverImage()
            )
            loadCachedCover(for: target, into: card)
            card.addAction(UIAction { [weak self] _ in
                self?.chooseTarget(target)
            }, for: .touchUpInside)
            targetStack.addArrangedSubview(card)
        }
    }

    private func loadCachedCover(for target: ShareImportMemoryTarget, into card: MemoryTargetCardControl) {
        guard let filename = target.coverThumbnailFilename,
              let url = shareImportCoverURL(filename: filename),
              let image = UIImage(contentsOfFile: url.path)
        else {
            return
        }
        let thumbnail = image.preparingThumbnail(of: CGSize(width: 56, height: 56)) ?? image
        card.setCoverImage(thumbnail)
    }

    private func shareImportCoverURL(filename: String) -> URL? {
        sharedContainerURL()?
            .appendingPathComponent("ShareImportCovers", isDirectory: true)
            .appendingPathComponent((filename as NSString).lastPathComponent)
    }

    private nonisolated static func placeholderCoverImage() -> UIImage {
        let size = CGSize(width: 56, height: 56)
        let renderer = UIGraphicsImageRenderer(size: size)
        return renderer.image { context in
            let rect = CGRect(origin: .zero, size: size)
            let background = UIColor { traitCollection in
                traitCollection.userInterfaceStyle == .dark
                    ? UIColor(red: 0.18, green: 0.16, blue: 0.30, alpha: 1)
                    : UIColor(red: 0.93, green: 0.91, blue: 1.0, alpha: 1)
            }
            background.setFill()
            UIBezierPath(roundedRect: rect, cornerRadius: 12).fill()

            let symbolConfiguration = UIImage.SymbolConfiguration(pointSize: 22, weight: .semibold)
            guard let symbol = UIImage(systemName: "photo.on.rectangle", withConfiguration: symbolConfiguration) else { return }
            let symbolColor = UIColor(red: 0.36, green: 0.30, blue: 0.96, alpha: 1)
            let symbolImage = symbol.withTintColor(symbolColor, renderingMode: .alwaysOriginal)
            let symbolSize = symbolImage.size
            let symbolOrigin = CGPoint(
                x: (size.width - symbolSize.width) / 2,
                y: (size.height - symbolSize.height) / 2
            )
            symbolImage.draw(at: symbolOrigin)
        }
    }

    private func chooseTarget(_ target: ShareImportMemoryTarget) {
        do {
            let batch = try persistSelectedTarget(target)
            showStatus(Self.localizedFormat("Preparing import for %@...", target.title))
            targetScrollView.isHidden = true
            statusLabel.isHidden = false
            openHostApp(batchId: batch.id)
        } catch {
            showStatus(Self.localized("The selected Memory could not be saved."))
            showCloseButton()
        }
    }

    @objc private func closeExtension() {
        extensionContext?.completeRequest(returningItems: nil)
    }

    private nonisolated static func localized(_ key: String) -> String {
        NSLocalizedString(key, comment: "")
    }

    private nonisolated static func localizedFormat(_ key: String, _ arguments: CVarArg...) -> String {
        String(format: localized(key), locale: Locale.current, arguments: arguments)
    }
}

private final class MemoryTargetCardControl: UIControl {
    private let coverImageView = UIImageView()
    private let selectedImageView = UIImageView()

    override var isHighlighted: Bool {
        didSet {
            UIView.animate(withDuration: 0.12) {
                self.alpha = self.isHighlighted ? 0.72 : 1
                self.transform = self.isHighlighted ? CGAffineTransform(scaleX: 0.99, y: 0.99) : .identity
            }
        }
    }

    init(target: ShareImportMemoryTarget, isSelected: Bool, placeholder: UIImage) {
        super.init(frame: .zero)
        translatesAutoresizingMaskIntoConstraints = false
        backgroundColor = .secondarySystemBackground
        layer.cornerRadius = 14
        layer.cornerCurve = .continuous
        layer.borderWidth = 1
        layer.borderColor = UIColor.separator.withAlphaComponent(0.28).cgColor
        accessibilityTraits = [.button]
        accessibilityLabel = target.title
        if let parentMemoryTitle = target.parentMemoryTitle {
            accessibilityValue = Self.localizedFormat("Chapter in %@", parentMemoryTitle)
        } else {
            accessibilityValue = target.dateRange
        }

        coverImageView.translatesAutoresizingMaskIntoConstraints = false
        coverImageView.image = placeholder
        coverImageView.contentMode = .scaleAspectFill
        coverImageView.clipsToBounds = true
        coverImageView.layer.cornerRadius = 12
        coverImageView.layer.cornerCurve = .continuous
        coverImageView.accessibilityIgnoresInvertColors = true

        let titleLabel = UILabel()
        titleLabel.translatesAutoresizingMaskIntoConstraints = false
        titleLabel.text = target.title
        titleLabel.font = .preferredFont(forTextStyle: .headline)
        titleLabel.adjustsFontForContentSizeCategory = true
        titleLabel.textColor = .label
        titleLabel.numberOfLines = 2

        let dateLabel = UILabel()
        dateLabel.translatesAutoresizingMaskIntoConstraints = false
        dateLabel.text = target.parentMemoryTitle.map { Self.localizedFormat("Chapter in %@", $0) } ?? target.dateRange
        dateLabel.font = .preferredFont(forTextStyle: .subheadline)
        dateLabel.adjustsFontForContentSizeCategory = true
        dateLabel.textColor = .secondaryLabel
        dateLabel.numberOfLines = 1

        let textStack = UIStackView(arrangedSubviews: [titleLabel, dateLabel])
        textStack.translatesAutoresizingMaskIntoConstraints = false
        textStack.axis = .vertical
        textStack.alignment = .leading
        textStack.spacing = 3
        textStack.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)

        let symbolName = isSelected ? "checkmark.circle.fill" : "chevron.right"
        selectedImageView.translatesAutoresizingMaskIntoConstraints = false
        selectedImageView.image = UIImage(systemName: symbolName)
        selectedImageView.preferredSymbolConfiguration = UIImage.SymbolConfiguration(pointSize: isSelected ? 18 : 14, weight: .semibold)
        selectedImageView.tintColor = isSelected ? Self.accentColor : .tertiaryLabel
        selectedImageView.setContentHuggingPriority(.required, for: .horizontal)

        let contentStack = UIStackView(arrangedSubviews: [coverImageView, textStack, selectedImageView])
        contentStack.translatesAutoresizingMaskIntoConstraints = false
        contentStack.axis = .horizontal
        contentStack.alignment = .center
        contentStack.spacing = 12
        addSubview(contentStack)

        let depthInset = CGFloat(min(max(target.depth ?? 0, 0), 2) * 18)
        NSLayoutConstraint.activate([
            heightAnchor.constraint(greaterThanOrEqualToConstant: 80),

            contentStack.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 12 + depthInset),
            contentStack.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -14),
            contentStack.topAnchor.constraint(equalTo: topAnchor, constant: 12),
            contentStack.bottomAnchor.constraint(equalTo: bottomAnchor, constant: -12),

            coverImageView.widthAnchor.constraint(equalToConstant: 56),
            coverImageView.heightAnchor.constraint(equalToConstant: 56),

            selectedImageView.widthAnchor.constraint(greaterThanOrEqualToConstant: 18)
        ])
    }

    required init?(coder: NSCoder) {
        nil
    }

    func setCoverImage(_ image: UIImage) {
        coverImageView.image = image.withRenderingMode(.alwaysOriginal)
    }

    private nonisolated static func localizedFormat(_ key: String, _ arguments: CVarArg...) -> String {
        String(format: NSLocalizedString(key, comment: ""), locale: Locale.current, arguments: arguments)
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        layer.borderColor = UIColor.separator.withAlphaComponent(0.28).cgColor
    }

    private static let accentColor = UIColor(red: 0.36, green: 0.30, blue: 0.96, alpha: 1)
}

private struct SharePendingImportBatch: Codable {
    let id: String
    let fileURLs: [URL]
    let createdAt: Date
    let targetMemory: ShareUploadTargetMemory?

    init(id: String, fileURLs: [URL], createdAt: Date, targetMemory: ShareUploadTargetMemory? = nil) {
        self.id = id
        self.fileURLs = fileURLs
        self.createdAt = createdAt
        self.targetMemory = targetMemory
    }
}

private struct ShareUploadTargetMemory: Codable {
    let tenantName: String
    let memoryId: String
    let memoryTitle: String
}

private struct ShareImportMemoryTarget: Codable {
    let id: String
    let title: String
    let dateRange: String
    let tenantName: String
    let coverUrl: String?
    let coverThumbnailFilename: String?
    let parentMemoryTitle: String?
    let depth: Int?
}

private struct ShareImportTargetSnapshot: Codable {
    let targets: [ShareImportMemoryTarget]
    let lastUsedMemoryId: String?
    let updatedAt: Date
    let includesOnlyKnownChapters: Bool?
}

private enum ShareImportError: LocalizedError {
    case appGroupUnavailable
    case missingBatch

    var errorDescription: String? {
        switch self {
        case .appGroupUnavailable:
            NSLocalizedString("The veyq App Group is not available for this build. Check the app and extension signing capabilities for group.com.veyq.ios.", comment: "")
        case .missingBatch:
            NSLocalizedString("Shared media could not be prepared.", comment: "")
        }
    }
}
