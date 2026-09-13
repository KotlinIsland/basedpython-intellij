package dev.basedpython.pycharm.env.manager

import com.intellij.openapi.util.io.FileUtil
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.ui.JBUI
import dev.basedpython.pycharm.util.BasedPythonBundle
import java.awt.Color
import java.awt.Graphics2D
import java.nio.file.Path

/**
 * The strip above the tree: which environment this is, whether it matches the lock, and what is
 * happening to it right now.
 *
 * One line, read left to right in the order the questions are asked: *where* (the environment's
 * directory, named the way the project names it — `.venv`, not a path from the root of the disk),
 * *is it right* (a chip, coloured, because that is the one fact here worth colour), and *what is it*
 * (the manager, and how much is installed). While something runs, the line is taken over by what is
 * running — a package being fetched is more useful to be told than anything that was there before.
 *
 * The full path is on the tooltip, which is where a path that long belongs.
 */
internal class EnvHeader : SimpleColoredComponent() {

    init {
        isOpaque = false
        border = JBUI.Borders.compound(
            JBUI.Borders.customLineBottom(JBColor.border()),
            JBUI.Borders.empty(5, 10, 6, 10),
        )
        iconTextGap = JBUI.scale(6)
    }

    override fun doPaintFragmentBackground(
        g: Graphics2D,
        index: Int,
        bgColor: Color,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ) {
        if (getFragmentTag(index) === EnvChips.Tag) {
            EnvChips.paintBackground(g, bgColor, x, width, height)
        } else {
            super.doPaintFragmentBackground(g, index, bgColor, x, y, width, height)
        }
    }

    /**
     * Redraws the line for [status].
     *
     * [headline] is what [EnvProgress] says is happening, passed only while the service is busy, and
     * [scanned] is false until the first read has come back — distinct from a read that came back
     * with nothing, which is a project no manager claims.
     */
    fun render(status: EnvStatus, headline: String?, scanned: Boolean) {
        clear()
        icon = null
        toolTipText = null

        if (headline != null) {
            icon = AnimatedIcon.Default.INSTANCE
            append(BasedPythonBundle.message("env.summary.working", headline))
            return
        }
        if (!scanned) {
            icon = AnimatedIcon.Default.INSTANCE
            append(BasedPythonBundle.message("env.summary.scanning"), SimpleTextAttributes.GRAYED_ATTRIBUTES)
            return
        }
        val backend = status.backend ?: run {
            append(BasedPythonBundle.message("env.summary.unmanaged"), SimpleTextAttributes.GRAYED_ATTRIBUTES)
            return
        }

        val environment = status.environment
        val where = environment?.root ?: status.environmentRoot
        where?.let {
            append(shortPath(status.projectRoot, it), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            toolTipText = it.toString()
        }

        val (label, chip) = when {
            status.health == EnvHealth.TOOL_MISSING ->
                BasedPythonBundle.message("env.chip.toolMissing", backend.displayName) to EnvChip.ERROR
            environment == null -> BasedPythonBundle.message("env.chip.noEnvironment") to EnvChip.WARNING
            status.drift == EnvDrift.OUT_OF_SYNC -> BasedPythonBundle.message("env.chip.outOfSync") to EnvChip.WARNING
            status.drift == EnvDrift.IN_SYNC -> BasedPythonBundle.message("env.chip.inSync") to EnvChip.POSITIVE
            else -> BasedPythonBundle.message("env.chip.unchecked") to EnvChip.NEUTRAL
        }
        EnvChips.append(this, "● $label", chip)

        val facts = buildList {
            add(backend.displayName)
            if (environment != null) add(BasedPythonBundle.message("env.summary.installed", status.packages.size))
        }
        append("   " + facts.joinToString(" · "), SimpleTextAttributes.GRAYED_ATTRIBUTES)
    }

    private companion object {

        /**
         * [path] as the project would name it: relative to [projectRoot] when it is inside it, and
         * relative to the home directory otherwise — `.venv`, or `~/envs/thing`.
         */
        fun shortPath(projectRoot: Path?, path: Path): String {
            val relative = projectRoot?.takeIf { path.startsWith(it) && path != it }
                ?.relativize(path)
                ?.joinToString("/") { it.toString() }
            return relative ?: FileUtil.getLocationRelativeToUserHome(path.toString())
        }
    }
}
