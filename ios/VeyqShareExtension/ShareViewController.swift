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
    private var availableTenants: [ShareTenant] = []
    private var availableSpaces: [ShareSpace] = []
    private var availableGroupSpaces: [ShareSpace] = []
    private var activeSpace: ShareSpace?
    private var activeContentRootPath = "/"
    private var currentNavigationContext: ShareMemoryNavigationContextResponse?
    private var searchQuery = ""
    private var activeTenant: ShareTenant?
    private var apiClient: ShareExtensionAPIClient?
    private let credentialStore = ShareExtensionCredentialStore()
    private let statusLabel = UILabel()
    private let closeButton = UIButton(type: .system)
    private let targetScrollView = UIScrollView()
    private let targetStack = UIStackView()
    private let searchTextField = UISearchTextField()
    private var searchFieldHeightConstraint: NSLayoutConstraint?
    private let spaceBarContainer = UIView()
    private let spaceBarScrollView = UIScrollView()
    private let spaceBarStack = UIStackView()
    private var spaceBarHeightConstraint: NSLayoutConstraint?

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
        configureSpaceBar(isVisible: false)
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
            availableTenants = bootstrap.tenants
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
        configureSpaceBar(isVisible: false)
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
            updateSpaces(tenant: tenant, navigationContext: navigationContext)
            showFolderBrowser(navigationContext: navigationContext)
        } catch {
            showStatus(error.localizedDescription)
            showCloseButton()
        }
    }

    private func showFolderBrowser(navigationContext: ShareMemoryNavigationContextResponse) {
        guard let tenant = activeTenant else { return }
        currentNavigationContext = navigationContext
        statusLabel.isHidden = true
        closeButton.isHidden = true
        targetScrollView.isHidden = false
        configureSpaceBar(isVisible: false)
        resetTargetStack()

        addHeader(
            title: navigationContext.location.displayName,
            subtitle: Self.localized("Choose a Memory or browse folders and chapters.")
        )

        addContextSelector(tenant: tenant)
        addSearchField()

        if navigationContext.location.path != activeContentRootPath,
           let parent = navigationContext.breadcrumbs.dropLast().last {
            addNavigationRow(title: Self.localized("Back"), subtitle: parent.displayName, systemImageName: "chevron.left") { [weak self] in
                Task { await self?.openTenant(tenant, path: parent.path) }
            }
        }

        if searchQuery.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            if navigationContext.location.path == activeContentRootPath {
                addTenantSection()
            }
            addRecentTargets(tenantName: tenant.name)
            if navigationContext.location.path == activeContentRootPath {
                addGroupSection()
            }
        }

        let visibleMemories = filteredMemories(in: navigationContext)
        let visibleFolders = filteredFolders(in: navigationContext)
        let memoryCount = visibleMemories.count + (navigationContext.memory == nil || !matchesSearch(navigationContext.memory?.displayName ?? "") ? 0 : 1)
        addSectionHeader(
            title: memorySectionTitle(for: navigationContext),
            subtitle: navigationContext.location.path == activeContentRootPath
                ? Self.localized("Curated trips, events and moments")
                : Self.localized("Memories in this organizational folder"),
            count: memoryCount
        )
        if let memory = navigationContext.memory, matchesSearch(memory.displayName) {
            addMemoryTarget(memory.target(tenantName: tenant.name))
        }
        for memory in visibleMemories {
            addMemoryTarget(memory.target(tenantName: tenant.name))
        }
        if memoryCount == 0 {
            addEmptyState(
                systemImageName: "rectangle.stack.badge.plus",
                title: Self.localized("No Memories here yet."),
                message: Self.localized("Create a Memory, then add photos, journal notes and context.")
            )
        }

        let chapterMemories = visibleMemories.filter { $0.childCount > 0 }
        if !chapterMemories.isEmpty {
            addSectionHeader(
                title: Self.localized("Chapters"),
                subtitle: Self.localized("Navigate into related Memories."),
                count: chapterMemories.reduce(0) { $0 + $1.childCount }
            )
            for memory in chapterMemories {
                addNavigationRow(
                    title: Self.localizedFormat("%@ Chapters", memory.displayName),
                    subtitle: Self.localizedFormat("%d chapters", memory.childCount),
                    systemImageName: "rectangle.stack"
                ) { [weak self] in
                    Task { await self?.openChapters(parent: memory) }
                }
            }
        }

        if navigationContext.location.path != activeContentRootPath || !navigationContext.folders.isEmpty {
            addSectionHeader(
                title: Self.localized("Folders"),
                subtitle: Self.localized("Keep related Memories together in one place."),
                count: visibleFolders.count
            )
            for folder in visibleFolders {
                addNavigationRow(
                    title: folder.displayName,
                    subtitle: folder.path,
                    systemImageName: "folder"
                ) { [weak self] in
                    Task { await self?.openTenant(tenant, path: folder.path) }
                }
            }
        }
    }

    private func addContextSelector(tenant: ShareTenant) {
        let spaceLabel = activeSpace?.label ?? tenant.displayName
        let button = ShareBrowserRowControl(
            title: Self.localized("Destination Area"),
            subtitle: "\(tenant.displayName) · \(spaceLabel)",
            systemImageName: "slider.horizontal.3"
        )
        button.addAction(UIAction { [weak self, weak button] _ in
            self?.showContextMenu(sourceView: button)
        }, for: .touchUpInside)
        targetStack.addArrangedSubview(button)
    }

    private func addSearchField() {
        searchTextField.text = searchQuery
        searchTextField.placeholder = Self.localized("Search Memories")
        searchTextField.font = .preferredFont(forTextStyle: .body)
        searchTextField.adjustsFontForContentSizeCategory = true
        searchTextField.backgroundColor = .secondarySystemBackground
        searchTextField.borderStyle = .none
        searchTextField.layer.cornerRadius = 16
        searchTextField.layer.cornerCurve = .continuous
        searchTextField.layer.masksToBounds = true
        searchTextField.returnKeyType = .done
        searchTextField.clearButtonMode = .whileEditing
        if searchFieldHeightConstraint == nil {
            searchFieldHeightConstraint = searchTextField.heightAnchor.constraint(equalToConstant: 52)
            searchFieldHeightConstraint?.isActive = true
        }
        searchTextField.removeTarget(self, action: #selector(searchTextDidChange), for: .editingChanged)
        searchTextField.removeTarget(self, action: #selector(searchDidEnd), for: .editingDidEndOnExit)
        searchTextField.addTarget(self, action: #selector(searchTextDidChange), for: .editingChanged)
        searchTextField.addTarget(self, action: #selector(searchDidEnd), for: .editingDidEndOnExit)
        targetStack.addArrangedSubview(searchTextField)
        targetStack.setCustomSpacing(18, after: searchTextField)
    }

    private func addTenantSection() {
        guard availableTenants.count > 1 else { return }
        addSectionHeader(
            title: Self.localized("Tenants"),
            subtitle: Self.localized("Choose a tenant."),
            count: availableTenants.count
        )

        let scrollView = UIScrollView()
        scrollView.translatesAutoresizingMaskIntoConstraints = false
        scrollView.showsHorizontalScrollIndicator = false

        let stack = UIStackView()
        stack.translatesAutoresizingMaskIntoConstraints = false
        stack.axis = .horizontal
        stack.spacing = 10

        scrollView.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: scrollView.contentLayoutGuide.leadingAnchor),
            stack.trailingAnchor.constraint(equalTo: scrollView.contentLayoutGuide.trailingAnchor),
            stack.topAnchor.constraint(equalTo: scrollView.contentLayoutGuide.topAnchor),
            stack.bottomAnchor.constraint(equalTo: scrollView.contentLayoutGuide.bottomAnchor),
            stack.heightAnchor.constraint(equalTo: scrollView.frameLayoutGuide.heightAnchor)
        ])

        for tenant in availableTenants {
            let card = TenantSelectorPillControl(tenant: tenant, isSelected: tenant.id == activeTenant?.id)
            card.addAction(UIAction { [weak self] _ in
                self?.searchQuery = ""
                Task { await self?.openTenant(tenant, path: "/") }
            }, for: .touchUpInside)
            stack.addArrangedSubview(card)
        }

        targetStack.addArrangedSubview(scrollView)
        scrollView.heightAnchor.constraint(equalToConstant: 76).isActive = true
        targetStack.setCustomSpacing(20, after: scrollView)
    }

    private func addRecentTargets(tenantName: String) {
        let targets = (loadShareImportTargets()?.targets ?? [])
            .filter { $0.tenantName == tenantName }
            .prefix(8)
        guard !targets.isEmpty else { return }

        addSectionHeader(
            title: Self.localized("Recent"),
            subtitle: Self.localized("Recently used import targets."),
            count: targets.count
        )

        let scrollView = UIScrollView()
        scrollView.translatesAutoresizingMaskIntoConstraints = false
        scrollView.showsHorizontalScrollIndicator = false

        let stack = UIStackView()
        stack.translatesAutoresizingMaskIntoConstraints = false
        stack.axis = .horizontal
        stack.spacing = 10

        scrollView.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: scrollView.contentLayoutGuide.leadingAnchor),
            stack.trailingAnchor.constraint(equalTo: scrollView.contentLayoutGuide.trailingAnchor),
            stack.topAnchor.constraint(equalTo: scrollView.contentLayoutGuide.topAnchor),
            stack.bottomAnchor.constraint(equalTo: scrollView.contentLayoutGuide.bottomAnchor),
            stack.heightAnchor.constraint(equalTo: scrollView.frameLayoutGuide.heightAnchor)
        ])

        for target in targets {
            let card = RecentTargetPillControl(
                target: target,
                coverImage: cachedCoverImage(for: target, size: CGSize(width: 260, height: 146))
            )
            card.addAction(UIAction { [weak self] _ in
                self?.showMemoryPreview(target)
            }, for: .touchUpInside)
            stack.addArrangedSubview(card)
        }

        targetStack.addArrangedSubview(scrollView)
        scrollView.heightAnchor.constraint(equalToConstant: 222).isActive = true
        targetStack.setCustomSpacing(20, after: scrollView)
    }

    private func addGroupSection() {
        guard !availableGroupSpaces.isEmpty else { return }
        addSectionHeader(
            title: Self.localized("Groups"),
            subtitle: Self.localized("Shared Memory spaces for family and friends."),
            count: availableGroupSpaces.count
        )
        for groupSpace in availableGroupSpaces {
            addNavigationRow(title: groupSpace.label, subtitle: Self.localized("Group"), systemImageName: "at") { [weak self] in
                guard let self, let tenant = self.activeTenant else { return }
                Task { await self.openTenant(tenant, path: groupSpace.path) }
            }
        }
    }

    private func filteredMemories(in navigationContext: ShareMemoryNavigationContextResponse) -> [ShareMemory] {
        navigationContext.memories.filter { matchesSearch($0.displayName) }
    }

    private func filteredFolders(in navigationContext: ShareMemoryNavigationContextResponse) -> [ShareMemoryFolderSummary] {
        navigationContext.folders.filter { matchesSearch($0.displayName) || matchesSearch($0.path) }
    }

    private func matchesSearch(_ value: String) -> Bool {
        let trimmed = searchQuery.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return true }
        return value.localizedCaseInsensitiveContains(trimmed)
    }

    private func showContextMenu(sourceView: UIView?) {
        let sheet = ShareContextPickerViewController(
            activeTenant: activeTenant,
            tenants: availableTenants,
            activeSpace: activeSpace,
            spaces: availableContextSpaces,
            onSelectTenant: { [weak self] tenant in
                Task { await self?.openTenant(tenant, path: "/") }
            },
            onSelectSpace: { [weak self] space in
                guard let self, let tenant = self.activeTenant else { return }
                if space.kind == .groupsOverview {
                    self.showGroupOverview()
                } else {
                    Task { await self.openTenant(tenant, path: space.path) }
                }
            }
        )
        if let presentation = sheet.sheetPresentationController {
            presentation.detents = [.medium(), .large()]
            presentation.prefersGrabberVisible = true
            presentation.preferredCornerRadius = 28
        }
        present(sheet, animated: true)
    }

    private var availableContextSpaces: [ShareSpace] {
        let existingIds = Set(availableSpaces.map(\.id))
        return availableSpaces + availableGroupSpaces.filter { !existingIds.contains($0.id) }
    }

    @objc private func searchTextDidChange() {
        searchQuery = searchTextField.text ?? ""
        guard let currentNavigationContext else { return }
        showFolderBrowser(navigationContext: currentNavigationContext)
        searchTextField.becomeFirstResponder()
    }

    @objc private func searchDidEnd() {
        searchTextField.resignFirstResponder()
    }

    private func updateSpaces(tenant: ShareTenant, navigationContext: ShareMemoryNavigationContextResponse) {
        let tenantSpace = ShareSpace(
            id: tenant.name,
            kind: .tenant,
            label: Self.localized("All Memories"),
            path: "/",
            groupName: nil
        )
        let scopedSpaces = navigationContext.scopeRoots.map { root in
            let kind: ShareSpaceKind = root.kind == "GROUP" ? .group : .personal
            return ShareSpace(
                id: "\(root.kind.lowercased())-\(root.name)",
                kind: kind,
                label: Self.scopedSpaceLabel(displayName: root.displayName, kind: kind),
                path: root.path,
                groupName: kind == .group ? root.name : nil
            )
        }
        availableGroupSpaces = scopedSpaces.filter { $0.kind == .group }
        let personalSpace = scopedSpaces.first { $0.kind == .personal }
        let contentRoot = ([tenantSpace] + scopedSpaces)
            .filter { space in
                navigationContext.location.path == space.path
                    || (space.path != "/" && navigationContext.location.path.hasPrefix("\(space.path)/"))
            }
            .max { lhs, rhs in lhs.path.count < rhs.path.count }
            ?? tenantSpace

        activeContentRootPath = contentRoot.path

        let groupTab = ShareSpace(
            id: "groups",
            kind: .groupsOverview,
            label: Self.localized("Groups"),
            path: "",
            groupName: nil
        )
        let personalTab = personalSpace.map { space in
            ShareSpace(
                id: space.id,
                kind: .personal,
                label: Self.localized("Personal"),
                path: space.path,
                groupName: nil
            )
        }
        availableSpaces = [tenantSpace]
            + (availableGroupSpaces.isEmpty ? [] : [groupTab])
            + (personalTab.map { [$0] } ?? [])
        activeSpace = contentRoot.kind == .group
            ? groupTab
            : availableSpaces.first { $0.kind == contentRoot.kind } ?? tenantSpace
        rebuildSpaceBar()
    }

    private nonisolated static func scopedSpaceLabel(displayName: String, kind: ShareSpaceKind) -> String {
        let trimmed = displayName.trimmingCharacters(in: .whitespacesAndNewlines)
        switch kind {
        case .tenant:
            return trimmed
        case .groupsOverview:
            return trimmed
        case .group:
            return trimmed.hasPrefix("@") ? trimmed : "@\(trimmed)"
        case .personal:
            return trimmed.hasPrefix("~") ? trimmed : "~\(trimmed)"
        }
    }

    private func shouldShowSpaceBar() -> Bool {
        availableSpaces.count > 1
    }

    private func showGroupOverview() {
        statusLabel.isHidden = true
        closeButton.isHidden = true
        targetScrollView.isHidden = false
        activeSpace = availableSpaces.first { $0.kind == .groupsOverview }
        activeContentRootPath = ""
        rebuildSpaceBar()
        configureSpaceBar(isVisible: availableSpaces.count > 1)
        resetTargetStack()

        addHeader(
            title: Self.localized("Groups"),
            subtitle: Self.localized("Shared Memory spaces for family and friends.")
        )

        if availableGroupSpaces.isEmpty {
            let empty = UILabel()
            empty.text = Self.localized("No groups available.")
            empty.font = .preferredFont(forTextStyle: .body)
            empty.textColor = .secondaryLabel
            empty.numberOfLines = 0
            targetStack.addArrangedSubview(empty)
            return
        }

        for groupSpace in availableGroupSpaces {
            addNavigationRow(
                title: groupSpace.label,
                subtitle: Self.localized("Group"),
                systemImageName: "at"
            ) { [weak self] in
                guard let self, let tenant = self.activeTenant else { return }
                Task { await self.openTenant(tenant, path: groupSpace.path) }
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

        spaceBarContainer.translatesAutoresizingMaskIntoConstraints = false
        spaceBarContainer.backgroundColor = .systemBackground
        spaceBarContainer.isHidden = true

        spaceBarScrollView.translatesAutoresizingMaskIntoConstraints = false
        spaceBarScrollView.showsHorizontalScrollIndicator = false
        spaceBarScrollView.alwaysBounceHorizontal = true

        spaceBarStack.translatesAutoresizingMaskIntoConstraints = false
        spaceBarStack.axis = .horizontal
        spaceBarStack.alignment = .center
        spaceBarStack.spacing = 8

        statusLabel.translatesAutoresizingMaskIntoConstraints = false
        statusLabel.font = .preferredFont(forTextStyle: .body)
        statusLabel.textAlignment = .center
        statusLabel.textColor = .label
        statusLabel.numberOfLines = 0

        closeButton.translatesAutoresizingMaskIntoConstraints = false
        closeButton.setTitle(Self.localized("Close"), for: .normal)
        closeButton.isHidden = true
        closeButton.alpha = 0
        closeButton.isUserInteractionEnabled = false
        closeButton.addTarget(self, action: #selector(closeExtension), for: .touchUpInside)

        view.addSubview(statusLabel)
        view.addSubview(targetScrollView)
        targetScrollView.addSubview(targetStack)
        view.addSubview(spaceBarContainer)
        spaceBarContainer.addSubview(spaceBarScrollView)
        spaceBarScrollView.addSubview(spaceBarStack)
        view.addSubview(closeButton)

        let spaceBarHeightConstraint = spaceBarContainer.heightAnchor.constraint(equalToConstant: 0)
        self.spaceBarHeightConstraint = spaceBarHeightConstraint

        NSLayoutConstraint.activate([
            statusLabel.leadingAnchor.constraint(equalTo: view.layoutMarginsGuide.leadingAnchor),
            statusLabel.trailingAnchor.constraint(equalTo: view.layoutMarginsGuide.trailingAnchor),
            statusLabel.centerYAnchor.constraint(equalTo: view.centerYAnchor),

            targetScrollView.leadingAnchor.constraint(equalTo: view.layoutMarginsGuide.leadingAnchor),
            targetScrollView.trailingAnchor.constraint(equalTo: view.layoutMarginsGuide.trailingAnchor),
            targetScrollView.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 20),
            targetScrollView.bottomAnchor.constraint(equalTo: spaceBarContainer.topAnchor, constant: -8),

            targetStack.leadingAnchor.constraint(equalTo: targetScrollView.contentLayoutGuide.leadingAnchor),
            targetStack.trailingAnchor.constraint(equalTo: targetScrollView.contentLayoutGuide.trailingAnchor),
            targetStack.topAnchor.constraint(equalTo: targetScrollView.contentLayoutGuide.topAnchor),
            targetStack.bottomAnchor.constraint(equalTo: targetScrollView.contentLayoutGuide.bottomAnchor),
            targetStack.widthAnchor.constraint(equalTo: targetScrollView.frameLayoutGuide.widthAnchor),

            spaceBarContainer.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            spaceBarContainer.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            spaceBarContainer.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor),
            spaceBarHeightConstraint,

            spaceBarScrollView.leadingAnchor.constraint(equalTo: spaceBarContainer.leadingAnchor),
            spaceBarScrollView.trailingAnchor.constraint(equalTo: spaceBarContainer.trailingAnchor),
            spaceBarScrollView.topAnchor.constraint(equalTo: spaceBarContainer.topAnchor),
            spaceBarScrollView.bottomAnchor.constraint(equalTo: spaceBarContainer.bottomAnchor),

            spaceBarStack.leadingAnchor.constraint(equalTo: spaceBarScrollView.contentLayoutGuide.leadingAnchor, constant: 16),
            spaceBarStack.trailingAnchor.constraint(equalTo: spaceBarScrollView.contentLayoutGuide.trailingAnchor, constant: -16),
            spaceBarStack.centerYAnchor.constraint(equalTo: spaceBarScrollView.frameLayoutGuide.centerYAnchor),
            spaceBarStack.heightAnchor.constraint(lessThanOrEqualTo: spaceBarScrollView.frameLayoutGuide.heightAnchor, constant: -8),

            closeButton.topAnchor.constraint(equalTo: statusLabel.bottomAnchor, constant: 20),
            closeButton.centerXAnchor.constraint(equalTo: view.centerXAnchor)
        ])
    }

    private func showStatus(_ message: String) {
        statusLabel.text = message
        configureSpaceBar(isVisible: false)
    }

    private func showCloseButton() {
        closeButton.setTitle(Self.localized("Close"), for: .normal)
        closeButton.alpha = 1
        closeButton.isHidden = false
        closeButton.isUserInteractionEnabled = true
        view.bringSubviewToFront(closeButton)
    }

    private func hideCloseButton() {
        closeButton.isUserInteractionEnabled = false
        closeButton.alpha = 0
        closeButton.isHidden = true
    }

    private func showLoadingBrowser(_ message: String) {
        targetScrollView.isHidden = true
        hideCloseButton()
        statusLabel.isHidden = false
        configureSpaceBar(isVisible: false)
        showStatus(message)
    }

    private func resetTargetStack() {
        hideCloseButton()
        targetStack.arrangedSubviews.forEach { view in
            targetStack.removeArrangedSubview(view)
            view.removeFromSuperview()
        }
    }

    private func configureSpaceBar(isVisible: Bool) {
        spaceBarContainer.isHidden = !isVisible
        spaceBarHeightConstraint?.constant = isVisible ? 64 : 0
    }

    private func rebuildSpaceBar() {
        spaceBarScrollView.alwaysBounceHorizontal = true
        spaceBarStack.arrangedSubviews.forEach { view in
            spaceBarStack.removeArrangedSubview(view)
            view.removeFromSuperview()
        }

        for space in availableSpaces {
            let tab = ShareSpaceTabControl(space: space, isSelected: activeSpace?.id == space.id)
            tab.addAction(UIAction { [weak self] _ in
                guard let self, let tenant = self.activeTenant else { return }
                if space.kind == .groupsOverview {
                    self.showGroupOverview()
                } else {
                    Task { await self.openTenant(tenant, path: space.path) }
                }
            }, for: .touchUpInside)
            spaceBarStack.addArrangedSubview(tab)
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

    private func memorySectionTitle(for navigationContext: ShareMemoryNavigationContextResponse) -> String {
        if navigationContext.location.path != activeContentRootPath {
            return navigationContext.location.displayName
        }
        return activeSpace?.label ?? Self.localized("Memories")
    }

    private func addSectionHeader(title: String, subtitle: String, count: Int) {
        let sectionStack = UIStackView()
        sectionStack.axis = .horizontal
        sectionStack.alignment = .lastBaseline
        sectionStack.spacing = 12

        let textStack = UIStackView()
        textStack.axis = .vertical
        textStack.alignment = .leading
        textStack.spacing = 3

        let titleLabel = UILabel()
        titleLabel.text = title
        titleLabel.font = .preferredFont(forTextStyle: .headline)
        titleLabel.adjustsFontForContentSizeCategory = true
        titleLabel.textColor = .label
        titleLabel.numberOfLines = 2

        let subtitleLabel = UILabel()
        subtitleLabel.text = subtitle
        subtitleLabel.font = .preferredFont(forTextStyle: .caption1)
        subtitleLabel.adjustsFontForContentSizeCategory = true
        subtitleLabel.textColor = .secondaryLabel
        subtitleLabel.numberOfLines = 0

        let countLabel = UILabel()
        countLabel.text = "\(count)"
        countLabel.font = .preferredFont(forTextStyle: .callout)
        countLabel.adjustsFontForContentSizeCategory = true
        countLabel.textColor = .secondaryLabel
        countLabel.setContentHuggingPriority(.required, for: .horizontal)

        textStack.addArrangedSubview(titleLabel)
        textStack.addArrangedSubview(subtitleLabel)
        sectionStack.addArrangedSubview(textStack)
        sectionStack.addArrangedSubview(countLabel)
        targetStack.addArrangedSubview(sectionStack)
        targetStack.setCustomSpacing(8, after: sectionStack)
    }

    private func addEmptyState(systemImageName: String, title: String, message: String) {
        let container = UIStackView()
        container.axis = .vertical
        container.alignment = .leading
        container.spacing = 8
        container.isLayoutMarginsRelativeArrangement = true
        container.directionalLayoutMargins = NSDirectionalEdgeInsets(top: 16, leading: 16, bottom: 16, trailing: 16)
        container.backgroundColor = .secondarySystemBackground
        container.layer.cornerRadius = 14
        container.layer.cornerCurve = .continuous
        container.layer.borderWidth = 1
        container.layer.borderColor = UIColor.separator.withAlphaComponent(0.28).cgColor

        let symbol = UIImageView(image: UIImage(systemName: systemImageName))
        symbol.preferredSymbolConfiguration = UIImage.SymbolConfiguration(pointSize: 24, weight: .semibold)
        symbol.tintColor = UIColor(red: 0.36, green: 0.30, blue: 0.96, alpha: 1)
        symbol.contentMode = .scaleAspectFit

        let titleLabel = UILabel()
        titleLabel.text = title
        titleLabel.font = .preferredFont(forTextStyle: .headline)
        titleLabel.adjustsFontForContentSizeCategory = true
        titleLabel.textColor = .label
        titleLabel.numberOfLines = 0

        let messageLabel = UILabel()
        messageLabel.text = message
        messageLabel.font = .preferredFont(forTextStyle: .subheadline)
        messageLabel.adjustsFontForContentSizeCategory = true
        messageLabel.textColor = .secondaryLabel
        messageLabel.numberOfLines = 0

        container.addArrangedSubview(symbol)
        container.addArrangedSubview(titleLabel)
        container.addArrangedSubview(messageLabel)
        targetStack.addArrangedSubview(container)
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
            self?.showMemoryPreview(target)
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
                self?.showMemoryPreview(target)
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
        let thumbnail = image.preparingThumbnail(of: CGSize(width: 160, height: 90)) ?? image
        card.setCoverImage(thumbnail)
    }

    private func shareImportCoverURL(filename: String) -> URL? {
        sharedContainerURL()?
            .appendingPathComponent("ShareImportCovers", isDirectory: true)
            .appendingPathComponent((filename as NSString).lastPathComponent)
    }

    private func cachedCoverImage(for target: ShareImportMemoryTarget, size: CGSize) -> UIImage {
        guard let filename = target.coverThumbnailFilename,
              let url = shareImportCoverURL(filename: filename),
              let image = UIImage(contentsOfFile: url.path)
        else {
            return Self.placeholderCoverImage()
        }
        return image.preparingThumbnail(of: size) ?? image
    }

    private nonisolated static func placeholderCoverImage() -> UIImage {
        let size = CGSize(width: 320, height: 180)
        let renderer = UIGraphicsImageRenderer(size: size)
        return renderer.image { context in
            let rect = CGRect(origin: .zero, size: size)
            let path = UIBezierPath(roundedRect: rect, cornerRadius: 24)
            path.addClip()

            let colors = [
                UIColor(red: 0.36, green: 0.30, blue: 0.96, alpha: 0.30).cgColor,
                UIColor(red: 0.93, green: 0.36, blue: 0.62, alpha: 0.18).cgColor,
                UIColor(red: 0.08, green: 0.49, blue: 0.35, alpha: 0.12).cgColor
            ] as CFArray
            let colorSpace = CGColorSpaceCreateDeviceRGB()
            let gradient = CGGradient(colorsSpace: colorSpace, colors: colors, locations: [0, 0.55, 1])!
            context.cgContext.drawLinearGradient(
                gradient,
                start: CGPoint(x: rect.minX, y: rect.minY),
                end: CGPoint(x: rect.maxX, y: rect.maxY),
                options: []
            )

            let symbolConfiguration = UIImage.SymbolConfiguration(pointSize: 46, weight: .semibold)
            guard let symbol = UIImage(systemName: "sparkles.rectangle.stack", withConfiguration: symbolConfiguration) else { return }
            let symbolColor = UIColor(red: 0.36, green: 0.30, blue: 0.96, alpha: 0.92)
            let symbolImage = symbol.withTintColor(symbolColor, renderingMode: .alwaysOriginal)
            let symbolSize = symbolImage.size
            let symbolOrigin = CGPoint(
                x: (size.width - symbolSize.width) / 2,
                y: (size.height - symbolSize.height) / 2
            )
            symbolImage.draw(at: symbolOrigin)
        }
    }

    private static var successColor: UIColor {
        UIColor(red: 0.08, green: 0.49, blue: 0.35, alpha: 1)
    }

    private static var infoColor: UIColor {
        UIColor(red: 0.20, green: 0.39, blue: 0.82, alpha: 1)
    }

    private func confirmTarget(_ target: ShareImportMemoryTarget, sourceView: UIView?) {
        let alert = UIAlertController(
            title: target.title,
            message: Self.localizedFormat("Import shared files into %@?", target.title),
            preferredStyle: .actionSheet
        )
        alert.addAction(UIAlertAction(title: Self.localized("Cancel"), style: .cancel))
        alert.addAction(UIAlertAction(title: Self.localized("Import"), style: .default) { [weak self] _ in
            self?.chooseTarget(target)
        })
        if let popover = alert.popoverPresentationController {
            popover.sourceView = sourceView ?? view
            popover.sourceRect = sourceView?.bounds ?? view.bounds
        }
        present(alert, animated: true)
    }

    private func showMemoryPreview(_ target: ShareImportMemoryTarget) {
        renderMemoryPreview(target: target, header: nil, media: [], isLoading: true, errorMessage: nil)
        Task { await loadMemoryPreview(target) }
    }

    private func loadMemoryPreview(_ target: ShareImportMemoryTarget) async {
        guard let apiClient else {
            renderMemoryPreview(target: target, header: nil, media: [], isLoading: false, errorMessage: Self.localized("No native session is stored."))
            return
        }
        do {
            async let header = apiClient.memoryHeader(tenantName: target.tenantName, memoryId: target.id)
            async let media = apiClient.memoryMedia(tenantName: target.tenantName, memoryId: target.id, includeChildren: false)
            renderMemoryPreview(
                target: target,
                header: try await header,
                media: try await media,
                isLoading: false,
                errorMessage: nil
            )
        } catch {
            renderMemoryPreview(target: target, header: nil, media: [], isLoading: false, errorMessage: error.localizedDescription)
        }
    }

    private func renderMemoryPreview(
        target: ShareImportMemoryTarget,
        header: ShareMemoryHeader?,
        media: [ShareMemoryMedia],
        isLoading: Bool,
        errorMessage: String?
    ) {
        statusLabel.isHidden = true
        closeButton.isHidden = true
        targetScrollView.isHidden = false
        resetTargetStack()

        addPreviewBackButton()
        addPreviewMemoryHeader(target: target, header: header, mediaCountFallback: media.count)

        if isLoading {
            let loadingLabel = UILabel()
            loadingLabel.text = Self.localized("Loading Memory...")
            loadingLabel.font = .preferredFont(forTextStyle: .subheadline)
            loadingLabel.adjustsFontForContentSizeCategory = true
            loadingLabel.textColor = .secondaryLabel
            targetStack.addArrangedSubview(loadingLabel)
        } else if let errorMessage {
            addEmptyState(
                systemImageName: "exclamationmark.triangle",
                title: Self.localized("Memory preview could not be loaded."),
                message: errorMessage
            )
        } else {
            addPreviewGallery(media)
        }

        showConfirmBar(for: target)
    }

    private func previewSubtitle(target: ShareImportMemoryTarget, header: ShareMemoryHeader?) -> String {
        let dateRange: String
        if let header {
            dateRange = header.startDate == header.endDate ? header.startDate : "\(header.startDate) - \(header.endDate)"
        } else {
            dateRange = target.dateRange
        }
        if let parentMemoryTitle = target.parentMemoryTitle {
            return Self.localizedFormat("Chapter in %@", parentMemoryTitle)
        }
        return dateRange
    }

    private func addPreviewBackButton() {
        let button = UIButton(type: .system)
        button.translatesAutoresizingMaskIntoConstraints = false
        button.setImage(UIImage(systemName: "chevron.left"), for: .normal)
        button.tintColor = .secondaryLabel
        button.accessibilityLabel = Self.localized("Back")
        button.contentHorizontalAlignment = .leading
        button.addAction(UIAction { [weak self] _ in
            guard let self, let context = self.currentNavigationContext else { return }
            self.configureSpaceBar(isVisible: false)
            self.showFolderBrowser(navigationContext: context)
        }, for: .touchUpInside)

        let row = UIStackView(arrangedSubviews: [button, UIView()])
        row.axis = .horizontal
        row.alignment = .center
        row.translatesAutoresizingMaskIntoConstraints = false
        button.widthAnchor.constraint(equalToConstant: 44).isActive = true
        button.heightAnchor.constraint(equalToConstant: 44).isActive = true

        targetStack.addArrangedSubview(row)
        targetStack.setCustomSpacing(2, after: row)
    }

    private func addPreviewMemoryHeader(target: ShareImportMemoryTarget, header: ShareMemoryHeader?, mediaCountFallback: Int) {
        let container = UIStackView()
        container.axis = .vertical
        container.alignment = .leading
        container.spacing = 10

        let coverImageView = UIImageView(image: cachedCoverImage(for: target, size: CGSize(width: 420, height: 236)))
        coverImageView.translatesAutoresizingMaskIntoConstraints = false
        coverImageView.contentMode = .scaleAspectFill
        coverImageView.clipsToBounds = true
        coverImageView.layer.cornerRadius = 18
        coverImageView.layer.cornerCurve = .continuous
        coverImageView.accessibilityIgnoresInvertColors = true
        container.addArrangedSubview(coverImageView)
        coverImageView.heightAnchor.constraint(equalToConstant: 190).isActive = true
        coverImageView.widthAnchor.constraint(equalTo: container.widthAnchor).isActive = true

        if let coverUrl = header?.coverUrl ?? target.coverUrl {
            loadRemoteImage(coverUrl, into: coverImageView, preferredSize: CGSize(width: 420, height: 236))
        }

        let titleLabel = UILabel()
        titleLabel.text = header?.displayName ?? target.title
        titleLabel.font = UIFontMetrics(forTextStyle: .title1)
            .scaledFont(for: .systemFont(ofSize: 28, weight: .bold))
        titleLabel.adjustsFontForContentSizeCategory = true
        titleLabel.textColor = .label
        titleLabel.numberOfLines = 2
        container.addArrangedSubview(titleLabel)

        let dateLabel = UILabel()
        dateLabel.text = previewSubtitle(target: target, header: header)
        dateLabel.font = .preferredFont(forTextStyle: .subheadline)
        dateLabel.adjustsFontForContentSizeCategory = true
        dateLabel.textColor = .secondaryLabel
        dateLabel.numberOfLines = 1
        container.addArrangedSubview(dateLabel)

        let metadataStack = UIStackView()
        metadataStack.axis = .horizontal
        metadataStack.alignment = .leading
        metadataStack.spacing = 8
        metadataStack.distribution = .fill
        metadataStack.translatesAutoresizingMaskIntoConstraints = false

        let directMediaCount = header?.directMediaCount ?? mediaCountFallback
        let chapterCount = header?.chapterCount ?? 0
        metadataStack.addArrangedSubview(ShareMetadataPillView(
            symbolName: "photo.on.rectangle",
            text: Self.localizedFormat("%d photos", directMediaCount),
            tintColor: Self.successColor
        ))
        if chapterCount > 0 {
            metadataStack.addArrangedSubview(ShareMetadataPillView(
                symbolName: "square.stack.3d.up",
                text: Self.localizedFormat("%d chapters", chapterCount),
                tintColor: Self.infoColor
            ))
        }
        container.addArrangedSubview(metadataStack)

        targetStack.addArrangedSubview(container)
        targetStack.setCustomSpacing(24, after: container)
    }

    private func addPreviewGallery(_ media: [ShareMemoryMedia]) {
        addSectionHeader(
            title: Self.localized("Gallery"),
            subtitle: Self.localized("Current media in this Memory."),
            count: media.count
        )
        guard !media.isEmpty else {
            addEmptyState(
                systemImageName: "photo.on.rectangle",
                title: Self.localized("No media yet."),
                message: Self.localized("Import shared files to start this Memory gallery.")
            )
            return
        }

        let containerWidth = max(0, floor(targetScrollView.bounds.width > 0 ? targetScrollView.bounds.width : view.bounds.width - view.layoutMargins.left - view.layoutMargins.right))
        let gap = 8.0
        let targetHeight = containerWidth <= 720 ? 150.0 : 200.0
        let layout = justifiedMediaLayout(
            media: Array(media.prefix(48)),
            containerWidth: Double(containerWidth),
            targetHeight: targetHeight,
            gap: gap
        )

        let gridView = UIView()
        gridView.translatesAutoresizingMaskIntoConstraints = false
        gridView.clipsToBounds = true

        let mediaById = Dictionary(uniqueKeysWithValues: media.map { ($0.id, $0) })
        for tile in layout.tiles {
            guard let item = mediaById[tile.id] else { continue }
            let imageView = UIImageView(image: Self.placeholderCoverImage())
            imageView.translatesAutoresizingMaskIntoConstraints = false
            imageView.contentMode = .scaleAspectFill
            imageView.clipsToBounds = true
            imageView.layer.cornerRadius = 12
            imageView.layer.cornerCurve = .continuous
            imageView.accessibilityIgnoresInvertColors = true
            gridView.addSubview(imageView)
            NSLayoutConstraint.activate([
                imageView.leadingAnchor.constraint(equalTo: gridView.leadingAnchor, constant: tile.left),
                imageView.topAnchor.constraint(equalTo: gridView.topAnchor, constant: tile.top),
                imageView.widthAnchor.constraint(equalToConstant: tile.renderedWidth),
                imageView.heightAnchor.constraint(equalToConstant: tile.renderedHeight)
            ])
            loadRemoteImage(
                item.preferredTileUrl,
                into: imageView,
                preferredSize: CGSize(width: max(tile.renderedWidth * 2, 120), height: max(tile.renderedHeight * 2, 120))
            )
        }

        targetStack.addArrangedSubview(gridView)
        gridView.heightAnchor.constraint(equalToConstant: layout.height).isActive = true
        targetStack.setCustomSpacing(24, after: gridView)
    }

    private struct ShareMediaGridTile {
        let id: String
        let left: CGFloat
        let top: CGFloat
        let renderedWidth: CGFloat
        let renderedHeight: CGFloat
    }

    private func justifiedMediaLayout(
        media: [ShareMemoryMedia],
        containerWidth: Double,
        targetHeight: Double,
        gap: Double
    ) -> (tiles: [ShareMediaGridTile], height: CGFloat) {
        guard !media.isEmpty, containerWidth > 0, targetHeight > 0 else { return ([], 0) }

        var tiles: [ShareMediaGridTile] = []
        var row: [ShareMemoryMedia] = []
        var rowAspectRatio = 0.0
        var top = 0.0
        let targetAspectRatio = containerWidth / targetHeight

        func layoutRow(_ row: [ShareMemoryMedia], top: Double, forceJustify: Bool) -> Double {
            let rowGapTotal = gap * Double(max(row.count - 1, 0))
            let availableWidth = max(containerWidth - rowGapTotal, 0)
            let aspectRatioSum = row.reduce(0.0) { $0 + shareGalleryAspectRatio(for: $1) }
            let height = forceJustify && aspectRatioSum > 0 ? availableWidth / aspectRatioSum : targetHeight
            var left = 0.0

            for (index, item) in row.enumerated() {
                let isLast = index == row.count - 1
                let rawWidth = shareGalleryAspectRatio(for: item) * height
                let width = forceJustify && isLast ? max(containerWidth - left, 0) : rawWidth
                tiles.append(ShareMediaGridTile(
                    id: item.id,
                    left: CGFloat(left),
                    top: CGFloat(top),
                    renderedWidth: CGFloat(width),
                    renderedHeight: CGFloat(height)
                ))
                left += width + gap
            }

            return height
        }

        for item in media {
            row.append(item)
            rowAspectRatio += shareGalleryAspectRatio(for: item)
            if rowAspectRatio >= targetAspectRatio {
                let rowHeight = layoutRow(row, top: top, forceJustify: true)
                top += rowHeight + gap
                row = []
                rowAspectRatio = 0
            }
        }

        if !row.isEmpty {
            top += layoutRow(row, top: top, forceJustify: false)
        } else if !tiles.isEmpty {
            top -= gap
        }

        return (tiles, CGFloat(max(top, 0)))
    }

    private func shareGalleryAspectRatio(for media: ShareMemoryMedia) -> Double {
        guard let width = media.imageWidth,
              let height = media.imageHeight,
              width > 0,
              height > 0
        else {
            return 4.0 / 3.0
        }
        let aspectRatio = Double(width) / Double(height)
        guard aspectRatio.isFinite, aspectRatio > 0 else { return 4.0 / 3.0 }
        return min(4.0 / 3.0, max(3.0 / 4.0, aspectRatio))
    }

    private func loadRemoteImage(_ urlString: String, into imageView: UIImageView, preferredSize: CGSize) {
        guard let apiClient else { return }
        Task { [weak imageView] in
            guard let image = try? await apiClient.image(from: urlString, preferredSize: preferredSize) else { return }
            await MainActor.run {
                imageView?.image = image
            }
        }
    }

    private func showConfirmBar(for target: ShareImportMemoryTarget) {
        spaceBarScrollView.alwaysBounceHorizontal = false
        spaceBarStack.arrangedSubviews.forEach { view in
            spaceBarStack.removeArrangedSubview(view)
            view.removeFromSuperview()
        }

        let importButton = UIButton(type: .system)
        var configuration = UIButton.Configuration.filled()
        configuration.attributedTitle = AttributedString(
            Self.localizedFormat("Import into %@", target.title),
            attributes: AttributeContainer([
                .font: UIFontMetrics(forTextStyle: .headline)
                    .scaledFont(for: .systemFont(ofSize: 17, weight: .semibold))
            ])
        )
        configuration.image = UIImage(systemName: "square.and.arrow.down")
        configuration.preferredSymbolConfigurationForImage = UIImage.SymbolConfiguration(pointSize: 21, weight: .semibold)
        configuration.imagePadding = 10
        configuration.contentInsets = NSDirectionalEdgeInsets(top: 13, leading: 18, bottom: 13, trailing: 18)
        configuration.cornerStyle = .capsule
        configuration.baseBackgroundColor = UIColor(red: 0.36, green: 0.30, blue: 0.96, alpha: 1)
        configuration.baseForegroundColor = .white
        importButton.configuration = configuration
        importButton.titleLabel?.lineBreakMode = .byTruncatingTail
        importButton.layer.shadowColor = UIColor(red: 0.36, green: 0.30, blue: 0.96, alpha: 1).cgColor
        importButton.layer.shadowOpacity = 0.20
        importButton.layer.shadowRadius = 16
        importButton.layer.shadowOffset = CGSize(width: 0, height: 8)
        importButton.addAction(UIAction { [weak self] _ in
            self?.chooseTarget(target)
        }, for: .touchUpInside)

        spaceBarStack.addArrangedSubview(importButton)
        importButton.heightAnchor.constraint(equalToConstant: 58).isActive = true
        importButton.widthAnchor.constraint(equalTo: view.layoutMarginsGuide.widthAnchor).isActive = true
        configureSpaceBar(isVisible: true)
        spaceBarHeightConstraint?.constant = 84
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

private final class ShareSpaceTabControl: UIControl {
    override var isHighlighted: Bool {
        didSet {
            UIView.animate(withDuration: 0.12) {
                self.alpha = self.isHighlighted ? 0.72 : 1
            }
        }
    }

    init(space: ShareSpace, isSelected: Bool) {
        super.init(frame: .zero)
        translatesAutoresizingMaskIntoConstraints = false
        backgroundColor = isSelected ? Self.selectedBackgroundColor : .clear
        layer.cornerRadius = 18
        layer.cornerCurve = .continuous
        accessibilityTraits = isSelected ? [.button, .selected] : [.button]
        accessibilityLabel = space.label

        let symbol = UIImageView(image: UIImage(systemName: space.symbolName))
        symbol.translatesAutoresizingMaskIntoConstraints = false
        symbol.preferredSymbolConfiguration = UIImage.SymbolConfiguration(pointSize: 16, weight: .semibold)
        symbol.tintColor = isSelected ? Self.accentColor : .secondaryLabel
        symbol.contentMode = .center

        let label = UILabel()
        label.translatesAutoresizingMaskIntoConstraints = false
        label.text = space.label
        label.font = .preferredFont(forTextStyle: .caption1)
        label.adjustsFontForContentSizeCategory = true
        label.textColor = isSelected ? .label : .secondaryLabel
        label.numberOfLines = 1

        let stack = UIStackView(arrangedSubviews: [symbol, label])
        stack.translatesAutoresizingMaskIntoConstraints = false
        stack.axis = .vertical
        stack.alignment = .center
        stack.spacing = 3
        stack.isUserInteractionEnabled = false
        addSubview(stack)

        NSLayoutConstraint.activate([
            heightAnchor.constraint(equalToConstant: 52),
            widthAnchor.constraint(greaterThanOrEqualToConstant: 72),
            stack.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 10),
            stack.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -10),
            stack.centerYAnchor.constraint(equalTo: centerYAnchor)
        ])
    }

    required init?(coder: NSCoder) {
        nil
    }

    private static let accentColor = UIColor(red: 0.36, green: 0.30, blue: 0.96, alpha: 1)
    private static let selectedBackgroundColor = UIColor { traitCollection in
        traitCollection.userInterfaceStyle == .dark
            ? UIColor(red: 0.22, green: 0.20, blue: 0.34, alpha: 1)
            : UIColor(red: 0.94, green: 0.93, blue: 1.0, alpha: 1)
    }
}

private final class ShareContextPickerViewController: UIViewController, UITableViewDataSource, UITableViewDelegate {
    private enum Section: Int, CaseIterable {
        case tenants
        case spaces
    }

    private let activeTenant: ShareTenant?
    private let tenants: [ShareTenant]
    private let activeSpace: ShareSpace?
    private let spaces: [ShareSpace]
    private let onSelectTenant: (ShareTenant) -> Void
    private let onSelectSpace: (ShareSpace) -> Void
    private let tableView = UITableView(frame: .zero, style: .insetGrouped)

    init(
        activeTenant: ShareTenant?,
        tenants: [ShareTenant],
        activeSpace: ShareSpace?,
        spaces: [ShareSpace],
        onSelectTenant: @escaping (ShareTenant) -> Void,
        onSelectSpace: @escaping (ShareSpace) -> Void
    ) {
        self.activeTenant = activeTenant
        self.tenants = tenants
        self.activeSpace = activeSpace
        self.spaces = spaces
        self.onSelectTenant = onSelectTenant
        self.onSelectSpace = onSelectSpace
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) {
        nil
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        title = Self.localized("Destination Area")
        view.backgroundColor = .systemBackground

        let titleLabel = UILabel()
        titleLabel.translatesAutoresizingMaskIntoConstraints = false
        titleLabel.text = Self.localized("Destination Area")
        titleLabel.font = .preferredFont(forTextStyle: .title2)
        titleLabel.adjustsFontForContentSizeCategory = true
        titleLabel.textColor = .label

        let subtitleLabel = UILabel()
        subtitleLabel.translatesAutoresizingMaskIntoConstraints = false
        subtitleLabel.text = Self.localized("Choose tenant or area.")
        subtitleLabel.font = .preferredFont(forTextStyle: .subheadline)
        subtitleLabel.adjustsFontForContentSizeCategory = true
        subtitleLabel.textColor = .secondaryLabel
        subtitleLabel.numberOfLines = 0

        tableView.translatesAutoresizingMaskIntoConstraints = false
        tableView.dataSource = self
        tableView.delegate = self
        tableView.backgroundColor = .systemBackground
        tableView.register(UITableViewCell.self, forCellReuseIdentifier: "cell")

        view.addSubview(titleLabel)
        view.addSubview(subtitleLabel)
        view.addSubview(tableView)

        NSLayoutConstraint.activate([
            titleLabel.leadingAnchor.constraint(equalTo: view.layoutMarginsGuide.leadingAnchor),
            titleLabel.trailingAnchor.constraint(equalTo: view.layoutMarginsGuide.trailingAnchor),
            titleLabel.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 16),

            subtitleLabel.leadingAnchor.constraint(equalTo: view.layoutMarginsGuide.leadingAnchor),
            subtitleLabel.trailingAnchor.constraint(equalTo: view.layoutMarginsGuide.trailingAnchor),
            subtitleLabel.topAnchor.constraint(equalTo: titleLabel.bottomAnchor, constant: 6),

            tableView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            tableView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            tableView.topAnchor.constraint(equalTo: subtitleLabel.bottomAnchor, constant: 8),
            tableView.bottomAnchor.constraint(equalTo: view.bottomAnchor)
        ])
    }

    func numberOfSections(in tableView: UITableView) -> Int {
        Section.allCases.count
    }

    func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        switch Section(rawValue: section) {
        case .tenants:
            return tenants.count
        case .spaces:
            return spaces.count
        case .none:
            return 0
        }
    }

    func tableView(_ tableView: UITableView, titleForHeaderInSection section: Int) -> String? {
        switch Section(rawValue: section) {
        case .tenants:
            return Self.localized("Tenants")
        case .spaces:
            return Self.localized("Spaces")
        case .none:
            return nil
        }
    }

    func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: "cell", for: indexPath)
        var content = UIListContentConfiguration.subtitleCell()
        content.textProperties.font = .preferredFont(forTextStyle: .headline)
        content.secondaryTextProperties.font = .preferredFont(forTextStyle: .caption1)
        content.secondaryTextProperties.color = .secondaryLabel

        switch Section(rawValue: indexPath.section) {
        case .tenants:
            let tenant = tenants[indexPath.row]
            content.text = tenant.displayName
            content.secondaryText = tenant.domain
            content.image = UIImage(systemName: "person.3")
            cell.accessoryType = tenant.id == activeTenant?.id ? .checkmark : .none
        case .spaces:
            let space = spaces[indexPath.row]
            content.text = space.label
            content.secondaryText = space.kind.localizedLabel
            content.image = UIImage(systemName: space.symbolName)
            cell.accessoryType = space.id == activeSpace?.id ? .checkmark : .none
        case .none:
            break
        }

        content.imageProperties.tintColor = UIColor(red: 0.36, green: 0.30, blue: 0.96, alpha: 1)
        cell.contentConfiguration = content
        cell.tintColor = UIColor(red: 0.36, green: 0.30, blue: 0.96, alpha: 1)
        return cell
    }

    func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        dismiss(animated: true) { [weak self] in
            guard let self else { return }
            switch Section(rawValue: indexPath.section) {
            case .tenants:
                self.onSelectTenant(self.tenants[indexPath.row])
            case .spaces:
                self.onSelectSpace(self.spaces[indexPath.row])
            case .none:
                break
            }
        }
    }

    private nonisolated static func localized(_ key: String) -> String {
        NSLocalizedString(key, comment: "")
    }
}

