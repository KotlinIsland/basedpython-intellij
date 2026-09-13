package dev.basedpython.pycharm.env.manager

import com.intellij.icons.AllIcons
import com.intellij.openapi.util.text.HtmlBuilder
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.ColorUtil
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.render.RenderingUtil
import com.intellij.ui.speedSearch.SpeedSearchUtil
import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.basedpython.pycharm.util.BasedPythonBundle
import java.awt.Color
import java.awt.Graphics2D
import java.awt.geom.RoundRectangle2D
import javax.swing.Icon
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode

/**
 * The small rounded labels the environment window draws beside a name — `member`, `editable`, a
 * count — in one of a handful of colours, each of which means one thing everywhere it appears.
 *
 * Drawn as text fragments of the row itself rather than as components beside it, so that a row stays
 * one [SimpleColoredComponent]: speed search highlights it, the tree measures it, and nothing about
 * the platform's handling of renderers has to be re-done by hand. The rounding is the only part
 * that is not the platform's: [SimpleColoredComponent] paints a fragment's background as a square,
 * and a component that owns chips overrides that one method to call [paintBackground] instead.
 */
internal enum class EnvChip(light: Int, dark: Int) {

    /** This workspace: the root, a member, a package that is one. */
    ACCENT(0x3574F0, 0x6B9BFA),

    /** Live: an editable install, whose edits are seen without reinstalling. */
    POSITIVE(0x208A3C, 0x5FB865),

    /** Something in the manifest worth a second look, though nothing is broken. */
    WARNING(0xA46704, 0xD6AE58),

    /** The environment and the lock disagree. */
    ERROR(0xDB3B4B, 0xE55765),

    /** Not from an index: a git checkout or a named archive. */
    SPECIAL(0x834DF0, 0xA982F2),

    /** Facts without an opinion: a count, a path dependency installed as a copy. */
    NEUTRAL(0x6C707E, 0x9DA0A8),
    ;

    val color: Color = JBColor(Color(light), Color(dark))
}

internal object EnvChips {

    /** The fragment tag that marks a chip, so [paintBackground] is used for it and nothing else. */
    object Tag

    /**
     * Appends [text] to [component] as a chip, after a gap.
     *
     * [selected] swaps the chip's own colour for the selection's foreground: a blue chip on a blue
     * selected row is a chip nobody can read.
     */
    fun append(component: SimpleColoredComponent, text: String, chip: EnvChip, selected: Boolean = false) {
        component.append(GAP, SimpleTextAttributes.REGULAR_ATTRIBUTES)
        val foreground = if (selected) UIUtil.getTreeSelectionForeground(true) else chip.color
        val background = ColorUtil.withAlpha(foreground, if (selected) 0.24 else 0.15)
        val attributes = SimpleTextAttributes(
            background,
            foreground,
            null,
            SimpleTextAttributes.STYLE_SMALLER or SimpleTextAttributes.STYLE_OPAQUE,
        )
        component.append(" $text ", attributes, Tag)
    }

    /**
     * A chip's background: a pill, centred on the row.
     *
     * The font set on [g] is the chip's own, smaller one — [SimpleColoredComponent] sets it before
     * asking for a fragment's background — so the pill is sized to the text it holds.
     */
    fun paintBackground(g: Graphics2D, background: Color, x: Int, width: Int, height: Int) {
        val config = GraphicsUtil.setupAAPainting(g)
        try {
            val pill = (g.fontMetrics.height + JBUI.scale(1)).coerceAtMost(height)
            val y = (height - pill) / 2f + JBUI.scale(1)
            g.color = background
            g.fill(RoundRectangle2D.Float(x.toFloat(), y, width.toFloat(), pill.toFloat(), pill.toFloat(), pill.toFloat()))
        } finally {
            config.restore()
        }
    }

    /** Between a row's text and a chip, and between two chips. */
    private const val GAP = "  "
}

/**
 * Draws the environment tree's rows.
 *
 * Every row is one line of text and at most a few chips, and the chips carry the colour so the text
 * does not have to: a name is drawn in the ordinary foreground when it was declared here and greyed
 * when it came along for the ride, and anything else worth knowing about it — where it comes from,
 * whether it is what the lock says, whether it is installing right now — is a chip or a quiet suffix.
 */
