import AuthenticationServices
import Foundation
import Security
import UIKit

protocol InstanceRepository {
    func normalizeInstanceURL(_ rawValue: String) throws -> URL
}

struct DefaultInstanceRepository: InstanceRepository {
    func normalizeInstanceURL(_ rawValue: String) throws -> URL {
        try InstanceURLValidator.normalize(rawValue)
    }
}

enum InstanceURLValidationError: Error, LocalizedError, Equatable {
    case empty
    case invalid
    case unsupportedScheme

    var errorDescription: String? {
        switch self {
        case .empty: L10n.string("Enter a veyq instance URL.")
        case .invalid: L10n.string("Enter a valid veyq instance URL.")
        case .unsupportedScheme: L10n.string("Use an HTTP or HTTPS veyq instance URL.")
        }
    }
}

enum VeyqAPIError: Error, LocalizedError, Equatable {
    case invalidDiscovery
    case missingCapability(String)
    case invalidCallback
    case unauthorized
    case serverStatus(Int)
    case serverError(status: Int, response: VeyqErrorResponse)
    case missingSession
    case invalidUploadFile

    var errorDescription: String? {
        switch self {
        case .invalidDiscovery: L10n.string("This does not look like a compatible veyq instance.")
        case .missingCapability(let capability): L10n.format("This instance does not support %@.", capability)
        case .invalidCallback: L10n.string("The login callback was invalid.")
        case .unauthorized: L10n.string("The session is no longer valid.")
        case .serverStatus(let status): L10n.format("The server returned HTTP %d.", status)
        case .serverError(let status, let response):
            Self.localizedMessage(for: response.code) ?? response.message.nonEmpty ?? L10n.format("The server returned HTTP %d.", status)
        case .missingSession: L10n.string("No native session is stored.")
        case .invalidUploadFile: L10n.string("The selected upload file could not be read.")
        }
    }

    var statusCode: Int? {
        switch self {
        case .serverStatus(let status): status
        case .serverError(let status, _): status
        default: nil
        }
    }

    var apiCode: String? {
        switch self {
        case .unauthorized: "UNAUTHORIZED"
        case .serverError(_, let response): response.code
        default: nil
        }
    }

    private static func localizedMessage(for code: String) -> String? {
        let key = "api.error.\(code)"
        let localized = L10n.string(key)
        return localized == key ? nil : localized
    }
}

struct VeyqErrorResponse: Decodable, Equatable {
    let code: String
    let message: String
}

private extension String {
    var nonEmpty: String? {
        isEmpty ? nil : self
    }
}

enum InstanceURLValidator {
    static func normalize(_ rawValue: String) throws -> URL {
        let trimmed = rawValue.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { throw InstanceURLValidationError.empty }

        let withScheme = trimmed.contains("://") ? trimmed : "https://\(trimmed)"
        guard var components = URLComponents(string: withScheme), let scheme = components.scheme?.lowercased() else {
            throw InstanceURLValidationError.invalid
        }

        components.scheme = scheme
        if components.path == "/" {
            components.path = ""
        }
        while components.path.count > 1 && components.path.hasSuffix("/") {
            components.path.removeLast()
        }
        components.query = nil
        components.fragment = nil

        guard let host = components.host, !host.isEmpty else {
            throw InstanceURLValidationError.invalid
        }

        if scheme == "https" || scheme == "http" {
            return try normalizedURL(from: components)
        }

        throw InstanceURLValidationError.unsupportedScheme
    }

    private static func normalizedURL(from components: URLComponents) throws -> URL {
        guard let url = components.url else { throw InstanceURLValidationError.invalid }
        return url
    }

}

struct VeyqAPIClient {
    let instanceURL: URL
    let apiBaseURL: URL
    var accessToken: String?
    var session: URLSession = .shared

    init(instanceURL: URL, apiBaseURL: URL? = nil, accessToken: String? = nil, session: URLSession = .shared) {
        self.instanceURL = instanceURL
        self.apiBaseURL = apiBaseURL ?? instanceURL.appending(path: "api")
        self.accessToken = accessToken
        self.session = session
    }

    func discover() async throws -> VeyqInstanceDiscovery {
        let discovery: VeyqInstanceDiscovery = try await getAbsolute(instanceURL.appending(path: ".well-known/veyq"))
        guard discovery.product == "veyq" else { throw VeyqAPIError.invalidDiscovery }
        return discovery
    }

