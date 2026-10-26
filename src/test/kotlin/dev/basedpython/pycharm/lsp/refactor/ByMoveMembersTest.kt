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
                ByMemberItem("sound", "attribute", Range(Position(1, 4), Position(1, 9))),
                ByMemberItem("fetch", "method", Range(Position(3, 8), Position(3, 13))),
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

        val rows = ByMoveMembers.memberInfos(fixture.project, members, site, fixture.caretOffset)

        assertEquals(listOf("sound" to false, "fetch" to true), rows.map { it.name to it.isChecked })
        assertEquals(listOf("fetch"), checkedNames(rows))
        assertEquals(listOf(false, false), rows.map { it.isStatic })
    }
}
