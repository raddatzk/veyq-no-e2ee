package com.veyq.api.memory

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.util.UUID

class MemoryMigrationTest {
    @Test
    fun `baseline migration creates memory model without legacy album tables`() {
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
                    assertThat(tables).contains("veyq_memory", "veyq_media", "veyq_media_blob", "veyq_tenant_folder")
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
                        and table_name = 'veyq_tenant'
                    """.trimIndent()
                ).use { rows ->
                    val columns = mutableListOf<String>()
                    while (rows.next()) columns += rows.getString("column_name")
                    assertThat(columns).contains("terms_markdown_by_locale", "terms_version")
                }

                statement.executeQuery(
                    """
                    select column_name
                    from information_schema.columns
                    where table_schema = 'veyq'
                        and table_name = 'veyq_memory_share'
                    """.trimIndent()
                ).use { rows ->
                    val columns = mutableListOf<String>()
                    while (rows.next()) columns += rows.getString("column_name")
                    assertThat(columns).contains("notice_markdown_by_locale", "notice_version")
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
                    assertThat(columns).contains("memory_id", "media_blob_id", "captured_at", "captured_at_override")
                    assertThat(columns).doesNotContain("storage_key", "mime_type", "size_bytes", "camera_make")
                }

                statement.executeQuery(
                    """
                    select column_name
                    from information_schema.columns
                    where table_schema = 'veyq'
                        and table_name = 'veyq_media_blob'
                    """.trimIndent()
                ).use { rows ->
                    val columns = mutableListOf<String>()
                    while (rows.next()) columns += rows.getString("column_name")
                    assertThat(columns).contains(
                        "tenant_id",
                        "content_sha256",
                        "storage_key",
                        "thumbnail_storage_key",
                        "preview_storage_key",
                        "mime_type",
                        "size_bytes",
                        "captured_at",
                        "camera_make",
                        "camera_model",
                        "image_width",
                        "image_height",
                        "gps_latitude",
                        "gps_longitude",
                    )
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
