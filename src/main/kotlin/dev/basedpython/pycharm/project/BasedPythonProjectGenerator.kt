package dev.basedpython.pycharm.project

import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.ide.wizard.AbstractNewProjectWizardStep
import com.intellij.ide.wizard.GeneratorNewProjectWizard
import com.intellij.ide.wizard.NewProjectWizardBaseStep
import com.intellij.ide.wizard.NewProjectWizardChainStep
import com.intellij.ide.wizard.NewProjectWizardStep
import com.intellij.ide.wizard.RootNewProjectWizardStep
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.project.Project
import dev.basedpython.pycharm.BasedPythonIcons
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import javax.swing.Icon

/**
 * New-project generator for basedpython projects.
 *
 * Shown in the "New Project" wizard under the generator list.
 * Scaffolds what [BasedPythonProjectScaffold] lists: pyproject.toml, src/main.by, .gitignore,
 * README.md.
 *
 * Registration in plugin.xml (Stream O integration):
 *
 *   <generatorNewProjectWizard
 *       implementation="dev.basedpython.pycharm.project.BasedPythonProjectGenerator"/>
 */
class BasedPythonProjectGenerator : GeneratorNewProjectWizard {

    override val id: String = "basedpython"

    override val name: String = "basedpython"

    override val icon: Icon =
        BasedPythonIcons.Logo

    override fun createStep(context: WizardContext): NewProjectWizardStep {
        val root = RootNewProjectWizardStep(context)
        val base = NewProjectWizardBaseStep(root).also { it.defaultName = "my-basedpython-project" }
        return NewProjectWizardChainStep(base).nextStep { ScaffoldStep(it) }
    }

    // -------------------------------------------------------------------
    // Scaffold step — runs after the base name/location step
    // -------------------------------------------------------------------

    private inner class ScaffoldStep(parent: NewProjectWizardBaseStep) :
        AbstractNewProjectWizardStep(parent) {

        override fun setupProject(project: Project) {
            val basePath = project.basePath ?: return
            val baseDir = VfsUtil.findFileByIoFile(java.io.File(basePath), true) ?: return
            WriteAction.runAndWait<Throwable> {
                scaffoldProject(baseDir, project.name)
            }
        }
    }

    private fun scaffoldProject(baseDir: VirtualFile, projectName: String) {
        for ((relativePath, content) in BasedPythonProjectScaffold.files(projectName)) {
            val subdirectory = relativePath.substringBeforeLast('/', "")
            val dir = if (subdirectory.isEmpty()) baseDir
            else VfsUtil.createDirectoryIfMissing(baseDir, subdirectory) ?: continue
            val name = relativePath.substringAfterLast('/')
            val file = dir.findChild(name) ?: dir.createChildData(this, name)
            VfsUtil.saveText(file, content)
        }
    }
}

/**
 * The files a new basedpython project starts with, by path relative to the project root.
 *
 * Every one of them has to be accepted as-is by the tools the README tells the user to run next:
 * `uv sync` reads the pyproject strictly, and `buff check` / `buff format --check` refuse a
 * configuration with an unknown key and report a scaffold that is not laid out the way they want —
 * so a new project that failed either would greet the user with an error they did not cause.
 * `BasedPythonProjectScaffoldLiveTest` runs them.
 */
internal object BasedPythonProjectScaffold {

    fun files(projectName: String): Map<String, String> = linkedMapOf(
        "pyproject.toml" to pyprojectToml(projectName),
        "src/main.by" to mainByContent(),
        ".gitignore" to gitignoreContent(),
        "README.md" to readmeContent(projectName),
    )

    // No `[build-system]`: this is an application, and a build backend would make `uv sync` try to
    // build and install it as a package — which fails, because `src/main.by` is not a package.
    //
    // `[dependency-groups]` is the standard (PEP 735) table uv reads development dependencies from;
    // `[tool.uv.dev-dependencies]` is a *list* under uv's own table, and uv rejects it as a table.
    //
    // Lint options sit under `[tool.ruff.lint]` and format options under `[tool.ruff.format]`:
    // `buff` rejects `quote-style` at the top level as an unknown field.
    private fun pyprojectToml(projectName: String): String = """
[project]
name = "$projectName"
version = "0.1.0"
description = ""
requires-python = ">=3.10"
dependencies = []

[dependency-groups]
dev = ["basedpython"]

[tool.ruff]
line-length = 88
target-version = "py310"

[tool.ruff.lint]
select = ["E", "F", "W", "I"]
ignore = []

[tool.ruff.format]
quote-style = "double"
indent-style = "space"
""".trimStart()

    private fun mainByContent(): String = """
# basedpython hello-world
# Demonstrates data class syntax (a basedpython extension over Python)


data class Point:
    x: float
    y: float

    def distance_to_origin(self) -> float:
        return (self.x**2 + self.y**2) ** 0.5


def greet(name: str) -> str:
    return f"Hello, {name}!"


if __name__ == "__main__":
    p = Point(x=3.0, y=4.0)
    print(greet("world"))
    print(f"Distance from origin: {p.distance_to_origin()}")
""".trimStart()

    private fun gitignoreContent(): String = """
# Python
__pycache__/
*.py[cod]
*.pyo
*.pyd
*.so

# Virtual environment
.venv/
venv/
env/

# basedpython build output
out/

# Distribution / packaging
dist/
build/
*.egg-info/

# IDE
.idea/
.vscode/

# uv lock
.uv/
""".trimStart()

    private fun readmeContent(projectName: String): String = """
# $projectName

A [basedpython](https://github.com/KotlinIsland/basedpython) project.

## Getting started

```bash
# Install dependencies (including basedpython)
uv sync --dev

# Type-check with by
by check

# Transpile .by -> .py
by build

# Format with buff
buff format .
```

## Project structure

```
$projectName/
├── src/
│   └── main.by        # basedpython source files
├── out/               # Transpiled Python (generated, excluded from indexing)
├── pyproject.toml     # Project config + [tool.ruff] config
└── .gitignore
```

## Config

Edit `[tool.ruff]` in `pyproject.toml` to configure lint/format rules.
""".trimStart()
}
