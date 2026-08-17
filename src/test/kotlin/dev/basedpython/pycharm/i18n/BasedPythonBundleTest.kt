package dev.basedpython.pycharm.i18n

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.text.MessageFormat
import java.util.Properties

/**
 * Validates the message bundle [src/main/resources/messages/BasedPythonBundle.properties] directly
 * as a resource: every key referenced from production code must exist, and every value with
 * placeholders must round-trip through [MessageFormat] without throwing and produce the expected
 * literal text (this is what catches the `''{0}''` apostrophe-escaping pitfall).
 *
 * Loaded with a UTF-8 reader to match IntelliJ's bundle loader (UTF-8 since the platform's
 * `DynamicBundle`), so em-dashes / curly quotes / arrows survive.
 */
class BasedPythonBundleTest {

    private val props: Properties by lazy {
        val p = Properties()
        val stream = javaClass.classLoader.getResourceAsStream("messages/BasedPythonBundle.properties")
            ?: error("messages/BasedPythonBundle.properties not on test classpath")
        stream.reader(Charsets.UTF_8).use { p.load(it) }
        p
    }

    private fun value(key: String): String {
        val v = props.getProperty(key)
        assertNotNull("missing bundle key: $key", v)
        return v!!
    }

    /**
     * These strings land in tooltips, notification titles, banners, run-config names and Swing
     * labels — all plain text, none of which renders markdown. A backtick written out of editing
     * habit therefore reaches the user as a literal character.
     */
    @Test
    fun `no bundle value contains a markdown backtick`() {
        val offenders = props.stringPropertyNames()
            .filter { props.getProperty(it).contains('`') }
            .sorted()
        assertTrue(
            offenders.isEmpty(),
            "bundle values must not contain backticks; these surfaces are plain text:\n" +
                offenders.joinToString("\n") { "  $it = ${props.getProperty(it)}" },
        )
    }

    /** Resolve a key the same way BasedPythonBundle.message(key, *args) would. */
    private fun msg(key: String, vararg args: Any?): String =
        MessageFormat.format(value(key), *args)

    @Test
    fun `bundle loads and is non-trivial`() {
        assertTrue(props.size > 100, "bundle should define many keys")
    }

    // ----- Keys that MUST exist (sampled across every feature area touched by i18n) -----

