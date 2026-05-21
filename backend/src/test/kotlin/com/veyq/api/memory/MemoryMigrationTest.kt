package com.veyq.api.memory

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.util.UUID

class MemoryMigrationTest {
    @Test
    fun `v2 migrates existing albums into root memories and assigns media from first visible album link`() {
        val database = "veyq_migration_${UUID.randomUUID().toString().replace("-", "")}"
        val url = "jdbc:h2:mem:$database;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS veyq\\;SET SCHEMA veyq"

        flyway(url, "1").migrate()
        DriverManager.getConnection(url, "sa", "").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    insert into veyq.veyq_user (id, password_hash, display_name, email, role, password_set_at)
                    values (1, 'hash', 'Owner', 'owner@example.test', 'ServerOwner', current_timestamp())
                    """.trimIndent()
                )
                statement.executeUpdate(
                    """
                    insert into veyq.veyq_tenant (id, name, displayName, domain)
                    values (1, 'tenant', 'Tenant', 'tenant.example.test')
                    """.trimIndent()
                )
                statement.executeUpdate(
                    """
                    insert into veyq.veyq_tenant_member (tenant_id, user_id, role, accepted_at)
                    values (1, 1, 'TenantOwner', current_timestamp())
                    """.trimIndent()
                )
                statement.executeUpdate(
                    """
                    insert into veyq.veyq_media
                        (id, public_id, tenant_id, owner_user_id, scope_type, title, mime_type, size_bytes, storage_key)
                    values
                        (10, 'mediafirst00001', 1, 1, 'Tenant', 'First', 'image/jpeg', 12, 'tenant-1/first/original'),
                        (11, 'mediasecond0002', 1, 1, 'Tenant', 'Second', 'image/jpeg', 12, 'tenant-1/second/original')
                    """.trimIndent()
                )
                statement.executeUpdate(
                    """
                    insert into veyq.veyq_tenant_album
                        (id, public_id, tenant_id, owner_user_id, scope_type, name, displayName, cover_media_id, created_at, updated_at)
                    values
                        (20, 'albumhidden0001', 1, 1, 'Tenant', 'hidden-album', 'Hidden Album', null, timestamp '2026-01-01 10:00:00', timestamp '2026-01-01 10:00:00'),
                        (21, 'albumvisible001', 1, 1, 'Tenant', 'visible-album', 'Visible Album', 11, timestamp '2026-01-01 11:00:00', timestamp '2026-01-01 11:00:00')
                    """.trimIndent()
                )
                statement.executeUpdate(
                    """
                    insert into veyq.veyq_album_media (album_id, media_id, position, hidden, created_at)
                    values
                        (20, 10, 0, 1, timestamp '2026-01-01 10:00:00'),
                        (21, 10, 0, 0, timestamp '2026-01-01 11:00:00'),
                        (21, 11, 0, 0, timestamp '2026-01-01 11:05:00')
                    """.trimIndent()
                )
            }
        }

        flyway(url).migrate()

        DriverManager.getConnection(url, "sa", "").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("select public_id, name, cover_media_id from veyq.veyq_memory order by id")
                    .use { rows ->
                        assertThat(rows.next()).isTrue()
                        assertThat(rows.getString("public_id")).isEqualTo("albumhidden0001")
                        assertThat(rows.getString("name")).isEqualTo("hidden-album")
                        assertThat(rows.getObject("cover_media_id")).isNull()
                        assertThat(rows.next()).isTrue()
                        assertThat(rows.getString("public_id")).isEqualTo("albumvisible001")
                        assertThat(rows.getString("name")).isEqualTo("visible-album")
                        assertThat(rows.getLong("cover_media_id")).isEqualTo(11)
                        assertThat(rows.next()).isFalse()
                    }
                statement.executeQuery(
                    """
                    select media.public_id as media_public_id, memory.public_id as memory_public_id
                    from veyq.veyq_media media
                        join veyq.veyq_memory memory on memory.id = media.memory_id
                    order by media.id
                    """.trimIndent()
                ).use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getString("media_public_id")).isEqualTo("mediafirst00001")
                    assertThat(rows.getString("memory_public_id")).isEqualTo("albumvisible001")
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getString("media_public_id")).isEqualTo("mediasecond0002")
                    assertThat(rows.getString("memory_public_id")).isEqualTo("albumvisible001")
                    assertThat(rows.next()).isFalse()
                }
            }
        }
    }

    private fun flyway(url: String, target: String? = null): Flyway {
        val configuration = Flyway.configure()
            .dataSource(url, "sa", "")
            .locations("classpath:db/migration")
            .schemas("veyq")
            .defaultSchema("veyq")
        if (target != null) {
            configuration.target(target)
        }
        return configuration.load()
    }
}
