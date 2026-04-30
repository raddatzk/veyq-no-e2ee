package com.lunva.api.library.media

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MediaRangesTest {
    @Test
    fun `parses explicit open ended and suffix ranges`() {
        assertThat(MediaRanges.parse("bytes=0-3", 8)).isEqualTo(ContentRange(0, 3))
        assertThat(MediaRanges.parse("bytes=4-", 8)).isEqualTo(ContentRange(4, 7))
        assertThat(MediaRanges.parse("bytes=-4", 8)).isEqualTo(ContentRange(4, 7))
        assertThat(MediaRanges.parse("bytes=-20", 8)).isEqualTo(ContentRange(0, 7))
    }

    @Test
    fun `rejects invalid ranges`() {
        assertThat(MediaRanges.parse(null, 8)).isNull()
        assertThat(MediaRanges.parse("items=0-3", 8)).isNull()
        assertThat(MediaRanges.parse("bytes=8-9", 8)).isNull()
        assertThat(MediaRanges.parse("bytes=6-4", 8)).isNull()
        assertThat(MediaRanges.parse("bytes=-0", 8)).isNull()
        assertThat(MediaRanges.parse("bytes=a-b", 8)).isNull()
        assertThat(MediaRanges.parse("bytes=0-3", 0)).isNull()
    }
}
