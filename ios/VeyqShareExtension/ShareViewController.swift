import Security
import UIKit
import UniformTypeIdentifiers

@objc(ShareViewController)
final class ShareViewController: UIViewController {
    private let appGroupIdentifier = "group.app.veyq.ios"
    private static let gpxTypeIdentifiers = [
        "com.topografix.gpx",
        "application/gpx+xml",
        "application/xml",
        "text/xml",
        UTType(filenameExtension: "gpx")?.identifier,
        UTType.xml.identifier
    ].compactMap { $0 }
    private var startedImport = false
    private var stagedBatch: SharePendingImportBatch?
    private var activeAccount: ShareLocalAccount?
    private var availableAccounts: [ShareLocalAccount] = []
    private var activeTenant: ShareTenant?
    private var apiClient: ShareExtensionAPIClient?
    private let credentialStore = ShareExtensionCredentialStore()
    private let statusLabel = UILabel()
    private let closeButton = UIButton(type: .system)
    private let targetScrollView = UIScrollView()
    private let targetStack = UIStackView()

    override func viewDidLoad() {
        super.viewDidLoad()
        configureStatusView()
        showStatus(Self.localized("Preparing shared files..."))
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
                showStatus(Self.localized("No photos, videos or GPX files were shared."))
                showCloseButton()
            } else {
                stagedBatch = batch
                await showAccountEntry()
            }
        } catch let error as ShareImportError {
            showStatus(error.localizedDescription)
            showCloseButton()
        } catch {
            showStatus(Self.localized("Shared files could not be prepared."))
            showCloseButton()
        }
    }

    private func showAccountEntry() async {
        let accounts = await loadLocalAccounts()
        availableAccounts = accounts
        guard !accounts.isEmpty else {
            showStatus(Self.localized("Open veyq once, then share again to choose a Memory here."))
            showCloseButton()
            return
        }

        if accounts.count == 1, let account = accounts.first {
            await startBrowser(account: account)
        } else {
            showAccountPicker(accounts)
        }
    }

    private func loadLocalAccounts() async -> [ShareLocalAccount] {
        if let accountSnapshot = loadSharedAccounts(), !accountSnapshot.accounts.isEmpty {
            return accountSnapshot.accounts
                .sorted { lhs, rhs in
                    if lhs.id == accountSnapshot.activeAccountId { return true }
                    if rhs.id == accountSnapshot.activeAccountId { return false }
                    return lhs.displayName.localizedCaseInsensitiveCompare(rhs.displayName) == .orderedAscending
                }
        }
        return (try? await credentialStore.loadAccounts()) ?? []
    }

    private func loadSharedAccounts() -> ShareLocalAccountSnapshot? {
        let url = sharedContainerURL()?.appendingPathComponent("shared-accounts.json")
        guard let url, let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder.veyqShare.decode(ShareLocalAccountSnapshot.self, from: data)
    }

    private func showAccountPicker(_ accounts: [ShareLocalAccount]) {
        statusLabel.isHidden = true
        closeButton.isHidden = true
        targetScrollView.isHidden = false
        resetTargetStack()

        addHeader(title: Self.localized("Choose Account"), subtitle: Self.localized("Use an account already connected in veyq."))
        for account in accounts {
            let button = ShareBrowserRowControl(
                title: account.displayName,
                subtitle: account.instanceURL.host ?? account.email,
                systemImageName: account.active ? "checkmark.circle.fill" : "person.crop.circle"
            )
            button.addAction(UIAction { [weak self] _ in
                Task { await self?.startBrowser(account: account) }
            }, for: .touchUpInside)
            targetStack.addArrangedSubview(button)
        }
    }

    private func startBrowser(account: ShareLocalAccount) async {
        do {
            showLoadingBrowser(Self.localized("Loading Memories..."))
            activeAccount = account
            let session = try await credentialStore.validSession(accountId: account.id)
            let client = ShareExtensionAPIClient(session: session)
            apiClient = client
            let bootstrap = try await client.bootstrap()
            if bootstrap.tenants.count == 1, let tenant = bootstrap.tenants.first {
                await openTenant(tenant, path: "/")
            } else {
                showTenantPicker(bootstrap.tenants)
            }
        } catch {
            showStatus(error.localizedDescription)
            showCloseButton()
        }
    }

    private func showTenantPicker(_ tenants: [ShareTenant]) {
        statusLabel.isHidden = true
        closeButton.isHidden = true
        targetScrollView.isHidden = false
        resetTargetStack()

        addHeader(title: Self.localized("Choose Tenant"), subtitle: Self.localized("Select where the shared files should be imported."))
        for tenant in tenants {
            let button = ShareBrowserRowControl(
                title: tenant.displayName,
                subtitle: tenant.domain,
                systemImageName: "person.3"
            )
            button.addAction(UIAction { [weak self] _ in
                Task { await self?.openTenant(tenant, path: "/") }
            }, for: .touchUpInside)
            targetStack.addArrangedSubview(button)
        }
    }

    private func openTenant(_ tenant: ShareTenant, path: String) async {
        guard let apiClient else { return }
        activeTenant = tenant
        do {
            showLoadingBrowser(Self.localized("Loading Memories..."))
            let navigationContext = try await apiClient.memoryNavigationContext(tenantName: tenant.name, path: path)
            showFolderBrowser(navigationContext: navigationContext)
        } catch {
            showStatus(error.localizedDescription)
            showCloseButton()
        }
    }

    private func showFolderBrowser(navigationContext: ShareMemoryNavigationContextResponse) {
        guard let tenant = activeTenant else { return }
        statusLabel.isHidden = true
        closeButton.isHidden = true
        targetScrollView.isHidden = false
        resetTargetStack()

        addHeader(
            title: navigationContext.location.displayName,
            subtitle: Self.localized("Choose a Memory or browse folders and chapters.")
        )

        if let parent = navigationContext.breadcrumbs.dropLast().last {
            addNavigationRow(title: Self.localized("Back"), subtitle: parent.displayName, systemImageName: "chevron.left") { [weak self] in
                Task { await self?.openTenant(tenant, path: parent.path) }
            }
        } else if availableAccounts.count > 1 {
            addNavigationRow(title: Self.localized("Accounts"), subtitle: activeAccount?.displayName ?? "", systemImageName: "person.crop.circle") { [weak self] in
                self?.showAccountPicker(self?.availableAccounts ?? [])
            }
        }

        if navigationContext.location.path == "/" {
            for root in navigationContext.scopeRoots {
                addNavigationRow(
                    title: root.kind == "GROUP" ? "@\(root.displayName)" : root.displayName,
                    subtitle: Self.localized("Memory Scope"),
                    systemImageName: root.kind == "GROUP" ? "at" : "person"
                ) { [weak self] in
                    Task { await self?.openTenant(tenant, path: root.path) }
                }
            }
        }

        for folder in navigationContext.folders {
            addNavigationRow(
                title: folder.displayName,
                subtitle: Self.localized("Folder"),
                systemImageName: "folder"
            ) { [weak self] in
                Task { await self?.openTenant(tenant, path: folder.path) }
            }
        }

        if let memory = navigationContext.memory {
            addMemoryTarget(memory.target(tenantName: tenant.name))
        }

        if navigationContext.memory == nil, navigationContext.memories.isEmpty, navigationContext.folders.isEmpty, navigationContext.location.path != "/" {
            let empty = UILabel()
            empty.text = Self.localized("No Memories here yet.")
            empty.font = .preferredFont(forTextStyle: .body)
            empty.textColor = .secondaryLabel
            empty.numberOfLines = 0
            targetStack.addArrangedSubview(empty)
        }

        for memory in navigationContext.memories {
            addMemoryTarget(memory.target(tenantName: tenant.name))
            if memory.childCount > 0 {
                addNavigationRow(
                    title: Self.localizedFormat("%@ Chapters", memory.displayName),
                    subtitle: Self.localizedFormat("%d chapters", memory.childCount),
                    systemImageName: "rectangle.stack"
                ) { [weak self] in
                    Task { await self?.openChapters(parent: memory) }
                }
            }
        }
    }

    private func openChapters(parent: ShareMemory) async {
        guard let apiClient, let tenant = activeTenant else { return }
        do {
            showLoadingBrowser(Self.localized("Loading chapters..."))
            let chapters = try await apiClient.listMemoryChapters(tenantName: tenant.name, memoryId: parent.id)
            showChapterBrowser(parent: parent, chapters: chapters)
        } catch {
            showStatus(error.localizedDescription)
            showCloseButton()
        }
    }

    private func showChapterBrowser(parent: ShareMemory, chapters: [ShareMemory]) {
        guard let tenant = activeTenant else { return }
        statusLabel.isHidden = true
        closeButton.isHidden = true
        targetScrollView.isHidden = false
        resetTargetStack()

        addHeader(title: parent.displayName, subtitle: Self.localized("Choose a Chapter."))
        addNavigationRow(title: Self.localized("Back"), subtitle: Self.localized("Memories"), systemImageName: "chevron.left") { [weak self] in
            Task { await self?.openTenant(tenant, path: parent.folderPath) }
        }

        if chapters.isEmpty {
            let empty = UILabel()
            empty.text = Self.localized("No chapters yet")
            empty.font = .preferredFont(forTextStyle: .body)
            empty.textColor = .secondaryLabel
            targetStack.addArrangedSubview(empty)
        }

        for chapter in chapters {
            addMemoryTarget(chapter.target(tenantName: tenant.name, parentMemoryTitle: parent.displayName, depth: 1))
            if chapter.childCount > 0 {
                addNavigationRow(
                    title: Self.localizedFormat("%@ Chapters", chapter.displayName),
                    subtitle: Self.localizedFormat("%d chapters", chapter.childCount),
                    systemImageName: "rectangle.stack"
                ) { [weak self] in
                    Task { await self?.openChapters(parent: chapter) }
                }
            }
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
            } else if let gpxTypeIdentifier = Self.gpxTypeIdentifiers.first(where: { provider.hasItemConformingToTypeIdentifier($0) }) {
                if let copied = try await copyItem(
                    from: provider,
                    typeIdentifier: gpxTypeIdentifier,
                    fallbackExtension: "gpx",
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

    private func showLoadingBrowser(_ message: String) {
        targetScrollView.isHidden = true
        closeButton.isHidden = true
        statusLabel.isHidden = false
        showStatus(message)
    }

    private func resetTargetStack() {
        targetStack.arrangedSubviews.forEach { view in
            targetStack.removeArrangedSubview(view)
            view.removeFromSuperview()
        }
    }

    private func addHeader(title: String, subtitle: String) {
        let headerStack = UIStackView()
        headerStack.axis = .vertical
        headerStack.alignment = .leading
        headerStack.spacing = 4

        let titleLabel = UILabel()
        titleLabel.text = title
        titleLabel.font = .preferredFont(forTextStyle: .title2)
        titleLabel.adjustsFontForContentSizeCategory = true
        titleLabel.textColor = .label
        titleLabel.numberOfLines = 2

        let subtitleLabel = UILabel()
        subtitleLabel.text = subtitle
        subtitleLabel.font = .preferredFont(forTextStyle: .subheadline)
        subtitleLabel.adjustsFontForContentSizeCategory = true
        subtitleLabel.textColor = .secondaryLabel
        subtitleLabel.numberOfLines = 0

        headerStack.addArrangedSubview(titleLabel)
        headerStack.addArrangedSubview(subtitleLabel)
        targetStack.addArrangedSubview(headerStack)
        targetStack.setCustomSpacing(16, after: headerStack)
    }

    private func addNavigationRow(
        title: String,
        subtitle: String,
        systemImageName: String,
        action: @escaping () -> Void
    ) {
        let row = ShareBrowserRowControl(title: title, subtitle: subtitle, systemImageName: systemImageName)
        row.addAction(UIAction { _ in action() }, for: .touchUpInside)
        targetStack.addArrangedSubview(row)
    }

    private func addMemoryTarget(_ target: ShareImportMemoryTarget) {
        let card = MemoryTargetCardControl(
            target: target,
            isSelected: loadShareImportTargets()?.lastUsedMemoryId == target.id,
            placeholder: Self.placeholderCoverImage()
        )
        loadCachedCover(for: target, into: card)
        card.addAction(UIAction { [weak self] _ in
            self?.chooseTarget(target)
        }, for: .touchUpInside)
        targetStack.addArrangedSubview(card)
    }

    private func showMemoryTargets(
        _ targets: [ShareImportMemoryTarget],
        selectedId: String?,
        includesOnlyKnownChapters: Bool
    ) {
        statusLabel.isHidden = true
        targetScrollView.isHidden = false
        resetTargetStack()

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
            : Self.localized("Add shared files to a Memory.")
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

private final class ShareBrowserRowControl: UIControl {
    override var isHighlighted: Bool {
        didSet {
            UIView.animate(withDuration: 0.12) {
                self.alpha = self.isHighlighted ? 0.72 : 1
                self.transform = self.isHighlighted ? CGAffineTransform(scaleX: 0.99, y: 0.99) : .identity
            }
        }
    }

    init(title: String, subtitle: String, systemImageName: String) {
        super.init(frame: .zero)
        translatesAutoresizingMaskIntoConstraints = false
        backgroundColor = .secondarySystemBackground
        layer.cornerRadius = 14
        layer.cornerCurve = .continuous
        layer.borderWidth = 1
        layer.borderColor = UIColor.separator.withAlphaComponent(0.28).cgColor
        accessibilityTraits = [.button]
        accessibilityLabel = title
        accessibilityValue = subtitle

        let symbol = UIImageView(image: UIImage(systemName: systemImageName))
        symbol.translatesAutoresizingMaskIntoConstraints = false
        symbol.preferredSymbolConfiguration = UIImage.SymbolConfiguration(pointSize: 20, weight: .semibold)
        symbol.tintColor = UIColor(red: 0.36, green: 0.30, blue: 0.96, alpha: 1)
        symbol.contentMode = .center

        let titleLabel = UILabel()
        titleLabel.text = title
        titleLabel.font = .preferredFont(forTextStyle: .headline)
        titleLabel.adjustsFontForContentSizeCategory = true
        titleLabel.textColor = .label
        titleLabel.numberOfLines = 2

        let subtitleLabel = UILabel()
        subtitleLabel.text = subtitle
        subtitleLabel.font = .preferredFont(forTextStyle: .subheadline)
        subtitleLabel.adjustsFontForContentSizeCategory = true
        subtitleLabel.textColor = .secondaryLabel
        subtitleLabel.numberOfLines = 1

        let textStack = UIStackView(arrangedSubviews: [titleLabel, subtitleLabel])
        textStack.axis = .vertical
        textStack.alignment = .leading
        textStack.spacing = 3
        textStack.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)

        let chevron = UIImageView(image: UIImage(systemName: "chevron.right"))
        chevron.translatesAutoresizingMaskIntoConstraints = false
        chevron.preferredSymbolConfiguration = UIImage.SymbolConfiguration(pointSize: 14, weight: .semibold)
        chevron.tintColor = .tertiaryLabel
        chevron.setContentHuggingPriority(.required, for: .horizontal)

        let contentStack = UIStackView(arrangedSubviews: [symbol, textStack, chevron])
        contentStack.translatesAutoresizingMaskIntoConstraints = false
        contentStack.axis = .horizontal
        contentStack.alignment = .center
        contentStack.spacing = 12
        addSubview(contentStack)

        NSLayoutConstraint.activate([
            heightAnchor.constraint(greaterThanOrEqualToConstant: 68),
            contentStack.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 14),
            contentStack.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -14),
            contentStack.topAnchor.constraint(equalTo: topAnchor, constant: 12),
            contentStack.bottomAnchor.constraint(equalTo: bottomAnchor, constant: -12),
            symbol.widthAnchor.constraint(equalToConstant: 28),
            chevron.widthAnchor.constraint(greaterThanOrEqualToConstant: 14)
        ])
    }

    required init?(coder: NSCoder) {
        nil
    }
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

private struct ShareLocalAccount: Identifiable, Codable, Equatable {
    let id: String
    let instanceURL: URL
    let apiBaseURL: URL
    let userId: Int
    let displayName: String
    let email: String
    let active: Bool
}

private struct ShareLocalAccountSnapshot: Codable, Equatable {
    let accounts: [ShareLocalAccount]
    let activeAccountId: String?
    let updatedAt: Date
}

private struct ShareUser: Codable, Equatable {
    let userId: Int
    let email: String
    let displayName: String
    let permalink: String
    let serverRole: String
    let passkeyCount: Int
    let hasPassword: Bool
    let avatarUrl: String?
}

private struct ShareCapability: Codable, Equatable {
    let id: String
    let introducedInVersion: String?
    let audience: String?
}

private struct ShareStoredNativeSession: Codable, Equatable {
    let instanceURL: URL
    let apiBaseURL: URL
    let accessToken: String
    let accessTokenExpiresAt: Date
    let refreshToken: String
    let refreshTokenExpiresAt: Date
    let user: ShareUser
    let capabilities: [ShareCapability]

    func withNativeSession(_ session: ShareNativeMobileSession) -> ShareStoredNativeSession {
        ShareStoredNativeSession(
            instanceURL: instanceURL,
            apiBaseURL: apiBaseURL,
            accessToken: session.accessToken,
            accessTokenExpiresAt: session.accessTokenExpiresAt,
            refreshToken: session.refreshToken,
            refreshTokenExpiresAt: session.refreshTokenExpiresAt,
            user: session.user,
            capabilities: capabilities
        )
    }

    func hasDifferentTokens(than session: ShareStoredNativeSession) -> Bool {
        accessToken != session.accessToken || refreshToken != session.refreshToken
    }

    var localAccount: ShareLocalAccount {
        ShareLocalAccount(
            id: instanceURL.absoluteString,
            instanceURL: instanceURL,
            apiBaseURL: apiBaseURL,
            userId: user.userId,
            displayName: user.displayName,
            email: user.email,
            active: false
        )
    }
}

private struct ShareStoredNativeSessionCollection: Codable, Equatable {
    let activeInstanceURL: URL?
    let sessions: [ShareStoredNativeSession]
}

private struct ShareNativeMobileSession: Codable, Equatable {
    let tokenType: String
    let accessToken: String
    let accessTokenExpiresAt: Date
    let refreshToken: String
    let refreshTokenExpiresAt: Date
    let user: ShareUser
}

private struct ShareBootstrap: Codable, Equatable {
    let serverId: String
    let user: ShareUser
    let tenants: [ShareTenant]
}

private struct ShareTenant: Codable, Identifiable, Hashable {
    let id: Int
    let name: String
    let displayName: String
    let domain: String
    let role: String
}

private struct ShareMemoryScopeRoot: Codable, Hashable {
    let kind: String
    let name: String
    let displayName: String
    let path: String
}

private struct ShareMemoryNavigationLocation: Codable, Hashable {
    let kind: String
    let path: String
    let displayName: String
}

private struct ShareMemoryNavigationBreadcrumb: Codable, Hashable {
    let kind: String
    let path: String
    let displayName: String
    let shortLabel: String?
}

private struct ShareMemoryFolderSummary: Codable, Hashable {
    let id: String?
    let name: String
    let displayName: String
    let path: String
}

private struct ShareMemoryNavigationContextResponse: Codable, Equatable {
    let location: ShareMemoryNavigationLocation
    let breadcrumbs: [ShareMemoryNavigationBreadcrumb]
    let scopeRoots: [ShareMemoryScopeRoot]
    let memory: ShareMemory?
    let folders: [ShareMemoryFolderSummary]
    let memories: [ShareMemory]

    enum CodingKeys: String, CodingKey {
        case location
        case breadcrumbs
        case scopeRoots
        case memory
        case folders
        case memories
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        location = try container.decode(ShareMemoryNavigationLocation.self, forKey: .location)
        breadcrumbs = try container.decodeIfPresent([ShareMemoryNavigationBreadcrumb].self, forKey: .breadcrumbs) ?? []
        scopeRoots = try container.decodeIfPresent([ShareMemoryScopeRoot].self, forKey: .scopeRoots) ?? []
        memory = try container.decodeIfPresent(ShareMemory.self, forKey: .memory)
        folders = try container.decodeIfPresent([ShareMemoryFolderSummary].self, forKey: .folders) ?? []
        memories = try container.decodeIfPresent([ShareMemory].self, forKey: .memories) ?? []
    }
}

private struct ShareMemory: Codable, Identifiable, Hashable {
    let id: String
    let name: String
    let displayName: String
    let path: String
    let parentId: String?
    let coverMediaId: String?
    let coverUrl: String?
    let startDate: String
    let endDate: String
    let mediaCount: Int
    let childCount: Int

    var dateRange: String {
        startDate == endDate ? startDate : "\(startDate) - \(endDate)"
    }

    var folderPath: String {
        let segments = path.split(separator: "/").map(String.init)
        guard segments.count > 1 else { return "/" }
        return "/" + segments.dropLast().joined(separator: "/")
    }

    func target(tenantName: String, parentMemoryTitle: String? = nil, depth: Int = 0) -> ShareImportMemoryTarget {
        ShareImportMemoryTarget(
            id: id,
            title: displayName,
            dateRange: dateRange,
            tenantName: tenantName,
            coverUrl: coverUrl,
            coverThumbnailFilename: nil,
            parentMemoryTitle: parentMemoryTitle,
            depth: depth
        )
    }
}

private actor ShareExtensionCredentialStore {
    private let service = "app.veyq.ios.native-session"
    private let account = "active"

    func loadAccounts() throws -> [ShareLocalAccount] {
        let collection = try loadCollection()
        let activeId = collection.activeInstanceURL?.absoluteString
        return collection.sessions.map { session in
            var account = session.localAccount
            if account.id == activeId {
                account = ShareLocalAccount(
                    id: account.id,
                    instanceURL: account.instanceURL,
                    apiBaseURL: account.apiBaseURL,
                    userId: account.userId,
                    displayName: account.displayName,
                    email: account.email,
                    active: true
                )
            }
            return account
        }
    }

    func validSession(accountId: String, now: Date = Date()) async throws -> ShareStoredNativeSession {
        let collection = try loadCollection()
        guard let session = collection.sessions.first(where: { $0.instanceURL.absoluteString == accountId }) else {
            throw ShareExtensionError.missingSession
        }
        guard session.accessTokenExpiresAt <= now.addingTimeInterval(60) else {
            return session
        }
        return try await refreshOrRecover(session, from: collection, now: now)
    }

    private func refreshOrRecover(
        _ session: ShareStoredNativeSession,
        from collection: ShareStoredNativeSessionCollection,
        now: Date
    ) async throws -> ShareStoredNativeSession {
        do {
            return try await refresh(session, from: collection)
        } catch ShareExtensionError.unauthorized {
            let latestCollection = try loadCollection()
            guard let latestSession = latestCollection.sessions.first(where: { $0.instanceURL == session.instanceURL }),
                  latestSession.hasDifferentTokens(than: session)
            else {
                throw ShareExtensionError.unauthorized
            }
            guard latestSession.accessTokenExpiresAt <= now.addingTimeInterval(60) else {
                return latestSession
            }
            return try await refresh(latestSession, from: latestCollection)
        }
    }

    private func refresh(
        _ session: ShareStoredNativeSession,
        from collection: ShareStoredNativeSessionCollection
    ) async throws -> ShareStoredNativeSession {
        let refreshed = try await ShareExtensionAPIClient(session: session).refreshMobileSession(refreshToken: session.refreshToken)
        let updated = session.withNativeSession(refreshed)
        let sessions = [updated] + collection.sessions.filter { $0.instanceURL != updated.instanceURL }
        try writeCollection(ShareStoredNativeSessionCollection(activeInstanceURL: updated.instanceURL, sessions: sessions))
        return updated
    }

    private func loadCollection() throws -> ShareStoredNativeSessionCollection {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true
        ]
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        if status == errSecItemNotFound {
            return ShareStoredNativeSessionCollection(activeInstanceURL: nil, sessions: [])
        }
        guard status == errSecSuccess, let data = item as? Data else {
            throw ShareExtensionError.missingSession
        }
        if let collection = try? JSONDecoder.veyqShare.decode(ShareStoredNativeSessionCollection.self, from: data) {
            return collection
        }
        let legacySession = try JSONDecoder.veyqShare.decode(ShareStoredNativeSession.self, from: data)
        return ShareStoredNativeSessionCollection(activeInstanceURL: legacySession.instanceURL, sessions: [legacySession])
    }

    private func writeCollection(_ collection: ShareStoredNativeSessionCollection) throws {
        try deleteCollection()
        let data = try JSONEncoder.veyqShare.encode(collection)
        let item: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecValueData as String: data
        ]
        let status = SecItemAdd(item as CFDictionary, nil)
        guard status == errSecSuccess else {
            throw ShareExtensionError.missingSession
        }
    }

    private func deleteCollection() throws {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
        let status = SecItemDelete(query as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw ShareExtensionError.missingSession
        }
    }
}

