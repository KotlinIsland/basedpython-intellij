package dev.basedpython.pycharm.lsp.refactor

import com.google.gson.JsonParser
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lsp.ByExperimental
import dev.basedpython.pycharm.lsp.ByTextHash
import dev.basedpython.pycharm.lsp.ext.ByNamedRenameParams
import dev.basedpython.pycharm.testFramework.AnsweringClient
import dev.basedpython.pycharm.testFramework.AnsweringClient.Companion.answered
import dev.basedpython.pycharm.testFramework.AnsweringClient.Companion.experimental
import dev.basedpython.pycharm.testFramework.AnsweringClient.Companion.failed
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.RenameFile
import org.eclipse.lsp4j.TextEdit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A rename as `by/rename` answers it: the rename's own edits, and the renames that go with it —
 * overriding methods, their parameters, the module named after a class — grouped by their change
 * annotation's label, made only when kept, a module's file renamed after every edit.
 */
@TestFixtures
class ByAnnotatedRenameTest {

    private val fixture by codeInsightFixture()

    private val animal = "class Animal:\n    def speak(self, loud):\n        return 1\n"
    private val dog = "from animal import Animal\n\n\nclass Dog(Animal):\n    def speak(self, loud):\n        return loud\n"

    /**
     * What `by` main's `annotated_workspace_edit` sends for renaming `Animal.speak`'s parameter and
     * class together, in its shape: the rename's own edits plain, each offer its own annotation, the
     * offers of a kind sharing a label, and the module's move a `RenameFile` after the text edits.
     */
    private val answer = """
        {
          "documentChanges": [
            {"textDocument": {"uri": "$ANIMAL", "version": null}, "edits": [
              {"range": {"start": {"line": 1, "character": 8}, "end": {"line": 1, "character": 13}}, "newText": "talk"}
            ]},
            {"textDocument": {"uri": "$DOG", "version": null}, "edits": [
              {"range": {"start": {"line": 0, "character": 5}, "end": {"line": 0, "character": 11}}, "newText": "beast", "annotationId": "module:0"},
              {"range": {"start": {"line": 4, "character": 8}, "end": {"line": 4, "character": 13}}, "newText": "talk", "annotationId": "override:0"},
              {"range": {"start": {"line": 4, "character": 20}, "end": {"line": 4, "character": 24}}, "newText": "noisy", "annotationId": "parameter:0"},
              {"range": {"start": {"line": 5, "character": 15}, "end": {"line": 5, "character": 19}}, "newText": "noisy", "annotationId": "parameter:0"},
              {"range": {"start": {"line": 3, "character": 0}, "end": {"line": 3, "character": 0}}, "newText": "", "annotationId": "plain"}
            ]},
            {"kind": "rename", "oldUri": "$ANIMAL", "newUri": "$BEAST", "annotationId": "module:0"}
          ],
          "changeAnnotations": {
            "override:0": {"label": "Rename overriding methods", "needsConfirmation": true, "description": "`dog.Dog.speak`"},
            "parameter:0": {"label": "Rename parameters of overriding methods", "needsConfirmation": true, "description": "`loud` of `dog.Dog.speak`"},
            "module:0": {"label": "Rename module", "needsConfirmation": true, "description": "`animal.by` to `beast.by`"},
            "plain": {"label": "Asks nothing"}
          }
        }
    """.trimIndent()

    private fun parsed() = checkNotNull(ByAnnotatedRename.parse(JsonParser.parseString(answer)))

    private fun edit(line: Int, from: Int, to: Int, text: String) = TextEdit(Range(Position(line, from), Position(line, to)), text)

    @Test
    fun `the rename is every edit that asks for no confirmation, and each label is one group, in by's order`() {
        val rename = parsed()

        assertEquals(
            mapOf(ANIMAL to listOf(edit(1, 8, 13, "talk")), DOG to listOf(edit(3, 0, 0, ""))),
            rename.rename.changes,
            "an annotation that needs no confirmation is applied as the rename's own",
        )
        assertEquals(listOf("Rename module", "Rename overriding methods", "Rename parameters of overriding methods"), rename.groups.map { it.label })
        val (module, overrides, parameters) = rename.groups
        assertEquals(listOf("`animal.by` to `beast.by`"), module.descriptions)
        assertEquals(listOf(RenameFile(ANIMAL, BEAST)), module.fileRenames)
        assertEquals(mapOf(DOG to listOf(edit(0, 5, 11, "beast"))), module.edit.changes)
        assertEquals(mapOf(DOG to listOf(edit(4, 8, 13, "talk"))), overrides.edit.changes)
        assertEquals(mapOf(DOG to listOf(edit(4, 20, 24, "noisy"), edit(5, 15, 19, "noisy"))), parameters.edit.changes)
        assertEquals(listOf("Rename overriding methods"), rename.keeping(setOf("Rename overriding methods", "Something else")).map { it.label })
    }

