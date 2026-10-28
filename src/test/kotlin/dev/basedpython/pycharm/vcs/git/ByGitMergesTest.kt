package dev.basedpython.pycharm.vcs.git

import com.intellij.dvcs.repo.Repository
import dev.basedpython.pycharm.vcs.git.ByGitMerges.Rev
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Which commits git names a merge's three versions by, in each operation, as measured with git 2.56. */
class ByGitMergesTest {

    @Test
    fun `a merge's base is the merge base of HEAD and MERGE_HEAD`() {
        assertEquals(
            ByGitMerges.Revs(Rev.MergeBase("HEAD", "MERGE_HEAD"), Rev.Named("HEAD"), Rev.Named("MERGE_HEAD")),
            ByGitMerges.revsFor(Repository.State.MERGING),
        )
    }

    @Test
    fun `a pick's base is the parent of the commit picked`() {
        assertEquals(ByGitMerges.Revs(Rev.Named("CHERRY_PICK_HEAD^"), Rev.Named("HEAD"), Rev.Named("CHERRY_PICK_HEAD")), ByGitMerges.revsFor(Repository.State.GRAFTING))
        assertEquals(ByGitMerges.Revs(Rev.Named("REBASE_HEAD^"), Rev.Named("HEAD"), Rev.Named("REBASE_HEAD")), ByGitMerges.revsFor(Repository.State.REBASING))
    }

    @Test
    fun `nothing in progress is no merge`() {
        assertNull(ByGitMerges.revsFor(Repository.State.NORMAL))
        assertNull(ByGitMerges.revsFor(Repository.State.DETACHED))
    }
}
