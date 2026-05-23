package com.veyq.api.memory

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.util.UUID

class MemoryMigrationTest {
    @Test
    fun `v2 creates memory model without legacy album tables`() {
        val database = "veyq_migration_${UUID.randomUUID().toString().replace("-", "")}"
        val url = "jdbc:h2:mem:$database;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS veyq\\;SET SCHEMA veyq"

        flyway(url).migrate()

        DriverManager.getConnection(url, "sa", "").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    """
                    select table_name
                    from information_schema.tables
                    where table_schema = 'veyq'
                    order by table_name
                    """.trimIndent()
                ).use { rows ->
                    val tables = mutableListOf<String>()
                    while (rows.next()) tables += rows.getString("table_name")
                    assertThat(tables).contains("veyq_memory", "veyq_media", "veyq_tenant_folder")
                    assertThat(tables).doesNotContain("veyq_tenant_album", "veyq_album_media")
                }

                statement.executeQuery(
                    """
                    select column_name
                    from information_schema.columns
                    where table_schema = 'veyq'
                        and table_name = 'veyq_memory'
                    """.trimIndent()
                ).use { rows ->
                    val columns = mutableListOf<String>()
                    while (rows.next()) columns += rows.getString("column_name")
                    assertThat(columns).contains(
                        "start_date",
                        "end_date",
                        "cover_crop_x",
                        "cover_crop_y",
                        "cover_crop_width",
                        "cover_crop_height",
                    )
                }

                statement.executeQuery(
                    """
                    select column_name
                    from information_schema.columns
                    where table_schema = 'veyq'
                        and table_name = 'veyq_media'
                    """.trimIndent()
                ).use { rows ->
                    val columns = mutableListOf<String>()
                    while (rows.next()) columns += rows.getString("column_name")
                    assertThat(columns).contains("memory_id")
                }
            }
        }
    }

    private fun flyway(url: String): Flyway =
        Flyway.configure()
            .dataSource(url, "sa", "")
            .locations("classpath:db/migration")
            .schemas("veyq")
            .defaultSchema("veyq")
            .load()
}
