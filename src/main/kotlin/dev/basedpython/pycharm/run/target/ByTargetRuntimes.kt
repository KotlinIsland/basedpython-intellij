package dev.basedpython.pycharm.run.target

import com.intellij.execution.target.ContributedConfigurationsList
import com.intellij.execution.target.LanguageRuntimeConfiguration
import com.intellij.execution.target.LanguageRuntimeType
import com.intellij.execution.target.TargetEnvironmentsManager
import com.intellij.ide.plugins.DynamicPluginListener
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.extensions.ExtensionPointListener
import com.intellij.openapi.extensions.PluginDescriptor
import com.intellij.openapi.project.ProjectManager
import dev.basedpython.pycharm.env.bundled.BundledBinaries
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

private typealias Runtimes = ContributedConfigurationsList<LanguageRuntimeConfiguration, LanguageRuntimeType<*>>

/**
 * Keeps each run target's basedpython runtime through an unload of the plugin, as an entry the
 * platform holds without the plugin's classes, and makes it a runtime again when the plugin is back.
 *
 * A target keeps its runtimes in a `ContributedConfigurationsList`, which resolves each entry to a
 * configuration object of its runtime type's own class when it is loaded, and does nothing when that
 * type's extension goes away: a target that carries a basedpython runtime keeps a
 * [ByLanguageRuntimeConfiguration] for as long as the project is open, and with it the plugin's
 * classes (measured: an unload with such a target left the classloader reachable through the
 * project's `TargetEnvironmentsManager`, and through nothing of it after this). Dropping the runtime
 * would lose the user's settings with the next save. Instead each list that holds one is loaded again
 * from its own serialized state once the type is gone, which is what the platform does with an entry
 * of a type it does not know: it keeps the entry as it was written, saves it as it was, and resolves
 * it once the type is there — at the next project load, or here, when the plugin loads again.
 *
 * The state is taken before the unload, while the type is still there to write it, and loaded after
 * the type's extension is removed, when the list can no longer resolve it; the extension point
 * reports that removal later in the same unload, when it no longer lists the type (measured on
 * 263.7355).
 */
internal object ByTargetRuntimes {

    fun beforeUnload() {
        val written = runtimeLists().filter { it.findByType(ByLanguageRuntimeConfiguration::class.java) != null }
            .associateWith { it.state }
        if (written.isEmpty()) return
        // A scope of its own, ended by the removal it waits for: a listener in a service's scope
        // of this plugin was never told of it (measured on 263.5701), the scope being cancelled
        // first.
        val listening = CoroutineScope(SupervisorJob())
        LanguageRuntimeType.EXTENSION_NAME.addExtensionPointListener(
            listening,
            object : ExtensionPointListener<LanguageRuntimeType<*>> {
                override fun extensionRemoved(extension: LanguageRuntimeType<*>, pluginDescriptor: PluginDescriptor) {
                    if (extension !is ByLanguageRuntimeType) return
                    for ((runtimes, state) in written) runtimes.loadState(state)
                    listening.cancel()
                }
            },
        )
    }

    /** Resolves the basedpython runtimes an unload left as entries, now that the type is back. */
    fun afterLoad() {
        for (runtimes in runtimeLists()) {
            if (runtimes.findByType(ByLanguageRuntimeConfiguration::class.java) != null) continue
            val state = runtimes.state
            if (state.configs.any { it.typeId == ByLanguageRuntimeType.TYPE_ID }) runtimes.loadState(state)
        }
    }

    /** The runtime list of every target of every open project, and of the default project. */
    private fun runtimeLists(): List<Runtimes> {
        val projects = ProjectManager.getInstance().let { it.openProjects.toList() + it.defaultProject }
        return projects.filterNot { it.isDisposed }
            .mapNotNull { it.serviceIfCreated<TargetEnvironmentsManager>() }
            .flatMap { it.targets.resolvedConfigs() }
            .map { it.runtimes }
    }
}

/** Has [ByTargetRuntimes] keep the basedpython runtimes as this plugin unloads and loads. */
class ByTargetRuntimesUnloadGuard : DynamicPluginListener {
    override fun beforePluginUnload(pluginDescriptor: IdeaPluginDescriptor, isUpdate: Boolean) {
        if (pluginDescriptor.pluginId.idString == BundledBinaries.PLUGIN_ID) ByTargetRuntimes.beforeUnload()
    }

    override fun pluginLoaded(pluginDescriptor: IdeaPluginDescriptor) {
        if (pluginDescriptor.pluginId.idString == BundledBinaries.PLUGIN_ID) ByTargetRuntimes.afterLoad()
    }
}
