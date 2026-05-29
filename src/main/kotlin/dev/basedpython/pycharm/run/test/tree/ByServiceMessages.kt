package dev.basedpython.pycharm.run.test.tree

/**
 * Pure translation of [ByTestEvent]s into TeamCity service-message strings
 * (the `##teamcity[...]` protocol that SMTRunner consumes).
 *
 * Kept free of any IntelliJ types so it can be unit-tested directly. The
 * production converter ([ByTestEventsConverter]) uses the platform's
 * `ServiceMessageBuilder`, which produces byte-identical output; this helper
 * mirrors that format so the mapping itself is verifiable in isolation.
 */
object ByServiceMessages {

    /**
     * Escape a value per the TeamCity service-message spec:
     * `|` -> `||`, `'` -> `|'`, newline -> `|n`, carriage return -> `|r`,
     * `[` -> `|[`, `]` -> `|]`.
     */
    fun escape(value: String): String {
        val sb = StringBuilder(value.length + 8)
        for (c in value) {
            when (c) {
                '|' -> sb.append("||")
                '\'' -> sb.append("|'")
                '\n' -> sb.append("|n")
                '\r' -> sb.append("|r")
                '[' -> sb.append("|[")
                ']' -> sb.append("|]")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun message(name: String, attrs: List<Pair<String, String>>): String {
        val body = attrs.joinToString(" ") { (k, v) -> "$k='${escape(v)}'" }
        return "##teamcity[$name $body]"
    }

    /** Translate one [ByTestEvent] into its service-message string. */
    fun toServiceMessage(event: ByTestEvent): String = when (event) {
        is ByTestEvent.TestSuiteStarted ->
            message("testSuiteStarted", listOf("name" to event.name))

        is ByTestEvent.SuiteFinished ->
            message("testSuiteFinished", listOf("name" to event.name))

        is ByTestEvent.TestStarted ->
            message("testStarted", listOf("name" to event.name, "captureStandardOutput" to "true"))

        is ByTestEvent.TestPassed ->
            message("testFinished", listOf("name" to event.name))

        is ByTestEvent.TestFailed ->
            message(
                "testFailed",
                buildList {
                    add("name" to event.name)
                    add("message" to event.message)
                    if (event.details.isNotEmpty()) add("details" to event.details)
                },
            )

        is ByTestEvent.TestIgnored ->
            message(
                "testIgnored",
                buildList {
                    add("name" to event.name)
                    if (event.message.isNotEmpty()) add("message" to event.message)
                },
            )

        is ByTestEvent.TestFinished ->
            message("testFinished", listOf("name" to event.name))
    }
}
