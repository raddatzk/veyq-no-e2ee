import CoreLocation
import CryptoKit
import Foundation
import SwiftUI

struct VeyqUser: Codable, Equatable {
    let userId: Int
    let email: String
    let displayName: String
    let permalink: String
    let serverRole: String
    let passkeyCount: Int
    let hasPassword: Bool
    let avatarUrl: String?
    let pendingEmail: String?
    let totpEnabled: Bool?

    init(
        userId: Int,
        email: String,
        displayName: String,
        permalink: String,
        serverRole: String,
        passkeyCount: Int,
        hasPassword: Bool,
        avatarUrl: String?,
        pendingEmail: String? = nil,
        totpEnabled: Bool? = nil
    ) {
        self.userId = userId
        self.email = email
        self.displayName = displayName
        self.permalink = permalink
        self.serverRole = serverRole
        self.passkeyCount = passkeyCount
        self.hasPassword = hasPassword
        self.avatarUrl = avatarUrl
        self.pendingEmail = pendingEmail
        self.totpEnabled = totpEnabled
    }

    enum CodingKeys: String, CodingKey {
        case userId
        case email
        case displayName
        case permalink
        case serverRole
        case passkeyCount
        case hasPassword
        case avatarUrl
        case pendingEmail
        case totpEnabled
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        userId = try container.decode(Int.self, forKey: .userId)
        email = try container.decode(String.self, forKey: .email)
        displayName = try container.decode(String.self, forKey: .displayName)
        permalink = try container.decode(String.self, forKey: .permalink)
        serverRole = try container.decode(String.self, forKey: .serverRole)
        passkeyCount = try container.decode(Int.self, forKey: .passkeyCount)
        hasPassword = try container.decode(Bool.self, forKey: .hasPassword)
        avatarUrl = try container.decodeIfPresent(String.self, forKey: .avatarUrl)
        pendingEmail = try container.decodeIfPresent(String.self, forKey: .pendingEmail)
        totpEnabled = try container.decodeIfPresent(Bool.self, forKey: .totpEnabled)
    }
}

struct VeyqTenant: Codable, Identifiable, Hashable {
    let id: Int
    let name: String
    let displayName: String
    let domain: String
    let role: String

    static let sample = VeyqTenant(id: 1, name: "family", displayName: "Family Memories", domain: "memories.example.test", role: "TenantOwner")
}

struct VeyqBootstrap: Codable, Equatable {
    let serverId: String
    let user: VeyqUser
    let tenants: [VeyqTenant]
}

struct PublicUserSummary: Codable, Equatable, Hashable {
    let userId: Int
    let displayName: String
    let permalink: String
    let avatarUrl: String?
}

struct GroupMemberSummary: Codable, Equatable, Hashable {
    let user: PublicUserSummary
    let role: String?
}

struct GroupSummary: Codable, Identifiable, Equatable, Hashable {
    let id: Int
    let name: String
    let displayName: String
    let members: [GroupMemberSummary]
}

struct CreateGroupRequest: Encodable, Equatable {
    let name: String
    let displayName: String
}

struct VeyqCapability: Codable, Equatable {
    let id: String
    let introducedInVersion: String?
    let audience: String?
}

struct VeyqClientCapabilities: Codable, Equatable {
    let capabilities: [VeyqCapability]

    func contains(_ capabilityId: String) -> Bool {
        capabilities.contains { $0.id == capabilityId }
    }
}

struct VeyqInstanceDiscovery: Codable, Equatable {
    let product: String
    let apiBaseUrl: URL
    let clientCapabilitiesUrl: URL
    let mobileLoginUrl: URL
    let mobileCallbackScheme: String
}

struct NativeMobileSession: Codable, Equatable {
    let tokenType: String
    let accessToken: String
    let accessTokenExpiresAt: Date
    let refreshToken: String
    let refreshTokenExpiresAt: Date
    let user: VeyqUser
}

struct UpdateUserSettingsRequest: Encodable, Equatable {
    let displayName: String
    let permalink: String
    let email: String
}