private final class ShareMetadataPillView: UIView {
    init(symbolName: String, text: String, tintColor: UIColor) {
        super.init(frame: .zero)
        translatesAutoresizingMaskIntoConstraints = false
        backgroundColor = tintColor.withAlphaComponent(0.11)
        layer.cornerRadius = 14
        layer.cornerCurve = .continuous
        accessibilityLabel = text

        let symbol = UIImageView(image: UIImage(systemName: symbolName))
        symbol.translatesAutoresizingMaskIntoConstraints = false
        symbol.preferredSymbolConfiguration = UIImage.SymbolConfiguration(pointSize: 13, weight: .semibold)
        symbol.tintColor = tintColor
        symbol.contentMode = .center
        symbol.setContentHuggingPriority(.required, for: .horizontal)

        let label = UILabel()
        label.translatesAutoresizingMaskIntoConstraints = false
        label.text = text
        label.font = .preferredFont(forTextStyle: .caption1)
        label.adjustsFontForContentSizeCategory = true
        label.textColor = tintColor
        label.numberOfLines = 1
        label.minimumScaleFactor = 0.86
        label.adjustsFontSizeToFitWidth = true

        let stack = UIStackView(arrangedSubviews: [symbol, label])
        stack.translatesAutoresizingMaskIntoConstraints = false
        stack.axis = .horizontal
        stack.alignment = .center
        stack.spacing = 7
        stack.isUserInteractionEnabled = false
        addSubview(stack)

        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 9),
            stack.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -9),
            stack.topAnchor.constraint(equalTo: topAnchor, constant: 6),
            stack.bottomAnchor.constraint(equalTo: bottomAnchor, constant: -6)
        ])
    }

    required init?(coder: NSCoder) {
        nil
    }
}

