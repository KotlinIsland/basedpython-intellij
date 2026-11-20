package dev.basedpython.pycharm.navigation.django

import com.google.gson.Gson
import dev.basedpython.pycharm.lsp.ext.ByDjangoStructure
import dev.basedpython.pycharm.lsp.ext.ByEndpoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The gutter's django icons read off `by/endpoints` and `by/djangoStructure` from one file's side,
 * the answers read with Gson as lsp4j reads them.
 */
class ByDjangoLinksTest {

    private fun range(line: Int, from: Int = 0, to: Int = 5) =
        """{"start":{"line":$line,"character":$from},"end":{"line":$line,"character":$to}}"""

    private fun symbol(name: String, file: String, line: Int) =
        """{"name":"$name","uri":"file:///p/$file","range":${range(line)},"selectionRange":${range(line, 4, 9)}}"""

    private val endpoints: List<ByEndpoint> = Gson().fromJson(
        """[
          {"framework":"django","path":"/polls/","handler":${symbol("polls.views.IndexView", "polls/views.py", 10)},
           "declaration":{"uri":"file:///p/polls/urls.py","range":${range(6, 4, 40)}}},
          {"framework":"django","path":"/p/","handler":${symbol("polls.views.IndexView", "polls/views.py", 10)},
           "declaration":{"uri":"file:///p/polls/urls.py","range":${range(7, 4, 40)}}},
          {"framework":"fastapi","path":"/items","handler":${symbol("api.items", "api.py", 3)},
           "declaration":{"uri":"file:///p/api.py","range":${range(2)}}}
        ]""",
        Array<ByEndpoint>::class.java,
    ).toList()

    private val structure: ByDjangoStructure = Gson().fromJson(
        """{"projects":[{"apps":[{"label":"polls",
          "models":[{"symbol":${symbol("polls.models.Question", "polls/models.py", 6)},"inAdmin":true,
                     "admins":[${symbol("polls.admin.QuestionAdmin", "polls/admin.py", 10)}]},
                    {"symbol":${symbol("polls.models.Choice", "polls/models.py", 20)}}],
          "admins":[{"symbol":${symbol("polls.admin.QuestionAdmin", "polls/admin.py", 10)},"models":["polls.Question"]},
                    {"symbol":${symbol("polls.admin.ChoiceInline", "polls/admin.py", 4)},"inline":true}],
          "views":[{"symbol":${symbol("polls.views.IndexView", "polls/views.py", 10)},"kind":"class",
                    "templates":[{"name":"polls/index.html","uri":"file:///p/polls/templates/polls/index.html"}]}]
        }]}]}""",
        ByDjangoStructure::class.java,
    )

    private fun links(file: String) = ByDjangoLinks.forFile("file:///p/$file", endpoints, structure)
        .map { "${it.kind} ${it.at.line}:${it.at.character} -> ${it.names}" }

    @Test
    fun `a view links its routes, every path in one icon, and the templates it renders`() {
        assertEquals(
            listOf("ROUTES 10:4 -> [/polls/, /p/]", "TEMPLATES 10:4 -> [polls/index.html]"),
            links("polls/views.py"),
        )
    }

    @Test
    fun `each path() links its view, with the path it serves`() {
        assertEquals(listOf("VIEW 6:4 -> [/polls/]", "VIEW 7:4 -> [/p/]"), links("polls/urls.py"))
    }

    @Test
    fun `a model links its admin and an admin its model, and an inline or unregistered model links nothing`() {
        assertEquals(listOf("ADMIN 6:4 -> [QuestionAdmin]"), links("polls/models.py"))
        assertEquals(listOf("MODEL 10:4 -> [polls.Question]"), links("polls/admin.py"))
    }

    @Test
    fun `a template is linked from its top to the views rendering it`() {
        assertEquals(listOf("RENDERED_BY 0:0 -> [polls.views.IndexView]"), links("polls/templates/polls/index.html"))
    }

    @Test
    fun `a route declared beside its handler gets no icon, and nothing at all without a structure`() {
        assertTrue(links("api.py").isEmpty())
        assertEquals(
            listOf("ROUTES 10:4 -> [/polls/, /p/]"),
            ByDjangoLinks.forFile("file:///p/polls/views.py", endpoints, null).map { "${it.kind} ${it.at.line}:${it.at.character} -> ${it.names}" },
        )
    }
}