    @Test
    fun `offers of one kind under annotations of their own are one group, each description kept`() {
        val json = """
            {"documentChanges": [{"textDocument": {"uri": "$DOG"}, "edits": [
              {"range": {"start": {"line": 4, "character": 8}, "end": {"line": 4, "character": 13}}, "newText": "talk", "annotationId": "override:0"},
              {"range": {"start": {"line": 9, "character": 8}, "end": {"line": 9, "character": 13}}, "newText": "talk", "annotationId": "override:1"}
            ]}],
             "changeAnnotations": {
               "override:0": {"label": "Rename overriding methods", "needsConfirmation": true, "description": "`dog.Dog.speak`"},
               "override:1": {"label": "Rename overriding methods", "needsConfirmation": true, "description": "`dog.Puppy.speak`"}
             }}
        """.trimIndent()
        val group = checkNotNull(ByAnnotatedRename.parse(JsonParser.parseString(json))).groups.single()
        assertEquals(listOf("`dog.Dog.speak`", "`dog.Puppy.speak`"), group.descriptions)
        assertEquals(2, group.edit.changes.getValue(DOG).size)
    }

    @Test
    fun `an answer this cannot make whole is not read at all`() {
        fun parse(json: String) = ByAnnotatedRename.parse(JsonParser.parseString(json))
        val edits = """[{"range": {"start": {"line": 0, "character": 0}, "end": {"line": 0, "character": 1}}, "newText": "x", "annotationId": "nowhere"}]"""
        assertNull(parse("""{"documentChanges": [{"textDocument": {"uri": "$DOG"}, "edits": $edits}], "changeAnnotations": {}}"""), "an annotation id naming nothing")
        assertNull(parse("""{"documentChanges": [{"kind": "create", "uri": "$BEAST"}]}"""), "a file created")
        assertNull(parse("""{"documentChanges": [{"kind": "rename", "oldUri": "$ANIMAL", "newUri": "$BEAST"}]}"""), "a move the rename makes unasked")
        assertNull(parse("[]"))
        assertEquals(mapOf(DOG to listOf(edit(0, 0, 1, "y"))), parse("""{"changes": {"$DOG": [{"range": {"start": {"line": 0, "character": 0}, "end": {"line": 0, "character": 1}}, "newText": "y"}]}}""")?.rename?.changes)
    }

    @Test
    fun `by-rename is asked with the rename's question, and only of a by that advertises it`() = onEdt {
        val source = fixture.configureByText("animal.by", animal)
        val document = fixture.editor.document
        val client = AnsweringClient(fixture.project, initializeResult = experimental(ByExperimental.RENAME_CHANGE_ANNOTATIONS)) { method ->
            assertEquals(ByAnnotatedRename.METHOD, method)
            answered(JsonParser.parseString(answer))
        }
        val old = AnsweringClient(fixture.project) { answered(JsonParser.parseString(answer)) }

        val (asked, unasked) = withContext(Dispatchers.Default) {
            readAction {
                ByAnnotatedRename.ask(client, fixture.project, source.virtualFile, document, 22, "talk") to
                    ByAnnotatedRename.ask(old, fixture.project, source.virtualFile, document, 22, "talk")
            }
        }

        assertEquals(parsed(), (asked as ByAsked.Answer).value)
        assertEquals(ByAsked.Unsupported, unasked)
        assertEquals(0, old.requests.get())
        val sent = client.sentParams.single() as ByNamedRenameParams
        assertEquals("talk", sent.newName)
        assertEquals(Position(1, 8), sent.position)
        assertEquals(ByTextHash.of(animal), sent.textHash)
    }

