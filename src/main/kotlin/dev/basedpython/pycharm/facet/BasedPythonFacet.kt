package dev.basedpython.pycharm.facet

import com.intellij.facet.Facet
import com.intellij.facet.FacetType
import com.intellij.openapi.module.Module

class BasedPythonFacet(
    facetType: FacetType<BasedPythonFacet, BasedPythonFacetConfiguration>,
    module: Module,
    name: String,
    configuration: BasedPythonFacetConfiguration,
    underlyingFacet: Facet<*>?,
) : Facet<BasedPythonFacetConfiguration>(facetType, module, name, configuration, underlyingFacet)
