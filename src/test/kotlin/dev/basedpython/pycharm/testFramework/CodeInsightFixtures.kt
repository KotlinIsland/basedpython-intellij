package dev.basedpython.pycharm.testFramework

import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.fixtures.impl.LightTempDirTestFixtureImpl
import com.intellij.testFramework.junit5.fixture.TestFixture
import com.intellij.testFramework.junit5.fixture.testFixture

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
  val factory = IdeaTestFixtureFactory.getFixtureFactory()
  val projectFixture = factory.createLightFixtureBuilder(projectDescriptor, context.testName).fixture
  val fixture = factory.createCodeInsightFixture(projectFixture, LightTempDirTestFixtureImpl(true))
  fixture.setUp()
  initialized(fixture) { fixture.tearDown() }
}