struct StoredNativeSession: Codable, Equatable {
    let instanceURL: URL
    let apiBaseURL: URL
    let accessToken: String
    let accessTokenExpiresAt: Date
    let refreshToken: String
    let refreshTokenExpiresAt: Date
    let user: VeyqUser
    let capabilities: [VeyqCapability]
    let selectedTenantName: String?

    init(
        instanceURL: URL,
        apiBaseURL: URL,
        accessToken: String,
        accessTokenExpiresAt: Date,
        refreshToken: String,
        refreshTokenExpiresAt: Date,
        user: VeyqUser,
        capabilities: [VeyqCapability] = [],
        selectedTenantName: String? = nil
    ) {
        self.instanceURL = instanceURL
        self.apiBaseURL = apiBaseURL
        self.accessToken = accessToken
        self.accessTokenExpiresAt = accessTokenExpiresAt
        self.refreshToken = refreshToken
        self.refreshTokenExpiresAt = refreshTokenExpiresAt
        self.user = user
        self.capabilities = capabilities
        self.selectedTenantName = selectedTenantName
    }

    enum CodingKeys: String, CodingKey {
        case instanceURL
        case apiBaseURL
        case accessToken
        case accessTokenExpiresAt
        case refreshToken
        case refreshTokenExpiresAt
        case user
        case capabilities
        case selectedTenantName
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        instanceURL = try container.decode(URL.self, forKey: .instanceURL)
        apiBaseURL = try container.decode(URL.self, forKey: .apiBaseURL)
        accessToken = try container.decode(String.self, forKey: .accessToken)
        accessTokenExpiresAt = try container.decode(Date.self, forKey: .accessTokenExpiresAt)
        refreshToken = try container.decode(String.self, forKey: .refreshToken)
        refreshTokenExpiresAt = try container.decode(Date.self, forKey: .refreshTokenExpiresAt)
        user = try container.decode(VeyqUser.self, forKey: .user)
        capabilities = try container.decodeIfPresent([VeyqCapability].self, forKey: .capabilities) ?? []
        selectedTenantName = try container.decodeIfPresent(String.self, forKey: .selectedTenantName)
    }

    func withCapabilities(_ capabilities: [VeyqCapability]) -> StoredNativeSession {
        StoredNativeSession(
            instanceURL: instanceURL,
            apiBaseURL: apiBaseURL,
            accessToken: accessToken,
            accessTokenExpiresAt: accessTokenExpiresAt,
            refreshToken: refreshToken,
            refreshTokenExpiresAt: refreshTokenExpiresAt,
            user: user,
            capabilities: capabilities,
            selectedTenantName: selectedTenantName
        )
    }

    func withUser(_ user: VeyqUser) -> StoredNativeSession {
        StoredNativeSession(
            instanceURL: instanceURL,
            apiBaseURL: apiBaseURL,
            accessToken: accessToken,
            accessTokenExpiresAt: accessTokenExpiresAt,
            refreshToken: refreshToken,
            refreshTokenExpiresAt: refreshTokenExpiresAt,
            user: user,
            capabilities: capabilities,
            selectedTenantName: selectedTenantName
        )
    }

    func withSelectedTenantName(_ selectedTenantName: String?) -> StoredNativeSession {
        StoredNativeSession(
            instanceURL: instanceURL,
            apiBaseURL: apiBaseURL,
            accessToken: accessToken,
            accessTokenExpiresAt: accessTokenExpiresAt,
            refreshToken: refreshToken,
            refreshTokenExpiresAt: refreshTokenExpiresAt,
            user: user,
            capabilities: capabilities,
            selectedTenantName: selectedTenantName
        )
    }

    func hasDifferentTokens(than session: StoredNativeSession) -> Bool {
        accessToken != session.accessToken || refreshToken != session.refreshToken
    }
}

struct StoredNativeSessionCollection: Codable, Equatable {
    let activeInstanceURL: URL?
    let sessions: [StoredNativeSession]
}

struct VeyqSpace: Identifiable, Hashable {
    let id: String
    let kind: VeyqSpaceKind
    let label: String
    let path: String
    let groupName: String?

    var symbolName: String {
        switch kind {
        case .tenant: "person.3"
        case .group: "at"
        case .personal: "person"
        }
    }

    var accessibilityIdentifier: String {
        "memory-space-\(id)"
    }

