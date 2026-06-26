import Foundation
import HTTPTypes
import OpenAPIRuntime

// Injects the current access token as the `Authorization: Bearer …` header on every outgoing request.
// Login / challenge / verify carry no token yet, so they go out bare; once a session is held, authed
// resource calls (logout, and the key-material fetch in phase 3) get the bearer. The token is read from
// the `Session` actor per request, so a refresh is picked up on the next call automatically.
struct BearerMiddleware: ClientMiddleware {
    let session: Session

    func intercept(
        _ request: HTTPRequest,
        body: HTTPBody?,
        baseURL: URL,
        operationID: String,
        next: @Sendable (HTTPRequest, HTTPBody?, URL) async throws -> (HTTPResponse, HTTPBody?)
    ) async throws -> (HTTPResponse, HTTPBody?) {
        var request = request
        if let token = await session.accessToken {
            request.headerFields[.authorization] = "Bearer \(token)"
        }
        return try await next(request, body, baseURL)
    }
}
