package dev.basedpython.pycharm.run.django

/** What a `manage.py` command's output has said so far. */
internal data class ByManageFacts(
    /** Where `runserver` serves. */
    val server: String? = null,
    /** How many times `runserver` has started, its first included. */
    val starts: Int = 0,
    /** `runserver` watches the project's files: it outlives a failure and starts again when one changes. */
    val watching: Boolean = false,
    /** A file changed and the server has not said it started since. */
    val reloading: Boolean = false,
    val requests: Int = 0,
    /** Those of [requests] answered 500 and above. */
    val failedRequests: Int = 0,
    /** How many migrations `migrate` has run, forwards or back. */
    val migrated: Int = 0,
    /** The migration being run: `polls.0002_choice`. */
    val migrating: String? = null,
    /** The files the command said it wrote, as it spelled them. */
    val written: List<String> = emptyList(),
    /** The question the command waits for an answer to. */
    val prompt: String? = null,
    /** Why it failed, in one line. */
    val error: String? = null,
    /** What it came to, in one line. */
    val summary: String? = null,
    /** What the command last said is to be done; a server that starts without saying it again takes it back. */
    val warnings: Map<ByManageWarning, Detail> = emptyMap(),
) {
    val reloads: Int get() = (starts - 1).coerceAtLeast(0)

    /** What a warning was about, as django said it, and when. */
    data class Detail(val text: String, val at: Long)
}

/** What django says is to be done about the project, in passing, while a command runs. */
internal enum class ByManageWarning(val fix: List<String>) {
    UNAPPLIED(listOf("migrate")),
    UNMIGRATED(listOf("makemigrations")),
    CONFLICT(listOf("makemigrations", "--merge")),
}

/**
 * Reads a `manage.py` command's output as it comes, for what the *manage.py* window says of the run.
 *
 * The lines are django's own, which it writes in English whatever the project's language
 * (`BaseCommand` and `runserver`'s are not translated). A line it does not know says nothing: the
 * output itself is in the Run window, and this is only its headline. Not thread-safe.
 *
 * @param echoes whether what is typed comes back as output, as a terminal sends it: without one, what
 *   follows a question is what the command wrote once it had been answered
 */
internal class ByManageOutput(private val echoes: Boolean = true, private val now: () -> Long = System::currentTimeMillis) {

    var facts = ByManageFacts()
        private set

    /** What has been written since the last line ended. */
    private val line = StringBuilder()

    /** An escape sequence the last text ended in the middle of. */
    private var escape = ""
    private var inTraceback = false
    private var returned = false
    private var unappliedSaid = false

    fun text(text: String) {
        if (!echoes && facts.prompt != null && text.isNotEmpty()) {
            facts = facts.copy(prompt = null)
            line.setLength(0)
        }
        val whole = escape + text
        val cut = UNFINISHED_ESCAPE.find(whole)
        escape = cut?.value.orEmpty()
        val plain = (if (cut == null) whole else whole.substring(0, cut.range.first)).replace(ESCAPES, "")
        for (char in plain) {
            // a progress line goes back to its start to be written over
            if (returned && char != '\n' && char != '\r') line.setLength(0)
            returned = char == '\r'
            when (char) {
                '\n' -> endLine()
                '\r' -> Unit
                '\b' -> if (line.isNotEmpty()) line.setLength(line.length - 1)
                else -> if (line.length < MAX_LINE) line.append(char)
            }
        }
        val applying = APPLYING.find(line)
        if (applying != null) facts = facts.copy(migrating = applying.groupValues[2])
    }

    /** Whether the line as it stands reads as a question; asked once the output has been still a while. */
    fun still(): Boolean {
        if (facts.prompt != null || !PROMPT.containsMatchIn(line)) return false
        // what asks a question goes on: an error before it, as a shell's, is not why the command failed
        facts = facts.copy(prompt = line.trim().toString(), error = null)
        return true
    }

    /** Whether [still] could find a question, without asking it. */
    fun mayBePrompt(): Boolean = facts.prompt == null && PROMPT.containsMatchIn(line)

    fun finished() {
        if (line.isNotEmpty()) endLine()
        facts = facts.copy(prompt = null, reloading = false, migrating = null)
    }

    private fun endLine() {
        val text = line.toString()
        line.setLength(0)
        if (facts.prompt != null) facts = facts.copy(prompt = null)
        read(text)
    }