private struct ShareExtensionAPIClient {
    let session: ShareStoredNativeSession
    var urlSession: URLSession = .shared

    func bootstrap() async throws -> ShareBootstrap {
        try await get(path: "frontend/bootstrap")
    }

    func memoryNavigationContext(tenantName: String, path: String) async throws -> ShareMemoryNavigationContextResponse {
        try await get(path: "\(tenantName)/memory-navigation-context", query: ["path": path])
    }

    func listMemoryChapters(tenantName: String, memoryId: String) async throws -> [ShareMemory] {
        try await get(path: "\(tenantName)/memories/\(memoryId)/children")
    }

    func refreshMobileSession(refreshToken: String) async throws -> ShareNativeMobileSession {
        try await post(path: "auth/mobile/session/refresh", body: ["refreshToken": refreshToken], authenticated: false)
    }

    private func get<T: Decodable>(path: String, query: [String: String] = [:]) async throws -> T {
        var request = URLRequest(url: session.apiBaseURL.appendingVeyqShareEncodedPath(path, query: query))
        request.httpMethod = "GET"
        return try await send(request)
    }

    private func post<T: Decodable, Body: Encodable>(path: String, body: Body, authenticated: Bool = true) async throws -> T {
        var request = URLRequest(url: session.apiBaseURL.appendingVeyqShareEncodedPath(path))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONEncoder.veyqShare.encode(body)
        return try await send(request, authenticated: authenticated)
    }

