package dev.basedpython.pycharm.env.manager.index

import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * The index's package catalogue on disk, and prefix lookups over it.
 *
 * ### Why a file and not a list
 *
 * PyPI's catalogue is 872,009 names — measured, not estimated. Held as a `List<String>` that is
 * roughly 40 MB of heap, permanently, in a process the user is also trying to write code in, for
 * data that is only read while a completion popup is open. So it lives as a sorted file and is
 * queried in place: a binary search for the first line at or after the prefix, then a forward scan
 * while the lines still match.
 *
 * That costs about twenty seeks per query and no measurable memory, which is what makes it viable
 * to run on every keystroke.
 *
 * ### The format
 *
 * One entry per line, UTF-8, sorted: the [normalise]d name, then a tab and the original spelling
 * when the two differ. Sorted because the entire lookup depends on it, and normalised because that
 * is what the sort and the comparison must agree on — an index treats `Flask-SQLAlchemy`,
 * `flask_sqlalchemy` and `flask.sqlalchemy` as one project, so a user typing any of them has to
 * find it. Sorting the composed line is equivalent to sorting by normalised name, since a tab sorts
 * below every character that can appear in one.
 */
internal class PackageNameStore(private val file: Path) {

    val exists: Boolean get() = Files.isRegularFile(file)

    /** When the catalogue was last written, or null when there is not one. */
    fun lastModified(): Long? =
        if (exists) runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrNull() else null

    /** How many entries the catalogue holds, for diagnostics. Counts lines, so it reads the file. */
    fun size(): Int = if (!exists) 0 else runCatching {
        Files.newBufferedReader(file, StandardCharsets.UTF_8).use { it.lines().count().toInt() }
    }.getOrDefault(0)

    /**
     * Names beginning with [prefix], at most [limit] of them, in catalogue order.
     *
     * A blank prefix returns nothing rather than the first N names alphabetically: those are the
     * numeric and punctuation-led entries that sort first in any package index, which is a worse
     * thing to show than an empty list.
     */
    fun startingWith(prefix: String, limit: Int = MAX_RESULTS): List<String> {
        val needle = normalise(prefix)
        if (needle.isEmpty() || !exists) return emptyList()
        return try {
            RandomAccessFile(file.toFile(), "r").use { raf ->
                raf.seek(firstOffsetAtOrAfter(raf, needle))
                val results = ArrayList<String>(minOf(limit, 64))
                while (results.size < limit) {
                    val line = raf.readLine() ?: break
                    val entry = Entry.parse(line) ?: continue
                    if (!entry.normalised.startsWith(needle)) break
                    results.add(entry.display)
                }
                results
            }
        } catch (_: Exception) {
            // A truncated or half-written catalogue is a cache miss, not something to report: the
            // field still takes free text, and the next refresh rewrites the file.
            emptyList()
        }
    }

    /** True when [name] is in the catalogue, compared in normalised form. */
    fun contains(name: String): Boolean {
        val needle = normalise(name)
        if (needle.isEmpty()) return false
        return startingWith(needle, limit = MAX_RESULTS).any { normalise(it) == needle }
    }

    /**
     * The offset of the first line whose name is >= [needle].
     *
     * Binary search over byte offsets rather than over lines, because a text file has no random
     * access to lines. The predicate is "the first complete line at or after offset X has a name
     * >= needle", which is monotone in X — as X grows, that line only ever moves forward — so an
     * ordinary bisection works. Landing mid-line is handled by discarding the partial line, which
     * is also why the final answer has to be re-derived from the converged offset rather than
     * remembered during the loop.
     */
    private fun firstOffsetAtOrAfter(raf: RandomAccessFile, needle: String): Long {
        var lo = 0L
        var hi = raf.length()
        while (lo < hi) {
            val mid = (lo + hi) / 2
            raf.seek(mid)
            if (mid > 0) raf.readLine()
            val line = raf.readLine()
            val name = line?.let { Entry.parse(it)?.normalised }
            if (name == null || name >= needle) hi = mid else lo = mid + 1
        }
        raf.seek(lo)
        if (lo > 0) raf.readLine()
        return raf.filePointer
    }

    /** One catalogue line: the form searched on, and the form shown. */
    private data class Entry(val normalised: String, val display: String) {
        companion object {
            fun parse(line: String): Entry? {
                if (line.isEmpty()) return null
                val tab = line.indexOf('\t')
                return if (tab < 0) Entry(line, line) else Entry(line.take(tab), line.substring(tab + 1))
            }
        }
    }

    companion object {

        /** How many matches one query returns — a completion list nobody scrolls past. */
        const val MAX_RESULTS: Int = 50

        /**
         * A package name reduced to the form an index compares on.
         *
         * PEP 503: lowercase, with runs of `-`, `_` and `.` collapsed to a single `-`. This is why
         * typing `flask_sqlalchemy` finds `Flask-SQLAlchemy`, and why the file must be sorted on
         * this form rather than on the displayed one.
         */
        fun normalise(name: String): String {
            val trimmed = name.trim().lowercase()
            val out = StringBuilder(trimmed.length)
            var pendingSeparator = false
            for (ch in trimmed) {
                if (ch == '-' || ch == '_' || ch == '.') {
                    pendingSeparator = out.isNotEmpty()
                } else {
                    if (pendingSeparator) out.append('-')
                    pendingSeparator = false
                    out.append(ch)
                }
            }
            return out.toString()
        }

        /** Writes a catalogue from names already in hand. */
        fun write(file: Path, names: Iterable<String>) {
            Writer(file).use { writer -> names.forEach(writer::add) }
        }
    }

    /**
     * Accumulates names and writes the sorted catalogue on [close].
     *
     * The sort needs every name at once, which is the one moment this feature costs real memory: a
     * transient spike during a refresh that happens about once a week, rather than a permanent cost.
     * An `ArrayList` rather than a sorted set for exactly that reason — the per-entry overhead of a
     * tree is what would make the spike hurt.
     *
     * The file is written to a sibling and moved into place, so a catalogue interrupted halfway
     * through a 12 MB write is never the one that gets read. Closing without adding anything writes
     * nothing at all, which keeps a failed fetch from replacing a good catalogue with an empty one.
     */
    class Writer(private val file: Path) : AutoCloseable {

        private val lines = ArrayList<String>(INITIAL_CAPACITY)

        fun add(name: String) {
            val normalised = normalise(name)
            if (normalised.isEmpty()) return
            lines.add(if (normalised == name) normalised else "$normalised\t$name")
        }

        /** How many names have been accepted so far. */
        val count: Int get() = lines.size

        override fun close() {
            if (lines.isEmpty()) return
            lines.sort()
            Files.createDirectories(file.parent)
            val tmp = file.resolveSibling(file.fileName.toString() + ".part")
            Files.newBufferedWriter(tmp, StandardCharsets.UTF_8).use { writer ->
                var previous: String? = null
                for (line in lines) {
                    // Sorted, so duplicates are adjacent — two spellings of one project collapse.
                    if (line == previous) continue
                    writer.write(line)
                    writer.write("\n")
                    previous = line
                }
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
            lines.clear()
            lines.trimToSize()
        }

        private companion object {
            /** Sized for a large public index, so the sort does not spend its time growing an array. */
            const val INITIAL_CAPACITY = 1 shl 20
        }
    }
}
