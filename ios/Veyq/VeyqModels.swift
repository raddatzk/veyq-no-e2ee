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

struct StoredNativeSession: Codable, Equatable {
    let instanceURL: URL
    let apiBaseURL: URL
    let accessToken: String
    let accessTokenExpiresAt: Date
    let refreshToken: String
    let refreshTokenExpiresAt: Date
    let user: VeyqUser
    let capabilities: [VeyqCapability]

    init(
        instanceURL: URL,
        apiBaseURL: URL,
        accessToken: String,
        accessTokenExpiresAt: Date,
        refreshToken: String,
        refreshTokenExpiresAt: Date,
        user: VeyqUser,
        capabilities: [VeyqCapability] = []
    ) {
        self.instanceURL = instanceURL
        self.apiBaseURL = apiBaseURL
        self.accessToken = accessToken
        self.accessTokenExpiresAt = accessTokenExpiresAt
        self.refreshToken = refreshToken
        self.refreshTokenExpiresAt = refreshTokenExpiresAt
        self.user = user
        self.capabilities = capabilities
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
            capabilities: capabilities
        )
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

struct MemoryNavigationResponse: Codable, Equatable {
    let location: MemoryNavigationLocation
    let scopeRoots: [MemoryScopeRoot]
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
    let capturedAt: Date?
    let capturedAtOverride: Bool
    let mimeType: String
    let sizeBytes: Int64
    let contentUrl: String
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

    var displayTitle: String {
        title.isEmpty ? id : title
    }

    var isVideo: Bool {
        mimeType.hasPrefix("video/")
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
}

extension Memory {
    static let samples: [Memory] = [
        Memory(id: "summer-south-tyrol", name: "summer-south-tyrol", displayName: "Summer in South Tyrol", path: "/summer-south-tyrol", parentId: nil, coverMediaId: nil, coverUrl: "/api/family/memories/summer-south-tyrol/cover/content", coverCrop: CoverCrop(x: 0.1, y: 0.0, width: 0.8, height: 0.45), startDate: "2026-07-12", endDate: "2026-07-18", mediaCount: 128, childCount: 4),
        Memory(id: "birthday-weekend", name: "birthday-weekend", displayName: "Birthday Weekend", path: "/@family/birthday-weekend", parentId: nil, coverMediaId: nil, coverUrl: nil, coverCrop: nil, startDate: "2026-03-07", endDate: "2026-03-09", mediaCount: 56, childCount: 2),
        Memory(id: "morning-garden", name: "morning-garden", displayName: "Morning Garden", path: "/~me/morning-garden", parentId: nil, coverMediaId: nil, coverUrl: nil, coverCrop: nil, startDate: "2026-05-04", endDate: "2026-05-04", mediaCount: 18, childCount: 0)
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
            thumbnailUrl: nil,
            previewUrl: nil,
            posterUrl: nil,
            cameraMake: "Fujifilm",
            cameraModel: "X100VI",
            lensModel: "23mm F2",
            imageWidth: 6240,
            imageHeight: 4160,
            gpsLatitude: nil,
            gpsLongitude: nil
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
            thumbnailUrl: nil,
            previewUrl: nil,
            posterUrl: nil,
            cameraMake: "Apple",
            cameraModel: "iPhone",
            lensModel: nil,
            imageWidth: 3024,
            imageHeight: 4032,
            gpsLatitude: nil,
            gpsLongitude: nil
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
