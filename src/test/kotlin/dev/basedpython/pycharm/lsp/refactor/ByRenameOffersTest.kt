package dev.basedpython.pycharm.lsp.refactor

import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.TextRange
import com.intellij.psi.ElementDescriptionUtil
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.refactoring.rename.RenameUtil
import com.intellij.refactoring.rename.naming.AutomaticRenamerFactory
import com.intellij.refactoring.util.RefactoringDescriptionLocation
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lsp.ByTextHash
import dev.basedpython.pycharm.lsp.ext.ByRenameOffer
import dev.basedpython.pycharm.lsp.ext.ByRenameOffersParams
import dev.basedpython.pycharm.testFramework.AnsweringClient
import dev.basedpython.pycharm.testFramework.AnsweringClient.Companion.answered
import dev.basedpython.pycharm.testFramework.AnsweringClient.Companion.failed
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.RenameFile
import org.eclipse.lsp4j.ResourceOperation
import org.eclipse.lsp4j.TextDocumentEdit
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The renames `by` offers beside a rename: asked with the rename's own question, handed to the
 * platform as one automatic renamer per kind, and made — a module's file renamed with its imports —
 * only once kept.
 */
@TestFixtures
class ByRenameOffersTest {

    private val fixture by codeInsightFixture()

    private val animal = "class Animal:\n    def speak(self, loud):\n        return 1\n"
    private val dog = "from animal import Animal\n\n\nclass Dog(Animal):\n    def speak(self, loud):\n        return loud\n"

    private fun override(n: Int) = ByRenameOffer(
        "override:$n", "Rename overriding methods", "`dog.Dog.speak`",
        WorkspaceEdit(mapOf(DOG to listOf(TextEdit(Range(Position(4, 8), Position(4, 13)), "talk")))),
    )

    private val parameter = ByRenameOffer(
        "parameter:0", "Rename parameters of overriding methods", "`loud` of `dog.Dog.speak`",
        WorkspaceEdit(
            mapOf(
                DOG to listOf(
                    TextEdit(Range(Position(4, 20), Position(4, 24)), "noisy"),
                    TextEdit(Range(Position(5, 15), Position(5, 19)), "noisy"),
                ),
            ),
        ),
    )

    private val module = ByRenameOffer(
        "module:0", "Rename module", "`animal.by` to `beast.by`",
        WorkspaceEdit(
            listOf<Either<TextDocumentEdit, ResourceOperation>>(
                Either.forLeft(TextDocumentEdit(VersionedTextDocumentIdentifier(DOG, null), listOf(Either.forLeft(TextEdit(Range(Position(0, 5), Position(0, 11)), "beast"))))),
                Either.forRight(RenameFile(ANIMAL, BEAST)),
            ),
        ),
    )

    private fun files() = fixture.addFileToProject("animal.by", animal) to fixture.addFileToProject("dog.by", dog)

    private fun fileOf(uri: String) = when (uri) {
        ANIMAL -> fixture.findFileInTempDir("animal.by")
        DOG -> fixture.findFileInTempDir("dog.by")
        else -> null
    }

    @Test
    fun `offers are asked with the rename's question, and a by without the request offers none`() = onEdt {
        val source = fixture.configureByText("main.by", animal)
        val document = fixture.editor.document
        val unknown = ByRenameOffer("rename:0", "Something new", "", WorkspaceEdit())
        val client = AnsweringClient(fixture.project) { answered(listOf(override(0), unknown)) }
        val old = AnsweringClient(fixture.project) { failed(ResponseErrorCode.MethodNotFound, "Unhandled method by/renameOffers") }

        val (offers, none) = withContext(Dispatchers.Default) {
            readAction {
                ByRenameOffers.ask(client, fixture.project, source.virtualFile, document, 22, "talk") to
                    ByRenameOffers.ask(old, fixture.project, source.virtualFile, document, 22, "talk")
            }
        }

        assertEquals(listOf("override:0"), offers.map { it.id }, "an offer of a kind this does not know is left out")
        assertEquals(emptyList<ByRenameOffer>(), none)
        val sent = client.sentParams.single() as ByRenameOffersParams
        assertEquals("talk", sent.newName)
        assertEquals(Position(1, 8), sent.position)
        assertEquals(ByTextHash.of(animal), sent.textHash)
    }

    @Test
    fun `each kind is one automatic renamer, titled with by's label, renaming each offer to by's name`() = onEdt {
        val (animalFile, _) = files()
        val name = ByRenamedName(animalFile, fixture.getDocument(animalFile), TextRange(22, 27))
        name.offers = listOf(override(0), override(1), parameter, module)
        val factories = AutomaticRenamerFactory.EP_NAME.extensionList.filterIsInstance<ByRenameOffersFactory>()
        assertEquals(3, factories.size)
        assertTrue(factories.all { it.isApplicable(name) && it.optionName == null })
        assertFalse(factories.any { it.isApplicable(ByRenamedName(animalFile, fixture.getDocument(animalFile), TextRange(22, 27))) })

        val elements = readAction { ByRenameOffers.elementsOf(fixture.project, name.offers, ::fileOf) }
        val renamer = ByRenameOffersRenamer(ByOfferKind.Parameter, parameter.label, elements.filter { it.kind == ByOfferKind.Parameter }, "loud", "noisy")
        assertEquals("Rename parameters of overriding methods", renamer.dialogTitle)
        assertFalse(renamer.allowChangeSuggestedName())
        assertEquals(mapOf("loud" to "noisy"), renamer.renames.mapKeys { it.key.name })
        assertEquals(
            listOf("speak" to "talk", "speak" to "talk", "loud" to "noisy", "animal.by" to "beast.by"),
            elements.map { it.name to it.newName },
        )
        val moduleElement = elements.last()
        assertTrue(RenameUtil.isValidName(fixture.project, moduleElement, "beast.by"), "by's name for a module is not an identifier")
        assertEquals("`animal.by` to `beast.by`", ElementDescriptionUtil.getElementDescription(moduleElement, RefactoringDescriptionLocation.WITH_PARENT))
    }

    @Test
    fun `a kept offer makes its edits, and a module's renames the file after rewriting its imports`() = onEdt {
        val (animalFile, dogFile) = files()
        val elements = readAction { ByRenameOffers.elementsOf(fixture.project, listOf(parameter, module), ::fileOf) }
        val processor = RenamePsiElementProcessor.forElement(elements.first())
        assertInstanceOf(ByRenameOfferProcessor::class.java, processor)

        WriteCommandAction.runWriteCommandAction(fixture.project) {
            for (element in elements) {
                val usages = ByImportEdit.of(fixture.project, element.offer.edit!!, element, ::fileOf)
                processor.renameElement(element, element.newName, usages.toTypedArray(), null)
            }
        }

        assertEquals(
            "from beast import Animal\n\n\nclass Dog(Animal):\n    def speak(self, noisy):\n        return noisy\n",
            fixture.getDocument(dogFile).text,
        )
        assertEquals("beast.by", animalFile.name)
        assertEquals(animal, fixture.getDocument(animalFile).text)
    }

    private companion object {
        const val ANIMAL = "file:///p/animal.by"
        const val BEAST = "file:///p/beast.by"
        const val DOG = "file:///p/dog.by"
    }
}
