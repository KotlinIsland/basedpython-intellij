package dev.basedpython.pycharm.navigation

import com.intellij.navigation.ChooseByNameContributorEx
import com.intellij.navigation.NavigationItem
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.Processor
import com.intellij.util.indexing.FindSymbolParameters
import com.intellij.util.indexing.IdFilter

/**
 * "Go to Symbol" contributor for `.by` files: exposes functions, fields and classes.
 */
class BasedPythonSymbolContributor : ChooseByNameContributorEx {

    override fun processNames(
        processor: Processor<in String>,
        scope: GlobalSearchScope,
        filter: IdFilter?,
    ) {
        for (name in ByChooseByNameSupport.collectNames(scope, ByChooseByNameSupport.SYMBOL_KINDS)) {
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
        for (item in ByChooseByNameSupport.collectItems(project, scope, name, ByChooseByNameSupport.SYMBOL_KINDS)) {
            if (!processor.process(item)) return
        }
    }
}
