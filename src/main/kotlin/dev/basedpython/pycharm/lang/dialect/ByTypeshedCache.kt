package dev.basedpython.pycharm.lang.dialect

import com.intellij.openapi.util.SystemInfo
import java.nio.file.Path

/**
 * Where `by` puts the stubs it carries inside its own binary.
 *
 * Goto-definition on anything from the standard library lands in typeshed, which `by` extracts file
 * by file into its cache (`ty_ide`'s `cached_vendored_path`, under `etcetera`'s XDG base strategy):
 * `~/.cache/ty/vendored/typeshed/<commit>` on macOS and Linux, `%LOCALAPPDATA%\ty\cache\...` on
 * Windows.
 *
 * The stubs are `.byi`, which is basedpython's own file type already, so nothing has to be said
 * about who owns them — only where they are, so they can be registered as the library files they
 * are. The path is a function of the environment, so it is worked out once.
 */
internal object ByTypeshedCache {

    /** The `vendored/typeshed` directory, whether or not it exists yet. */
    val root: Path? by lazy { cacheDir()?.resolve("vendored")?.resolve("typeshed") }

    /** Where `by` keeps its cache, by the same rule the binary uses. */
    private fun cacheDir(): Path? = when {
        SystemInfo.isWindows ->
            System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }?.let { Path.of(it, "ty", "cache") }

        else -> {
            val xdg = System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }
            val base = xdg?.let { Path.of(it) } ?: Path.of(System.getProperty("user.home"), ".cache")
            base.resolve("ty")
        }
    }
}
