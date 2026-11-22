package dev.basedpython.pycharm.run.django

import dev.basedpython.pycharm.run.ByRunOptions
import com.intellij.util.execution.ParametersListUtil

/**
 * What a `manage.py` command runs against: two commands are of the same project and database only when
 * all of it is the same, since any of it can choose another settings module or another database.
 */
internal data class ByManagePlace(
    val directory: String,
    val module: String,
    /** `--settings` and `--pythonpath`, as written. */
    val settings: List<String> = emptyList(),
    val database: String? = null,
    val environment: Map<String, String> = emptyMap(),
    val environmentFiles: List<String> = emptyList(),
    /** `by` runs it in a copy of the project, so what it writes there is not the project's. */
    val staged: Boolean = false,
) {
    companion object {
        fun of(options: ByRunOptions, directory: String, words: List<String>, staged: Boolean) = ByManagePlace(
            directory = options.workingDir.ifBlank { directory },
            module = options.module.trim(),
            settings = listOf("--settings", "--pythonpath").mapNotNull { flag -> ByManageWords.value(words, flag)?.let { "$flag=$it" } },
            database = ByManageWords.value(words, "--database"),
            environment = options.envVars.toMap(),
            environmentFiles = options.envFilePaths.toList(),
            staged = staged,
        )
    }
}

/** The words of a `manage.py` command line, as far as this asks: an option's value, and what is no option. */
internal object ByManageWords {

    /** Those of every command, `migrate` and `makemigrations` that take a value. */
    private val VALUED = setOf("-v", "--verbosity", "--settings", "--pythonpath", "--database", "-n", "--name")

    fun parse(line: String): List<String> = ParametersListUtil.parse(line)

    fun value(words: List<String>, flag: String): String? {
        val at = words.indexOfLast { it == flag || it.startsWith("$flag=") }
        if (at < 0) return null
        return if (words[at] == flag) words.getOrNull(at + 1) else words[at].substringAfter('=')
    }

    /** Whether nothing but options follows the command: it is of every app. */
    fun ofEverything(words: List<String>): Boolean {
        var at = 1
        while (at < words.size) {
            if (!words[at].startsWith("-")) return false
            at += if (words[at] in VALUED) 2 else 1
        }
        return true
    }
}

/** What django last said is to be done somewhere, until something has done it. */
internal data class ByManageWarned(
    val warning: ByManageWarning,
    val place: ByManagePlace,
    /** What it was about, as django said it. */
    val text: String,
) {
    /** Unapplied migrations are a database's; the rest are the project's whichever database was asked of. */
    val key: Any get() = warning to if (warning == ByManageWarning.UNAPPLIED) place else place.copy(database = null)
}

/**
 * Follows warnings through a project's runs. Kept apart from the runs themselves, which are forgotten:
 * what a run did stays done.
 */
internal object ByManageWarnings {

    /** [warned] after a run has become [now] from [before], null as it starts. */
    fun after(warned: List<ByManageWarned>, before: ByManageRun?, now: ByManageRun): List<ByManageWarned> {
        var all = warned
        for ((warning, detail) in now.facts.warnings) {
            if (before?.facts?.warnings?.get(warning) == detail) continue
            val one = ByManageWarned(warning, now.place, detail.text)
            all = all.filterNot { it.key == one.key } + one
        }
        // the server looks each time it starts, and says when there is something to apply
        if (now.facts.starts > (before?.facts?.starts ?: 0) && ByManageWarning.UNAPPLIED !in now.facts.warnings) {
            all = without(all, ByManageWarning.UNAPPLIED, now.place)
        }
        if (before?.running == true && !now.running && now.exitCode == 0) {
            for (warning in ByManageWarning.entries) if (done(now, warning)) all = without(all, warning, now.place)
        }
        return all
    }

    private fun without(warned: List<ByManageWarned>, warning: ByManageWarning, place: ByManagePlace): List<ByManageWarned> {
        val key = ByManageWarned(warning, place, "").key
        return warned.filterNot { it.key == key }
    }

    /** Whether [run], which ended well, did all [warning] asks for; one that only may have has not. */
    private fun done(run: ByManageRun, warning: ByManageWarning): Boolean {
        // in a copy of the project what it wrote is gone, a database of it included
        if (run.place.staged || warning in run.facts.warnings) return false
        val words = run.words
        fun has(vararg flags: String) = words.any { it in flags }
        val everything = ByManageWords.ofEverything(words) && !has("--plan", "--check", "--dry-run")
        return when (warning) {
            ByManageWarning.UNAPPLIED -> run.command == "migrate" && everything
            ByManageWarning.UNMIGRATED -> run.command == "makemigrations" && everything && !has("--merge")
            // a merge that was declined exits well too
            ByManageWarning.CONFLICT -> run.command == "makemigrations" && everything && has("--merge") && run.facts.written.isNotEmpty() ||
                run.command == "migrate" && everything
        }
    }
}
