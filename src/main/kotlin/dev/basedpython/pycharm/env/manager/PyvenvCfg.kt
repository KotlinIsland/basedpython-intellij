package dev.basedpython.pycharm.env.manager

/**
 * The `pyvenv.cfg` a PEP 405 virtual environment carries at its root.
 *
 * Read rather than asked, deliberately. The alternative to parsing this file is running the
 * environment's interpreter to print its version — a process spawn, on the EDT-adjacent path that
 * refreshes the tool window, for a fact that is already written down in a six-line text file next to
 * it. The file is also the only source that survives an environment whose interpreter is broken,
 * which is exactly when the user most needs the view to say what the environment *was* built on.
 *
 * The format is `key = value` lines, no sections, no quoting, no comments — a `.ini` without any of
 * the parts that make `.ini` hard.
 */
object PyvenvCfg {

    /**
     * What a venv's config says about itself. Every field is optional: this is another program's
     * file and the useful ones are absent in environments built by older tools.
     */
    data class Info(
        /** `version_info` (or `version`) — `3.12.8`, or `3.12` in the abbreviated form uv writes. */
        val version: String?,
        /** `home` — the bin directory of the interpreter this environment was built from. */
        val home: String?,
        /** `prompt` — the name the environment displays when activated. */
        val prompt: String?,
        /** Which tool created it, when it says: uv writes `uv = <version>`, virtualenv writes its own. */
        val createdBy: String?,
    ) {
        /** `3.12` — the feature version, which is how a Python version is normally spoken about. */
        val featureVersion: String?
            get() = version?.split('.')?.take(2)?.takeIf { it.size == 2 }?.joinToString(".")
    }

    /** Parses the contents of a `pyvenv.cfg`. Never throws — a file it cannot read yields all-nulls. */
    fun parse(text: String): Info {
        val values = LinkedHashMap<String, String>()
        for (line in text.lineSequence()) {
            val separator = line.indexOf('=')
            if (separator <= 0) continue
            val key = line.substring(0, separator).trim().lowercase()
            val value = line.substring(separator + 1).trim()
            if (key.isNotEmpty() && value.isNotEmpty()) values[key] = value
        }
        return Info(
            // `version_info` is CPython's own key and carries the full `3.12.8`; `version` is what
            // uv and virtualenv write, sometimes abbreviated. Prefer the more precise one.
            version = values["version_info"] ?: values["version"],
            home = values["home"],
            prompt = values["prompt"],
            createdBy = CREATOR_KEYS.firstNotNullOfOrNull { key ->
                values[key]?.let { "$key $it" }
            },
        )
    }

    /** Keys whose presence identifies the tool that built the environment, most specific first. */
    private val CREATOR_KEYS = listOf("uv", "virtualenv")
}