private final class ShareCompactMetadataPillView: UIView {
    init(symbolName: String, text: String, tintColor: UIColor) {
        super.init(frame: .zero)
        translatesAutoresizingMaskIntoConstraints = false
        backgroundColor = tintColor.withAlphaComponent(0.11)
        layer.cornerRadius = 13
        layer.cornerCurve = .continuous
        accessibilityLabel = text

        let symbol = UIImageView(image: UIImage(systemName: symbolName))
        symbol.translatesAutoresizingMaskIntoConstraints = false
        symbol.preferredSymbolConfiguration = UIImage.SymbolConfiguration(pointSize: 12, weight: .semibold)
        symbol.tintColor = tintColor
        symbol.contentMode = .center
        symbol.setContentHuggingPriority(.required, for: .horizontal)

        let label = UILabel()
        label.translatesAutoresizingMaskIntoConstraints = false
        label.text = text
        label.font = UIFontMetrics(forTextStyle: .caption2)
            .scaledFont(for: .systemFont(ofSize: 13, weight: .semibold))
        label.adjustsFontForContentSizeCategory = true
        label.textColor = tintColor
        label.numberOfLines = 1
        label.minimumScaleFactor = 0.78
        label.adjustsFontSizeToFitWidth = true

        let stack = UIStackView(arrangedSubviews: [symbol, label])
        stack.translatesAutoresizingMaskIntoConstraints = false
        stack.axis = .horizontal
        stack.alignment = .center
        stack.spacing = 5
        stack.isUserInteractionEnabled = false
        addSubview(stack)

        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 7),
            stack.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -7),
            stack.topAnchor.constraint(equalTo: topAnchor, constant: 5),
            stack.bottomAnchor.constraint(equalTo: bottomAnchor, constant: -5)
        ])
    }

    required init?(coder: NSCoder) {
        nil
    }
}

