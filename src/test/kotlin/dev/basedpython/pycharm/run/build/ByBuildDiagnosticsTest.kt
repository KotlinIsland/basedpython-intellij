package dev.basedpython.pycharm.run.build

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Reading diagnostics out of a `by build`'s output, from lines `by build --output-format github` prints. */
class ByBuildDiagnosticsTest {

    @Test
    fun `a diagnostic on one line has its file, place, rule and message`() {
        // Printed by `by build` for `x: int = "nope"`, with the secondary annotation it carries.
        val line = "::error title=ty (invalid-assignment),file=/p/src/pkg/main.by,line=8,col=10,endLine=8," +
            "endColumn=16::src/pkg/main.by:8:10: invalid-assignment: Object of type `\"nope\"` is not " +
            "assignable to `int`%0A  src/pkg/main.by:8:4: Declared type"

        assertEquals(
            ByBuildDiagnostic(
                severity = ByBuildDiagnostic.Severity.ERROR,
                rule = "invalid-assignment",
                file = "/p/src/pkg/main.by",
                line = 8,
                column = 10,
                title = "Object of type `\"nope\"` is not assignable to `int`",
                message = "invalid-assignment: Object of type `\"nope\"` is not assignable to `int`\n" +
                    "  src/pkg/main.by:8:4: Declared type",
            ),
            ByBuildDiagnostics.parse(line),
        )
    }

    @Test
    fun `a diagnostic spanning lines has no column`() {
        val line = "::warning title=ty (unused-ignore-comment),file=/p/a.by,line=3,endLine=5::a.by:3:1: " +
            "unused-ignore-comment: Unused blanket `type: ignore` directive"

        val diagnostic = ByBuildDiagnostics.parse(line)!!

        assertEquals(ByBuildDiagnostic.Severity.WARNING, diagnostic.severity)
        assertEquals(3, diagnostic.line)
        assertNull(diagnostic.column)
        assertEquals("Unused blanket `type: ignore` directive", diagnostic.title)
    }

    @Test
    fun `a diagnostic about no file has no place`() {
        val diagnostic = ByBuildDiagnostics.parse("::notice title=ty (some-rule)::some-rule: about the project")!!

        assertEquals(ByBuildDiagnostic.Severity.INFO, diagnostic.severity)
        assertNull(diagnostic.file)
        assertNull(diagnostic.line)
        assertEquals("about the project", diagnostic.title)
    }

    @Test
    fun `a path with a comma in it is still one path`() {
        val line = "::error title=ty (invalid-syntax),file=/p/a,b/main.by,line=1,col=3,endLine=1,endColumn=4::" +
            "a,b/main.by:1:3: invalid-syntax: Expected an expression"

        val diagnostic = ByBuildDiagnostics.parse(line)!!

        assertEquals("/p/a,b/main.by", diagnostic.file)
        assertEquals("Expected an expression", diagnostic.title)
    }

    @Test
    fun `everything else the build prints is not a diagnostic`() {
        // What a build says it wrote, its summary, and a `by` from before `--output-format`, which
        // prints its diagnostics for a person whatever the environment asks.
        for (line in listOf(
            "/p/src/pkg/main.by -> /p/build/pkg/main.py",
            "build complete (2 files)",
            "error[invalid-assignment]: Object of type `\"nope\"` is not assignable to `int`",
            " --> src/pkg/main.by:8:10",
            "Found 1 diagnostic",
        )) {
            assertNull(ByBuildDiagnostics.parse(line), line)
        }
    }
}
