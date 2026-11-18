package dev.basedpython.pycharm.project.python

import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.bindSelected
import com.jetbrains.python.errorProcessing.PyResult
import com.jetbrains.python.newProjectWizard.PyV3BaseProjectSettings
import com.jetbrains.python.newProjectWizard.PyV3ProjectBaseGenerator
import com.jetbrains.python.newProjectWizard.PyV3ProjectTypeSpecificSettings
import com.jetbrains.python.newProjectWizard.PyV3ProjectTypeSpecificUI
import dev.basedpython.pycharm.BasedPythonIcons
import dev.basedpython.pycharm.project.BasedPythonProjectScaffold
import dev.basedpython.pycharm.settings.BasedPythonSettings
import dev.basedpython.pycharm.util.BasedPythonBundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.swing.Icon

/**
 * basedpython in PyCharm's New Project dialog, in its Python group beside *Pure Python*, with the
 * interpreter choice every Python project there gets.
 *
 * PyCharm's dialog lists `directoryProjectGenerator`s and not IntelliJ IDEA's
 * `newProjectWizard.languageGenerator`s, which is what
 * [dev.basedpython.pycharm.project.BasedPythonLanguageGenerator] is; and it puts a generator in its
 * Python group, with the interpreter settings, only when it is a [PyV3ProjectBaseGenerator]
 * (`PyV3NewProjectStepAction`), so this is one. IntelliJ IDEA's New Project wizard reads no
 * `directoryProjectGenerator`, so this does not show basedpython there twice.
 *
 * The scaffold is written before the interpreter is made, so the tool making it finds the project's
 * `pyproject.toml`: uv syncs it instead of running `uv init`, which installs the `dev` group and
 * with it `by`. An interpreter of any other kind is made without `by`, and
 * [dev.basedpython.pycharm.env.ByMissingBannerProvider] offers to add it.
 */
class BasedPythonPyCharmGenerator private constructor(
    private val typeSpecific: BasedPythonPyCharmSettings,
) : PyV3ProjectBaseGenerator<BasedPythonPyCharmSettings>(
    typeSpecificSettings = typeSpecific,
    typeSpecificUI = BasedPythonPyCharmUI,
    _newProjectName = "basedpythonProject",
) {
    constructor() : this(BasedPythonPyCharmSettings())

    override fun getName(): String = "basedpython"

    override fun getLogo(): Icon = BasedPythonIcons.Logo

    override fun getDescription(): String = BasedPythonBundle.message("wizard.pycharm.description")

    override fun generateProject(project: Project, baseDir: VirtualFile, settings: PyV3BaseProjectSettings, module: Module) {
        // To disk, not through the VFS: the dialog calls this where a write action is refused, and
        // the base generator refreshes baseDir once the interpreter exists.
        BasedPythonProjectScaffold.write(baseDir.toNioPath(), project.name)
        if (settings.createGitRepository && typeSpecific.gitMerge) {
            BasedPythonSettings.getInstance(project).enableGitMergeIntegration = true
        }
        super.generateProject(project, baseDir, settings, module)
    }
}

/**
 * What the dialog asks of a basedpython project beyond what it asks of any Python one, and what is
 * done with the answer once the interpreter exists: the scaffold itself is already written, see
 * [BasedPythonPyCharmGenerator.generateProject], so this only opens its entry point.
 */
class BasedPythonPyCharmSettings(
    /** See `wizard.gitMerge`; only when the dialog also creates a Git repository. */
    var gitMerge: Boolean = true,
) : PyV3ProjectTypeSpecificSettings {

    override suspend fun generateProject(module: Module, baseDir: VirtualFile, sdk: Sdk): PyResult<Unit> {
        baseDir.findFileByRelativePath(BasedPythonProjectScaffold.ENTRY_POINT)?.let { main ->
            withContext(Dispatchers.EDT) {
                if (main.isValid && !module.project.isDisposed) OpenFileDescriptor(module.project, main).navigate(true)
            }
        }
        return PyResult.success(Unit)
    }
}

/** The git merge driver, offered under the dialog's *Create Git repository*, as IntelliJ IDEA's wizard offers it. */
internal object BasedPythonPyCharmUI : PyV3ProjectTypeSpecificUI<BasedPythonPyCharmSettings> {
    override fun configureUpperPanel(settings: BasedPythonPyCharmSettings, checkBoxRow: Row, belowCheckBoxes: Panel) {
        with(belowCheckBoxes) {
            row {
                checkBox(BasedPythonBundle.message("wizard.gitMerge"))
                    .bindSelected(settings::gitMerge)
                    .comment(BasedPythonBundle.message("wizard.gitMerge.comment"))
            }
        }
    }
}
