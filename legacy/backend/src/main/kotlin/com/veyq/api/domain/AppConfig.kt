package com.veyq.api.domain

import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.*

@Entity
@Table(name = "veyq_config")
class AppConfig : PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(name = "config_key", nullable = false, unique = true)
    lateinit var configKey: String

    @Column(name = "config_value", nullable = false)
    lateinit var configValue: String

    companion object {
        const val ARGON2_PARAMS = "auth.argon2.params"
        const val SERVER_ID = "server.id"
    }
}

@ApplicationScoped
class AppConfigRepo : PanacheRepository<AppConfig> {
    fun findByKey(configKey: String): AppConfig? =
        find("configKey", configKey).firstResult()
}
