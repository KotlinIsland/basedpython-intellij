package dev.basedpython.pycharm.navigation.django

import com.google.gson.Gson
import dev.basedpython.pycharm.lsp.ext.ByDjangoStructure
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The model diagram's graph: the project's models, the other apps' models they point at, and an edge per relation. */
class ByDjangoModelGraphTest {

    @Test
    fun `the project's models, the external ones they point at, and an edge per relation field`() {
        val structure = Gson().fromJson(
            """{"projects":[{"apps":[
              {"label":"auth","external":true,"models":[{"symbol":{"name":"User"}},{"symbol":{"name":"Group"}}]},
              {"label":"library","models":[
                {"symbol":{"name":"library.models.Author"}},
                {"symbol":{"name":"library.models.Book"},"fields":[
                  {"symbol":{"name":"title"},"kind":"CharField"},
                  {"symbol":{"name":"author"},"kind":"ForeignKey","relation":{"kind":"foreignKey","target":"library.Author","relatedName":"books"}},
                  {"symbol":{"name":"readers"},"kind":"ManyToManyField","relation":{"kind":"manyToMany","target":"auth.User"}},
                  {"symbol":{"name":"lost"},"kind":"ForeignKey","relation":{"kind":"foreignKey","target":"gone.Model"}}]}]}
            ]}]}""",
            ByDjangoStructure::class.java,
        )
        val graph = ByDjangoModelGraph.build(structure)
        assertEquals(listOf("library.Author", "library.Book", "auth.User"), graph.nodes.map { it.label })
        assertEquals(
            listOf("library.Book -author-> library.Author", "library.Book -readers-> auth.User"),
            graph.edges.map { "${it.from} -${it.field.symbol?.name}-> ${it.to}" },
        )
    }
}
