package com.veyq.api.library.playlist

import com.veyq.api.auth.BadRequestException
import com.veyq.api.domain.MemoryPlaylistProvider
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class MemoryPlaylistUrlParserTest {
    @Test
    fun `parses supported playlist urls`() {
        playlistUrls().forEach { (url, provider, externalId) ->
            val parsed = MemoryPlaylistUrlParser.parse(url)

            assertThat(parsed.provider).isEqualTo(provider)
            assertThat(parsed.externalId).isEqualTo(externalId)
            assertThat(parsed.url).startsWith("https://")
        }
    }

    @Test
    fun `rejects unsupported playlist urls`() {
        assertThatThrownBy { MemoryPlaylistUrlParser.parse("https://example.com/playlist/abc") }
            .isInstanceOf(BadRequestException::class.java)
            .hasMessageContaining("Unsupported playlist URL")
    }

    @Test
    fun `rejects non-https playlist urls`() {
        assertThatThrownBy { MemoryPlaylistUrlParser.parse("http://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M") }
            .isInstanceOf(BadRequestException::class.java)
            .hasMessageContaining("https")
    }

    @Test
    fun `rejects urls with fragments`() {
        assertThatThrownBy { MemoryPlaylistUrlParser.parse("https://www.youtube.com/playlist?list=PL1234567890#top") }
            .isInstanceOf(BadRequestException::class.java)
            .hasMessageContaining("fragment")
    }

    private data class PlaylistUrl(
        val url: String,
        val provider: MemoryPlaylistProvider,
        val externalId: String,
    )

    private fun playlistUrls(): List<PlaylistUrl> =
        listOf(
                PlaylistUrl(
                    "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=test",
                    MemoryPlaylistProvider.SPOTIFY,
                    "37i9dQZF1DXcBWIGoYBM5M",
                ),
                PlaylistUrl(
                    "https://music.apple.com/de/playlist/roadtrip/pl.u-1234567890",
                    MemoryPlaylistProvider.APPLE_MUSIC,
                    "pl.u-1234567890",
                ),
                PlaylistUrl(
                    "https://www.youtube.com/playlist?list=PL1234567890abcdef",
                    MemoryPlaylistProvider.YOUTUBE,
                    "PL1234567890abcdef",
                ),
                PlaylistUrl(
                    "https://music.youtube.com/playlist?list=OLAK5uy_1234567890",
                    MemoryPlaylistProvider.YOUTUBE,
                    "OLAK5uy_1234567890",
                ),
                PlaylistUrl(
                    "https://soundcloud.com/artist/sets/summer-mix",
                    MemoryPlaylistProvider.SOUNDCLOUD,
                    "artist/sets/summer-mix",
                ),
                PlaylistUrl(
                    "https://www.deezer.com/de/playlist/123456789",
                    MemoryPlaylistProvider.DEEZER,
                    "123456789",
                ),
        )
}