private final class ShareCoverGradientView: UIView {
    private let gradientLayer = CAGradientLayer()

    override init(frame: CGRect) {
        super.init(frame: frame)
        isOpaque = false
        gradientLayer.colors = [
            UIColor.clear.cgColor,
            UIColor.black.withAlphaComponent(0.52).cgColor
        ]
        gradientLayer.startPoint = CGPoint(x: 0.5, y: 0.38)
        gradientLayer.endPoint = CGPoint(x: 0.5, y: 1.0)
        layer.addSublayer(gradientLayer)
    }

    required init?(coder: NSCoder) {
        nil
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        gradientLayer.frame = bounds
    }
}

private final class TenantSelectorPillControl: UIControl {
    override var isHighlighted: Bool {
        didSet {
            UIView.animate(withDuration: 0.12) {
                self.alpha = self.isHighlighted ? 0.72 : 1
            }
        }
    }

    init(tenant: ShareTenant, isSelected: Bool) {
        super.init(frame: .zero)
        translatesAutoresizingMaskIntoConstraints = false
        backgroundColor = isSelected ? Self.selectedBackgroundColor : .secondarySystemBackground
        layer.cornerRadius = 14
        layer.cornerCurve = .continuous
        layer.borderWidth = 1
        layer.borderColor = UIColor.separator.withAlphaComponent(0.28).cgColor
        accessibilityTraits = isSelected ? [.button, .selected] : [.button]
        accessibilityLabel = tenant.displayName
        accessibilityValue = tenant.domain

        let symbol = UIImageView(image: UIImage(systemName: "person.3"))
        symbol.translatesAutoresizingMaskIntoConstraints = false
        symbol.preferredSymbolConfiguration = UIImage.SymbolConfiguration(pointSize: 18, weight: .semibold)
        symbol.tintColor = Self.accentColor
        symbol.contentMode = .center

        let titleLabel = UILabel()
        titleLabel.translatesAutoresizingMaskIntoConstraints = false
        titleLabel.text = tenant.displayName
        titleLabel.font = .preferredFont(forTextStyle: .headline)
        titleLabel.adjustsFontForContentSizeCategory = true
        titleLabel.textColor = .label
        titleLabel.numberOfLines = 1

        let subtitleLabel = UILabel()
        subtitleLabel.translatesAutoresizingMaskIntoConstraints = false
        subtitleLabel.text = tenant.domain
        subtitleLabel.font = .preferredFont(forTextStyle: .caption1)
        subtitleLabel.adjustsFontForContentSizeCategory = true
        subtitleLabel.textColor = .secondaryLabel
        subtitleLabel.numberOfLines = 1

        let textStack = UIStackView(arrangedSubviews: [titleLabel, subtitleLabel])
        textStack.translatesAutoresizingMaskIntoConstraints = false
        textStack.axis = .vertical
        textStack.alignment = .leading
        textStack.spacing = 2
        textStack.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)

