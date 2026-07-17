package dev.basedpython.pycharm.run.ergonomics

import com.intellij.execution.BeforeRunTask
import com.intellij.openapi.util.Key

/**
 * A before-run task marker that, when enabled on a run configuration, triggers a `by build`
 * at the project base (or the config's working dir) before the configuration launches.
 *
 * Carries no extra state beyond the inherited enabled flag, so the default
 * [BeforeRunTask] (de)serialization is sufficient.
 */
class BuildBeforeRunTask : BeforeRunTask<BuildBeforeRunTask>(PROVIDER_ID) {
    companion object {
        @JvmField
        val PROVIDER_ID: Key<BuildBeforeRunTask> = Key.create("basedpython.ByBuildBeforeRunTask")
    }
}
