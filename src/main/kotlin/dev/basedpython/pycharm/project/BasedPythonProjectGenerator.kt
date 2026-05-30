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
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import javax.swing.Icon

/**
 * New-project generator for BasedPython projects.
 *
 * Shown in the "New Project" wizard under the generator list.
 * Scaffolds: pyproject.toml, src/main.by, .gitignore, README.md.
 *
 * Registration in plugin.xml (Stream O integration):
 *
 *   <generatorNewProjectWizard
 *       implementation="dev.basedpython.pycharm.project.BasedPythonProjectGenerator"/>
 */
class BasedPythonProjectGenerator : GeneratorNewProjectWizard {

    override val id: String = "BasedPython"

    override val name: String = "basedpython"

    override val icon: Icon =
        IconLoader.getIcon("/icons/basedpython.svg", BasedPythonProjectGenerator::class.java)

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

    // -------------------------------------------------------------------
    // Scaffolding helpers
    // -------------------------------------------------------------------

    private fun scaffoldProject(baseDir: VirtualFile, projectName: String) {
        write(baseDir, "pyproject.toml", pyprojectToml(projectName))

        val srcDir = VfsUtil.createDirectoryIfMissing(baseDir, "src")
        if (srcDir != null) {
            write(srcDir, "main.by", mainByContent())
        }

        write(baseDir, ".gitignore", gitignoreContent())
        write(baseDir, "README.md", readmeContent(projectName))
    }

    private fun write(dir: VirtualFile, name: String, content: String) {
        var file = dir.findChild(name)
        if (file == null) {
            file = dir.createChildData(this, name)
        }
        VfsUtil.saveText(file, content)
    }

    private fun pyprojectToml(projectName: String): String = """
[project]
name = "$projectName"
version = "0.1.0"
description = ""
requires-python = ">=3.10"
dependencies = []

[build-system]
requires = ["hatchling"]
build-backend = "hatchling.build"

[tool.uv.dev-dependencies]
dev = ["basedpython"]

[tool.ruff]
line-length = 88
target-version = "py310"
select = ["E", "F", "W", "I"]
ignore = []
quote-style = "double"

[tool.ruff.format]
quote-style = "double"
indent-style = "space"
""".trimIndent()

    private fun mainByContent(): String = """
# basedpython hello-world
# Demonstrates data class syntax (a basedpython extension over Python)

data class Point:
    x: float
    y: float

    def distance_to_origin(self) -> float:
        return (self.x ** 2 + self.y ** 2) ** 0.5


def greet(name: str) -> str:
    return f"Hello, {name}!"


if __name__ == "__main__":
    p = Point(x=3.0, y=4.0)
    print(greet("world"))
    print(f"Distance from origin: {p.distance_to_origin()}")
""".trimIndent()

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
""".trimIndent()

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
""".trimIndent()
}
