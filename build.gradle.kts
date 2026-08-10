import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask

plugins {
  id("org.jetbrains.kotlin.jvm")
  id("org.jetbrains.intellij.platform")
  id("org.jetbrains.changelog")
}

dependencies {
  testImplementation("junit:junit:4.13.2")

  // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
  intellijPlatform {
    intellijIdea("2026.2")
    testFramework(TestFrameworkType.Platform)

    // Bundled plugins used by features (present in IDEA/PyCharm 2026.1+)
    bundledPlugin("org.toml.lang")
    // Optional at runtime (see the optional <depends> in plugin.xml); needed to compile the
    // code-fence language suggester.
    bundledPlugin("org.intellij.plugins.markdown")
    // The SM test runner (SMTRunnerConsoleProperties, TestConsoleProperties, …) left the core
    // platform in 2026.2 and now ships as this bundled plugin.
    bundledPlugin("intellij.testRunner.plugin")
    // Spellchecker ships as a platform module, not a bundled plugin.
    bundledModule("intellij.spellchecker")
  }
}

// Configure IntelliJ Platform Gradle Plugin - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-extension.html
intellijPlatform {
  pluginVerification {
    // Fail on things that actually break at runtime. Deprecated/experimental/internal usages stay
    // informational: observing LSP server state needs `LspServerManagerListener`, which is marked
    // internal but has no public equivalent, and both the reloader and the status widget need it.
    failureLevel = listOf(
      VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
      VerifyPluginTask.FailureLevel.MISSING_DEPENDENCIES,
      VerifyPluginTask.FailureLevel.INVALID_PLUGIN,
    )
    ides {
      recommended()
    }
  }

  pluginConfiguration {
    ideaVersion {
      sinceBuild = "261"
      untilBuild = "263.*"
    }

    // Extract the <!-- Plugin description --> section from README.md and provide for the plugin's manifest
    description = providers.fileContents(layout.projectDirectory.file("README.md")).asText.map {
      val start = "<!-- Plugin description -->"
      val end = "<!-- Plugin description end -->"

      with(it.lines()) {
        if (!containsAll(listOf(start, end))) {
          throw GradleException("Plugin description section not found in README.md:\n$start ... $end")
        }
        subList(indexOf(start) + 1, indexOf(end)).joinToString("\n").let(::markdownToHTML)
      }
    }

    val changelog = project.changelog // local variable for configuration cache compatibility
    // Get the latest available change notes from the changelog file
    changeNotes = version.map { pluginVersion ->
      with(changelog) {
        renderItem(
          (getOrNull(pluginVersion) ?: getUnreleased())
            .withHeader(false)
            .withEmptySections(false),
          Changelog.OutputType.HTML,
        )
      }
    }
  }
}

// Configure Gradle Changelog Plugin - read more: https://github.com/JetBrains/gradle-changelog-plugin
changelog {
  groups.empty()
  repositoryUrl = providers.gradleProperty("pluginRepositoryUrl")
  versionPrefix = ""
}

tasks {
  publishPlugin {
    dependsOn(patchChangelog)
  }
}