    private func send<T: Decodable>(_ request: URLRequest, authenticated: Bool = true) async throws -> T {
        var request = request
        if authenticated {
            request.setValue("Bearer \(session.accessToken)", forHTTPHeaderField: "Authorization")
        }
        let (data, response) = try await urlSession.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw ShareExtensionError.invalidResponse
        }
        guard 200..<300 ~= http.statusCode else {
            if http.statusCode == 401 {
                throw ShareExtensionError.unauthorized
            }
            throw ShareExtensionError.serverStatus(http.statusCode)
        }
        return try JSONDecoder.veyqShare.decode(T.self, from: data)
    }
}

private enum ShareExtensionError: LocalizedError {
    case missingSession
    case invalidResponse
    case unauthorized
    case serverStatus(Int)

    var errorDescription: String? {
        switch self {
        case .missingSession:
            NSLocalizedString("No native session is stored.", comment: "")
        case .invalidResponse:
            NSLocalizedString("This does not look like a compatible veyq instance.", comment: "")
        case .unauthorized:
            NSLocalizedString("The session is no longer valid.", comment: "")
        case .serverStatus(let status):
            String(format: NSLocalizedString("The server returned HTTP %d.", comment: ""), status)
        }
    }
}

private extension URL {
    func appendingVeyqShareEncodedPath(_ path: String, query: [String: String] = [:]) -> URL {
        var url = self
        path.split(separator: "/").forEach { segment in
            url.append(path: String(segment))
        }
        guard !query.isEmpty, var components = URLComponents(url: url, resolvingAgainstBaseURL: false) else {
            return url
        }
        components.queryItems = query.map { URLQueryItem(name: $0.key, value: $0.value) }
        return components.url ?? url
    }
}

