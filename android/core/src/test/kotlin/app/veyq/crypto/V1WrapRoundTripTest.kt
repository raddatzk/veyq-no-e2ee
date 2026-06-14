package app.veyq.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals

/** Proves asym-wrap (encap) and asym-unwrap (decap) are mutually consistent on this stack. Combined with
 *  the cross-stack decap KAT in V1ConformanceTest, this makes the encap direction interoperable too. */
class V1WrapRoundTripTest {
    @Test
    fun asymWrapRoundTrip() {
        val id = V1.generateIdentity()
        val secret = V1.randomBytes(32)
        val blob = V1.asymWrap(id.pubKemX, id.pubKemM, secret, "scope-grant", V1.randomBytes(12))
        val opened = V1.asymUnwrap(id.privKemX, id.mlkemSeed, id.pubKemX, id.pubKemM, blob, "scope-grant")
        assertContentEquals(secret, opened)
    }
}
