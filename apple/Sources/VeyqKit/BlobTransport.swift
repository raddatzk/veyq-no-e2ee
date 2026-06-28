import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// The direct-to-store byte transport (cr-0028, ADR 0013): the client PUT/GETs **raw sealed ciphertext**
/// straight to a presigned object-store URL — never through the backend, never with credentials. Injectable
/// so the hermetic data-layer tests drive an in-memory store instead of a real MinIO.
public protocol BlobTransport: Sendable {
    /// PUT raw bytes to a presigned URL (no base64, no JSON).
    func put(_ url: URL, _ body: Data) async throws
    /// GET raw bytes from a presigned URL.
    func get(_ url: URL) async throws -> Data
}

/// Default `URLSession`-backed transport for presigned PUT/GET.
public struct URLSessionBlobTransport: BlobTransport {
    private let session: URLSession

    public init(session: URLSession = .shared) { self.session = session }

    public func put(_ url: URL, _ body: Data) async throws {
        var request = URLRequest(url: url)
        request.httpMethod = "PUT"
        let (_, response) = try await session.upload(for: request, from: body)
        try Self.check(response, url)
    }

    public func get(_ url: URL) async throws -> Data {
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        let (data, response) = try await session.data(for: request)
        try Self.check(response, url)
        return data
    }

    private static func check(_ response: URLResponse, _ url: URL) throws {
        guard let http = response as? HTTPURLResponse, (200 ..< 300).contains(http.statusCode) else {
            throw VeyqError.blobTransferFailed
        }
    }
}
