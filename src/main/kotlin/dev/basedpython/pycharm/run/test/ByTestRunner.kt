package dev.basedpython.pycharm.run.test

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.basedpython.pycharm.run.django.ByDjangoManage
import dev.basedpython.pycharm.run.model.ByProgramModel
import dev.basedpython.pycharm.settings.BasedPythonSettings

/**
 * What runs a project's tests.
 *
 * pytest runs every kind of test, a `unittest.TestCase` included, and is what a basedpython project
 * runs. Django's own runner, `manage.py test`, is what a django project without pytest-django runs:
 * plain pytest cannot even import such a project's tests, which touch the ORM before django is set up
 * (measured on django 5.2: `ImportError while loading conftest`, settings not configured), and only
 * pytest-django sets it up for pytest.
 */
enum class ByTestRunner(
    /** The value `by/testItems` names it by, and the one a run configuration stores. */
    val id: String,
) {
    PYTEST("pytest"),
    DJANGO("django"),
    ;

    companion object {
        /** The runner [id] names; null for one this plugin does not know, or none. */
        fun of(id: String?): ByTestRunner? = entries.firstOrNull { it.id == id }
    }
}

/**
 * The project's choice of test runner in Settings: what `by` says each project uses, or one for all of
 * them, as PyCharm's *Default test runner* is.
 */
enum class ByTestRunnerChoice(val id: String) {
    AUTOMATIC(""),
    PYTEST(ByTestRunner.PYTEST.id),
    DJANGO(ByTestRunner.DJANGO.id),
    ;

    /** The runner this choice forces, or null for [AUTOMATIC]. */
    val runner: ByTestRunner? get() = ByTestRunner.of(id)

    companion object {
        /** Falls back to [AUTOMATIC] for a value written by another version of the plugin. */
        fun of(id: String?): ByTestRunnerChoice = entries.firstOrNull { it.id == id } ?: AUTOMATIC
    }
}

/** Which runner runs a given test. */
internal object ByTestRunners {

    /**
     * The runner for a test in [file]: the one chosen in Settings, else the one `by` says the file's
     * project runs its tests with (`by/testItems`), else pytest. Django's only where its runner can run
     * it: a `unittest` test ([unittest] false names a pytest-style function, which `manage.py test`
     * never collects), in a project with a `manage.py` to run.
     */
    fun forFile(project: Project, file: VirtualFile?, unittest: Boolean = true): ByTestRunner {
        val said = BasedPythonSettings.getInstance(project).testRunner.runner
            ?: file?.let { ByProgramModel.getInstance(project).testRunner(it) }
            ?: ByTestRunner.PYTEST
        return resolve(said, unittest, hasManage = { ByDjangoManage.find(project) != null })
    }

    /** The runner for the project's tests as a whole, by what Settings or `by` say of any test file. */
    fun forProject(project: Project): ByTestRunner {
        BasedPythonSettings.getInstance(project).testRunner.runner?.let { chosen ->
            return resolve(chosen, unittest = true, hasManage = { ByDjangoManage.find(project) != null })
        }
        val model = ByProgramModel.getInstance(project)
        val said = model.projectTests()?.byFile?.keys.orEmpty().mapNotNull(model::testRunner).toSet()
        val runner = if (said == setOf(ByTestRunner.DJANGO)) ByTestRunner.DJANGO else ByTestRunner.PYTEST
        return resolve(runner, unittest = true, hasManage = { ByDjangoManage.find(project) != null })
    }

    /** [said], unless it is Django's and Django's runner cannot run the test. */
    fun resolve(said: ByTestRunner, unittest: Boolean, hasManage: () -> Boolean): ByTestRunner =
        if (said == ByTestRunner.DJANGO && (!unittest || !hasManage())) ByTestRunner.PYTEST else said
}
