package com.veyq.api.settings

import com.veyq.api.domain.ServerRole
import com.veyq.api.domain.User
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CapabilityRegistryTest {
    private val registry = CapabilityRegistry()

    @Test
    fun `upload eta capability is visible to regular users`() {
        val user = User().apply {
            role = ServerRole.ServerMember
        }

        assertThat(registry.listFor(user).map { it.id })
            .contains("memory.media-upload-eta")
            .contains("memory.media-live-updates")
    }

    @Test
    fun `upload eta capability is announced as new`() {
        val user = User().apply {
            role = ServerRole.ServerMember
        }

        assertThat(registry.notificationCandidatesFor(user).map { it.id })
            .contains("memory.media-upload-eta")
            .contains("memory.media-live-updates")
    }
}
