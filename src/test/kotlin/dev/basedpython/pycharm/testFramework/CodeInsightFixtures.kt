package dev.basedpython.pycharm.testFramework

import com.intellij.openapi.Disposable
import com.intellij.platform.lsp.api.LspIntegrationProvider
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.LeakHunter
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.fixtures.impl.LightTempDirTestFixtureImpl
import com.intellij.testFramework.junit5.fixture.TestFixture
import com.intellij.testFramework.junit5.fixture.testFixture
import com.intellij.testFramework.junit5.impl.testApplication
import com.intellij.testFramework.runInEdtAndWait
import dev.basedpython.pycharm.lsp.BuffLspServerSupportProvider
import dev.basedpython.pycharm.lsp.ByLspServerSupportProvider
import org.junit.jupiter.api.extension.ExtensionContext

/**
 * A JUnit 5 [TestFixture] wrapping the classic [CodeInsightTestFixture] — the light, in-memory
 * project that `BasePlatformTestCase` used to build in its `setUp`.
 *
 * The platform ships an equivalent `codeInsightFixture(...)` in its `junit5/codeInsight` module, but
 * that module is not published to the IntelliJ maven repository, so plugins have to bridge the two
 * frameworks themselves. This wires the exact chain `BasePlatformTestCase.createMyFixture` used
 * (light fixture builder -> code insight fixture over a [LightTempDirTestFixtureImpl]) so the
 * migrated tests keep their original semantics: a shared light project, no real files on disk, and
 * none of the cost of opening a full project per test.
 *
 * Declare it as an instance field so each test gets a clean fixture, and run each test body in
 * [onEdt]: `UsefulTestCase` ran JUnit 3 tests on the EDT holding the write-intent lock, and PSI
 * access here expects the same, so off it every test touching PSI fails a read-access assertion.
 * ```
 * @TestFixtures
 * class MyTest {
 *   private val fixture by codeInsightFixture()
 *
 *   @Test
 *   fun `it works`() = onEdt { ... }
 * }
 * ```
 */
fun codeInsightFixture(
  projectDescriptor: LightProjectDescriptor = LightProjectDescriptor.EMPTY_PROJECT_DESCRIPTOR,
): TestFixture<CodeInsightTestFixture> = testFixture("codeInsight") { context ->
  closeLightProjectsBeforeTheApplication(context.extensionContext)
  val factory = IdeaTestFixtureFactory.getFixtureFactory()
  val projectFixture = factory.createLightFixtureBuilder(projectDescriptor, context.testName).fixture
  val fixture = factory.createCodeInsightFixture(projectFixture, LightTempDirTestFixtureImpl(true))
  fixture.setUp()
  withoutOwnLanguageServers(fixture.testRootDisposable)
  initialized(fixture) { fixture.tearDown() }
}

/**
 * Takes this plugin's two LSP integrations out of the platform's list for one test, so opening a
 * file in the light project never starts a real `by` or `buff`.
 *
 * Tests that are about the servers use fakes ([RecordingByClient]) or call a provider's `fileOpened`
 * themselves with a recording starter. A server the platform starts behind a test is never wanted,
 * and cannot be ended cleanly: the platform adds the client later, in a write action on the EDT, and
 * when that lands after the light fixture's close — which only marks the shared project disposed
 * temporarily, so the LSP manager's coroutines keep running — `LspServiceViewSupport` builds its
 * console against a project whose message bus throws. The half-built `ConsoleViewImpl` has already
 * registered its alarms under itself, so it stays at the Disposer root holding the project, and the
 * leak check at the end of the run fails on it: an `executionError` naming no test, about one time in
 * four. Any `.by` file opened while `by` resolves does it, through a settings override or a `by` on
 * `PATH`.
 */
private fun withoutOwnLanguageServers(disposable: Disposable) {
  val ep = LspIntegrationProvider.EP_NAME
  ExtensionTestUtil.maskExtensions(
    ep,
    ep.extensionList.filter { it !is ByLspServerSupportProvider && it !is BuffLspServerSupportProvider },
    disposable,
  )
}

/**
 * Closes the shared light project before the JUnit 5 application checks for leaked projects.
 *
 * The light project outlives every test by design, and only the JUnit 3 shutdown
 * (`TestApplicationManager.disposeApplicationAndCheckForLeaks`) closes it, through
 * [PlatformTestUtil.cleanupAllProjects]. The JUnit 5 one never does: it asserts that no project is
 * left and then disposes the application. So a run that held both this fixture and any
 * `@TestApplication` class — which is what creates that shutdown — ended reporting the light project
 * as leaked, through whichever of the platform's project services happened to be found first.
 *
 * The application is asked for first so that its resource is in the root store before this one:
 * the store closes what it holds in reverse order, which puts the project's close first.
 */
private fun closeLightProjectsBeforeTheApplication(context: ExtensionContext) {
  context.testApplication().getOrThrow()
  context.root.getStore(ExtensionContext.Namespace.GLOBAL).getOrComputeIfAbsent(
    "basedpython.lightProjects",
    { AutoCloseable { runInEdtAndWait { LeakHunter.cleanupAllProjects() } } },
    AutoCloseable::class.java,
  )
}
