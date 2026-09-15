package dev.basedpython.pycharm.run.model

import com.intellij.openapi.vfs.VirtualFile
import dev.basedpython.pycharm.lsp.ext.ByBuildOutput
import dev.basedpython.pycharm.lsp.ext.ByEntryPointResponse
import dev.basedpython.pycharm.lsp.ext.ByRunModulesProjectReply
import dev.basedpython.pycharm.lsp.ext.ByTestItemReply
import dev.basedpython.pycharm.run.main.ByMainFunction
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import java.nio.file.Paths

/**
 * How a module runs, as `by/entryPoint` said: the lines that start it, and the command line its
 * `main` makes of the program's arguments.
 */
internal data class ByEntryPoint(
    /**
     * The 0-based line of the module's `def main`, when a run starting there means something — a
     * `.by` whose top-level `main` is not `private`. Set even when that `main` is no entry point, so
     * the gutter can say why.
     */
    val mainLine: Int?,
    /** 0-based lines of the module's hand-written `if __name__ == "__main__":` guards. */
    val guardLines: List<Int>,
    /**
     * `main` as the program's command line; null when there is none to fill in — no `main`, a
     * `private` one, or a module that calls `main` itself and so gets no argument parser.
     */
    val commandLine: ByMainFunction?,
) {
    companion object {
        fun of(reply: ByEntryPointResponse): ByEntryPoint {
            val main = reply.main?.takeIf { !it.isPrivate }
            return ByEntryPoint(
                mainLine = main?.nameRange?.start?.line,
                guardLines = reply.guards.map { it.start.line },
                commandLine = main?.takeIf { !it.moduleInvokesMain }?.let(ByMainFunction::of),
            )
        }
    }
}

/** A test pytest would collect, or a class tests are collected under — from `by/testItems`. */
internal data class ByTestItem(
    val name: String,
    val isClass: Boolean,
    /** The node id's `::`-separated names after the file: `["TestA", "test_b"]`. */
    val symbols: List<String>,
    /** The whole declaration. */
    val range: Range,
    /** The name. */
    val selectionRange: Range,
    val children: List<ByTestItem>,
) {
    /** How many tests running this item runs, before parametrization multiplies them. */
    val testCount: Int get() = if (isClass) children.sumOf { it.testCount } else 1

    companion object {
        fun of(reply: ByTestItemReply): ByTestItem = ByTestItem(
            name = reply.name.orEmpty(),
            isClass = reply.kind == CLASS,
            symbols = reply.id.orEmpty().split("::").filter { it.isNotEmpty() },
            range = reply.range ?: EMPTY,
            selectionRange = reply.selectionRange ?: EMPTY,
            children = reply.children.map(::of),
        )

        private const val CLASS = "class"
        private val EMPTY = Range(Position(0, 0), Position(0, 0))
    }
}

/** Every item in [items] and below, depth first in source order. */
internal fun Iterable<ByTestItem>.walk(): Sequence<ByTestItem> =
    asSequence().flatMap { sequenceOf(it) + it.children.walk() }

/**
 * The innermost item whose declaration contains ([line], [column]), 0-based; null outside all of
 * them.
 */
internal fun List<ByTestItem>.innermostAt(line: Int, column: Int): ByTestItem? =
    walk().filter { it.range.contains(line, column) }.lastOrNull()

private fun Range.contains(line: Int, column: Int): Boolean {
    val afterStart = line > start.line || (line == start.line && column >= start.character)
    val beforeEnd = line < end.line || (line == end.line && column <= end.character)
    return afterStart && beforeEnd
}

/**
 * The path, `/`-separated, a source is staged at inside the tree `by run` builds — which is the path
 * pytest, collecting that tree, names the file by in a node id; null when the answer places no source.
 *
 * `by/buildOutput` says where `by build` writes the file, and a `by run` tree is laid out the same
 * way: both place a source with `by_stage::transpiled_destination`, relative to the module root that
 * holds it. So a src-layout project's `src/tests/test_x.by` is `tests/test_x.py` — the source's own
 * path with its extension swapped is right only for a project whose module root is its root.
 */
internal val ByBuildOutput.stagedPath: String?
    get() {
        val directory = buildDirectory ?: return null
        val file = generated ?: return null
        val relative = runCatching { Paths.get(directory).relativize(Paths.get(file)) }.getOrNull() ?: return null
        if (relative.startsWith("..")) return null
        return relative.joinToString("/")
    }

/** The tests of every project file that holds some. */
internal data class ByProjectTests(val byFile: Map<VirtualFile, List<ByTestItem>>)

/** What `by/runModules` said about the project. */
internal data class ByRunModules(
    /** Module name by VFS path, for every file `by run` runs under a name of its own. */
    val byPath: Map<String, String>,
    /** The configured `run.main`, if any. */
    val main: String?,
) {
    /** The configured `run.main`, or the absence of an answer about it. */
    data class Main(val known: Boolean, val module: String?)

    companion object {
        fun of(reply: ByRunModulesProjectReply, pathOf: (String) -> String?): ByRunModules = ByRunModules(
            byPath = reply.modules.mapNotNull { module ->
                val name = module.module ?: return@mapNotNull null
                val path = module.uri?.let(pathOf) ?: return@mapNotNull null
                path to name
            }.toMap(),
            main = reply.main?.module,
        )
    }
}