    func clientCapabilities(from url: URL) async throws -> VeyqClientCapabilities {
        try await getAbsolute(url)
    }

    func clientCapabilities() async throws -> VeyqClientCapabilities {
        try await get(path: "client-capabilities")
    }

    func exchangeMobileSession(code: String) async throws -> NativeMobileSession {
        try await post(path: "auth/mobile/session/exchange", body: ["code": code])
    }

    func refreshMobileSession(refreshToken: String) async throws -> NativeMobileSession {
        try await post(path: "auth/mobile/session/refresh", body: ["refreshToken": refreshToken])
    }

    func bootstrap() async throws -> VeyqBootstrap {
        try await get(path: "frontend/bootstrap")
    }

    func navigation(tenantName: String, path: String = "/") async throws -> MemoryNavigationResponse {
        try await get(path: "\(tenantName)/memory-navigation", query: ["path": path])
    }

    func memoriesByFolder(tenantName: String, folderPath: String = "/") async throws -> [Memory] {
        try await get(path: "\(tenantName)/memories/by-folder", query: ["folderPath": folderPath])
    }

    func memoryHeader(tenantName: String, memoryId: String) async throws -> MemoryHeader {
        try await get(path: "\(tenantName)/memories/\(memoryId)/header")
    }

    func memoryJournal(tenantName: String, memoryId: String) async throws -> MemoryJournal {
        try await get(path: "\(tenantName)/memories/\(memoryId)/journal")
    }

    func listMemoryChapters(tenantName: String, memoryId: String) async throws -> [Memory] {
        try await get(path: "\(tenantName)/memories/\(memoryId)/children")
    }

    func memoryMedia(tenantName: String, memoryId: String, includeChildren: Bool = true) async throws -> [MemoryMedia] {
        try await get(path: "\(tenantName)/memories/\(memoryId)/media", query: ["includeChildren": includeChildren ? "true" : "false"])
    }

    func mediaData(from urlString: String) async throws -> Data {
        var request = URLRequest(url: try mediaURL(from: urlString))
        request.httpMethod = "GET"
        return try await sendData(request)
    }

    func createMemoryMediaUpload(
        tenantName: String,
        memoryId: String,
        filename: String,
        mimeType: String,
        sizeBytes: Int64,
        title: String?,
        bucketId: String? = nil
    ) async throws -> MemoryMediaUploadSessionResponse {
        let body = CreateMemoryMediaUploadBody(
            filename: filename,
            mimeType: mimeType,
            sizeBytes: sizeBytes,
            title: title,
            bucketId: bucketId
        )
        return try await post(path: "\(tenantName)/memories/\(memoryId)/media/uploads", body: body)
    }

    func memoryMediaUpload(
        tenantName: String,
        memoryId: String,
        uploadId: String
    ) async throws -> PendingMemoryMediaUploadSessionResponse {
        try await get(path: "\(tenantName)/memories/\(memoryId)/media/uploads/\(uploadId)")
    }

    func uploadMemoryMediaChunk(
        tenantName: String,
        memoryId: String,
        uploadId: String,
        chunkIndex: Int,
        data: Data
    ) async throws {
        var request = URLRequest(url: apiBaseURL.appendingEncodedPath("\(tenantName)/memories/\(memoryId)/media/uploads/\(uploadId)/chunks/\(chunkIndex)"))
        request.httpMethod = "PUT"
        request.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
        request.httpBody = data
        let _: EmptyResponse = try await send(request)
    }

    func completeMemoryMediaUpload(
        tenantName: String,
        memoryId: String,
        uploadId: String
    ) async throws -> MemoryMedia {
        try await post(path: "\(tenantName)/memories/\(memoryId)/media/uploads/\(uploadId)/complete", body: EmptyRequest())
    }

    func abortMemoryMediaUpload(
        tenantName: String,
        memoryId: String,
        uploadId: String
    ) async throws {
        var request = URLRequest(url: apiBaseURL.appendingEncodedPath("\(tenantName)/memories/\(memoryId)/media/uploads/\(uploadId)"))
        request.httpMethod = "DELETE"
        let _: EmptyResponse = try await send(request)
    }

