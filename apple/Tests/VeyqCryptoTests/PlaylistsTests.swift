import XCTest
import VeyqCrypto

// Native parity for playlist provider detection — byte-compatible with the web parser
// frontend/src/features/memory/playlist/playlist.ts (mirrors playlist.test.ts).
final class PlaylistsTests: XCTestCase {
    func testDetectsKnownProvidersFromHost() {
        XCTAssertEqual(Playlists.detectProvider("https://open.spotify.com/playlist/37i9dQ"), .spotify)
        XCTAssertEqual(Playlists.detectProvider("https://music.apple.com/us/playlist/x/pl.u-123"), .appleMusic)
        XCTAssertEqual(Playlists.detectProvider("https://www.youtube.com/playlist?list=PLabc"), .youtube)
        XCTAssertEqual(Playlists.detectProvider("https://youtu.be/dQw4w9WgXcQ"), .youtube)
        XCTAssertEqual(Playlists.detectProvider("https://soundcloud.com/artist/sets/mix"), .soundcloud)
        XCTAssertEqual(Playlists.detectProvider("https://www.deezer.com/en/playlist/123"), .deezer)
    }

    func testFallsBackToOtherForUnknownHost() {
        XCTAssertEqual(Playlists.detectProvider("https://example.com/track/1"), .other)
    }

    func testReturnsOtherForMalformedUrl() {
        XCTAssertEqual(Playlists.detectProvider("not a url"), .other)
        XCTAssertEqual(Playlists.detectProvider(""), .other)
    }

    func testHasLabelForEveryProvider() {
        for p in [Playlists.Provider.spotify, .appleMusic, .youtube, .soundcloud, .deezer, .other] {
            XCTAssertFalse(p.label.isEmpty)
        }
    }
}