    var spaceOptionAccessibilityIdentifier: String {
        "space-option-\(id)"
    }
}

enum VeyqSpaceKind: String, CaseIterable, Hashable {
    case tenant
    case group
    case personal
}

struct MemoryScopeRoot: Codable, Hashable {
    let kind: String
    let name: String
    let displayName: String
    let path: String
}

struct MemoryNavigationLocation: Codable, Hashable {
    let kind: String
    let path: String
    let displayName: String
}

struct MemoryNavigationContextResponse: Codable, Equatable {
    let location: MemoryNavigationLocation
    let scopeRoots: [MemoryScopeRoot]
    let memory: Memory?
    let folders: [MemoryFolder]
    let memories: [Memory]

    enum CodingKeys: String, CodingKey {
        case location
        case scopeRoots
        case memory
        case folders
        case memories
    }

    init(location: MemoryNavigationLocation, scopeRoots: [MemoryScopeRoot], memory: Memory? = nil, folders: [MemoryFolder] = [], memories: [Memory] = []) {
        self.location = location
        self.scopeRoots = scopeRoots
        self.memory = memory
        self.folders = folders
        self.memories = memories
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        location = try container.decode(MemoryNavigationLocation.self, forKey: .location)
        scopeRoots = try container.decodeIfPresent([MemoryScopeRoot].self, forKey: .scopeRoots) ?? []
        memory = try container.decodeIfPresent(Memory.self, forKey: .memory)
        folders = try container.decodeIfPresent([MemoryFolder].self, forKey: .folders) ?? []
        memories = try container.decodeIfPresent([Memory].self, forKey: .memories) ?? []
    }
}

struct MemoryFolder: Codable, Identifiable, Hashable {
    let id: String
    let name: String
    let displayName: String
    let path: String
}

struct MemoryScope: Codable, Hashable {
    let type: String
    let groupName: String?
}

struct SearchResultSummary: Codable, Identifiable, Hashable {
    let kind: Kind
    let id: String
    let displayName: String
    let path: String
    let scope: MemoryScope
    let coverUrl: String?
    let coverCrop: CoverCrop?
    let matchTypes: [MatchType]
    let journalSnippet: String?

    enum Kind: String, Codable, Hashable {
        case memory = "MEMORY"
        case folder = "FOLDER"
    }

    enum MatchType: String, Codable, Hashable {
        case title = "TITLE"
        case path = "PATH"
        case journal = "JOURNAL"
    }
}

struct CreateFolderRequest: Encodable, Equatable {
    let parentPath: String?
    let name: String
    let displayName: String
    let scope: MemoryScopeRequest?
}

struct CoverCrop: Codable, Hashable {
    let x: Double
    let y: Double
    let width: Double
    let height: Double

    var isRenderable: Bool {
        x >= 0 &&
            y >= 0 &&
            width > 0 &&
            height > 0 &&
            x + width <= 1.000001 &&
            y + height <= 1.000001
    }
}

struct Memory: Codable, Identifiable, Hashable {
    let id: String
    let name: String
    let displayName: String
    let path: String
    let parentId: String?
    let coverMediaId: String?
    let coverUrl: String?
    let coverCrop: CoverCrop?
    let startDate: String
    let endDate: String
    let mediaCount: Int
    let childCount: Int

    var title: String { displayName }
    var dateRange: String {
        startDate == endDate ? startDate : "\(startDate) - \(endDate)"
    }
}

extension SearchResultSummary {
    init(memory: Memory) {
        self.init(
            kind: .memory,
            id: memory.id,
            displayName: memory.displayName,
            path: memory.path,
            scope: MemoryScope(type: "TENANT", groupName: nil),
            coverUrl: memory.coverUrl,
            coverCrop: memory.coverCrop,
            matchTypes: [.title],
            journalSnippet: nil
        )
    }

    var memory: Memory {
        Memory(
            id: id,
            name: path.split(separator: "/").last.map(String.init) ?? id,
            displayName: displayName,
            path: path,
            parentId: nil,
            coverMediaId: nil,
            coverUrl: coverUrl,
            coverCrop: coverCrop,
            startDate: "",
            endDate: "",
            mediaCount: 0,
            childCount: 0
        )
    }
}

