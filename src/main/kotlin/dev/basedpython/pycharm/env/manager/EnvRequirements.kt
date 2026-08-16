package dev.basedpython.pycharm.env.manager

/**
 * Turning what the user typed into arguments, and back.
 *
 * A requirement is not just a package name — `httpx[http2]>=0.27,<1.0` is one string carrying three
 * separate things — so the dialog needs to pull the name out of it (to look the package up) and put
 * extras back into it (when the user ticks one) without disturbing the rest of what was typed.
 */
internal object EnvRequirements {

    /**
     * Splits a typed line into requirements.
     *
     * Whitespace-separated, because `uv add` itself takes several and pasting a line out of a README
     * is how this will be used. Commas are deliberately **not** separators: they are meaningful
     * inside a version specifier (`httpx>=0.27,<1.0`), and splitting on them would quietly turn one
     * correct requirement into two broken ones.
     */
    fun split(text: String): List<String> =
        text.split(' ', '\t', '\n').map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * True when [c] is part of a package name rather than something that ends one.
     *
     * Names are letters, digits and the three separators PEP 503 normalises (`-`, `_`, `.`).
     * Everything else — a space, a `>` or `=` starting a specifier, a `[` opening extras, a `;`
     * before a marker — means the name is finished and the catalogue has nothing further to offer.
     *
     * This is what decides whether typing keeps the completion popup open, so it is deliberately a
     * plain predicate with its own tests rather than a condition buried in a UI callback.
     */
    fun continuesPackageName(c: Char): Boolean =
        c.isLetterOrDigit() || c == '-' || c == '_' || c == '.'

    /**
     * The package name in [requirement], or null when there is not one to find.
     *
     * What the index gets asked about. Everything a requirement can carry after the name is cut:
     * extras, version specifiers, environment markers. A URL or a path is not a name the index can
     * answer for — `git+https://…` names a repository, `./vendor/lib` names a directory — so those
     * yield null rather than a guess, and the dialog simply shows nothing about them.
     */
    fun packageName(requirement: String): String? {
        val text = requirement.trim()
        if (text.isEmpty()) return null
        // A marker (`; python_version < "3.11"`) applies to the whole requirement; drop it first.
        val withoutMarker = text.substringBefore(';').trim()
        // Anything with a scheme or a path separator is a direct reference, not a named package.
        if (withoutMarker.contains("://") || withoutMarker.startsWith(".") || withoutMarker.startsWith("/")) {
            return null
        }
        if (withoutMarker.contains('@')) return null
        val name = withoutMarker.takeWhile { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }
        return name.takeIf { it.isNotEmpty() && it.first().isLetterOrDigit() }
    }

    /** The extras already written into [requirement], e.g. `http2` and `cli` for `httpx[http2,cli]`. */
    fun extrasOf(requirement: String): List<String> {
        val open = requirement.indexOf('[')
        val close = requirement.indexOf(']', startIndex = open + 1)
        if (open < 0 || close < 0) return emptyList()
        return requirement.substring(open + 1, close)
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    /**
     * The version pinned in [requirement], when it is pinned with a plain `==`.
     *
     * Only `==`, because that is the only form the version picker produces and the only one it can
     * faithfully show back. A requirement carrying a range was typed by hand, and the picker reports
     * no selection rather than pretending a range is a choice it made.
     */
    fun pinnedVersion(requirement: String): String? {
        val name = packageName(requirement) ?: return null
        val tail = requirement.trim().substring(name.length).let { t ->
            val open = t.indexOf('[')
            val close = t.indexOf(']', startIndex = open + 1)
            if (open == 0 && close > 0) t.substring(close + 1) else t
        }.trim()
        if (!tail.startsWith("==") || tail.startsWith("===")) return null
        val value = tail.removePrefix("==").substringBefore(';').trim()
        // A second clause means a range, not a pin.
        if (value.isEmpty() || value.contains(',')) return null
        return value
    }

    /**
     * [requirement] pinned to [version], or unpinned when it is null.
     *
     * Replaces whatever specifier was there, since the picker offering a version and the field
     * carrying a different one would be two answers to the same question. Extras are preserved:
     * `httpx[http2]` pinned to `0.28.1` is `httpx[http2]==0.28.1`.
     */
    fun withVersion(requirement: String, version: String?): String {
        val text = requirement.trim()
        val name = packageName(text) ?: return requirement
        val extras = extrasOf(text)
        val marker = text.substringAfter(';', "").trim()
        val base = if (extras.isEmpty()) name else "$name[${extras.joinToString(",")}]"
        val pinned = if (version.isNullOrBlank()) base else "$base==${version.trim()}"
        return if (marker.isEmpty()) pinned else "$pinned ; $marker"
    }

    /**
     * [requirement] with exactly [extras] written into it, replacing whatever was there.
     *
     * Inserted after the name and before any version specifier, which is where the syntax puts them:
     * `httpx>=0.27` with `http2` is `httpx[http2]>=0.27`, not `httpx>=0.27[http2]`. Ticking every
     * box off removes the brackets entirely rather than leaving `httpx[]`, which no resolver accepts.
     *
     * A requirement with no name — a URL, a path — is returned untouched: there is nowhere in it
     * that extras could go, and mangling it would be worse than ignoring the request.
     */
    fun withExtras(requirement: String, extras: Collection<String>): String {
        val text = requirement.trim()
        val name = packageName(text) ?: return requirement
        val rest = text.substring(name.length).let { tail ->
            // Drop an existing bracket group, keeping the specifier that follows it.
            val open = tail.indexOf('[')
            val close = tail.indexOf(']', startIndex = open + 1)
            if (open == 0 && close > 0) tail.substring(close + 1) else tail
        }
        val wanted = extras.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        return if (wanted.isEmpty()) name + rest else "$name[${wanted.joinToString(",")}]$rest"
    }
}