    @Test
    fun `by's refusal is its reason, and any other failure is not one`() = onEdt {
        val source = fixture.configureByText("animal.by", animal)
        val document = fixture.editor.document
        val advertised = experimental(ByExperimental.RENAME_CHANGE_ANNOTATIONS)
        val refusing = AnsweringClient(fixture.project, initializeResult = advertised) {
            failed(ResponseErrorCode.RequestFailed, "`talk` is already defined in the same scope")
        }
        val broken = AnsweringClient(fixture.project, initializeResult = advertised) { failed(ResponseErrorCode.InternalError) }

        val (refused, failure) = withContext(Dispatchers.Default) {
            readAction {
                ByAnnotatedRename.ask(refusing, fixture.project, source.virtualFile, document, 22, "talk") to
                    ByAnnotatedRename.ask(broken, fixture.project, source.virtualFile, document, 22, "talk")
            }
        }

        assertEquals(ByAsked.Refused("`talk` is already defined in the same scope"), refused)
        assertEquals(ByAsked.Failed, failure)
    }

    @Test
    fun `the groups kept are made with the rename, and a module's file is renamed after its imports`() = onEdt {
        val animalFile = fixture.addFileToProject("animal.by", animal)
        val dogFile = fixture.addFileToProject("dog.by", dog)
        val name = ByRenamedName(animalFile, fixture.getDocument(animalFile), TextRange(22, 27))
        val rename = parsed()
        val kept = rename.keeping(setOf("Rename module", "Rename parameters of overriding methods"))
        val fileOf = { uri: String ->
            when (uri) {
                ANIMAL -> animalFile.virtualFile
                DOG -> dogFile.virtualFile
                else -> null
            }
        }
        val usages = readAction {
            (listOf(rename.rename) + kept.map { it.edit }).flatMap { ByImportEdit.of(fixture.project, it, name, fileOf) }
        }
        val moves = kept.flatMap { it.fileRenames }
        assertEquals(emptyList<Any>(), readAction { ByRenameGroups.conflicts(name, moves, fileOf) })

        WriteCommandAction.runWriteCommandAction(fixture.project) {
            ByImportEdit.apply(fixture.project, usages)
            ByRenameGroups.moveFiles(fixture.project, moves, fileOf)
        }

        assertEquals(animal.replace("speak", "talk"), fixture.getDocument(animalFile).text)
        assertEquals(
            "from beast import Animal\n\n\nclass Dog(Animal):\n    def speak(self, noisy):\n        return noisy\n",
            fixture.getDocument(dogFile).text,
            "the overriding method was not kept",
        )
        assertEquals("beast.by", animalFile.name)
    }

    @Test
    fun `a group's move to another directory is a conflict, not a rename`() = onEdt {
        val animalFile = fixture.addFileToProject("animal.by", animal)
        val name = ByRenamedName(animalFile, fixture.getDocument(animalFile), TextRange(22, 27))
        val elsewhere = RenameFile(ANIMAL, "file:///q/beast.by")

        val conflicts = readAction { ByRenameGroups.conflicts(name, listOf(elsewhere), fileOf = { _: String -> animalFile.virtualFile }) }
        WriteCommandAction.runWriteCommandAction(fixture.project) { ByRenameGroups.moveFiles(fixture.project, listOf(elsewhere), fileOf = { _: String -> animalFile.virtualFile }) }

        assertEquals(listOf("by would move animal.by to beast.by, which is not a rename of the file, so it is not moved"), conflicts.map { it.description })
        assertEquals("animal.by", animalFile.name)
    }

    @Test
    fun `each group is a checkbox named by its label, checked until unchecked, and stays unchecked when shown again`() = onEdt {
        val groups = parsed().groups
        val choices = ByRenameGroupChoices()
        choices.panel(groups)
        assertEquals(groups.map { it.label }.toSet(), choices.kept(groups))
        val module = checkNotNull(choices.box("Rename module"))
        assertTrue(module.isSelected)
        module.doClick()

        choices.panel(groups)

        assertFalse(checkNotNull(choices.box("Rename module")).isSelected)
        assertEquals(setOf("Rename overriding methods", "Rename parameters of overriding methods"), choices.kept(groups))
    }

    private companion object {
        const val ANIMAL = "file:///p/animal.by"
        const val BEAST = "file:///p/beast.by"
        const val DOG = "file:///p/dog.by"
    }
}
