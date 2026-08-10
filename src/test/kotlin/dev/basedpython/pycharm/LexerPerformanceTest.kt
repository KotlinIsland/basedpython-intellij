package dev.basedpython.pycharm

import dev.basedpython.pycharm.lang.BasedPythonLexer
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Performance benchmark for [BasedPythonLexer].
 *
 * Synthesizes a large (~10k line) .by source and asserts a full tokenization pass
 * completes within a generous time bound. The bound is intentionally loose: the goal
 * is to catch pathological (e.g. accidental O(n^2)) regressions, not microbenchmark.
 * Observed throughput is printed for visibility in test output.
 */
class LexerPerformanceTest {

    /** A realistic basedpython snippet exercising every scanner branch. */
    private val snippet = """
        # module-level comment describing the widget factory
        final class Widget(Base):
            let id: int = 1_000_000
            let ratio: float = 1_0.5_0e-10
            let mask: int = 0xDEAD_BEEF
            let bits: int = 0b1010_1010
            let perms: int = 0o777
            let phase: complex = 3j

            def __init__(self, name: str = "world", *args, **kwargs) -> None:
                self.name = name
                msg = f"hello {name}, id={self.id}"
                raw = r"raw\path\n"
                doc = ${"\"\"\""}triple
                quoted ${"\"\"\""}
                val = self?.inner ?? "default"
                total = id + ratio * 2 // 3 % 4 ** 2
                ok = id >= 0 and ratio <= 1.0 or name != ""
                ptr := id  # walrus

            @staticmethod
            def combine(a: int, b: int) -> int:
                return a | b & a ^ b << 1 >> 2

            override def render(self) -> str:
                items = [1, 2, 3, 4, 5]
                mapping = {"a": 1, "b": 2}
                return f"{self.name}: {len(items)}"
    """.trimIndent() + "\n"

    private fun buildLargeSource(repetitions: Int): String {
        val sb = StringBuilder(snippet.length * repetitions)
        for (i in 0 until repetitions) {
            sb.append(snippet)
            sb.append('\n')
        }
        return sb.toString()
    }

    private fun tokenize(src: String): Long {
        val lexer = BasedPythonLexer()
        lexer.start(src, 0, src.length, 0)
        var count = 0L
        while (lexer.tokenType != null) {
            count++
            lexer.advance()
        }
        return count
    }

    @Test
    fun `lexer tokenizes a large source within a generous time bound`() {
        // snippet is ~35 lines; 1500 repetitions => well over 10k lines.
        val repetitions = 1500
        val src = buildLargeSource(repetitions)
        val lines = src.count { it == '\n' }
        assertTrue(lines >= 10_000, "generated source should exceed 10k lines (was $lines)")

        // Warm up (JIT) on a single pass; result intentionally ignored.
        tokenize(src)

        val startNs = System.nanoTime()
        val tokens = tokenize(src)
        val elapsedNs = System.nanoTime() - startNs
        val elapsedMs = elapsedNs / 1_000_000.0

        val charsPerSec = src.length / (elapsedNs / 1_000_000_000.0)
        val tokensPerSec = tokens / (elapsedNs / 1_000_000_000.0)

        println(
            "LexerPerformanceTest: %,d chars / %,d lines -> %,d tokens in %.2f ms"
                .format(src.length, lines, tokens, elapsedMs)
        )
        println(
            "LexerPerformanceTest: throughput = %,.0f tokens/sec, %,.0f chars/sec"
                .format(tokensPerSec, charsPerSec)
        )

        assertTrue(tokens > 0, "lexer produced no tokens")
        // Generous bound: a healthy linear lexer finishes this in tens of ms.
        // 5 seconds catches only catastrophic (quadratic) regressions.
        assertTrue(
            elapsedMs < 5_000.0,
            "tokenization took %.2f ms, exceeding the 5000 ms bound".format(elapsedMs)
        )
    }
}
