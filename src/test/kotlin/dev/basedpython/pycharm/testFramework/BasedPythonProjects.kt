package dev.basedpython.pycharm.testFramework

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import dev.basedpython.pycharm.lang.dialect.PyFileHandling
import dev.basedpython.pycharm.settings.BasedPythonSettings

/**
 * Runs [body] with [project] recognised as basedpython and owning its `.py` files, then puts the
 * project back the way it was.
 *
 * The marker is an `api.lock` written through the VFS, not straight to disk: the project detector's
 * verdict is dropped by VFS events, the way it is when a user or `git` changes the files, so a
 * marker written behind the VFS would not be seen.
 *
 * `.py` ownership is pinned rather than left on AUTO so the outcome does not depend on whether the
 * IDE running the tests happens to provide the Python language. Both settings are restored as the
 * raw state they were, so a toggle that was following the IDE-wide default goes back to following it.
 */
fun asBasedPythonProject(project: Project, body: () -> Unit) {
  val settings = BasedPythonSettings.getInstance(project)
  val handling = settings.state.pyFileHandling
  val enabled = settings.state.byEnabled
  val created = WriteAction.computeAndWait<Boolean, RuntimeException> {
    val base = VfsUtil.createDirectories(project.basePath!!)
    if (base.findChild("api.lock") != null) false else base.createChildData(project, "api.lock").let { true }
  }
  settings.byEnabled = true
  settings.pyFileHandling = PyFileHandling.ALWAYS
  try {
    body()
  } finally {
    settings.state.pyFileHandling = handling
    settings.state.byEnabled = enabled
    if (created) {
      WriteAction.runAndWait<RuntimeException> {
        VfsUtil.findFile(java.nio.file.Path.of(project.basePath!!, "api.lock"), false)?.delete(project)
      }
    }
  }
}