    func uploadMemoryMediaFile(
        tenantName: String,
        memoryId: String,
        fileURL: URL,
        filename: String,
        mimeType: String,
        title: String?,
        existingUploadId: String?,
        progress: @escaping @Sendable (String, Int64, Int64) async -> Void
    ) async throws -> MemoryMedia {
        let attributes = try FileManager.default.attributesOfItem(atPath: fileURL.path)
        guard let size = attributes[.size] as? NSNumber else { throw VeyqAPIError.invalidUploadFile }
        let sizeBytes = size.int64Value
        var uploadId: String
        var pending: PendingMemoryMediaUploadSessionResponse

        if let existingUploadId {
            do {
                uploadId = existingUploadId
                pending = try await memoryMediaUpload(tenantName: tenantName, memoryId: memoryId, uploadId: existingUploadId)
                if pending.sizeBytes != sizeBytes {
                    (uploadId, pending) = try await createAndLoadMemoryMediaUpload(
                        tenantName: tenantName,
                        memoryId: memoryId,
                        filename: filename,
                        mimeType: mimeType,
                        sizeBytes: sizeBytes,
                        title: title,
                        progress: progress
                    )
                }
            } catch {
                guard shouldRestartUploadSession(after: error) else { throw error }
                (uploadId, pending) = try await createAndLoadMemoryMediaUpload(
                    tenantName: tenantName,
                    memoryId: memoryId,
                    filename: filename,
                    mimeType: mimeType,
                    sizeBytes: sizeBytes,
                    title: title,
                    progress: progress
                )
            }
        } else {
            (uploadId, pending) = try await createAndLoadMemoryMediaUpload(
                tenantName: tenantName,
                memoryId: memoryId,
                filename: filename,
                mimeType: mimeType,
                sizeBytes: sizeBytes,
                title: title,
                progress: progress
            )
        }

        let uploadedChunks = Set(pending.uploadedChunks)
        var uploadedBytes = pending.uploadedBytes
        await progress(uploadId, uploadedBytes, sizeBytes)

        let handle = try FileHandle(forReadingFrom: fileURL)
        defer { try? handle.close() }

        for (index, range) in UploadChunkPlanner.ranges(sizeBytes: sizeBytes, chunkSizeBytes: pending.chunkSizeBytes).enumerated() {
            try Task.checkCancellation()
            if uploadedChunks.contains(index) {
                uploadedBytes = max(uploadedBytes, range.upperBound)
                await progress(uploadId, uploadedBytes, sizeBytes)
                continue
            }
            try handle.seek(toOffset: UInt64(range.lowerBound))
            let data = try handle.read(upToCount: Int(range.upperBound - range.lowerBound)) ?? Data()
            try await retryTransientUploadOperation {
                try await uploadMemoryMediaChunk(
                    tenantName: tenantName,
                    memoryId: memoryId,
                    uploadId: uploadId,
                    chunkIndex: index,
                    data: data
                )
            }
            uploadedBytes = range.upperBound
            await progress(uploadId, uploadedBytes, sizeBytes)
        }

        return try await retryTransientUploadOperation {
            try await completeMemoryMediaUpload(tenantName: tenantName, memoryId: memoryId, uploadId: uploadId)
        }
    }

    private func createAndLoadMemoryMediaUpload(
        tenantName: String,
        memoryId: String,
        filename: String,
        mimeType: String,
        sizeBytes: Int64,
        title: String?,
        progress: @escaping @Sendable (String, Int64, Int64) async -> Void
    ) async throws -> (String, PendingMemoryMediaUploadSessionResponse) {
        let created = try await createMemoryMediaUpload(
            tenantName: tenantName,
            memoryId: memoryId,
            filename: filename,
            mimeType: mimeType,
            sizeBytes: sizeBytes,
            title: title
        )
        await progress(created.uploadId, 0, sizeBytes)
        let pending = try await memoryMediaUpload(tenantName: tenantName, memoryId: memoryId, uploadId: created.uploadId)
        return (created.uploadId, pending)
    }

    private func shouldRestartUploadSession(after error: Error) -> Bool {
        switch error {
        case VeyqAPIError.serverStatus(404):
            return true
        case VeyqAPIError.serverError(let status, _):
            return status == 404
        default:
            return false
        }
    }

    private func retryTransientUploadOperation<T>(
        maxRetries: Int = 2,
        operation: () async throws -> T
    ) async throws -> T {
        var attempt = 0
        while true {
            do {
                return try await operation()
            } catch {
                guard attempt < maxRetries, isTransientUploadError(error) else { throw error }
                let delayNanoseconds = UInt64(300_000_000 * (1 << attempt))
                attempt += 1
                try await Task.sleep(nanoseconds: delayNanoseconds)
            }
        }
    }

