package dev.basedpython.pycharm.navigation.django

import dev.basedpython.pycharm.lsp.ext.ByDjangoDatabase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** `DATABASES` as the Database tool window's data sources: a driver and a JDBC URL per django backend. */
class ByDjangoDataSourcesTest {

    @Test
    fun `each backend django ships is its driver and url, and a backend nobody has a driver for is left out`() {
        val specs = ByDjangoDataSources.specs(
            listOf(
                ByDjangoDatabase("default", "django.db.backends.sqlite3", "/p/db.sqlite3"),
                ByDjangoDatabase("reports", "django.db.backends.postgresql", "reports", "db.local", "", "ro"),
                ByDjangoDatabase("legacy", "django.db.backends.mysql", "legacy", null, "3307", "app"),
                ByDjangoDatabase("x", "django_cockroachdb", "x"),
                ByDjangoDatabase("unread", "django.db.backends.sqlite3", null),
            ),
            "mysite",
        )
        assertEquals(
            listOf(
                ByDataSourceSpec("mysite: default", "sqlite.xerial", "jdbc:sqlite:/p/db.sqlite3", null),
                ByDataSourceSpec("mysite: reports", "postgresql", "jdbc:postgresql://db.local:5432/reports", "ro"),
                ByDataSourceSpec("mysite: legacy", "mysql.8", "jdbc:mysql://localhost:3307/legacy", "app"),
            ),
            specs,
        )
    }
}