struct MemoryScopeRequest: Encodable, Equatable {
    let type: String
    let groupName: String?
}

struct CreateMemoryRequest: Encodable, Equatable {
    let parentId: String?
    let folderPath: String?
    let name: String
    let displayName: String
    let startDate: String
    let endDate: String
    let scope: MemoryScopeRequest?
}

struct UpdateMemoryRequest: Encodable, Equatable {
    let parentId: String?
    let name: String?
    let displayName: String?
    let startDate: String?
    let endDate: String?
}

struct MemoryHeader: Codable, Equatable {
    let id: String
    let displayName: String
    let path: String
    let coverUrl: String?
    let coverCrop: CoverCrop?
    let startDate: String
    let endDate: String
    let directMediaCount: Int
    let descendantMediaCount: Int
    let chapterCount: Int
    let favorite: Bool?

    enum CodingKeys: String, CodingKey {
        case id
        case displayName
        case path
        case coverUrl
        case coverCrop
        case startDate
        case endDate
        case directMediaCount
        case descendantMediaCount
        case chapterCount = "childMemoryCount"
        case favorite
    }
}

struct MemoryJournal: Codable, Equatable {
    let memoryId: String
    let markdown: String
    let updatedAt: String?
}

struct UpdateMemoryJournalRequest: Encodable, Equatable {
    let markdown: String
}

struct MediaUploader: Codable, Equatable {
    let type: String
    let displayName: String
    let permalink: String?
    let userId: Int?
}

struct MemoryMedia: Codable, Identifiable, Equatable {
    let id: String
    let memoryId: String
    let bucketId: String?
    let title: String
    let uploader: MediaUploader
    let createdAt: Date
    let updatedAt: Date
    let capturedAt: Date?
    let capturedAtOverride: Bool
    let mimeType: String
    let sizeBytes: Int64
    let contentUrl: String
    let highlight: Bool
    let thumbnailUrl: String?
    let previewUrl: String?
    let posterUrl: String?
    let cameraMake: String?
    let cameraModel: String?
    let lensModel: String?
    let imageWidth: Int?
    let imageHeight: Int?
    let gpsLatitude: Double?
    let gpsLongitude: Double?

    init(
        id: String,
        memoryId: String,
        bucketId: String?,
        title: String,
        uploader: MediaUploader,
        createdAt: Date,
        updatedAt: Date? = nil,
        capturedAt: Date?,
        capturedAtOverride: Bool,
        mimeType: String,
        sizeBytes: Int64,
        contentUrl: String,
        highlight: Bool = false,
        thumbnailUrl: String?,
        previewUrl: String?,
        posterUrl: String?,
        cameraMake: String?,
        cameraModel: String?,
        lensModel: String?,
        imageWidth: Int?,
        imageHeight: Int?,
        gpsLatitude: Double?,
        gpsLongitude: Double?
    ) {
        self.id = id
        self.memoryId = memoryId
        self.bucketId = bucketId
        self.title = title
        self.uploader = uploader
        self.createdAt = createdAt
        self.updatedAt = updatedAt ?? createdAt
        self.capturedAt = capturedAt
        self.capturedAtOverride = capturedAtOverride
        self.mimeType = mimeType
        self.sizeBytes = sizeBytes
        self.contentUrl = contentUrl
        self.highlight = highlight
        self.thumbnailUrl = thumbnailUrl
        self.previewUrl = previewUrl
        self.posterUrl = posterUrl
        self.cameraMake = cameraMake
        self.cameraModel = cameraModel
        self.lensModel = lensModel
        self.imageWidth = imageWidth
        self.imageHeight = imageHeight
        self.gpsLatitude = gpsLatitude
        self.gpsLongitude = gpsLongitude
    }