    private func isTransientUploadError(_ error: Error) -> Bool {
        if let apiError = error as? VeyqAPIError {
            return apiError.statusCode.map { 500..<600 ~= $0 } ?? false
        }
        guard let urlError = error as? URLError else { return false }
        switch urlError.code {
        case .timedOut,
             .cannotFindHost,
             .cannotConnectToHost,
             .networkConnectionLost,
             .dnsLookupFailed,
             .notConnectedToInternet,
             .internationalRoamingOff,
             .callIsActive,
             .dataNotAllowed:
            return true
        default:
            return false
        }
    }

    func logoutMobileSession() async throws {
        let _: EmptyResponse = try await post(path: "auth/mobile/logout", body: EmptyRequest())
    }

    private func get<T: Decodable>(path: String, query: [String: String] = [:]) async throws -> T {
        try await getAbsolute(apiBaseURL.appendingEncodedPath(path, query: query))
    }

    private func getAbsolute<T: Decodable>(_ url: URL) async throws -> T {
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        return try await send(request)
    }

    private func post<T: Decodable, Body: Encodable>(path: String, body: Body) async throws -> T {
        var request = URLRequest(url: apiBaseURL.appendingEncodedPath(path))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if Body.self != EmptyRequest.self {
            request.httpBody = try JSONEncoder.veyq.encode(body)
        }
        return try await send(request)
    }

    private func send<T: Decodable>(_ request: URLRequest) async throws -> T {
        let data = try await sendData(request)
        if T.self == EmptyResponse.self {
            return EmptyResponse() as! T
        }
        return try JSONDecoder.veyq.decode(T.self, from: data)
    }

    private func sendData(_ request: URLRequest) async throws -> Data {
        var request = request
        if let accessToken {
            request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        }
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw VeyqAPIError.invalidDiscovery }
        guard 200..<300 ~= http.statusCode else {
            if http.statusCode == 401 { throw VeyqAPIError.unauthorized }
            if let errorResponse = try? JSONDecoder.veyq.decode(VeyqErrorResponse.self, from: data) {
                throw VeyqAPIError.serverError(status: http.statusCode, response: errorResponse)
            }
            throw VeyqAPIError.serverStatus(http.statusCode)
        }
        return data
    }

    private func mediaURL(from urlString: String) throws -> URL {
        if let absolute = URL(string: urlString), absolute.scheme != nil {
            return absolute
        }

        guard var components = URLComponents(url: instanceURL, resolvingAgainstBaseURL: false) else {
            throw VeyqAPIError.invalidDiscovery
        }

        if urlString.hasPrefix("/") {
            components.path = urlString
        } else {
            let basePath = components.path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            components.path = "/" + ([basePath, urlString].filter { !$0.isEmpty }.joined(separator: "/"))
        }
        components.query = nil
        components.fragment = nil

        guard let url = components.url else { throw VeyqAPIError.invalidDiscovery }
        return url
    }
}

struct EmptyRequest: Encodable {}
struct EmptyResponse: Decodable {}

protocol NativeSessionStoring {
    func loadActive() throws -> StoredNativeSession?
    func loadAll() throws -> [StoredNativeSession]
    func save(_ session: StoredNativeSession) throws
    func delete() throws
    func delete(instanceURL: URL) throws
}

final class KeychainNativeSessionStore: NativeSessionStoring {
    private let service: String
    private let account: String

    init(service: String = "com.veyq.ios.native-session", account: String = "active") {
        self.service = service
        self.account = account
    }

    func loadActive() throws -> StoredNativeSession? {
        let collection = try loadCollection()
        guard let activeInstanceURL = collection.activeInstanceURL else {
            return collection.sessions.first
        }
        return collection.sessions.first { $0.instanceURL == activeInstanceURL } ?? collection.sessions.first
    }

    func loadAll() throws -> [StoredNativeSession] {
        try loadCollection().sessions
    }

