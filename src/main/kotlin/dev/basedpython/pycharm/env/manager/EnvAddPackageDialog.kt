package dev.basedpython.pycharm.env.manager

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.PlainPrefixMatcher
import com.intellij.codeInsight.completion.PrefixMatcher
import com.intellij.codeInsight.lookup.CharFilter
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.util.ProgressIndicatorUtils
import com.intellij.ui.JBColor
import com.intellij.ui.TextFieldWithAutoCompletion
import com.intellij.ui.TextFieldWithAutoCompletionListProvider
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.Alarm
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.basedpython.pycharm.env.manager.index.PackageDetails
import dev.basedpython.pycharm.env.manager.index.PackageIndex
import dev.basedpython.pycharm.env.manager.index.PackageIndexCache
import dev.basedpython.pycharm.env.manager.index.PackageNameStore
import dev.basedpython.pycharm.util.BasedPythonBundle
import java.awt.FlowLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * "Add package": a requirement line, which of the project's dependency lists it joins, and — once
 * the index has been asked — what the package actually is and which of its extras to turn on.
 *
 * ### Free text first
 *
 * The field takes whatever the tool accepts and passes it through untouched: `httpx`,
 * `httpx>=0.27`, `httpx[http2]`, a git URL, a local path. That is deliberate and comes before every
 * convenience layered on top of it — completion and extras are help, not a gate, and everything here
 * still works with the index unreachable, the catalogue not yet downloaded, or the package private.
 *
 * ### What the index adds
 *
 * The package's summary, newest version and declared extras. Extras are the part that could not be
 * discovered any other way: nothing short of reading a project's documentation tells you that
 * `httpx` offers `http2`, so they arrive as checkboxes and write themselves back into the
 * requirement.
 *
 * Everything the index provides is read from [PackageIndexCache] first and fetched only when it is
 * missing, so a second Add in the same week costs nothing.
 */