private extension JSONDecoder {
    static var veyqShare: JSONDecoder {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .custom { decoder in
            let container = try decoder.singleValueContainer()
            let value = try container.decode(String.self)
            if let date = ISO8601DateFormatter.veyqShareDate(from: value) {
                return date
            }
            throw DecodingError.dataCorruptedError(in: container, debugDescription: "Invalid ISO-8601 date: \(value)")
        }
        return decoder
    }
}

private extension JSONEncoder {
    static var veyqShare: JSONEncoder {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        return encoder
    }
}

private extension ISO8601DateFormatter {
    static func veyqShareDate(from value: String) -> Date? {
        let standard = ISO8601DateFormatter()
        standard.formatOptions = [.withInternetDateTime]
        if let date = standard.date(from: value) {
            return date
        }

        let fractional = ISO8601DateFormatter()
        fractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return fractional.date(from: value)
    }
}

private enum ShareImportError: LocalizedError {
    case appGroupUnavailable
    case missingBatch

    var errorDescription: String? {
        switch self {
        case .appGroupUnavailable:
            NSLocalizedString("The veyq App Group is not available for this build. Check the app and extension signing capabilities for group.app.veyq.ios.", comment: "")
        case .missingBatch:
            NSLocalizedString("Shared files could not be prepared.", comment: "")
        }
    }
}
