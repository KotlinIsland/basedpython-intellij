package dev.basedpython.pycharm.navigation

import com.intellij.navigation.ChooseByNameContributorEx
import com.intellij.navigation.NavigationItem
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.Processor
import com.intellij.util.indexing.FindSymbolParameters
import com.intellij.util.indexing.IdFilter

/**
 * "Go to Class" contributor for `.by` files: exposes class-like declarations only
 * (class, data class, enum class, protocol, …).
 */
class BasedPythonClassContributor : ChooseByNameContributorEx {

    override fun processNames(
        processor: Processor<in String>,
        scope: GlobalSearchScope,
        filter: IdFilter?,
    ) {
        for (name in ByChooseByNameSupport.collectNames(scope, ByChooseByNameSupport.CLASS_KINDS)) {
            if (!processor.process(name)) return
        }
    }

    override fun processElementsWithName(
        name: String,
        processor: Processor<in NavigationItem>,
        parameters: FindSymbolParameters,
    ) {
        val project = parameters.project
        val scope = parameters.searchScope
        for (item in ByChooseByNameSupport.collectItems(project, scope, name, ByChooseByNameSupport.CLASS_KINDS)) {
            if (!processor.process(item)) return
        }
    }
}
