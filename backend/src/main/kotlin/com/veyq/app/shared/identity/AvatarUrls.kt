package com.veyq.app.shared.identity

import com.veyq.app.generated.model.UserSummary
import com.veyq.app.shared.storage.BlobStore
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class AvatarUrls(private val store: BlobStore) {
    fun currentUserSummary(user: User): UserSummary =
        user.toUserSummary(avatarUrl = currentAvatarUrl(user))

    fun currentAvatarUrl(user: User): String? =
        avatarUrl(user)

    fun profileAvatarUrl(user: User): String? =
        avatarUrl(user)

    private fun avatarUrl(user: User): String? {
        val key = user.avatarObjectKey ?: return null
        return store.presignGet(key, avatarUrlTtlSeconds)
    }

    private companion object {
        const val avatarUrlTtlSeconds = 900
    }
}