    enum CodingKeys: String, CodingKey {
        case id
        case memoryId
        case bucketId
        case title
        case uploader
        case createdAt
        case updatedAt
        case capturedAt
        case capturedAtOverride
        case mimeType
        case sizeBytes
        case contentUrl
        case highlight
        case thumbnailUrl
        case previewUrl
        case posterUrl
        case cameraMake
        case cameraModel
        case lensModel
        case imageWidth
        case imageHeight
        case gpsLatitude
        case gpsLongitude
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        memoryId = try container.decode(String.self, forKey: .memoryId)
        bucketId = try container.decodeIfPresent(String.self, forKey: .bucketId)
        title = try container.decode(String.self, forKey: .title)
        uploader = try container.decode(MediaUploader.self, forKey: .uploader)
        createdAt = try container.decode(Date.self, forKey: .createdAt)
        updatedAt = try container.decodeIfPresent(Date.self, forKey: .updatedAt) ?? createdAt
        capturedAt = try container.decodeIfPresent(Date.self, forKey: .capturedAt)
        capturedAtOverride = try container.decode(Bool.self, forKey: .capturedAtOverride)
        mimeType = try container.decode(String.self, forKey: .mimeType)
        sizeBytes = try container.decode(Int64.self, forKey: .sizeBytes)
        contentUrl = try container.decode(String.self, forKey: .contentUrl)
        highlight = try container.decodeIfPresent(Bool.self, forKey: .highlight) ?? false
        thumbnailUrl = try container.decodeIfPresent(String.self, forKey: .thumbnailUrl)
        previewUrl = try container.decodeIfPresent(String.self, forKey: .previewUrl)
        posterUrl = try container.decodeIfPresent(String.self, forKey: .posterUrl)
        cameraMake = try container.decodeIfPresent(String.self, forKey: .cameraMake)
        cameraModel = try container.decodeIfPresent(String.self, forKey: .cameraModel)
        lensModel = try container.decodeIfPresent(String.self, forKey: .lensModel)
        imageWidth = try container.decodeIfPresent(Int.self, forKey: .imageWidth)
        imageHeight = try container.decodeIfPresent(Int.self, forKey: .imageHeight)
        gpsLatitude = try container.decodeIfPresent(Double.self, forKey: .gpsLatitude)
        gpsLongitude = try container.decodeIfPresent(Double.self, forKey: .gpsLongitude)
    }

    var displayTitle: String {
        title.isEmpty ? id : title
    }

    var isVideo: Bool {
        mimeType.hasPrefix("video/")
    }

    var isImage: Bool {
        mimeType.hasPrefix("image/")
    }

    var preferredTileUrl: String {
        thumbnailUrl ?? posterUrl ?? previewUrl ?? contentUrl
    }

    var chronologicalSortDate: Date {
        capturedAt ?? createdAt
    }

    static func chronologicallySorted(_ media: [MemoryMedia]) -> [MemoryMedia] {
        media.sorted { left, right in
            if left.chronologicalSortDate != right.chronologicalSortDate {
                return left.chronologicalSortDate < right.chronologicalSortDate
            }
            if left.createdAt != right.createdAt {
                return left.createdAt < right.createdAt
            }
            return left.id < right.id
        }
    }

    static func newestUpdateCursor(in media: [MemoryMedia]) -> MemoryMediaUpdateCursor? {
        media.max { left, right in
            if left.updatedAt != right.updatedAt {
                return left.updatedAt < right.updatedAt
            }
            return left.id < right.id
        }.map { MemoryMediaUpdateCursor(updatedAt: $0.updatedAt, mediaId: $0.id) }
    }

    static func mediaSetHash(_ ids: [String]) -> String {
        let canonical = ids.sorted().map { "\($0.count):\($0)\n" }.joined()
        let digest = SHA256.hash(data: Data(canonical.utf8))
        return digest.map { String(format: "%02x", $0) }.joined()
    }
}

struct MemoryMediaUpdateCursor: Equatable {
    let updatedAt: Date
    let mediaId: String
}

struct MemoryMediaUpdatesResponse: Codable, Equatable {
    let media: [MemoryMedia]
    let currentMediaIds: [String]?
}

struct UpdateMemoryMediaCapturedAtRequest: Encodable, Equatable {
    let capturedAt: Date?
}

struct UpdateMemoryMediaHighlightRequest: Encodable, Equatable {
    let highlight: Bool
}

struct MemoryMapPoint: Identifiable, Equatable {
    let mediaId: String
    let title: String
    let latitude: Double
    let longitude: Double

    var id: String { mediaId }

