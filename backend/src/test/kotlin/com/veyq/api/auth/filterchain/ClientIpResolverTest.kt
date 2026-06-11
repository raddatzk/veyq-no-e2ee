package com.veyq.api.auth.filterchain

import com.veyq.api.security.SecurityConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ClientIpResolverTest {
    @Test
    fun `ignores forwarded for when it is not trusted`() {
        val resolver = ClientIpResolver(securityConfig(trusted = false))

        val clientIp = resolver.resolve("203.0.113.10, 203.0.113.11", "127.0.0.1")

        assertThat(clientIp).isEqualTo("127.0.0.1")
    }

    @Test
    fun `uses first forwarded for value when trusted`() {
        val resolver = ClientIpResolver(securityConfig(trusted = true))

        val clientIp = resolver.resolve("203.0.113.10, 203.0.113.11", "127.0.0.1")

        assertThat(clientIp).isEqualTo("203.0.113.10")
    }

    @Test
    fun `falls back to remote host when trusted forwarded for is blank`() {
        val resolver = ClientIpResolver(securityConfig(trusted = true))

        val clientIp = resolver.resolve("  ", "127.0.0.1")

        assertThat(clientIp).isEqualTo("127.0.0.1")
    }

    private fun securityConfig(trusted: Boolean): SecurityConfig =
        object : SecurityConfig {
            override val trustForwardedFor = trusted
            override val publicShareUpdates = object : SecurityConfig.PublicShareUpdates {
                override val maxConcurrentPerIp = 2
                override val maxConcurrentTotal = 64
            }
        }
}
