package app.veyq.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Native parity for playlist provider detection — byte-compatible with the web parser
// frontend/src/features/memory/playlist/playlist.ts (mirrors playlist.test.ts).
class PlaylistsTest {
    @Test fun detectsKnownProvidersFromHost() {
        assertEquals(Playlists.Provider.SPOTIFY, Playlists.detectProvider("https://open.spotify.com/playlist/37i9dQ"))
        assertEquals(Playlists.Provider.APPLE_MUSIC, Playlists.detectProvider("https://music.apple.com/us/playlist/x/pl.u-123"))
        assertEquals(Playlists.Provider.YOUTUBE, Playlists.detectProvider("https://www.youtube.com/playlist?list=PLabc"))
        assertEquals(Playlists.Provider.YOUTUBE, Playlists.detectProvider("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals(Playlists.Provider.SOUNDCLOUD, Playlists.detectProvider("https://soundcloud.com/artist/sets/mix"))
        assertEquals(Playlists.Provider.DEEZER, Playlists.detectProvider("https://www.deezer.com/en/playlist/123"))
    }

    @Test fun fallsBackToOtherForUnknownHost() {
        assertEquals(Playlists.Provider.OTHER, Playlists.detectProvider("https://example.com/track/1"))
    }

    @Test fun returnsOtherForMalformedUrl() {
        assertEquals(Playlists.Provider.OTHER, Playlists.detectProvider("not a url"))
        assertEquals(Playlists.Provider.OTHER, Playlists.detectProvider(""))
    }

    @Test fun hasLabelForEveryProvider() {
        Playlists.Provider.entries.forEach { assertTrue(it.label.isNotEmpty()) }
    }
}