    private func loadCollection() throws -> StoredNativeSessionCollection {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true
        ]
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        if status == errSecItemNotFound {
            return StoredNativeSessionCollection(activeInstanceURL: nil, sessions: [])
        }
        guard status == errSecSuccess, let data = item as? Data else { throw VeyqAPIError.missingSession }
        if let collection = try? JSONDecoder.veyq.decode(StoredNativeSessionCollection.self, from: data) {
            return collection
        }
        let legacySession = try JSONDecoder.veyq.decode(StoredNativeSession.self, from: data)
        return StoredNativeSessionCollection(activeInstanceURL: legacySession.instanceURL, sessions: [legacySession])
    }

    func save(_ session: StoredNativeSession) throws {
        let existing = try loadCollection().sessions.filter { $0.instanceURL != session.instanceURL }
        try writeCollection(StoredNativeSessionCollection(activeInstanceURL: session.instanceURL, sessions: [session] + existing))
    }

    private func writeCollection(_ collection: StoredNativeSessionCollection) throws {
        try delete()
        let data = try JSONEncoder.veyq.encode(collection)
        let item: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecValueData as String: data
        ]
        let status = SecItemAdd(item as CFDictionary, nil)
        guard status == errSecSuccess else { throw VeyqAPIError.missingSession }
    }

    func delete() throws {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
        let status = SecItemDelete(query as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else { throw VeyqAPIError.missingSession }
    }

    func delete(instanceURL: URL) throws {
        let collection = try loadCollection()
        let remaining = collection.sessions.filter { $0.instanceURL != instanceURL }
        let active = collection.activeInstanceURL == instanceURL ? remaining.first?.instanceURL : collection.activeInstanceURL
        try writeCollection(StoredNativeSessionCollection(activeInstanceURL: active, sessions: remaining))
    }
}

final class InMemoryNativeSessionStore: NativeSessionStoring {
    private var activeInstanceURL: URL?
    private var sessions: [StoredNativeSession] = []

    func loadActive() throws -> StoredNativeSession? {
        guard let activeInstanceURL else { return sessions.first }
        return sessions.first { $0.instanceURL == activeInstanceURL } ?? sessions.first
    }

    func loadAll() throws -> [StoredNativeSession] { sessions }

    func save(_ session: StoredNativeSession) throws {
        sessions.removeAll { $0.instanceURL == session.instanceURL }
        sessions.insert(session, at: 0)
        activeInstanceURL = session.instanceURL
    }

    func delete() throws {
        sessions = []
        activeInstanceURL = nil
    }

    func delete(instanceURL: URL) throws {
        sessions.removeAll { $0.instanceURL == instanceURL }
        if activeInstanceURL == instanceURL {
            activeInstanceURL = sessions.first?.instanceURL
        }
    }
}

@MainActor
protocol MobileAuthenticating {
    func authenticate(loginURL: URL, callbackScheme: String) async throws -> URL
}

@MainActor
final class SystemMobileAuthenticator: NSObject, MobileAuthenticating, ASWebAuthenticationPresentationContextProviding {
    private var anchor: ASPresentationAnchor?

    func authenticate(loginURL: URL, callbackScheme: String) async throws -> URL {
        try await withCheckedThrowingContinuation { continuation in
            let session = ASWebAuthenticationSession(url: loginURL, callback: .customScheme(callbackScheme)) { callbackURL, error in
                if let callbackURL {
                    continuation.resume(returning: callbackURL)
                } else {
                    continuation.resume(throwing: error ?? VeyqAPIError.invalidCallback)
                }
            }
            session.presentationContextProvider = self
            session.prefersEphemeralWebBrowserSession = false
            session.start()
        }
    }

    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        if let anchor {
            return anchor
        }
        let scenes = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
        if let window = scenes
            .flatMap(\.windows)
            .first(where: \.isKeyWindow) {
            return window
        }
        if let window = scenes
            .first(where: { $0.activationState == .foregroundActive })?
            .windows
            .first {
            return window
        }
        guard let scene = scenes.first else {
            preconditionFailure("ASWebAuthenticationSession requires an active UIWindowScene.")
        }
        let anchor = ASPresentationAnchor(windowScene: scene)
        self.anchor = anchor
        return anchor
    }
}

private extension URL {
    func appendingEncodedPath(_ path: String, query: [String: String] = [:]) -> URL {
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

extension JSONDecoder {
    static var veyq: JSONDecoder {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .custom { decoder in
            let container = try decoder.singleValueContainer()
            let value = try container.decode(String.self)
            if let date = ISO8601DateFormatter.veyqDate(from: value) {
                return date
            }
            throw DecodingError.dataCorruptedError(in: container, debugDescription: "Invalid ISO-8601 date: \(value)")
        }
        return decoder
    }
}

private extension ISO8601DateFormatter {
    static func veyqDate(from value: String) -> Date? {
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

extension JSONEncoder {
    static var veyq: JSONEncoder {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        return encoder
    }
}
