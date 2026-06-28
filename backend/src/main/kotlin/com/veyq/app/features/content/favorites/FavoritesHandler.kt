package com.veyq.app.features.content.favorites

import jakarta.enterprise.context.ApplicationScoped

sealed interface GetFavoritesOutcome {
    data class Found(val ciphertext: String?) : GetFavoritesOutcome
    object NoScope : GetFavoritesOutcome
}

sealed interface PutFavoritesOutcome {
    object Stored : PutFavoritesOutcome
    object NoScope : PutFavoritesOutcome
}

/**
 * Use-case logic for per-(user, scope) favorites (cr-0031). Scope access = the caller holds a membership for
 * the scope (operator-blind: a non-member sees a 404, not a reveal). The handler never inspects the blob.
 */
@ApplicationScoped
class FavoritesHandler(private val store: FavoritesStore) {

    fun get(userId: String, scopeId: String): GetFavoritesOutcome {
        if (!store.isMember(userId, scopeId)) return GetFavoritesOutcome.NoScope
        return GetFavoritesOutcome.Found(store.get(userId, scopeId))
    }

    fun put(userId: String, scopeId: String, ciphertext: String): PutFavoritesOutcome {
        if (!store.isMember(userId, scopeId)) return PutFavoritesOutcome.NoScope
        store.upsert(userId, scopeId, ciphertext)
        return PutFavoritesOutcome.Stored
    }
}
