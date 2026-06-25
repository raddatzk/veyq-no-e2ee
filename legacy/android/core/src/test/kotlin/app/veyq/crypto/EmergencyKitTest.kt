package app.veyq.crypto

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EmergencyKitTest {
    private val data = EmergencyKit.KitData("https://veyq.example", "a@veyq.app", "KEY-abc_123")

    @Test
    fun roundTrips() {
        assertEquals(data, EmergencyKit.parse(EmergencyKit.payload(data)))
    }

    @Test
    fun findsPayloadEmbeddedInText() {
        val blob = "%PDF-1.7 …/Keywords (${EmergencyKit.payload(data)})… stream"
        assertEquals(data, EmergencyKit.parse(blob))
    }

    /** A kit produced by the web client must parse here byte-for-byte (cross-platform token). */
    @Test
    fun parsesWebProducedToken() {
        val b64 = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"v":1,"instance":"","email":"a@veyq.app","sk":"KEY-abc_123"}""".toByteArray())
        assertEquals(EmergencyKit.KitData("", "a@veyq.app", "KEY-abc_123"), EmergencyKit.parse("veyqkit:$b64"))
    }

    @Test
    fun rejectsGarbage() {
        assertNull(EmergencyKit.parse("no kit token here"))
        assertNull(EmergencyKit.parse("veyqkit:not-valid-json"))
    }
}