    @Test
    fun `every required key is present`() {
        val required = listOf(
            "group.basedpython.ActionGroup.text",
            "action.basedpython.RestartLsp.text",
            "action.basedpython.RestartLsp.description",
            "settings.title",
            "notification.basedPython.title",
            "notification.exitCode",
            "notification.binaryMissing.title",
            "notification.binaryMissing.content",
            "notification.action.openSettings",
            "notification.action.documentation",
            "notification.action.dontShowAgain",
            "notification.action.restart",
            "notification.action.restartLsp",
            "notification.action.viewLog",
            "progress.cleaningCaches",
            "progress.explainingRule",
            "progress.formattingWithBuff",
            "progress.optimizeImports",
            "progress.generatingApiLock",
            "progress.transpiling",
            "progress.reverseTranspiling",
            "notification.cleanCachesFailed.title",
            "notification.cleanCachesSuccess",
            "notification.formatFailed.title",
            "notification.formatted",
            "format.serverNotRunning",
            "format.serverDidNotAnswer",
            "format.serverTooOld",
            "notification.organizeImportsFailed.title",
            "actionOnSave.fixAllName",
            "actionOnSave.cleanupName",
            "commit.fixAllName",
            "progress.cleanup.format",
            "progress.cleanup.fixAll",
            "progress.cleanupOnCommit",
            "notification.transpileFailed.title",
            "notification.reverseTranspileFailed.title",
            "notification.generateApiFailed.title",
            "notification.apiLockGenerated",
            "notification.apiLockGenerated.withRules",
            "explainRule.noExplanation",
            "explainRule.noExplanationFor.title",
            "explainRule.prompt.message",
            "explainRule.prompt.title",
            "intention.explainRule.familyName",
            "intention.explainRule.textWithCode",
            "intention.explainNamedTuple.text",
            "intention.explainNamedTuple.familyName",
            "intention.explainNamedTuple.dialogTitle",
            "intention.explainNamedTuple.message",
            "settings.export.dialog.title",
            "settings.export.dialog.description",
            "settings.export.success.title",
            "settings.export.success.content",
            "settings.export.failed.title",
            "settings.import.dialog.title",
            "settings.import.dialog.description",
            "settings.import.success.title",
            "settings.import.success.content",
            "settings.import.failed.title",
            "repl.starting",
            "repl.consoleTitle",
            "repl.binaryMissing",
            "repl.startFailed.title",
            "newFile.action.text",
            "newFile.action.description",
            "newFile.dialog.title",
            "newFile.kind.emptyFile",
            "newFile.kind.class",
            "newFile.kind.dataClass",
            "newFile.kind.protocol",
            "newFile.actionName",
            "banner.byMissing.text",
            "banner.byMissing.installWithUv",
            "banner.byMissing.environment",
            "banner.byMissing.configure",
            "banner.byMissing.dismiss",
            // The `install.basedpython.*` and `uv.sync.*` keys were the banner's and the sync
            // action's own progress reporting, back when each spawned uv itself. Both go through
            // env.manager.EnvOperations now, which reports under the `env.*` keys below.
            "env.summary.scanning",
            "env.summary.unmanaged",
            "env.summary.wouldBe",
            "env.summary.ready",
            "env.banner.toolMissing",
            "env.banner.noEnvironment",
            "env.banner.outOfSync",
            "env.banner.error",
            "env.packages.empty",
            "env.packages.noEnvironment",
            "env.package.editable",
            "env.tree.count",
            "env.tree.notInstalled",
            "env.tree.installedVersion",
            "env.tree.shownAbove",
            "env.tree.tooltip.main",
            "env.tree.tooltip.group",
            "env.tree.tooltip.extra",
            "env.tree.tooltip.declared",
            "env.tree.tooltip.transitive",
            "env.action.setUp",
            "env.action.setUp.description",
            "env.action.sync",
            "env.action.sync.description",
            "env.action.add",
            "env.action.add.description",
            "env.action.remove",
            "env.action.remove.description",
            "env.action.upgrade",
            "env.action.upgrade.description",
            "env.action.interpreter",
            "env.action.interpreter.description",
            "env.action.interpreter.current",
            "env.action.reread",
            "env.action.reread.description",
            "env.action.expandAll",
            "env.action.collapseAll",
            "env.action.installTool",
            "env.action.createEnvironment",
            "env.action.chooseInterpreter",
            "env.progress.settingUp",
            "env.progress.installing",
            "env.progress.creating",
            "env.progress.syncing",
            "env.progress.locking",
            "env.progress.upgrading",
            "env.progress.adding",
            "env.progress.removing",
            "env.progress.installingPython",
            "env.failed.title",
            "env.tool.installed.title",
            "env.tool.installed.text",
            "env.tool.failed.title",
            "env.tool.unsupported",
            "env.add.title",
            "env.add.ok",
            "env.add.label",
            "env.add.hint",
            "env.add.empty",
            "env.add.target",
            "env.add.target.hint",
            "env.add.target.empty",
            "env.add.extras",
            "env.add.latest",
            "env.add.newerPreRelease",
            "env.add.lookingUp",
            "env.add.unknownPackage",
            "env.add.version",
            "env.add.searching",
            "env.add.downloadingCatalogue",
            "env.add.startTyping",
            "env.add.noMatches",
            "env.version.any",
            "env.version.prerelease",
            "env.version.yanked",
            "env.version.yanked.reason",
            "env.version.needsPython",
            "env.version.incompatible",
            "env.remove.confirm.title",
            "env.remove.confirm.message",
            "env.remove.confirm.item",
            "env.upgrade.confirm.title",
            "env.upgrade.confirm.message",
            "env.python.title",
            "env.python.fromProject",
            "env.python.installed",
            "env.python.installed.current",
            "env.python.download",
            "env.python.recreate.title",
            "env.python.recreate.message",
            "download.title",
            "download.unsupportedPlatform",
            "download.confirm.message",
            "download.progress.title",
            "download.progress.item",
            "download.result.success",
            "download.result.partialPrefix",
            "download.result.failed",
            "notification.lspBinaryMissing.title",
            "notification.lspBinaryMissing.content",
            "notification.lspCrashed.title",
            "notification.lspCrashed.content",
            "notification.byOutdated.title",
            "notification.byOutdated.content",
            "notification.welcome.title",
            "notification.welcome.content",
            "refactoring.invalidIdentifier",
            "refactoring.extractMethod.title",
            "refactoring.extractMethod.prompt",
            "refactoring.extractVariable.title",
            "refactoring.extractVariable.prompt",
            "refactoring.introduceConstant.title",
            "refactoring.introduceConstant.prompt",
            "refactoring.inlineVariable.title",
            "refactoring.inlineVariable.placeCaret",
            "refactoring.inlineVariable.cannotInline",
            "runConfig.buildBeforeRun.name",
            "runConfig.buildBeforeRun.binaryMissing",
            "runConfig.buildBeforeRun.failed",
            "runConfig.buildBeforeRun.launchFailed",
            "runConfig.buildBeforeRun.failed.title",
        )
        val missing = required.filter { props.getProperty(it) == null }
        assertTrue(missing.isEmpty(), "missing bundle keys: $missing")
    }

