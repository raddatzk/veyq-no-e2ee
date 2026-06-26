import Foundation
import OpenAPIRuntime
import OpenAPIURLSession

// Re-export the generated clients so the rest of the app imports a single `VeyqApi`.
@_exported import SetupAPI
@_exported import AuthAPI
@_exported import AccountAPI

/// Builds the URLSession transport every generated client shares.
///
/// The three generated `Client`s (`SetupAPI.Client`, `AuthAPI.Client`, `AccountAPI.Client`) all
/// point at the same instance base URL and the same transport; `VeyqKit` constructs them from this.
public enum VeyqTransport {
    /// The shared client transport (URLSession-backed).
    public static func make() -> any ClientTransport {
        URLSessionTransport()
    }
}