    var coordinate: CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
    }

    static func points(from media: [MemoryMedia]) -> [MemoryMapPoint] {
        media.compactMap { item in
            guard let latitude = item.gpsLatitude,
                  let longitude = item.gpsLongitude,
                  (-90...90).contains(latitude),
                  (-180...180).contains(longitude)
            else {
                return nil
            }
            return MemoryMapPoint(
                mediaId: item.id,
                title: item.displayTitle,
                latitude: latitude,
                longitude: longitude
            )
        }
    }
}

struct MemoryTrackBounds: Codable, Equatable {
    let minLatitude: Double
    let minLongitude: Double
    let maxLatitude: Double
    let maxLongitude: Double
}

struct MemoryTrackGeometry: Codable, Equatable {
    let type: String
    let coordinates: [[Double]]
}

struct MemoryTrack: Codable, Identifiable, Equatable {
    let id: String
    let memoryId: String
    let title: String
    let activityType: String
    let originalFilename: String
    let distanceMeters: Int64
    let ascentMeters: Int64?
    let descentMeters: Int64?
    let pointCount: Int
    let startedAt: Date?
    let endedAt: Date?
    let bounds: MemoryTrackBounds
    let geometry: MemoryTrackGeometry
    let contentUrl: String
    let createdAt: Date
    let updatedAt: Date

    var displayTitle: String {
        title.isEmpty ? originalFilename : title
    }

    var coordinates: [CLLocationCoordinate2D] {
        geometry.coordinates.compactMap { pair in
            guard pair.count >= 2 else { return nil }
            let longitude = pair[0]
            let latitude = pair[1]
            guard (-90...90).contains(latitude), (-180...180).contains(longitude) else { return nil }
            return CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
        }
    }

    var durationSeconds: TimeInterval? {
        guard let startedAt, let endedAt, endedAt > startedAt else { return nil }
        return endedAt.timeIntervalSince(startedAt)
    }

    var hasRenderableGeometry: Bool {
        coordinates.count >= 2
    }
}

extension Memory {
    static let samples: [Memory] = [
        Memory(id: "summer-south-tyrol", name: "summer-south-tyrol", displayName: "Summer in South Tyrol", path: "/summer-south-tyrol", parentId: nil, coverMediaId: nil, coverUrl: "/api/family/memories/summer-south-tyrol/cover/content", coverCrop: CoverCrop(x: 0.1, y: 0.0, width: 0.8, height: 0.45), startDate: "2026-07-12", endDate: "2026-07-18", mediaCount: 128, childCount: 4),
        Memory(id: "birthday-weekend", name: "birthday-weekend", displayName: "Birthday Weekend", path: "/@family/birthday-weekend", parentId: nil, coverMediaId: nil, coverUrl: nil, coverCrop: nil, startDate: "2026-03-07", endDate: "2026-03-09", mediaCount: 56, childCount: 2),
        Memory(id: "morning-garden", name: "morning-garden", displayName: "Morning Garden", path: "/~me/morning-garden", parentId: nil, coverMediaId: nil, coverUrl: nil, coverCrop: nil, startDate: "2026-05-04", endDate: "2026-05-04", mediaCount: 18, childCount: 0)
    ]
}