    private fun read(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        if (inTraceback) {
            // the frames are indented, and the exception is the first line that is not
            if (text[0].isWhitespace()) return
            inTraceback = false
            // a server that is up logs a request's exception and goes on serving
            if (facts.server == null || facts.reloading) facts = facts.copy(error = trimmed)
            return
        }
        // a migration that fails leaves its own line open, which the traceback then begins on
        if (trimmed.endsWith(TRACEBACK)) {
            inTraceback = true
            facts = facts.copy(migrating = null)
            return
        }
        if (trimmed.startsWith(WATCHING)) {
            facts = facts.copy(watching = true)
            return
        }
        STARTING.find(trimmed)?.let {
            facts = facts.copy(
                server = it.groupValues[1],
                starts = facts.starts + 1,
                reloading = false,
                error = null,
                warnings = if (unappliedSaid) facts.warnings else facts.warnings - ByManageWarning.UNAPPLIED,
            )
            unappliedSaid = false
            return
        }
        if (trimmed.endsWith(RELOADING)) {
            facts = facts.copy(reloading = true)
            return
        }
        REQUEST.find(trimmed)?.let {
            val failed = it.groupValues[1].toInt() >= 500
            facts = facts.copy(requests = facts.requests + 1, failedRequests = facts.failedRequests + if (failed) 1 else 0)
            return
        }
        APPLYING.find(text)?.let { applied ->
            facts = facts.copy(migrating = applied.groupValues[2])
            // "OK" ends the line django wrote the migration on, as "FAKED" does
            if (DONE.containsMatchIn(text)) facts = facts.copy(migrated = facts.migrated + 1, migrating = null)
            return
        }
        WRITTEN.find(text)?.let {
            facts = facts.copy(written = facts.written + it.groupValues[1])
            return
        }
        UNAPPLIED.find(trimmed)?.let {
            unappliedSaid = true
            warn(ByManageWarning.UNAPPLIED, it.groupValues[2])
        }
        UNMIGRATED.find(trimmed)?.let { warn(ByManageWarning.UNMIGRATED, it.groupValues[1].replace("'", "")) }
        CONFLICT.find(trimmed)?.let { warn(ByManageWarning.CONFLICT, it.groupValues[1]) }
        ERROR.find(trimmed)?.let { facts = facts.copy(error = it.value.removePrefix("CommandError: ")) }
        if (SUMMARIES.any { it.containsMatchIn(trimmed) }) facts = facts.copy(summary = trimmed)
    }

    private fun warn(warning: ByManageWarning, text: String) {
        facts = facts.copy(warnings = facts.warnings + (warning to ByManageFacts.Detail(text, now())))
    }

    private companion object {
        const val MAX_LINE = 8_192
        const val TRACEBACK = "Traceback (most recent call last):"
        const val WATCHING = "Watching for file changes with "
        const val RELOADING = " changed, reloading."

        /** CSI, OSC and two-character escapes: what a command writes to a terminal besides its text. */
        val ESCAPES = Regex("\u001b(?:\\[[0-?]*[ -/]*[@-~]|][^\u0007\u001b]*(?:\u0007|\u001b\\\\)|[()*+][0-9A-Za-z]|[@-Z\\\\^_=>])")
        val UNFINISHED_ESCAPE = Regex("\u001b(?:\\[[0-?]*[ -/]*|][^\u0007\u001b]{0,512}|[()*+])?$")

        /** `Starting development server at`, `Starting WSGI …` (django 6) and daphne's `Starting ASGI/Daphne …`. */
        val STARTING = Regex("^Starting .*development server at (https?://\\S+)")
/** `"GET / HTTP/1.1" 200 17` of `runserver`, and daphne's `HTTP GET / 200 [0.01, 127.0.0.1:1]`. */
        val REQUEST = Regex("(?:\"[A-Z]+ \\S+ HTTP/[\\d.]+\"|^HTTP [A-Z]+ \\S+) (\\d{3}) ")
        val APPLYING = Regex("^ {2}(Applying|Unapplying) ([\\w.]+?)\\.\\.\\.")
        val DONE = Regex("\\.\\.\\.(?: \\([\\d.]+s\\))? *(?:OK|FAKED)")

        /** `makemigrations` names each file it writes, indented under its app, and `--merge` the one it makes. */
        val WRITTEN = Regex("^(?: {2}|Created new merge migration )(\\S.*[/\\\\]migrations[/\\\\]\\w+\\.py)$")
        val UNAPPLIED = Regex("^You have (\\d+) unapplied migration\\(s\\)\\..* for app\\(s\\): (.+?)\\.?$")
        val UNMIGRATED = Regex("^Your models in app\\(s\\): (.+?) have changes that are not yet reflected in a migration")
        val CONFLICT = Regex("Conflicting migrations detected; multiple leaf nodes in the migration graph: (.+?)\\.?$")
        val ERROR = Regex("^(?:CommandError: .*|Unknown command: .*|.*manage\\.py \\S+: error: .*|Error: .*)$")
        val SUMMARIES = listOf(
            Regex("^System check identified "),
            Regex("^No (?:changes|migrations) "),
            Regex("^Installed \\d+ object"),
            Regex("^\\d+ (?:static file|unmodified)"),
            Regex("^Superuser created"),
        )

        /** A line left open on what a question ends with, and a space to answer after. */
        val PROMPT = Regex("[:?>\\]#] $")
    }
}
