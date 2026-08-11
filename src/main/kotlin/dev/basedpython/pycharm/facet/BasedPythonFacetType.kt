package dev.basedpython.pycharm.facet

import com.intellij.facet.Facet
import com.intellij.facet.FacetType
import com.intellij.facet.FacetTypeId
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleType
import dev.basedpython.pycharm.BasedPythonIcons
import javax.swing.Icon

class BasedPythonFacetType :
    FacetType<BasedPythonFacet, BasedPythonFacetConfiguration>(ID, "basedpython", "basedpython") {

    override fun createDefaultConfiguration(): BasedPythonFacetConfiguration =
        BasedPythonFacetConfiguration()

    override fun createFacet(
        module: Module,
        name: String,
        configuration: BasedPythonFacetConfiguration,
        underlyingFacet: Facet<*>?,
    ): BasedPythonFacet = BasedPythonFacet(this, module, name, configuration, underlyingFacet)

    override fun isSuitableModuleType(moduleType: ModuleType<*>?): Boolean = true

    override fun getIcon(): Icon = ICON

    companion object {
        val ID: FacetTypeId<BasedPythonFacet> = FacetTypeId("basedpython")

        private val ICON: Icon =
            BasedPythonIcons.Logo
    }
}
