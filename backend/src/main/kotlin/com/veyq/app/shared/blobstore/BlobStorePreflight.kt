package com.veyq.app.shared.blobstore

import jakarta.enterprise.context.ApplicationScoped

/**
 * Advisory blob-transport capability surfaced on `/api/setup` (ADR 0013 §4). This slice reports the store's
 * presign capability; a deeper public-route reachability probe (a HEAD against the signed endpoint) is a later
 * refinement. Never blocks startup.
 */
@ApplicationScoped
class BlobStorePreflight(private val blobStore: BlobStore) {
    /** True when direct-to-store media transport is available (the store can presign). */
    fun directTransport(): Boolean = blobStore.canPresign
}
