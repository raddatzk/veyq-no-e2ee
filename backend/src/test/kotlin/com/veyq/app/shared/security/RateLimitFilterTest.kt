package com.veyq.app.shared.security

import com.veyq.app.shared.config.RateLimitConfig
import java.util.Optional
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pure unit test for the rate-limit client-IP resolution (the A1 fix). No Quarkus/JAX-RS — the security
 * property is "a client cannot pick its own rate-limit bucket by spoofing X-Forwarded-For".
 */
class RateLimitFilterTest {
    private fun cfg(trusted: String) = object : RateLimitConfig {
        override fun enabled() = true
        override fun trustedProxies(): Optional<String> = Optional.of(trusted)
        override fun shareEgressBytesPerMin() = 524_288_000L
    }

    private fun filter(trustedProxies: String) = RateLimitFilter(RateLimiter(), cfg(trustedProxies))

    @Test
    fun `no trusted proxies — XFF is ignored, socket peer is the key`() {
        val f = filter("")
        // attacker rotates XFF — must not change the key
        assertEquals("203.0.113.7", f.resolveClientIp("203.0.113.7", "1.2.3.4"))
        assertEquals("203.0.113.7", f.resolveClientIp("203.0.113.7", "9.9.9.9, 8.8.8.8"))
        assertEquals("unknown", f.resolveClientIp(null, "1.2.3.4"))
    }

    @Test
    fun `untrusted peer — XFF ignored even if present`() {
        val f = filter("10.0.0.0/8")
        // peer is NOT a trusted proxy, so its XFF claim is worthless
        assertEquals("203.0.113.7", f.resolveClientIp("203.0.113.7", "1.2.3.4"))
    }

    @Test
    fun `trusted proxy peer — take the rightmost non-proxy hop`() {
        val f = filter("10.0.0.0/8")
        // client -> ourProxy(10.0.0.5) -> app: XFF = "client"
        assertEquals("198.51.100.23", f.resolveClientIp("10.0.0.5", "198.51.100.23"))
        // client -> edge(198.51.100.1, untrusted) -> ourProxy(10.0.0.5): take the rightmost untrusted hop
        assertEquals("198.51.100.1", f.resolveClientIp("10.0.0.5", "203.0.113.9, 198.51.100.1, 10.0.0.9"))
    }

    @Test
    fun `trusted proxy peer but no usable XFF — fall back to the socket`() {
        val f = filter("10.0.0.0/8")
        assertEquals("10.0.0.5", f.resolveClientIp("10.0.0.5", null))
        assertEquals("10.0.0.5", f.resolveClientIp("10.0.0.5", "   "))
        // every hop is itself a trusted proxy → nothing untrusted to attribute to → socket
        assertEquals("10.0.0.5", f.resolveClientIp("10.0.0.5", "10.0.0.9, 10.0.0.8"))
    }

    @Test
    fun `non-canonical CIDR (host bits set) still trusts the whole subnet`() {
        // operator wrote "10.0.0.5/24" — host bits must be masked so the trusted network is 10.0.0.0/24,
        // not just the single host. Any 10.0.0.x peer is then a trusted proxy.
        val f = filter("10.0.0.5/24")
        assertEquals("198.51.100.23", f.resolveClientIp("10.0.0.5", "198.51.100.23"))
        assertEquals("198.51.100.23", f.resolveClientIp("10.0.0.99", "198.51.100.23"))
        assertEquals("198.51.100.23", f.resolveClientIp("10.0.0.1", "198.51.100.23"))
        // outside the /24 → untrusted, its XFF claim is ignored
        assertEquals("10.0.1.1", f.resolveClientIp("10.0.1.1", "198.51.100.23"))
    }

    @Test
    fun `exact-IP and IPv6 trusted-proxy specs`() {
        val f = filter("::1, 192.168.1.1")
        assertEquals("198.51.100.23", f.resolveClientIp("192.168.1.1", "198.51.100.23"))
        assertEquals("2001:db8::1", f.resolveClientIp("::1", "2001:db8::1"))
        // a different IPv6 peer is untrusted
        assertEquals("2001:db8::99", f.resolveClientIp("2001:db8::99", "198.51.100.23"))
    }
}
