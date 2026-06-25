package com.veyq.app.shared.security

import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepositoryBase
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.LockModeType
import jakarta.persistence.Table

/** Single seeded row ('SERVER') used purely as a setup mutex (see [ServerSetupRepository.lockForSetup]). */
@Entity
@Table(name = "veyq_server_setup")
class ServerSetup {
    @Id var id: String = ""
}

@ApplicationScoped
class ServerSetupRepository : PanacheRepositoryBase<ServerSetup, String> {
    /**
     * Row-lock the singleton inside the caller's transaction so concurrent server-owner setups serialize:
     * the first holds the lock through its commit, the second blocks then sees the owner already exists.
     */
    fun lockForSetup() {
        find("id", "SERVER").withLock(LockModeType.PESSIMISTIC_WRITE).firstResult()
    }
}