extension MemoryTrack {
    static let samples: [MemoryTrack] = [
        MemoryTrack(
            id: "track-ridge",
            memoryId: "summer-south-tyrol",
            title: "Ridge walk",
            activityType: "HIKING",
            originalFilename: "ridge-walk.gpx",
            distanceMeters: 12_430,
            ascentMeters: 820,
            descentMeters: 610,
            pointCount: 4,
            startedAt: Date(timeIntervalSince1970: 1_775_020_200),
            endedAt: Date(timeIntervalSince1970: 1_775_034_000),
            bounds: MemoryTrackBounds(minLatitude: 46.4924, minLongitude: 11.3461, maxLatitude: 46.526, maxLongitude: 11.398),
            geometry: MemoryTrackGeometry(
                type: "LineString",
                coordinates: [
                    [11.3461, 46.4924],
                    [11.3548, 46.4983],
                    [11.3729, 46.5121],
                    [11.398, 46.526]
                ]
            ),
            contentUrl: "/api/family/memories/summer-south-tyrol/tracks/track-ridge/content",
            createdAt: Date(timeIntervalSince1970: 1_775_034_300),
            updatedAt: Date(timeIntervalSince1970: 1_775_034_300)
        ),
        MemoryTrack(
            id: "track-lake",
            memoryId: "summer-south-tyrol",
            title: "Lake loop",
            activityType: "WALKING",
            originalFilename: "lake-loop.gpx",
            distanceMeters: 4_860,
            ascentMeters: 120,
            descentMeters: 120,
            pointCount: 4,
            startedAt: Date(timeIntervalSince1970: 1_775_088_000),
            endedAt: Date(timeIntervalSince1970: 1_775_093_400),
            bounds: MemoryTrackBounds(minLatitude: 46.478, minLongitude: 11.322, maxLatitude: 46.494, maxLongitude: 11.352),
            geometry: MemoryTrackGeometry(
                type: "LineString",
                coordinates: [
                    [11.322, 46.478],
                    [11.338, 46.486],
                    [11.352, 46.494],
                    [11.329, 46.488]
                ]
            ),
            contentUrl: "/api/family/memories/summer-south-tyrol/tracks/track-lake/content",
            createdAt: Date(timeIntervalSince1970: 1_775_093_600),
            updatedAt: Date(timeIntervalSince1970: 1_775_093_600)
        )
    ]
}

extension MemoryMedia {
    static let samples: [MemoryMedia] = [
        MemoryMedia(
            id: "media-summit",
            memoryId: "summer-south-tyrol",
            bucketId: nil,
            title: "Summit view",
            uploader: MediaUploader(type: "USER", displayName: "Kevin", permalink: "kevin", userId: 1),
            createdAt: Date(timeIntervalSince1970: 1_775_000_000),
            capturedAt: Date(timeIntervalSince1970: 1_775_000_000),
            capturedAtOverride: false,
            mimeType: "image/jpeg",
            sizeBytes: 2_048_000,
            contentUrl: "/api/family/memory-media/media-summit/content",
            highlight: true,
            thumbnailUrl: nil,
            previewUrl: nil,
            posterUrl: nil,
            cameraMake: "Fujifilm",
            cameraModel: "X100VI",
            lensModel: "23mm F2",
            imageWidth: 6240,
            imageHeight: 4160,
            gpsLatitude: 46.4983,
            gpsLongitude: 11.3548
        ),
        MemoryMedia(
            id: "media-trail",
            memoryId: "summer-south-tyrol",
            bucketId: nil,
            title: "Trail morning",
            uploader: MediaUploader(type: "USER", displayName: "Kevin", permalink: "kevin", userId: 1),
            createdAt: Date(timeIntervalSince1970: 1_775_003_600),
            capturedAt: Date(timeIntervalSince1970: 1_775_003_600),
            capturedAtOverride: false,
            mimeType: "image/jpeg",
            sizeBytes: 1_728_000,
            contentUrl: "/api/family/memory-media/media-trail/content",
            highlight: false,
            thumbnailUrl: nil,
            previewUrl: nil,
            posterUrl: nil,
            cameraMake: "Apple",
            cameraModel: "iPhone",
            lensModel: nil,
            imageWidth: 3024,
            imageHeight: 4032,
            gpsLatitude: 46.5121,
            gpsLongitude: 11.3897
        ),
        MemoryMedia(
            id: "media-family-table",
            memoryId: "birthday-weekend",
            bucketId: nil,
            title: "Family table",
            uploader: MediaUploader(type: "USER", displayName: "Kevin", permalink: "kevin", userId: 1),
            createdAt: Date(timeIntervalSince1970: 1_775_007_200),
            capturedAt: Date(timeIntervalSince1970: 1_775_007_200),
            capturedAtOverride: false,
            mimeType: "video/mp4",
            sizeBytes: 12_288_000,
            contentUrl: "/api/family/memory-media/media-family-table/content",
            highlight: false,
            thumbnailUrl: nil,
            previewUrl: nil,
            posterUrl: nil,
            cameraMake: "Apple",
            cameraModel: "iPhone",
            lensModel: nil,
            imageWidth: 1920,
            imageHeight: 1080,
            gpsLatitude: nil,
            gpsLongitude: nil
        )
    ]
}