        let selectedImageView = UIImageView(image: UIImage(systemName: isSelected ? "checkmark.circle.fill" : "chevron.right"))
        selectedImageView.translatesAutoresizingMaskIntoConstraints = false
        selectedImageView.preferredSymbolConfiguration = UIImage.SymbolConfiguration(pointSize: isSelected ? 18 : 13, weight: .semibold)
        selectedImageView.tintColor = isSelected ? Self.accentColor : .tertiaryLabel
        selectedImageView.setContentHuggingPriority(.required, for: .horizontal)

        let stack = UIStackView(arrangedSubviews: [symbol, textStack, selectedImageView])
        stack.translatesAutoresizingMaskIntoConstraints = false
        stack.axis = .horizontal
        stack.alignment = .center
        stack.spacing = 10
        stack.isUserInteractionEnabled = false
        addSubview(stack)

        NSLayoutConstraint.activate([
            widthAnchor.constraint(equalToConstant: 216),
            stack.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 14),
            stack.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -14),
            stack.centerYAnchor.constraint(equalTo: centerYAnchor)
        ])
    }

    required init?(coder: NSCoder) {
        nil
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        layer.borderColor = UIColor.separator.withAlphaComponent(0.28).cgColor
    }

    private static let accentColor = UIColor(red: 0.36, green: 0.30, blue: 0.96, alpha: 1)
    private static let selectedBackgroundColor = UIColor { traitCollection in
        traitCollection.userInterfaceStyle == .dark
            ? UIColor(red: 0.22, green: 0.20, blue: 0.34, alpha: 1)
            : UIColor(red: 0.94, green: 0.93, blue: 1.0, alpha: 1)
    }
}

