package app.veyq.crypto

import kotlin.test.Test
import kotlin.test.assertEquals

class PwnedTest {
    @Test fun sha1HexUpperKnownVector() {
        assertEquals("5BAA61E4C9B93F3F0682250B6CF8331B7EE68FD8", Pwned.sha1HexUpper("password"))
    }

    @Test fun breachCountParsesMatchingSuffix() {
        val hash = "5BAA61E4C9B93F3F0682250B6CF8331B7EE68FD8"
        val body = "0018A45C4D1DEF81644B54AB7F969B88D65:1\r\n1E4C9B93F3F0682250B6CF8331B7EE68FD8:9999999\r\nFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF:3"
        assertEquals(9999999, Pwned.breachCount(hash, body))
    }

    @Test fun breachCountZeroWhenAbsent() {
        assertEquals(0, Pwned.breachCount("5BAA61E4C9B93F3F0682250B6CF8331B7EE68FD8", "0018A45C4D1DEF81644B54AB7F969B88D65:1"))
    }
}
