package dev.basedpython.pycharm.vcs.git

import com.intellij.dvcs.repo.Repository
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.VcsException
import dev.basedpython.pycharm.vcs.ByMergeVersions
import git4idea.GitRevisionNumber
import git4idea.GitUtil
import git4idea.history.GitHistoryUtils
import git4idea.repo.GitRepository
import git4idea.util.GitFileUtils

private val LOG = Logger.getInstance(ByGitMerges::class.java)

/**
 * The three versions of a file that a merge, a rebase step or a cherry-pick in progress merged, as git
 * still holds them while the operation waits to be committed, read through git4idea.
 *
 * Measured with git 2.56: a stopped merge records `MERGE_HEAD` (theirs) beside `HEAD` (ours), whose
 * base is their merge base; a cherry-pick records `CHERRY_PICK_HEAD`, the commit being picked, and a
 * rebase `REBASE_HEAD`, the commit being replayed, each merged with its own parent as the base, which
 * is what git's three-way merge of a pick uses.
 */
internal object ByGitMerges {

    /** How git names one version: a revision, or the merge base of two. */
    sealed interface Rev {
        data class Named(val rev: String) : Rev
        data class MergeBase(val first: String, val second: String) : Rev
    }

    data class Revs(val base: Rev, val ours: Rev, val theirs: Rev)

    /** The versions of an operation in [state], or `null` where there is no merge in progress. */
    fun revsFor(state: Repository.State): Revs? = when (state) {
        Repository.State.MERGING -> Revs(Rev.MergeBase(HEAD, GitUtil.MERGE_HEAD), Rev.Named(HEAD), Rev.Named(GitUtil.MERGE_HEAD))
        Repository.State.GRAFTING -> picked(GitUtil.CHERRY_PICK_HEAD)
        Repository.State.REBASING -> picked(GitUtil.REBASE_HEAD)
        else -> null
    }

    private fun picked(head: String) = Revs(Rev.Named("$head^"), Rev.Named(HEAD), Rev.Named(head))

    /** The commits [revs] name in [repository], or `null` when git cannot name one, as a pick of a root commit. Runs git. */
    fun resolve(project: Project, repository: GitRepository, revs: Revs): Triple<String, String, String>? {
        fun commit(rev: Rev): String? = try {
            when (rev) {
                is Rev.Named -> GitRevisionNumber.resolve(project, repository.root, rev.rev).asString()
                is Rev.MergeBase -> GitHistoryUtils.getMergeBase(project, repository.root, rev.first, rev.second)?.asString()
            }
        } catch (e: VcsException) {
            LOG.debug("cannot name $rev in ${repository.root}: ${e.message}")
            null
        }
        return Triple(commit(revs.base) ?: return null, commit(revs.ours) ?: return null, commit(revs.theirs) ?: return null)
    }

    /** [file]'s text in each of [commits] (base, ours, theirs), or `null` where one of them has no such file. Runs git. */
    fun versions(project: Project, repository: GitRepository, commits: Triple<String, String, String>, file: FilePath): ByMergeVersions? {
        val path = file.path.removePrefix(repository.root.path).removePrefix("/")
        fun text(commit: String): String? = try {
            StringUtil.convertLineSeparators(String(GitFileUtils.getFileContent(project, repository.root, commit, path), file.charset))
        } catch (e: VcsException) {
            null
        }
        return ByMergeVersions(text(commits.first) ?: return null, text(commits.second) ?: return null, text(commits.third) ?: return null)
    }

    private const val HEAD = "HEAD"
}
