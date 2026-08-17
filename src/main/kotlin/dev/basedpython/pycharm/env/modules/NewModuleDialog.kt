package dev.basedpython.pycharm.env.modules

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.UIUtil
import dev.basedpython.pycharm.env.manager.EnvOp
import dev.basedpython.pycharm.env.manager.EnvService
import dev.basedpython.pycharm.util.BasedPythonBundle
import java.nio.file.Files
import javax.swing.JComponent
import javax.swing.event.DocumentEvent

/**
 * *New module*: a name, where it goes, what shape it has, and — optionally — which existing module
 * should start depending on it.
 *
 * ### The command is on screen
 *
 * The dialog shows the `uv init` it is about to run, and updates it as the fields change. This is
 * the same choice the rest of the plugin makes about uv (the environment view streams uv's output
 * into the log rather than reporting "done"): the tool is doing the work, the user can run the same
 * command themselves, and a wizard that hides which flags it picked is a wizard whose result cannot
 * be predicted or reproduced.
 *
 * ### What it does not ask
 *
 * Anything uv would decide better. The interpreter is left to the project's own `requires-python`,
 * the build backend to uv's default, the authors to git — every one of those is a field that could
 * be added and that would then have to be kept in step with uv's own defaults for ever.
 */
internal class NewModuleDialog(
    private val project: Project,
    private val layout: ModuleLayout,
) : DialogWrapper(project) {

    private val nameField = JBTextField(24)

    /**
     * Where the module goes, seeded from the name and left alone once it has been typed in.
     *
     * The seeding is the whole reason this is a path field rather than a directory picker: a module
     * is nearly always `<the directory the others are in>/<its name>`, and typing the name has
     * therefore already answered this. [locationEdited] is what stops the seeding from overwriting
     * an answer the user gave.
     */
    private val locationField = JBTextField(24)

    private var locationEdited = false

    private val kindBox = ComboBox(ModuleKind.entries.toTypedArray()).apply {
        renderer = SimpleListCellRenderer.create("") { BasedPythonBundle.message(kindKey(it)) }
        selectedItem = ModuleKind.LIBRARY
    }

    private val descriptionField = JBTextField(24)

    /** Which module should depend on the new one; the first entry is "none". */
    private val dependentBox = ComboBox(
        (listOf(null) + layout.all.map { it.name }).toTypedArray(),
    ).apply {
        renderer = SimpleListCellRenderer.create("") {
            it ?: BasedPythonBundle.message("modules.new.dependent.none")
        }
    }

    /** The command, kept current as the fields change. */
    private val commandLabel = JBLabel().apply {
        componentStyle = UIUtil.ComponentStyle.SMALL
        foreground = UIUtil.getContextHelpForeground()
    }

    init {
        title = BasedPythonBundle.message("modules.new.title")
        setOKButtonText(BasedPythonBundle.message("modules.new.ok"))
        locationField.text = defaultParent()
        nameField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                if (!locationEdited) locationField.text = suggestedLocation()
                updateCommand()
            }
        })
        locationField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                // Only a change the *user* made counts; the seeding above sets the same document.
                if (locationField.hasFocus()) locationEdited = true
                updateCommand()
            }
        })
        kindBox.addActionListener { updateCommand() }
        descriptionField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = updateCommand()
        })
        init()
        updateCommand()
    }

    override fun getPreferredFocusedComponent(): JComponent = nameField

    override fun createCenterPanel(): JComponent = panel {
        row(BasedPythonBundle.message("modules.new.name")) {
            cell(nameField).align(AlignX.FILL)
        }.rowComment(BasedPythonBundle.message("modules.new.name.hint"))

        row(BasedPythonBundle.message("modules.new.location")) {
            cell(locationField).align(AlignX.FILL)
        }.rowComment(BasedPythonBundle.message("modules.new.location.hint"))

        row(BasedPythonBundle.message("modules.new.kind")) {
            cell(kindBox).align(AlignX.FILL)
        }.rowComment(BasedPythonBundle.message("modules.new.kind.hint"))

        row(BasedPythonBundle.message("modules.new.description")) {
            cell(descriptionField).align(AlignX.FILL)
        }

        row(BasedPythonBundle.message("modules.new.dependent")) {
            cell(dependentBox).align(AlignX.FILL)
        }.rowComment(BasedPythonBundle.message("modules.new.dependent.hint"))

        row {
            cell(commandLabel).align(AlignX.FILL)
        }
    }

    /**
     * The request, or null when the dialog was cancelled.
     *
     * Must be called on the EDT — it shows the dialog.
     */
    fun ask(): ModuleOperations.NewModule? {
        if (!showAndGet()) return null
        return ModuleOperations.NewModule(
            name = nameField.text.trim(),
            path = path(),
            kind = kindBox.selectedItem as? ModuleKind ?: ModuleKind.LIBRARY,
            description = descriptionField.text.trim().takeIf { it.isNotEmpty() },
            dependents = listOfNotNull(dependentBox.selectedItem as? String),
        )
    }

    override fun doValidate(): ValidationInfo? {
        val name = nameField.text.trim()
        if (name.isEmpty()) return ValidationInfo(BasedPythonBundle.message("modules.new.error.noName"), nameField)
        if (!ModuleNames.isValid(name)) {
            return ValidationInfo(BasedPythonBundle.message("modules.new.error.badName"), nameField)
        }
        if (layout.byName(name) != null) {
            return ValidationInfo(BasedPythonBundle.message("modules.new.error.nameTaken", name), nameField)
        }

        val path = path()
        if (path.isEmpty()) {
            return ValidationInfo(BasedPythonBundle.message("modules.new.error.noLocation"), locationField)
        }
        // `..` would put the module outside the project, where uv would not find it and the plugin
        // would be scaffolding into a directory the user did not open.
        if (path.split('/').any { it == ".." } || path.startsWith("/") || path.contains(':')) {
            return ValidationInfo(BasedPythonBundle.message("modules.new.error.badLocation"), locationField)
        }
        val root = EnvService.getInstance(project).status.projectRoot
        val directory = root?.resolve(path.replace('/', java.io.File.separatorChar))
        if (directory != null && Files.exists(directory.resolve(UvWorkspace.MANIFEST))) {
            return ValidationInfo(BasedPythonBundle.message("modules.new.error.exists", path), locationField)
        }
        if (layout.all.any { it.relativePath == path }) {
            return ValidationInfo(BasedPythonBundle.message("modules.new.error.exists", path), locationField)
        }
        return null
    }

    // ---- the fields talking to each other ----------------------------------

    /** The location as a path relative to the project root, `/`-separated and tidied. */
    private fun path(): String = UvWorkspace.normalizePattern(locationField.text.replace('\\', '/'))

    /**
     * Where the modules that already exist live, or `packages` when there are none.
     *
     * Read from the project rather than fixed, because a workspace that keeps its modules in `libs`
     * should offer `libs`. The common parent only counts when every member agrees on it — modules
     * scattered across two directories have no convention to follow.
     */
    private fun defaultParent(): String {
        val parents = layout.members
            .mapNotNull { it.relativePath.substringBeforeLast('/', "").takeIf { parent -> parent.isNotEmpty() } }
            .distinct()
        return parents.singleOrNull() ?: DEFAULT_PARENT
    }

    private fun suggestedLocation(): String {
        val name = nameField.text.trim()
        val parent = defaultParent()
        return if (name.isEmpty()) parent else "$parent/${ModuleNames.normalize(name)}"
    }

    /** Renders the command the OK button will run, so that what it does is not a surprise. */
    private fun updateCommand() {
        val backend = EnvService.getInstance(project).status.backend
        val name = nameField.text.trim()
        if (name.isEmpty()) {
            // A space rather than nothing: an empty label has no height, and the dialog would resize
            // itself the moment the first character is typed.
            commandLabel.text = " "
            return
        }
        val command = backend?.command(
            EnvOp.InitModule(
                path = path(),
                name = name,
                kind = kindBox.selectedItem as? ModuleKind ?: ModuleKind.LIBRARY,
                description = descriptionField.text.trim().takeIf { it.isNotEmpty() },
            ),
        )
        commandLabel.text = command?.describe(backend.executableName).orEmpty()
    }

    private fun kindKey(kind: ModuleKind): String = when (kind) {
        ModuleKind.LIBRARY -> "modules.kind.library"
        ModuleKind.APPLICATION -> "modules.kind.application"
        ModuleKind.PACKAGED_APPLICATION -> "modules.kind.packagedApplication"
        ModuleKind.BARE -> "modules.kind.bare"
    }

    private companion object {
        /** uv's own convention, and the one its documentation's workspace example uses. */
        const val DEFAULT_PARENT = "packages"
    }
}
