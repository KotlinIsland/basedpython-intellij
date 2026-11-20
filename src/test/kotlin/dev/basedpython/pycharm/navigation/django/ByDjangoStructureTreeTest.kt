package dev.basedpython.pycharm.navigation.django

import com.google.gson.Gson
import dev.basedpython.pycharm.lsp.ext.ByDjangoStructure
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `by/djangoStructure`'s answer laid out as the *Django* tool window's rows, read from JSON with
 * Gson as lsp4j reads it — so a key `by` leaves out falls back to its default rather than to null.
 * The answer is the one `scratch.django.md`'s contract describes for `~/projects/DjangoProject`,
 * cut to what the rows show.
 */
class ByDjangoStructureTreeTest {

    private fun symbol(name: String, file: String = "polls/models.py", line: Int = 0) =
        """{"name":"$name","uri":"file:///p/$file","range":{"start":{"line":$line,"character":0},"end":{"line":$line,"character":9}},
           "selectionRange":{"start":{"line":$line,"character":6},"end":{"line":$line,"character":9}}}"""

    private val answer = """
    {"projects":[{
      "settings":${symbol("djangoproject.settings", "djangoproject/settings.py")},
      "apps":[
        {"label":"admin","name":"django.contrib.admin","external":true,
         "models":[{"symbol":${symbol("LogEntry", "site-packages/django/contrib/admin/models.py")}}]},
        {"label":"polls","name":"polls","config":${symbol("PollsConfig", "polls/apps.py")},
         "models":[
           {"symbol":${symbol("Question", line = 6)},"inAdmin":true,"admins":[${symbol("QuestionAdmin", "polls/admin.py", 10)}],
            "fields":[{"symbol":${symbol("question_text", line = 7)},"kind":"CharField","type":"str"},
                      {"symbol":${symbol("pub_date", line = 8)},"kind":"DateTimeField","type":"datetime"}]},
           {"symbol":${symbol("Choice", line = 18)},
            "fields":[{"symbol":${symbol("question", line = 19)},"kind":"ForeignKey","type":"Question",
                       "relation":{"kind":"foreignKey","target":"polls.Question","relatedName":"choices"}}]}
         ],
         "admins":[{"symbol":${symbol("QuestionAdmin", "polls/admin.py", 10)},"models":["polls.Question"]},
                   {"symbol":${symbol("ChoiceInline", "polls/admin.py", 5)},"inline":true},
                   {"symbol":${symbol("OrphanAdmin", "polls/admin.py", 22)},"registered":false}],
         "views":[{"symbol":${symbol("IndexView", "polls/views.py", 11)},"kind":"class","routes":["/polls/"]},
                  {"symbol":${symbol("vote", "polls/views.py", 34)},"kind":"function","routes":["/polls/<int:question_id>/vote/"]}],
         "forms":[${symbol("QuestionForm", "polls/forms.py", 5)}]},
        {"label":"library","name":"library",
         "models":[{"symbol":${symbol("Tag", "library/models.py", 15)},"inAdmin":true}],
         "receivers":[{"symbol":${symbol("count_loan", "library/signals.py", 8)},"signal":"post_save","sender":"library.Loan"}],
         "commands":[${symbol("overdue", "library/management/commands/overdue.py")}]}
      ]}]}
    """.trimIndent()

    private val structure: ByDjangoStructure = Gson().fromJson(answer, ByDjangoStructure::class.java)

    /** Each row as `name  detail`, indented by depth, for the whole tree. */
    private fun render(nodes: List<ByDjangoNode>, depth: Int = 0): List<String> = nodes.flatMap { node ->
        listOf("  ".repeat(depth) + listOfNotNull(node.text, node.detail).joinToString("  ")) + render(node.children, depth + 1)
    }

    @Test
    fun `an app's models, admin, views and forms are its groups, each row with what django makes of it`() {
        assertEquals(
            listOf(
                "polls",
                "  Models  2",
                "    Question",
                "      question_text  CharField: str",
                "      pub_date  DateTimeField: datetime",
                "    Choice",
                "      question  ForeignKey → polls.Question (reverse choices)",
                "  Admin  3",
                "    QuestionAdmin  polls.Question",
                "    ChoiceInline  inline",
                "    OrphanAdmin  not registered",
                "  Views  2",
                "    IndexView  /polls/",
                "    vote  /polls/<int:question_id>/vote/",
                "  Forms  1",
                "    QuestionForm",
                "library",
                "  Models  1",
                "    Tag",
                "  Admin  1",
                "    Tag  default ModelAdmin",
                "  Receivers  1",
                "    count_loan  post_save from library.Loan",
                "  Commands  1",
                "    overdue",
            ),
            render(ByDjangoStructureTree.build(structure, showExternal = false)),
        )
    }

    @Test
    fun `apps outside the project are listed only when asked for, with their models alone`() {
        val shown = ByDjangoStructureTree.build(structure, showExternal = true)
        assertEquals(listOf("admin", "polls", "library"), shown.map { it.text })
        val admin = shown.first()
        assertEquals(ByDjangoNodeKind.EXTERNAL_APP, admin.kind)
        assertEquals("django.contrib.admin", admin.detail)
        assertEquals(listOf("LogEntry"), admin.children.map { it.text })
    }

    @Test
    fun `a row opens where its symbol is, and only an unregistered admin is a warning`() {
        val polls = ByDjangoStructureTree.build(structure, showExternal = false).first()
        val question = polls.children.first().children.first()
        assertEquals("file:///p/polls/models.py", question.symbol?.uri)
        assertEquals(6, question.symbol?.selectionRange?.start?.line)
        val admin = polls.children.first { it.text == "Admin" }.children
        assertEquals(listOf(false, false, true), admin.map { it.warning })
        assertEquals(ByDjangoNodeKind.RELATION, polls.children.first().children[1].children.single().kind)
    }

    @Test
    fun `several settings modules are a project row each`() {
        val two = Gson().fromJson(
            """{"projects":[{"settings":${symbol("site_a.settings")},"apps":[]},{"settings":${symbol("site_b.settings")},"apps":[]}]}""",
            ByDjangoStructure::class.java,
        )
        assertEquals(listOf("site_a.settings", "site_b.settings"), ByDjangoStructureTree.build(two, showExternal = false).map { it.text })
    }

    @Test
    fun `keys by leaves out read as their defaults`() {
        val bare = Gson().fromJson("""{"projects":[{"apps":[{"label":"x"}]}]}""", ByDjangoStructure::class.java)
        val app = bare.projects.single().apps.single()
        assertTrue(app.models.isEmpty() && app.serializers.isEmpty() && !app.external)
        assertEquals(listOf("x"), render(ByDjangoStructureTree.build(bare, showExternal = false)))
    }
}