    // ----- Plain (no-placeholder) values -----

    @Test
    fun `plain values match expected text`() {
        assertEquals("basedpython", value("notification.basedPython.title"))
        assertEquals("buff caches cleaned", value("notification.cleanCachesSuccess"))
        assertEquals("no explanation available", value("explainRule.noExplanation"))
        assertEquals("Restart", value("notification.action.restart"))
        assertEquals("Restart LSP", value("notification.action.restartLsp"))
        assertEquals("Empty file", value("newFile.kind.emptyFile"))
    }

    @Test
    fun `apostrophe in no-arg value is literal single quote`() {
        // No placeholders → BundleBase does NOT run MessageFormat, so a lone apostrophe stays as-is.
        assertEquals("Don't show again", value("notification.action.dontShowAgain"))
    }

    // ----- Placeholder values round-trip through MessageFormat -----

    @Test
    fun `single int placeholder formats`() {
        assertEquals("exit 7", msg("notification.exitCode", 7))
    }

    @Test
    fun `file-name placeholders format`() {
        assertEquals("Transpiling foo.by", msg("progress.transpiling", "foo.by"))
        assertEquals("Formatting foo.by with buff", msg("progress.formattingWithBuff", "foo.by"))
        assertEquals("Formatted foo.by", msg("notification.formatted", "foo.by"))
    }

    @Test
    fun `quoted placeholder uses doubled apostrophes so value is wrapped in single quotes`() {
        // notification.lspBinaryMissing.title=basedpython: ''{0}'' not found
        assertEquals("basedpython: 'by' not found", msg("notification.lspBinaryMissing.title", "by"))
        assertEquals("basedpython: 'buff' language server stopped", msg("notification.lspCrashed.title", "buff"))
        assertEquals("'x y' is not a valid identifier.", msg("refactoring.invalidIdentifier", "x y"))
        assertTrue(msg("refactoring.inlineVariable.cannotInline", "n").startsWith("Cannot inline 'n':"))
    }

    @Test
    fun `multi-arg messages format`() {
        assertEquals(
            "Detected by version 1.0, but 2.0 or newer is recommended. Some language features may not work correctly.",
            msg("notification.byOutdated.content", "1.0", "2.0"),
        )
        val confirm = msg("download.confirm.message", "by and buff", "darwin-arm64", "/tmp/bin")
        assertTrue(confirm.startsWith("Download prebuilt by and buff for darwin-arm64 into"))
        assertTrue(confirm.contains("\n"), "newline expected in confirm message")
        assertTrue(confirm.endsWith("/tmp/bin?"))
    }

    @Test
    fun `partial-failure prefix keeps trailing space`() {
        // Encoded as   in the bundle so the concatenated message reads cleanly.
        val prefix = msg("download.result.partialPrefix", "by")
        assertEquals("Installed by. ", prefix)
        assertTrue(msg("download.result.failed", prefix, "buff").startsWith("Installed by. Failed: buff"))
    }

    @Test
    fun `multiline named-tuple explanation has structure`() {
        val m = value("intention.explainNamedTuple.message")
        assertTrue(m.contains("NamedTuple"))
        assertTrue(m.contains("\n"), "should contain literal newlines")
        assertTrue(m.contains("(name: str, age: int)"))
    }

    @Test
    fun `utf8 glyphs survive load`() {
        // em-dash in the with-rules summary, ellipsis in progress item
        assertTrue(msg("notification.apiLockGenerated.withRules", 3).contains("—"))
        assertTrue(msg("download.progress.item", "by").contains("…"))
        assertTrue(value("banner.byMissing.configure").contains("…"))
    }

    @Test
    fun `every placeholder value parses as a valid MessageFormat`() {
        // Any value containing an unescaped {N} must be a well-formed pattern. This guards against
        // accidental single quotes that would silently swallow text or throw at runtime.
        val placeholder = Regex("""\{\d""")
        val bad = mutableListOf<String>()
        for (name in props.stringPropertyNames()) {
            val v = props.getProperty(name)
            if (placeholder.containsMatchIn(v)) {
                runCatching { MessageFormat(v) }.onFailure { bad += "$name (${it.message})" }
            }
        }
        assertTrue(bad.isEmpty(), "malformed MessageFormat patterns: $bad")
    }

    @Test
    fun `no value is left as the raw key (empty)`() {
        val empties = props.stringPropertyNames().filter { props.getProperty(it).isBlank() }
        assertFalse(empties.isNotEmpty(), "blank bundle values: $empties")
    }
}