internal class EnvAddPackageDialog(
    project: Project,
    private val initialTarget: EnvDependencyTarget,
    existingTargets: List<EnvDependencyTarget>,
    private val index: PackageIndex? = null,
    /** The environment's interpreter, which is what `requires_python` is judged against. */
    private val pythonVersion: String? = null,
) : DialogWrapper(project) {

    /** What the dialog asks for. */
    data class Request(val requirements: List<String>, val target: EnvDependencyTarget)

    /**
     * The requirement, with the catalogue behind it.
     *
     * A completion-capable editor rather than a plain field, so the 872,009-name catalogue is
     * reachable as a dropdown while you type. It stays free text: completion offers, it does not
     * constrain, and a name the index has never heard of is typed and added exactly as before.
     *
     * This constructor already asks for the autopopup, so typing opens the list without a
     * Ctrl+Space. What it cannot do is have an answer before the catalogue exists — see
     * [CatalogueCompletion].
     */
    private val field: TextFieldWithAutoCompletion<String> =
        TextFieldWithAutoCompletion(project, CatalogueCompletion(), false, "")

    /**
     * The lists a requirement can join, as text.
     *
     * Text rather than [EnvDependencyTarget] because this combo is editable — a new group can be
     * typed — and an editable combo renders its selected value through its *editor*, which calls
     * `toString()`, not through any renderer set on it. A typed model therefore put `Group(name=dev)`
     * on screen. [EnvTargetLabels] owns the mapping in both directions.
     */
    private val targetBox = ComboBox(EnvTargetLabels.options(existingTargets, initialTarget).toTypedArray()).apply {
        isEditable = true
        selectedItem = EnvTargetLabels.format(initialTarget)
    }

    /** Which release to pin, or the row that pins nothing. */
    private val versionBox = ComboBox(arrayOf(BasedPythonBundle.message("env.version.any"))).apply {
        isEnabled = false
    }

    /** The rows [versionBox] is currently showing, so a selection can be read back to a version. */
    private var versionChoices: List<EnvVersionChoices.Choice> = emptyList()

    /** Guards the field-rewrite that selecting a version performs against re-entering the lookup. */
    private var updatingField = false

    private val summaryLabel = JBLabel().apply {
        componentStyle = UIUtil.ComponentStyle.SMALL
        foreground = JBColor.GRAY
    }

    /** The extras row, rebuilt whenever the package under the caret changes. */
    private val extrasPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0))

    private val extrasLabel = JBLabel(BasedPythonBundle.message("env.add.extras"))

    /** Which extras are ticked, so the state survives the panel being rebuilt. */
    private val selectedExtras = linkedSetOf<String>()

    /** The package the row currently describes, so typing inside one name is not re-looked-up. */
    private var describedPackage: String? = null

    /**
     * Debounces the lookup.
     *
     * A name is typed one character at a time and every prefix of it is a package somebody has
     * published, so a lookup per keystroke would be a request per keystroke for answers nobody
     * wanted.
     */
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, disposable)

    init {
        title = BasedPythonBundle.message("env.add.title")
        setOKButtonText(BasedPythonBundle.message("env.add.ok"))
        field.addDocumentListener(object : com.intellij.openapi.editor.event.DocumentListener {
            override fun documentChanged(event: com.intellij.openapi.editor.event.DocumentEvent) {
                if (!updatingField) scheduleLookup()
            }
        })
        versionBox.addActionListener { if (!updatingField) applyVersionToField() }
        init()
        warmCatalogue()
        renderDetails(null)
    }

    override fun createCenterPanel(): JComponent = FormBuilder.createFormBuilder()
        .addLabeledComponent(BasedPythonBundle.message("env.add.label"), field)
        .addComponentToRightColumn(
            JBLabel(BasedPythonBundle.message("env.add.hint")).apply {
                componentStyle = UIUtil.ComponentStyle.SMALL
                foreground = JBColor.GRAY
            },
        )
        .addComponentToRightColumn(summaryLabel)
        .addLabeledComponent(BasedPythonBundle.message("env.add.version"), versionBox)
        .addLabeledComponent(
            extrasLabel,
            JBScrollPane(extrasPanel).apply {
                border = JBUI.Borders.empty()
                preferredSize = JBUI.size(360, 56)
            },
        )
        .addLabeledComponent(BasedPythonBundle.message("env.add.target"), targetBox)
        .addComponentToRightColumn(
            JBLabel(BasedPythonBundle.message("env.add.target.hint")).apply {
                componentStyle = UIUtil.ComponentStyle.SMALL
                foreground = JBColor.GRAY
            },
        )
        .panel
        .apply { border = JBUI.Borders.empty(8) }

    override fun getPreferredFocusedComponent(): JComponent = field

    override fun doValidate(): ValidationInfo? = when {
        EnvRequirements.split(field.text).isEmpty() ->
            ValidationInfo(BasedPythonBundle.message("env.add.empty"), field)
        selectedTarget() == null ->
            ValidationInfo(BasedPythonBundle.message("env.add.target.empty"), targetBox)
        else -> null
    }

    /** Shows the dialog; null when the user cancelled. Must be called on the EDT. */
    fun ask(): Request? {
        if (!showAndGet()) return null
        return Request(EnvRequirements.split(field.text), selectedTarget() ?: initialTarget)
    }

    /** The chosen list, whether it was picked from the list or typed. */
    private fun selectedTarget(): EnvDependencyTarget? =
        EnvTargetLabels.parse(targetBox.selectedItem?.toString().orEmpty())

    // ---- the index -----------------------------------------------------------

    /**
     * Downloads the catalogue if it is missing or a week old.
     *
     * Here rather than on project open, because opening this dialog is a user gesture and opening a
     * project is not — the same rule the rest of this feature follows. Silent either way: the field
     * is fully usable without it.
     */
    private fun warmCatalogue() {
        val index = index ?: return
        // Starts the download and returns immediately; completion awaits the same future if the user
        // types before it lands.
        PackageIndexCache.getInstance().refreshCatalogue(index)
    }

    private fun scheduleLookup() {
        alarm.cancelAllRequests()
        alarm.addRequest({ lookup() }, LOOKUP_DELAY_MILLIS)
    }

    /**
     * Looks up whatever package the field currently names.
     *
     * Only the *last* requirement on the line, since that is the one being typed. A cached answer is
     * applied synchronously so a package looked up before does not flicker; only a genuine miss goes
     * to the network.
     */
    private fun lookup() {
        val index = index ?: return
        val name = EnvRequirements.split(field.text).lastOrNull()
            ?.let { EnvRequirements.packageName(it) }
        if (name == null) {
            describedPackage = null
            renderDetails(null)
            return
        }
        if (name.equals(describedPackage, ignoreCase = true)) return
        describedPackage = name

        val cache = PackageIndexCache.getInstance()
        cache.cachedDetailsFor(index, name)?.let {
            renderDetails(it)
            return
        }

        summaryLabel.text = BasedPythonBundle.message("env.add.lookingUp", name)
        rebuildVersions(null)
        val modality = ModalityState.stateForComponent(field)
        ApplicationManager.getApplication().executeOnPooledThread {
            val details = cache.detailsFor(index, name)
            ApplicationManager.getApplication().invokeLater({
                // The user may have typed on while this was in flight.
                if (name.equals(describedPackage, ignoreCase = true)) {
                    renderDetails(details ?: PackageDetails.unknown(name))
                }
            }, modality)
        }
    }

    /**
     * Puts what the index said on screen.
     *
     * A package it has never heard of gets a quiet note rather than an error: the name may be
     * private, brand new, or simply half-typed, and none of those should stop the user adding it.
     */
    private fun renderDetails(details: PackageDetails?) {
        summaryLabel.text = when {
            details == null -> " "
            details.latestVersion == null && details.summary == null ->
                BasedPythonBundle.message("env.add.unknownPackage", details.name)
            else -> listOfNotNull(
                details.latestVersion?.let { BasedPythonBundle.message("env.add.latest", it) },
                details.summary,
            ).joinToString(" · ")
        }
        rebuildExtras(details?.extras.orEmpty())
        rebuildVersions(details)
    }

    /**
     * Rebuilds the extras checkboxes.
     *
     * Ticks carry across a rebuild only for extras the new package also declares, so switching from
     * `httpx` to `requests` does not silently keep an `http2` that means nothing there.
     */
    private fun rebuildExtras(extras: List<String>) {
        selectedExtras.retainAll(extras.toSet())
        extrasPanel.removeAll()
        val any = extras.isNotEmpty()
        extrasLabel.isVisible = any
        extrasPanel.isVisible = any

        for (extra in extras) {
            val box = JBCheckBox(extra, selectedExtras.contains(extra))
            box.addActionListener {
                if (box.isSelected) selectedExtras.add(extra) else selectedExtras.remove(extra)
                applyExtrasToField()
            }
            extrasPanel.add(box)
        }
        extrasPanel.revalidate()
        extrasPanel.repaint()
    }

    /**
     * Writes the ticked extras back into the requirement being typed.
     *
     * Rewrites only the last requirement on the line and leaves everything else as typed — a version
     * specifier especially. This edits the field, which fires the document listener; the
     * [describedPackage] check is what stops that becoming a loop, since the name has not changed.
     */
    private fun applyExtrasToField() {
        val requirements = EnvRequirements.split(field.text).toMutableList()
        if (requirements.isEmpty()) return
        requirements[requirements.lastIndex] =
            EnvRequirements.withExtras(requirements.last(), selectedExtras)
        setFieldText(requirements.joinToString(" "))
    }

    /**
     * Offers catalogue names as you type.
     *
     * Queried straight off the sorted catalogue file — a prefix lookup is about twenty seeks — so
     * this can answer on the completion thread without anything cached in memory. An index that has
     * not been downloaded, or a project with none, simply offers nothing and the field stays plain
     * free text.
     */
    private inner class CatalogueCompletion :
        TextFieldWithAutoCompletionListProvider<String>(emptyList()) {

        override fun getLookupString(item: String): String = item

        /**
         * Strict prefix matching — every result starts with what was typed.
         *
         * Two defaults have to be turned off to get that. The platform's camel-hump matcher treats
         * the query as a *subsequence*, so `ba` matches `b-aws-dynamodb-backup`; and
         * `PlainPrefixMatcher`'s one-argument constructor is not a prefix matcher at all, it is
         * `containsIgnoreCase`, which matches that same name on the `ba` in `backup`. Only the
         * two-argument form asks for a genuine start match.
         *
         * Right for class names, where `NPE` should find `NullPointerException`; wrong for a package
         * index, where someone is typing the beginning of a name they half-remember.
         */
        override fun createPrefixMatcher(prefix: String): PrefixMatcher =
            PlainPrefixMatcher(prefix, /* prefixMatchesOnly = */ true)

        /**
         * Re-queries the catalogue on every keystroke instead of filtering the previous answer.
         *
         * The catalogue is 872,009 names and a query returns at most [PackageNameStore.MAX_RESULTS]
         * of them, which makes the result for `b` **not** a superset of the result for `ba` — the
         * first fifty names starting with `b` are all `b-…`, and none of them starts with `ba`.
         * Without this the platform kept that first set and narrowed it client-side, so typing `ba`
         * showed leftovers from `b` that happened to fuzzy-match, and the names actually starting
         * with `ba` never appeared at all.
         */
        override fun applyPrefixMatcher(
            result: CompletionResultSet,
            prefix: String,
        ): CompletionResultSet {
            result.restartCompletionOnAnyPrefixChange()
            return super.applyPrefixMatcher(result, prefix)
        }

        /**
         * Whether typing [c] should open, or keep open, the completion popup.
         *
         * **This method is the autopopup.** `TextCompletionContributor.invokeAutoPopup` decides
         * whether a keystroke opens the list with
         * `CharFilter.Result.ADD_TO_PREFIX == provider.acceptChar(c)`, and
         * `TextFieldWithAutoCompletionListProvider.acceptChar` returns *null* by default — so a
         * provider that does not override this never autopopups at all, and its completion is
         * reachable only by an explicit Ctrl+Space. That was the entire reason the list appeared to
         * need triggering by hand.
         *
         * Characters that end a name hide the popup rather than merely not extending it: once a
         * specifier, an extra or a marker has begun, the catalogue has nothing left to say.
         */
        override fun acceptChar(c: Char): CharFilter.Result? =
            if (EnvRequirements.continuesPackageName(c)) {
                CharFilter.Result.ADD_TO_PREFIX
            } else {
                CharFilter.Result.HIDE_LOOKUP
            }

        /** Said in the lookup while the catalogue is still arriving, so an empty list is explained. */
        override fun getAdvertisement(): String? {
            val index = index ?: return null
            return if (PackageIndexCache.getInstance().isRefreshing(index)) {
                BasedPythonBundle.message("env.add.downloadingCatalogue")
            } else {
                null
            }
        }

        override fun getItems(
            prefix: String?,
            cached: Boolean,
            parameters: CompletionParameters?,
        ): Collection<String> {
            val index = index ?: return emptyList()
            // Complete the package name only. Once a specifier or an extra has been typed there is
            // nothing left for the catalogue to say, and offering names inside `>=0.27` is noise.
            val typed = prefix.orEmpty().substringAfterLast(' ')
            val name = EnvRequirements.packageName(typed) ?: return emptyList()
            if (name != typed) return emptyList()

            val cache = PackageIndexCache.getInstance()
            // The pass that may wait. The platform runs this one on a pooled thread under a
            // cancellable indicator — see TextFieldWithAutoCompletionListProvider.addNonCachedItems —
            // which is exactly the place a completion is allowed to block, and typing another
            // character cancels it. Waiting here is what turns the first Add on a machine from "No
            // suggestions" into "the list appears once the download lands"; without it the answer
            // was empty and wrong, and looked like a broken feature rather than a 9.5 MB download.
            if (!cached && !cache.isCatalogueFresh(index)) {
                awaitCatalogue(cache, index)
            }
            return cache.names(index).startingWith(name)
        }

        /** Waits for the in-flight catalogue, giving up the moment the completion is cancelled. */
        private fun awaitCatalogue(cache: PackageIndexCache, index: PackageIndex) {
            try {
                ProgressIndicatorUtils.awaitWithCheckCanceled(cache.refreshCatalogue(index))
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (_: Exception) {
                // A download that failed leaves no catalogue, which the empty list already conveys.
            }
        }
    }

    // ---- the version picker --------------------------------------------------

    /**
     * Rebuilds the version rows for [details], keeping the pin already typed if it is still offered.
     *
     * Disabled outright when the index said nothing: an empty picker that looks operable is worse
     * than one that plainly is not.
     */
    private fun rebuildVersions(details: PackageDetails?) {
        val choices = EnvVersionChoices.of(details?.releases.orEmpty(), pythonVersion)
        versionChoices = choices
        val pinned = EnvRequirements.split(field.text).lastOrNull()
            ?.let { EnvRequirements.pinnedVersion(it) }

        updatingField = true
        try {
            versionBox.removeAllItems()
            choices.forEach { versionBox.addItem(it.label) }
            versionBox.selectedItem = EnvVersionChoices.select(choices, pinned).label
            versionBox.isEnabled = choices.size > 1
        } finally {
            updatingField = false
        }
    }

    /**
     * Writes the chosen version into the requirement being typed.
     *
     * Rewrites only the last requirement on the line, and preserves the extras already in it — the
     * two pickers edit different parts of the same string and must not undo each other.
     */
    private fun applyVersionToField() {
        val selected = versionBox.selectedItem?.toString() ?: return
        val choice = versionChoices.firstOrNull { it.label == selected } ?: return
        val requirements = EnvRequirements.split(field.text).toMutableList()
        if (requirements.isEmpty()) return
        requirements[requirements.lastIndex] =
            EnvRequirements.withVersion(requirements.last(), choice.version)
        setFieldText(requirements.joinToString(" "))
    }

    /**
     * Replaces the field's text without re-triggering the lookup.
     *
     * Both pickers write back into the field, and both do so in response to the user changing a
     * control rather than the text — so the resulting document change must not be read as "the user
     * typed a new package" and start the whole cycle again.
     */
    private fun setFieldText(text: String) {
        if (field.text == text) return
        updatingField = true
        try {
            field.text = text
        } finally {
            updatingField = false
        }
    }

    private companion object {
        /** Long enough that typing a name is one lookup, short enough to feel immediate. */
        const val LOOKUP_DELAY_MILLIS = 400
    }
}
