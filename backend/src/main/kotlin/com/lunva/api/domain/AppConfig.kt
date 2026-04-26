package com.lunva.api.domain

import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "lunva_config")
class AppConfig : PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(name = "config_key", nullable = false, unique = true)
    lateinit var configKey: String

    @Column(name = "config_value", nullable = false)
    lateinit var configValue: String

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: Instant
}

@ApplicationScoped
class AppConfigRepo : PanacheRepository<AppConfig> {
    fun findByKey(configKey: String): AppConfig? =
        find("configKey", configKey).firstResult()
}

