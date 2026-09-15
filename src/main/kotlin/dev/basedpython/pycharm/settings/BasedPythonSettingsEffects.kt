package dev.basedpython.pycharm.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.RootsChangeRescanningInfo
import com.intellij.openapi.roots.ex.ProjectRootManagerEx
import com.intellij.openapi.util.EmptyRunnable
import dev.basedpython.pycharm.lang.dialect.BasedPythonProjectDetector
import dev.basedpython.pycharm.lang.dialect.PyFileHandling

/**
 * What the IDE has to be told when settings that it caches an answer to change — so every place
 * that changes them, the settings page and *Import Settings* alike, tells it the same things.
 */
internal object BasedPythonSettingsEffects {

  /** The settings whose change the IDE does not notice by itself. */
  data class Snapshot(val byEnabled: Boolean, val pyFileHandling: PyFileHandling, val indexGeneratedPython: Boolean)

  fun snapshot(settings: BasedPythonSettings): Snapshot =
    Snapshot(settings.byEnabled, settings.pyFileHandling, settings.indexGeneratedPython)

  /** Announces whatever differs between [before] and the settings [project] has now. */
  fun announce(project: Project, before: Snapshot) {
    val after = snapshot(BasedPythonSettings.getInstance(project))
    // File types are cached per file; without this, open .py editors keep the old one. `by` being
    // on is half of what makes a project basedpython, and so of who owns its `.py`.
    if (after.pyFileHandling != before.pyFileHandling || after.byEnabled != before.byEnabled) {
      BasedPythonProjectDetector.fileTypesMayHaveChanged("basedpython .py handling changed")
    }
    if (after.indexGeneratedPython != before.indexGeneratedPython) rescanRoots(project)
  }

  /**
   * Re-evaluates directory-index exclusions, so toggling [BasedPythonSettings.indexGeneratedPython]
   * includes or excludes the build directories straight away.
   *
   * Later and in a write action, for the reason [BasedPythonProjectDetector.fileTypesMayHaveChanged]
   * gives; and not at all for a project closed in the meantime, whose root manager is gone.
   */
  fun rescanRoots(project: Project) {
    ApplicationManager.getApplication().invokeLater {
      if (project.isDisposed) return@invokeLater
      WriteAction.run<RuntimeException> {
        ProjectRootManagerEx.getInstanceEx(project)
          .makeRootsChange(EmptyRunnable.getInstance(), RootsChangeRescanningInfo.TOTAL_RESCAN)
      }
    }
  }
}