private final class RecentTargetPillControl: UIControl {
    private let coverImageView = UIImageView()

    override var isHighlighted: Bool {
        didSet {
            UIView.animate(withDuration: 0.12) {
                self.alpha = self.isHighlighted ? 0.72 : 1
            }
        }
    }

    init(target: ShareImportMemoryTarget, coverImage: UIImage) {
        super.init(frame: .zero)
        translatesAutoresizingMaskIntoConstraints = false
        backgroundColor = .secondarySystemBackground
        layer.cornerRadius = 16
        layer.cornerCurve = .continuous
        layer.borderWidth = 1
        layer.borderColor = UIColor.separator.withAlphaComponent(0.18).cgColor
        layer.shadowColor = UIColor.black.cgColor
        layer.shadowOpacity = 0.06
        layer.shadowRadius = 10
        layer.shadowOffset = CGSize(width: 0, height: 5)
        accessibilityTraits = [.button]
        accessibilityLabel = target.title

        let coverContainer = UIView()
        coverContainer.translatesAutoresizingMaskIntoConstraints = false
        coverContainer.clipsToBounds = true
        coverContainer.layer.cornerRadius = 16
        coverContainer.layer.cornerCurve = .continuous

        coverImageView.translatesAutoresizingMaskIntoConstraints = false
        coverImageView.image = coverImage
        coverImageView.contentMode = .scaleAspectFill
        coverImageView.clipsToBounds = true
        coverImageView.accessibilityIgnoresInvertColors = true
        coverContainer.addSubview(coverImageView)

        let gradientOverlay = ShareCoverGradientView()
        gradientOverlay.translatesAutoresizingMaskIntoConstraints = false
        gradientOverlay.isUserInteractionEnabled = false
        coverContainer.addSubview(gradientOverlay)

        let titleLabel = UILabel()
        titleLabel.translatesAutoresizingMaskIntoConstraints = false
        titleLabel.text = target.title
        titleLabel.font = UIFontMetrics(forTextStyle: .headline)
            .scaledFont(for: .systemFont(ofSize: 19, weight: .bold))
        titleLabel.adjustsFontForContentSizeCategory = true
        titleLabel.textColor = .white
        titleLabel.numberOfLines = 2
        titleLabel.layer.shadowColor = UIColor.black.cgColor
        titleLabel.layer.shadowOpacity = 0.32
        titleLabel.layer.shadowRadius = 8

        let dateLabel = UILabel()
        dateLabel.translatesAutoresizingMaskIntoConstraints = false
        dateLabel.text = target.dateRange
        dateLabel.font = UIFontMetrics(forTextStyle: .caption1)
            .scaledFont(for: .systemFont(ofSize: 12, weight: .semibold))
        dateLabel.adjustsFontForContentSizeCategory = true
        dateLabel.textColor = UIColor.white.withAlphaComponent(0.88)
        dateLabel.numberOfLines = 1
        dateLabel.layer.shadowColor = UIColor.black.cgColor
        dateLabel.layer.shadowOpacity = 0.32
        dateLabel.layer.shadowRadius = 8

        let coverTextStack = UIStackView(arrangedSubviews: [titleLabel, dateLabel])
        coverTextStack.translatesAutoresizingMaskIntoConstraints = false
        coverTextStack.axis = .vertical
        coverTextStack.alignment = .leading
        coverTextStack.spacing = 5
        coverTextStack.isUserInteractionEnabled = false
        coverContainer.addSubview(coverTextStack)

        let metadataStack = UIStackView()
        metadataStack.translatesAutoresizingMaskIntoConstraints = false
        metadataStack.axis = .horizontal
        metadataStack.alignment = .center
        metadataStack.spacing = 6
        metadataStack.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        if let mediaCount = target.mediaCount, let childCount = target.childCount {
            metadataStack.addArrangedSubview(ShareCompactMetadataPillView(
                symbolName: "photo.on.rectangle",
                text: Self.localizedFormat("%d photos", mediaCount),
                tintColor: Self.successColor
            ))
            metadataStack.addArrangedSubview(ShareCompactMetadataPillView(
                symbolName: "square.stack.3d.up",
                text: Self.localizedFormat("%d chapters", childCount),
                tintColor: Self.infoColor
            ))
        }

        let chevronImageView = UIImageView(image: UIImage(systemName: "chevron.right"))
        chevronImageView.translatesAutoresizingMaskIntoConstraints = false
        chevronImageView.preferredSymbolConfiguration = UIImage.SymbolConfiguration(pointSize: 16, weight: .semibold)
        chevronImageView.tintColor = .tertiaryLabel
        chevronImageView.setContentHuggingPriority(.required, for: .horizontal)
        chevronImageView.setContentCompressionResistancePriority(.required, for: .horizontal)

        let bottomStack = UIStackView(arrangedSubviews: [metadataStack, UIView(), chevronImageView])
        bottomStack.translatesAutoresizingMaskIntoConstraints = false
        bottomStack.axis = .horizontal
        bottomStack.alignment = .center
        bottomStack.spacing = 8
        bottomStack.isUserInteractionEnabled = false

        let stack = UIStackView(arrangedSubviews: [coverContainer, bottomStack])
        stack.translatesAutoresizingMaskIntoConstraints = false
        stack.axis = .vertical
        stack.alignment = .fill
        stack.spacing = 10
        stack.isUserInteractionEnabled = false
        addSubview(stack)

        NSLayoutConstraint.activate([
            widthAnchor.constraint(equalToConstant: 286),
            coverContainer.heightAnchor.constraint(equalToConstant: 154),
            coverImageView.leadingAnchor.constraint(equalTo: coverContainer.leadingAnchor),
            coverImageView.trailingAnchor.constraint(equalTo: coverContainer.trailingAnchor),
            coverImageView.topAnchor.constraint(equalTo: coverContainer.topAnchor),
            coverImageView.bottomAnchor.constraint(equalTo: coverContainer.bottomAnchor),
            gradientOverlay.leadingAnchor.constraint(equalTo: coverContainer.leadingAnchor),
            gradientOverlay.trailingAnchor.constraint(equalTo: coverContainer.trailingAnchor),
            gradientOverlay.topAnchor.constraint(equalTo: coverContainer.topAnchor),
            gradientOverlay.bottomAnchor.constraint(equalTo: coverContainer.bottomAnchor),
            coverTextStack.leadingAnchor.constraint(equalTo: coverContainer.leadingAnchor, constant: 12),
            coverTextStack.trailingAnchor.constraint(lessThanOrEqualTo: coverContainer.trailingAnchor, constant: -12),
            coverTextStack.bottomAnchor.constraint(equalTo: coverContainer.bottomAnchor, constant: -12),
            stack.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 12),
            stack.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -12),
            stack.topAnchor.constraint(equalTo: topAnchor, constant: 10),
            stack.bottomAnchor.constraint(lessThanOrEqualTo: bottomAnchor, constant: -10),
            chevronImageView.widthAnchor.constraint(greaterThanOrEqualToConstant: 16)
        ])
    }

    required init?(coder: NSCoder) {
        nil
    }

    private nonisolated static func localizedFormat(_ key: String, _ arguments: CVarArg...) -> String {
        String(format: NSLocalizedString(key, comment: ""), locale: Locale.current, arguments: arguments)
    }

    private static let successColor = UIColor(red: 0.08, green: 0.49, blue: 0.35, alpha: 1)
    private static let infoColor = UIColor(red: 0.20, green: 0.39, blue: 0.82, alpha: 1)
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
        layer.cornerRadius = 18
        layer.cornerCurve = .continuous
        layer.borderWidth = 1
        layer.borderColor = UIColor.separator.withAlphaComponent(0.18).cgColor
        layer.shadowColor = UIColor.black.cgColor
        layer.shadowOpacity = 0.05
        layer.shadowRadius = 12
        layer.shadowOffset = CGSize(width: 0, height: 6)
        accessibilityTraits = [.button]
        accessibilityLabel = target.title
        if let parentMemoryTitle = target.parentMemoryTitle {
            accessibilityValue = Self.localizedFormat("Chapter in %@", parentMemoryTitle)
        } else {
            accessibilityValue = target.dateRange
        }

        clipsToBounds = false

        let coverContainer = UIView()
        coverContainer.translatesAutoresizingMaskIntoConstraints = false
        coverContainer.clipsToBounds = true
        coverContainer.layer.cornerRadius = 18
        coverContainer.layer.cornerCurve = .continuous

        coverImageView.translatesAutoresizingMaskIntoConstraints = false
        coverImageView.image = placeholder
        coverImageView.contentMode = .scaleAspectFill
        coverImageView.clipsToBounds = true
        coverImageView.accessibilityIgnoresInvertColors = true
        coverContainer.addSubview(coverImageView)

        let gradientOverlay = ShareCoverGradientView()
        gradientOverlay.translatesAutoresizingMaskIntoConstraints = false
        gradientOverlay.isUserInteractionEnabled = false
        coverContainer.addSubview(gradientOverlay)

        let titleLabel = UILabel()
        titleLabel.translatesAutoresizingMaskIntoConstraints = false
        titleLabel.text = target.title
        titleLabel.font = UIFontMetrics(forTextStyle: .title3)
            .scaledFont(for: .systemFont(ofSize: 22, weight: .bold))
        titleLabel.adjustsFontForContentSizeCategory = true
        titleLabel.textColor = .white
        titleLabel.numberOfLines = 2
        titleLabel.layer.shadowColor = UIColor.black.cgColor
        titleLabel.layer.shadowOpacity = 0.32
        titleLabel.layer.shadowRadius = 8

        let dateLabel = UILabel()
        dateLabel.translatesAutoresizingMaskIntoConstraints = false
        dateLabel.text = target.parentMemoryTitle.map { Self.localizedFormat("Chapter in %@", $0) } ?? target.dateRange
        dateLabel.font = UIFontMetrics(forTextStyle: .caption1)
            .scaledFont(for: .systemFont(ofSize: 13, weight: .semibold))
        dateLabel.adjustsFontForContentSizeCategory = true
        dateLabel.textColor = UIColor.white.withAlphaComponent(0.88)
        dateLabel.numberOfLines = 1
        dateLabel.layer.shadowColor = UIColor.black.cgColor
        dateLabel.layer.shadowOpacity = 0.32
        dateLabel.layer.shadowRadius = 8

        let coverTextStack = UIStackView(arrangedSubviews: [titleLabel, dateLabel])
        coverTextStack.translatesAutoresizingMaskIntoConstraints = false
        coverTextStack.axis = .vertical
        coverTextStack.alignment = .leading
        coverTextStack.spacing = 6
        coverTextStack.isUserInteractionEnabled = false
        coverContainer.addSubview(coverTextStack)

        let metadataStack = UIStackView()
        metadataStack.translatesAutoresizingMaskIntoConstraints = false
        metadataStack.axis = .horizontal
        metadataStack.alignment = .center
        metadataStack.spacing = 8
        metadataStack.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        if let mediaCount = target.mediaCount, let childCount = target.childCount {
            metadataStack.addArrangedSubview(ShareCompactMetadataPillView(
                symbolName: "photo.on.rectangle",
                text: Self.localizedFormat("%d photos", mediaCount),
                tintColor: Self.successColor
            ))
            metadataStack.addArrangedSubview(ShareCompactMetadataPillView(
                symbolName: "square.stack.3d.up",
                text: Self.localizedFormat("%d chapters", childCount),
                tintColor: Self.infoColor
            ))
        }

        selectedImageView.translatesAutoresizingMaskIntoConstraints = false
        selectedImageView.image = UIImage(systemName: "chevron.right")
        selectedImageView.preferredSymbolConfiguration = UIImage.SymbolConfiguration(pointSize: 18, weight: .semibold)
        selectedImageView.tintColor = .tertiaryLabel
        selectedImageView.setContentHuggingPriority(.required, for: .horizontal)

        let bottomStack = UIStackView(arrangedSubviews: [metadataStack, UIView(), selectedImageView])
        bottomStack.translatesAutoresizingMaskIntoConstraints = false
        bottomStack.axis = .horizontal
        bottomStack.alignment = .center
        bottomStack.spacing = 8
        bottomStack.isUserInteractionEnabled = false
        bottomStack.isLayoutMarginsRelativeArrangement = true
        bottomStack.directionalLayoutMargins = NSDirectionalEdgeInsets(top: 12, leading: 12, bottom: 12, trailing: 12)

        let contentStack = UIStackView(arrangedSubviews: [coverContainer, bottomStack])
        contentStack.translatesAutoresizingMaskIntoConstraints = false
        contentStack.axis = .vertical
        contentStack.alignment = .fill
        contentStack.spacing = 0
        contentStack.isUserInteractionEnabled = false
        addSubview(contentStack)

        let depthInset = CGFloat(min(max(target.depth ?? 0, 0), 2) * 18)
        NSLayoutConstraint.activate([
            heightAnchor.constraint(greaterThanOrEqualToConstant: 236),

            contentStack.leadingAnchor.constraint(equalTo: leadingAnchor, constant: depthInset),
            contentStack.trailingAnchor.constraint(equalTo: trailingAnchor),
            contentStack.topAnchor.constraint(equalTo: topAnchor),
            contentStack.bottomAnchor.constraint(equalTo: bottomAnchor),

            coverContainer.heightAnchor.constraint(equalToConstant: 168),
            coverImageView.leadingAnchor.constraint(equalTo: coverContainer.leadingAnchor),
            coverImageView.trailingAnchor.constraint(equalTo: coverContainer.trailingAnchor),
            coverImageView.topAnchor.constraint(equalTo: coverContainer.topAnchor),
            coverImageView.bottomAnchor.constraint(equalTo: coverContainer.bottomAnchor),
            gradientOverlay.leadingAnchor.constraint(equalTo: coverContainer.leadingAnchor),
            gradientOverlay.trailingAnchor.constraint(equalTo: coverContainer.trailingAnchor),
            gradientOverlay.topAnchor.constraint(equalTo: coverContainer.topAnchor),
            gradientOverlay.bottomAnchor.constraint(equalTo: coverContainer.bottomAnchor),
            coverTextStack.leadingAnchor.constraint(equalTo: coverContainer.leadingAnchor, constant: 14),
            coverTextStack.trailingAnchor.constraint(lessThanOrEqualTo: coverContainer.trailingAnchor, constant: -14),
            coverTextStack.bottomAnchor.constraint(equalTo: coverContainer.bottomAnchor, constant: -14),

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
        layer.borderColor = UIColor.separator.withAlphaComponent(0.18).cgColor
    }

    private static let accentColor = UIColor(red: 0.36, green: 0.30, blue: 0.96, alpha: 1)
    private static let successColor = UIColor(red: 0.08, green: 0.49, blue: 0.35, alpha: 1)
    private static let infoColor = UIColor(red: 0.20, green: 0.39, blue: 0.82, alpha: 1)
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
        contentStack.isUserInteractionEnabled = false
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
    let mediaCount: Int?
    let childCount: Int?
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

private struct ShareSpace: Identifiable, Hashable {
    let id: String
    let kind: ShareSpaceKind
    let label: String
    let path: String
    let groupName: String?

    var symbolName: String {
        switch kind {
        case .tenant:
            return "person.3"
        case .groupsOverview:
            return "at"
        case .group:
            return "at"
        case .personal:
            return "person"
        }
    }
}

private enum ShareSpaceKind: Hashable {
    case tenant
    case groupsOverview
    case group
    case personal

    var localizedLabel: String {
        switch self {
        case .tenant:
            return NSLocalizedString("Tenant", comment: "")
        case .groupsOverview:
            return NSLocalizedString("Groups", comment: "")
        case .group:
            return NSLocalizedString("Group", comment: "")
        case .personal:
            return NSLocalizedString("Personal", comment: "")
        }
    }
}

private struct ShareMemoryHeader: Codable, Equatable {
    let id: String
    let displayName: String
    let path: String
    let coverUrl: String?
    let startDate: String
    let endDate: String
    let directMediaCount: Int
    let descendantMediaCount: Int
    let chapterCount: Int

    enum CodingKeys: String, CodingKey {
        case id
        case displayName
        case path
        case coverUrl
        case startDate
        case endDate
        case directMediaCount
        case descendantMediaCount
        case chapterCount = "childMemoryCount"
    }
}

private struct ShareMediaUploader: Codable, Equatable {
    let type: String
    let displayName: String
    let permalink: String?
    let userId: Int?
}

private struct ShareMemoryMedia: Codable, Identifiable, Equatable {
    let id: String
    let memoryId: String
    let title: String
    let uploader: ShareMediaUploader
    let createdAt: Date
    let updatedAt: Date
    let capturedAt: Date?
    let capturedAtOverride: Bool
    let mimeType: String
    let sizeBytes: Int64
    let contentUrl: String
    let thumbnailUrl: String?
    let previewUrl: String?
    let posterUrl: String?
    let imageWidth: Int?
    let imageHeight: Int?

    var preferredTileUrl: String {
        thumbnailUrl ?? posterUrl ?? previewUrl ?? contentUrl
    }
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
            depth: depth,
            mediaCount: mediaCount,
            childCount: childCount
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

    func memoryHeader(tenantName: String, memoryId: String) async throws -> ShareMemoryHeader {
        try await get(path: "\(tenantName)/memories/\(memoryId)/header")
    }

    func memoryMedia(tenantName: String, memoryId: String, includeChildren: Bool) async throws -> [ShareMemoryMedia] {
        try await get(path: "\(tenantName)/memories/\(memoryId)/media", query: ["includeChildren": includeChildren ? "true" : "false"])
    }

    func image(from urlString: String, preferredSize: CGSize) async throws -> UIImage {
        let data = try await mediaData(from: urlString)
        guard let image = UIImage(data: data) else { throw ShareExtensionError.invalidResponse }
        return image.preparingThumbnail(of: preferredSize) ?? image
    }

    func refreshMobileSession(refreshToken: String) async throws -> ShareNativeMobileSession {
        try await post(path: "auth/mobile/session/refresh", body: ["refreshToken": refreshToken], authenticated: false)
    }

    private func mediaData(from urlString: String) async throws -> Data {
        var request = URLRequest(url: try mediaURL(from: urlString))
        request.httpMethod = "GET"
        return try await sendData(request)
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
        let data = try await sendData(request, authenticated: authenticated)
        return try JSONDecoder.veyqShare.decode(T.self, from: data)
    }

    private func sendData(_ request: URLRequest, authenticated: Bool = true) async throws -> Data {
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
        return data
    }

    private func mediaURL(from urlString: String) throws -> URL {
        if let absolute = URL(string: urlString), absolute.scheme != nil {
            return absolute
        }

        guard var components = URLComponents(url: session.instanceURL, resolvingAgainstBaseURL: false) else {
            throw ShareExtensionError.invalidResponse
        }
        if urlString.hasPrefix("/") {
            components.path = urlString
        } else {
            let basePath = components.path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            components.path = "/" + ([basePath, urlString].filter { !$0.isEmpty }.joined(separator: "/"))
        }
        components.query = nil
        components.fragment = nil
        guard let url = components.url else { throw ShareExtensionError.invalidResponse }
        return url
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
