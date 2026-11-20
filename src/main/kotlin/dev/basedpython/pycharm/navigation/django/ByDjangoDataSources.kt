package dev.basedpython.pycharm.navigation.django

import dev.basedpython.pycharm.lsp.ext.ByDjangoDatabase

/** A data source to make in the Database tool window: its name, the driver's id there, its JDBC URL and user. */
internal data class ByDataSourceSpec(val name: String, val driver: String, val url: String, val user: String?)

/**
 * A project's `DATABASES`, as `by/djangoStructure` reads them, as Database tool window data sources:
 * which of that window's drivers speaks each django backend, and the JDBC URL its settings make.
 *
 * Only the backends django ships and the window has a driver for; a third-party backend, or a
 * database whose settings `by` could not read to a name, is left out rather than guessed at. The
 * password never reaches here — `by` does not send it — so the window asks for it on connecting.
 */
internal object ByDjangoDataSources {

    fun specs(databases: List<ByDjangoDatabase>, project: String): List<ByDataSourceSpec> = databases.mapNotNull { spec(it, project) }

    private fun spec(database: ByDjangoDatabase, project: String): ByDataSourceSpec? {
        val name = database.name?.takeIf { it.isNotBlank() } ?: return null
        val title = "$project: ${database.alias ?: "default"}"
        val host = database.host?.takeIf { it.isNotBlank() } ?: "localhost"
        fun server(scheme: String, defaultPort: String) = "jdbc:$scheme://$host:${database.port?.takeIf { it.isNotBlank() } ?: defaultPort}/$name"
        return when (database.engine) {
            "django.db.backends.sqlite3" -> ByDataSourceSpec(title, "sqlite.xerial", "jdbc:sqlite:$name", null)
            "django.db.backends.postgresql", "django.db.backends.postgresql_psycopg2" ->
                ByDataSourceSpec(title, "postgresql", server("postgresql", "5432"), database.user)
            "django.db.backends.mysql" -> ByDataSourceSpec(title, "mysql.8", server("mysql", "3306"), database.user)
            "django.db.backends.oracle" -> {
                val port = database.port?.takeIf { it.isNotBlank() } ?: "1521"
                ByDataSourceSpec(title, "oracle", "jdbc:oracle:thin:@$host:$port/$name", database.user)
            }
            else -> null
        }
    }
}
