package dev.basedpython.pycharm.testFramework

import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.fixtures.impl.LightTempDirTestFixtureImpl
import com.intellij.testFramework.junit5.fixture.TestFixture
import com.intellij.testFramework.junit5.fixture.testFixture
import com.intellij.testFramework.junit5.impl.testApplication
import com.intellij.testFramework.runInEdtAndWait
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
 * Declare it as an instance field so each test gets a clean fixture. `writeIntent = true` matters:
 * `UsefulTestCase` ran JUnit 3 tests on the EDT holding the write-intent lock, and PSI access here
 * expects the same, so without it every test touching PSI fails a read-access assertion.
 * ```
 * @TestFixtures
 * @RunInEdt(writeIntent = true)
 * class MyTest {
 *   private val fixture by codeInsightFixture()
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
  initialized(fixture) { fixture.tearDown() }
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
    { AutoCloseable { runInEdtAndWait { PlatformTestUtil.cleanupAllProjects() } } },
    AutoCloseable::class.java,
  )
}