internal class EnvTreeRenderer(
    private val installed: () -> Map<String, EnvPackage>,
    private val progress: () -> EnvProgress,
) : ColoredTreeCellRenderer() {

    /** True when a chip should be drawn in the selection's colours. See [EnvChips.append]. */
    private var onSelection = false

    override fun customizeCellRenderer(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ) {
        // The same test the tree paints its selection background by — not `isFocused()`, which asks
        // only the tree itself and so disagrees with a background painted as focused for a sibling.
        onSelection = selected && RenderingUtil.isFocused(tree)
        when (val item = (value as? DefaultMutableTreeNode)?.userObject) {
            is EnvRow.Project -> renderProject(item)
            is EnvRow.Group -> renderGroup(item)
            is EnvRow.Package -> renderPackage(item)
            is EnvRow.Flat -> renderFlat(item)
            else -> Unit
        }
        // Main text only — the first fragment, which is the name [EnvTreeRows.searchText] matches on.
        SpeedSearchUtil.applySpeedSearchHighlighting(tree, this, true, selected)
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

    private fun chip(text: String, chip: EnvChip) = EnvChips.append(this, text, chip, onSelection)

    /**
     * A project: its name, its version, what it is to the workspace, and where it lives.
     *
     * A virtual root has no name of its own — it is a manifest holding only the workspace table — so
     * it is called by its directory, which is what its author calls it too.
     */
    private fun renderProject(row: EnvRow.Project) {
        val project = row.project
        icon = projectIcon(project)
        append(
            project.name ?: project.path?.fileName?.toString() ?: BasedPythonBundle.message("env.tree.project.unnamed"),
            SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES,
        )
        project.version?.let { append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }

        when (project.role) {
            EnvProjectRole.ROOT -> chip(BasedPythonBundle.message("env.chip.root"), EnvChip.ACCENT)
            EnvProjectRole.MEMBER -> chip(BasedPythonBundle.message("env.chip.member"), EnvChip.ACCENT)
            EnvProjectRole.LOCAL -> if (project.editable) {
                chip(BasedPythonBundle.message("env.chip.editable"), EnvChip.POSITIVE)
            } else {
                chip(BasedPythonBundle.message("env.chip.path"), EnvChip.NEUTRAL)
            }
        }
        if (row.excluded) chip(BasedPythonBundle.message("env.chip.excluded"), EnvChip.WARNING)
        // `submodule` in a directory called `submodule` says its name twice and its place never.
        row.location?.takeIf { it != project.name }?.let { append("  $it", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES) }

        toolTipText = when {
            row.excluded -> BasedPythonBundle.message("env.tree.tooltip.excluded", row.location.orEmpty())
            else -> BasedPythonBundle.message(projectTooltipKey(project))
        }
    }

    /**
     * A list: its name, what kind of list it is when the name alone does not say, and how much is
     * in it.
     *
     * The kind is spelled out because `cli` the extra and `cli` the group are both legal and look
     * identical, and they are installed on entirely different occasions.
     */
    private fun renderGroup(row: EnvRow.Group) {
        val group = row.group
        icon = groupIcon(group.target)
        append(group.target.label)
        when (group.target) {
            EnvDependencyTarget.Main -> Unit
            is EnvDependencyTarget.Group ->
                append("  " + BasedPythonBundle.message("env.tree.kind.group"), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            is EnvDependencyTarget.Extra ->
                append("  " + BasedPythonBundle.message("env.tree.kind.extra"), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        }
        val count = group.packageCount()
        chip(
            if (count == 0) BasedPythonBundle.message("env.chip.empty") else count.toString(),
            EnvChip.NEUTRAL,
        )
        val differing = EnvTreeRows.differing(group, installed())
        if (differing > 0) chip(BasedPythonBundle.message("env.chip.differing", differing), EnvChip.ERROR)

        toolTipText = lines(
            BasedPythonBundle.message(targetTooltipKey(group.target)),
            BasedPythonBundle.message("env.tree.tooltip.differing", differing).takeIf { differing > 0 },
            BasedPythonBundle.message("env.tree.tooltip.readOnly").takeUnless { group.writable },
        )
    }

    /**
     * A declared requirement is drawn as ordinary text and a transitive one greyed, so the two
     * levels the user acts on differently look different without needing a legend.
     */
    private fun renderPackage(row: EnvRow.Package) {
        val node = row.node
        val here = installed()[node.name.lowercase()]
        val activity = progress().activityOf(node.name)
        // The platform's spinner, which paints its own frames — but only in a tree that opted in
        // with ANIMATION_IN_RENDERER_ALLOWED.
        icon = if (activity != null) AnimatedIcon.Default.INSTANCE else sourceIcon(node.source)

        val nameAttributes = when {
            here == null -> SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES
            row.declared -> SimpleTextAttributes.REGULAR_ATTRIBUTES
            else -> SimpleTextAttributes.GRAYED_ATTRIBUTES
        }
        append(node.name, nameAttributes)
        node.extra?.let { append("[$it]", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
        if (node.version.isNotEmpty()) append("  ${node.version}", SimpleTextAttributes.GRAYED_ATTRIBUTES)

        sourceChip(node.source)

        when {
            // Resolved to one version, a different one on disk. This is what drift looks like
            // when you point at it, and it is the row the sync banner is talking about.
            here != null && here.version.isNotEmpty() && here.version != node.version ->
                chip(BasedPythonBundle.message("env.tree.installedVersion", here.version), EnvChip.ERROR)
            // Not on disk. Ordinary for an extra or a non-default group, which is why it is
            // stated quietly rather than coloured as a problem.
            here == null ->
                append(
                    "  " + BasedPythonBundle.message("env.tree.notInstalled"),
                    SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES,
                )
        }

        if (activity != null) {
            append(
                "  " + BasedPythonBundle.message(activityKey(activity)),
                SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES,
            )
        }
        if (node.expandedElsewhere) {
            append(
                "  " + BasedPythonBundle.message("env.tree.shownAbove"),
                SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES,
            )
        }
        toolTipText = lines(
            BasedPythonBundle.message(if (row.declared) "env.tree.tooltip.declared" else "env.tree.tooltip.transitive"),
            sourceTooltip(node.source),
        )
    }

    /** A tooltip of one line per non-null entry, escaped — paths and URLs are not markup. */
    private fun lines(vararg lines: String?): String {
        val html = HtmlBuilder()
        lines.filterNotNull().forEachIndexed { i, line ->
            if (i > 0) html.br()
            html.append(line)
        }
        return html.wrapWithHtmlBody().toString()
    }

    private fun renderFlat(row: EnvRow.Flat) {
        val activity = progress().activityOf(row.pkg.name)
        icon = when {
            activity != null -> AnimatedIcon.Default.INSTANCE
            row.pkg.isEditable -> AllIcons.Nodes.Module
            else -> AllIcons.Nodes.PpLib
        }
        append(row.pkg.name)
        if (row.pkg.version.isNotEmpty()) append("  ${row.pkg.version}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        row.pkg.editableLocation?.let {
            chip(BasedPythonBundle.message("env.chip.editable"), EnvChip.POSITIVE)
            append("  $it", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        }
    }

    /**
     * Where a package comes from, as a chip — or nothing, for an index, which is where almost
     * everything comes from and so is the one source not worth a mark.
     */
    private fun sourceChip(source: EnvSource) {
        when (source) {
            EnvSource.Index -> Unit
            EnvSource.Member -> chip(BasedPythonBundle.message("env.chip.workspace"), EnvChip.ACCENT)
            is EnvSource.Local -> if (source.editable) {
                chip(BasedPythonBundle.message("env.chip.editable"), EnvChip.POSITIVE)
            } else {
                chip(BasedPythonBundle.message("env.chip.path"), EnvChip.NEUTRAL)
            }
            is EnvSource.Git -> chip(BasedPythonBundle.message("env.chip.git"), EnvChip.SPECIAL)
            is EnvSource.Archive -> chip(BasedPythonBundle.message("env.chip.archive"), EnvChip.SPECIAL)
        }
    }

    private fun sourceTooltip(source: EnvSource): String? = when (source) {
        EnvSource.Index -> null
        EnvSource.Member -> BasedPythonBundle.message("env.tree.tooltip.source.member")
        is EnvSource.Local -> BasedPythonBundle.message(
            if (source.editable) "env.tree.tooltip.source.editable" else "env.tree.tooltip.source.copy",
            source.path.toString(),
        )
        is EnvSource.Git -> BasedPythonBundle.message("env.tree.tooltip.source.git", source.url)
        is EnvSource.Archive -> BasedPythonBundle.message("env.tree.tooltip.source.archive", source.location)
    }

    private fun sourceIcon(source: EnvSource): Icon = when (source) {
        EnvSource.Index -> AllIcons.Nodes.PpLib
        EnvSource.Member -> AllIcons.Nodes.Module
        is EnvSource.Local -> if (source.editable) AllIcons.Nodes.Symlink else AllIcons.Nodes.CopyOfFolder
        is EnvSource.Git -> AllIcons.Vcs.Branch
        is EnvSource.Archive -> AllIcons.FileTypes.Archive
    }

    private fun projectIcon(project: EnvProject): Icon = when (project.role) {
        EnvProjectRole.ROOT, EnvProjectRole.MEMBER -> AllIcons.Nodes.Module
        EnvProjectRole.LOCAL -> if (project.editable) AllIcons.Nodes.Symlink else AllIcons.Nodes.CopyOfFolder
    }

    private fun projectTooltipKey(project: EnvProject): String = when (project.role) {
        EnvProjectRole.ROOT -> "env.tree.tooltip.project.root"
        EnvProjectRole.MEMBER -> "env.tree.tooltip.project.member"
        EnvProjectRole.LOCAL ->
            if (project.editable) "env.tree.tooltip.project.editable" else "env.tree.tooltip.project.copy"
    }

    private fun activityKey(activity: EnvPackageActivity): String = when (activity) {
        EnvPackageActivity.DOWNLOADING -> "env.activity.downloading"
        EnvPackageActivity.PREPARING -> "env.activity.preparing"
        EnvPackageActivity.REMOVING -> "env.activity.removing"
    }

    private fun groupIcon(target: EnvDependencyTarget): Icon = when (target) {
        EnvDependencyTarget.Main -> AllIcons.Nodes.PpLibFolder
        is EnvDependencyTarget.Extra -> AllIcons.Nodes.Plugin
        is EnvDependencyTarget.Group -> AllIcons.Nodes.ConfigFolder
    }

    private fun targetTooltipKey(target: EnvDependencyTarget): String = when (target) {
        EnvDependencyTarget.Main -> "env.tree.tooltip.main"
        is EnvDependencyTarget.Extra -> "env.tree.tooltip.extra"
        is EnvDependencyTarget.Group -> "env.tree.tooltip.group"
    }
}
