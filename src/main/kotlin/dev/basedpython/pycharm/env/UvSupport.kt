package dev.basedpython.pycharm.env

import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** Shared helpers for locating `uv` and reporting through the "BasedPython" notification group. */
internal object UvSupport {

    const val NOTIFICATION_GROUP_ID: String = "BasedPython"

    /** Locate a `uv` executable on PATH, or `null`. */
    fun findUv(): Path? {
        val name = if (SystemInfo.isWindows) "uv.exe" else "uv"
        return PathEnvironmentVariableUtil.findInPath(name)?.toPath()
    }

    /** Project base path as a [Path], or `null`. */
    fun basePath(project: Project): Path? = project.basePath?.let { Paths.get(it) }

    /** True when a uv-managed project marker exists at the base. */
    fun hasProjectMarker(project: Project): Boolean {
        val base = basePath(project) ?: return false
        return Files.isRegularFile(base.resolve("uv.lock")) ||
            Files.isRegularFile(base.resolve("pyproject.toml"))
    }

    /** True when `uv sync` is meaningful: a `uv` exe is on PATH or a project marker exists. */
    fun canSync(project: Project): Boolean = findUv() != null || hasProjectMarker(project)

    fun notify(project: Project, title: String, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP_ID)
            .createNotification(title, content, type)
            .notify(project)
    }
}
