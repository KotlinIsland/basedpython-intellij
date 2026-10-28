package dev.basedpython.pycharm.lsp.refactor

import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lsp.ext.ByMemberItem
import dev.basedpython.pycharm.lsp.ext.ByMovableMembers
import dev.basedpython.pycharm.testFramework.AnsweringClient
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Pull Members Up and Push Members Down: the rows of the member table, from `by/prepareMoveMembers`. */
@TestFixtures
class ByMoveMembersTest {

    private val fixture by codeInsightFixture()

    @Test
    fun `every member by lists is a row, named and kinded as by says, the one at the caret checked`() = onEdt {
        val text = "class Dog(Animal):\n    sound = \"woof\"\n\n    def fe<caret>tch(self) -> str:\n        return self.sound\n"
        val file = fixture.configureByText("dogs.by", text)
        val members = ByMovableMembers(
            members = listOf(
                ByMemberItem("Animal", "base", "Animal", Range(Position(0, 10), Position(0, 16))),
                ByMemberItem("sound", "attribute", "sound", Range(Position(1, 4), Position(1, 9))),
                ByMemberItem("fetch", "method", "fetch(self) -> str", Range(Position(3, 8), Position(3, 13))),
            ),
        )
        val site = ByRefactoringSite(
            AnsweringClient(fixture.project) { error("nothing is asked") },
            fixture.project,
            file.virtualFile,
            fixture.editor.document,
            TextDocumentIdentifier("file:///dogs.by"),
            Position(3, 10),
        )

        val rows = ByMoveMembers.memberInfos(fixture.project, members, site, fixture.caretOffset, bases = false)

        assertEquals(listOf("sound" to false, "fetch" to true), rows.map { it.name to it.isChecked })
        assertEquals(listOf("sound", "fetch(self) -> str"), rows.map { it.displayName }, "each as by writes it in a list")
        assertEquals(listOf("fetch"), checkedNames(rows))
        assertEquals(listOf(false, false), rows.map { it.isStatic })

        val up = ByMoveMembers.memberInfos(fixture.project, members, site, fixture.caretOffset, bases = true)
        assertEquals(listOf("Animal", "sound", "fetch"), up.map { it.name }, "a base moves only up, into the superclass's bases")
    }

    @Test
    fun `pull members up names the checked bases apart from the checked members, as bases even when none is`() = onEdt {
        // a base and a class attribute of the same name: only `bases` says which one moves
        val text = "class Dog(Mixin):\n    Mix<caret>in = 1\n\n    def fetch(self) -> str:\n        return \"stick\"\n"
        val file = fixture.configureByText("dogs.by", text)
        val members = ByMovableMembers(
            members = listOf(
                ByMemberItem("Mixin", "base", "Mixin", Range(Position(0, 10), Position(0, 15))),
                ByMemberItem("Mixin", "attribute", "Mixin", Range(Position(1, 4), Position(1, 9))),
                ByMemberItem("fetch", "method", "fetch(self) -> str", Range(Position(3, 8), Position(3, 13))),
            ),
        )
        val site = ByRefactoringSite(
            AnsweringClient(fixture.project) { error("nothing is asked") },
            fixture.project,
            file.virtualFile,
            fixture.editor.document,
            TextDocumentIdentifier("file:///dogs.by"),
            Position(1, 6),
        )
        val rows = ByMoveMembers.memberInfos(fixture.project, members, site, fixture.caretOffset, bases = true)
        assertEquals(listOf(false, true, false), rows.map { it.isChecked }, "the attribute at the caret, not the base")

        val attribute = pullUpParams(site, rows, "pets.Animal")
        assertEquals(listOf("Mixin") to emptyList<String>(), attribute.members to attribute.bases)
        assertEquals("pets.Animal", attribute.target)

        rows[0].isChecked = true
        rows[1].isChecked = false
        rows[2].isChecked = true
        val base = pullUpParams(site, rows, "pets.Animal")
        assertEquals(listOf("fetch") to listOf("Mixin"), base.members to base.bases)
    }
}
