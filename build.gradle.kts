import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask

plugins {
  id("org.jetbrains.kotlin.jvm")
  id("org.jetbrains.intellij.platform")
  id("org.jetbrains.changelog")
}

dependencies {
  testImplementation(platform("org.junit:junit-bom:5.14.2"))
  testImplementation("org.junit.jupiter:junit-jupiter")
  // Gradle needs the launcher on the test runtime classpath to drive the JUnit Platform.
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
  // The platform's own test bootstrap (com.intellij.tests.JUnit5TestSessionListener, auto-registered
  // as a LauncherSessionListener) dereferences junit.framework.TestCase in its constructor, so the
  // old JUnit jar has to be present at runtime or no test process starts at all. Runtime-only on
  // purpose: it is off the compile classpath, and with no vintage engine here a JUnit 3/4 test can
  // neither be written nor discovered.
  testRuntimeOnly("junit:junit:4.13.2")

  // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
  intellijPlatform {
    intellijIdea("2026.2")
    testFramework(TestFrameworkType.Platform)
    // The platform's JUnit 5 support: @TestApplication, @TestFixtures, @RunInEdt, projectFixture.
    // Note it does *not* publish the junit5 `codeInsightFixture`; see testFramework/CodeInsightFixtures.kt.
    testFramework(TestFrameworkType.JUnit5)

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
    // The platform's Debug Adapter Protocol client (DebugAdapterSupportProvider, DapProcessStarter,
    // …), also a platform module rather than a bundled plugin. Powers `.by` debugging.
    bundledModule("intellij.platform.dap")

    // `./gradlew runIde -PideAgent` — puts MCP Steroid in the sandbox, which exposes the running
    // IDE over a local MCP server: execute Kotlin inside its JVM, screenshot windows, send real
    // input. It is how the tool windows and other UI here get verified in a live IDE rather than
    // argued about, and it screenshots from inside the JVM, so it needs no macOS screen-recording
    // permission. On connecting: the IDE writes ~/.mcp-steroid/markers/<pid>.mcp-steroid with the
    // MCP URL and a bearer token for that run.
    //
    // Opt-in because it is a ~180 MB download (it ships a Kotlin compiler) that also opens a local
    // port on every launch, and an ordinary build or `runIde` should do neither. It never reaches
    // the shipped plugin: nothing in plugin.xml depends on it, so it exists only in the sandbox.
    //
    // `hasProperty` rather than `providers.gradleProperty(…).isPresent`, which the rest of this
    // file uses: a bare `-PideAgent` carries an empty value, and that provider reports an empty
    // value as *absent*, so the way the flag is naturally typed would silently do nothing.
    if (project.hasProperty("ideAgent")) {
      plugin("com.jonnyzzz.mcp-steroid", "0.102.0-r-c68d8f15d")
    }
  }
}

// --- Bundled `by` / `buff` binaries (FEATURES.md §58) ------------------------------------------
//
// `-PbundledBinariesDir=<dir>` copies that directory into `<plugin>/bin` in the sandbox and in the
// distribution zip, so the plugin ships a working toolchain and needs neither a venv nor a download
// to run. `-PbundledPlatform=<slug>` records which platform those binaries are for (a slug from
// `ByBinaryDownloadPlan.Platform`, e.g. `mac-arm64`); it names the artifact and is written into
// `bin/platform.txt`, which `BundledBinaries` reads to refuse binaries that cannot exec here.
//
// Opt-in, and the default build stays exactly as it was: the binaries are ~200 MB each, so this is
// one zip per platform produced by CI (.github/workflows/bundled-distributions.yml), not something
// a local `buildPlugin` should ever pull in. Both properties use `isPresent` rather than
// `hasProperty` because both are meaningless without a value.
val bundledBinariesDir = providers.gradleProperty("bundledBinariesDir")
val bundledPlatform = providers.gradleProperty("bundledPlatform")

// The platform modules each slug's artifact is gated on, which is what makes these Marketplace
// *versions* of one plugin rather than six downloads: Marketplace reads these `<depends>` and
// serves each IDE the artifact matching its OS and CPU. The names are built by the platform as
// `com.intellij.modules.os.` + IdeaPluginOsRequirement.name.lowercase() and
// `com.intellij.modules.arch.` + PluginCpuArchRequirement.name.lowercase() (verified against
// intellij.platform.core.jar in IU-2026.2), so they are fixed spellings, not guesses.
//
// Needs an IDE of build 261+ to resolve the arch modules, which this plugin already requires. The
// mechanism is undocumented and self-described as experimental (JetBrains' own
// jreznot/native-versions-showcase; MP-1896 is still open), so expect it to need revisiting.
val bundledPlatformModules = mapOf(
  "mac-arm64" to listOf("com.intellij.modules.os.mac", "com.intellij.modules.arch.arm64"),
  "mac-x64" to listOf("com.intellij.modules.os.mac", "com.intellij.modules.arch.x86_64"),
  "linux-x64" to listOf("com.intellij.modules.os.linux", "com.intellij.modules.arch.x86_64"),
  "linux-arm64" to listOf("com.intellij.modules.os.linux", "com.intellij.modules.arch.arm64"),
  "windows-x64" to listOf("com.intellij.modules.os.windows", "com.intellij.modules.arch.x86_64"),
  "windows-arm64" to listOf("com.intellij.modules.os.windows", "com.intellij.modules.arch.arm64"),
)

/** The two modules to gate on, failing on a slug that is not a real target rather than shipping it ungated. */
fun gatingModules(slug: String): List<String> = bundledPlatformModules[slug]
  ?: throw GradleException(
    "Unknown -PbundledPlatform=$slug. Expected one of ${bundledPlatformModules.keys.sorted()} " +
      "(the ByBinaryDownloadPlan.Platform slugs).",
  )

// A separate task rather than a `doLast` on prepareSandbox: the marker is an *input file* to the
// sandbox copy, and generating it inside the copy task would write into a directory Sync has
// already synchronised.
val writeBundledPlatformMarker = tasks.register("writeBundledPlatformMarker") {
  description = "Records the platform slug the bundled by/buff binaries were built for."
  val marker = layout.buildDirectory.file("bundled/platform.txt")
  val slug = bundledPlatform
  inputs.property("platform", slug)
  outputs.file(marker)
  onlyIf { slug.isPresent }
  doLast {
    marker.get().asFile.apply {
      parentFile.mkdirs()
      writeText(slug.get().trim() + "\n")
    }
  }
}

// Configure IntelliJ Platform Gradle Plugin - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-extension.html
intellijPlatform {
  publishing {
    // Marketplace personal access token. From the environment only — never a Gradle property, which
    // would end up in a properties file or a shell history.
    //
    // A bundled release is six `publishPlugin` runs, one per `-PbundledPlatform`, each uploading its
    // own version. There is no batch upload: the task takes a single archive, and the Marketplace
    // upload API takes a single file (no OS/arch parameter — the gating lives in the manifest).
    token = providers.environmentVariable("PUBLISH_TOKEN")
  }

  // No `signing { }` block on purpose: the plugin already defaults every signing property to the
  // environment (`CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD`), and `signPlugin` runs
  // itself before `publishPlugin` when they are set and skips when they are not. Restating that
  // here would only be one more place for the two to disagree. Unsigned is publishable — the IDE
  // shows the user a warning dialog on install, which is the reason to set the secrets.

  pluginVerification {
    // Fail on things that actually break at runtime. Deprecated/experimental/internal usages stay
    // informational: observing LSP server state needs `LspServerManagerListener`, which is marked
    // internal but has no public equivalent, and both the reloader and the status widget need it.
    //
    // MISSING_DEPENDENCIES is deliberately *not* here. The plugin depends optionally on
    // `intellij.testRunner.plugin`, which exists in 2026.2 and not in 2026.1 — and being absent on
    // one of two supported IDEs is the entire meaning of an optional dependency, not a defect. The
    // verifier counts it all the same, and the level cannot distinguish optional from required.
    // Little is lost: a missing *required* dependency takes its classes with it, so it still fails
    // the build as COMPATIBILITY_PROBLEMS — that is exactly how the undeclared test runner showed
    // up on 2026.2, as 19 unresolved classes rather than as a dependency note.
    failureLevel = listOf(
      VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
      VerifyPluginTask.FailureLevel.INVALID_PLUGIN,
    )
    ides {
      recommended()
    }
  }

  pluginConfiguration {
    // Marketplace keys updates by version, so the six per-platform artifacts of one release have to
    // carry six distinct versions — the slug suffix is what makes them distinct. Routing itself is
    // by the gating modules below, not by this string.
    bundledPlatform.orNull?.let { slug ->
      gatingModules(slug) // rejects a slug that is not a real target before anything is built
      version = "${project.version}-$slug"
    }

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
  test {
    useJUnitPlatform()
    // The platform's test framework ships TestLoggerExtension/TestLoggerInterceptor as
    // auto-registered extensions (META-INF/services). They are what turns a logged error into a
    // test failure — the behaviour UsefulTestCase gave the JUnit 3 tests for free, and which
    // BasedPythonLogTest asserts against. JUnit 5 only picks them up with autodetection on.
    systemProperty("junit.jupiter.extensions.autodetection.enabled", "true")
    // Without this the test JVM dies with a SIGABRT on the AppKit thread about a second in, before
    // a single test runs — on macOS 26.5 with the bundled JBR 25, and on any test, including ones
    // that touch no UI at all. Nothing here needs a window server: the Swing the tests do build
    // (combo boxes, the test-node panel) is built, queried and thrown away, never shown.
    systemProperty("java.awt.headless", "true")
  }

  // No `publishPlugin { dependsOn(patchChangelog) }`. It reads like housekeeping and is not: a
  // release is six parallel `publishPlugin` runs, so it would rewrite CHANGELOG.md six times on six
  // runners and discard all six, while `changeNotes` reads the *unpatched* file perfectly well
  // (`getOrNull(version) ?: getUnreleased()`). The rollover happens once, after every upload has
  // landed, in the changelog job of .github/workflows/bundled-distributions.yml.

  // Gate a bundled artifact to the OS/arch it actually holds binaries for. Marketplace reads these
  // to route; the IDE reads them too, so a `by` built for another machine cannot even be installed.
  //
  // Appended to patchPluginXml's *output* — the file the jar is built from — rather than to the
  // source plugin.xml, so the six artifacts differ only in the manifest the build writes and the
  // checked-in descriptor stays platform-neutral.
  patchPluginXml {
    val modules = bundledPlatform.orNull?.let(::gatingModules) ?: return@patchPluginXml
    val output = outputFile
    doLast {
      val file = output.get().asFile
      val text = file.readText()
      // After the last existing <depends>, where a reader looking for dependencies will find them.
      val anchor = text.lastIndexOf("</depends>")
      if (anchor < 0) {
        // Never silently: an ungated artifact is one Marketplace would hand to every machine.
        throw GradleException("No <depends> element in ${file.absolutePath} to anchor the OS/arch gating to")
      }
      val at = anchor + "</depends>".length
      val gating = modules.joinToString("") { "\n    <depends>$it</depends>" }
      file.writeText(text.substring(0, at) + gating + text.substring(at))
    }
  }

  // `buildPlugin` zips prepareSandbox's plugin directory, so everything added here reaches both the
  // sandbox (`runIde`) and the distribution.
  prepareSandbox {
    if (bundledBinariesDir.isPresent) {
      // Must stay in step with BundledBinaries.BIN_DIR, which is where the plugin looks at runtime.
      val binDir = "${pluginName.get()}/bin"
      from(bundledBinariesDir) {
        into(binDir)
        // Gradle's Zip does store unix modes, but the IDE's plugin installer does not restore
        // them, so this is only the sandbox's benefit; BundledBinaries re-chmods on first use.
        filePermissions { unix("0755") }
      }
      // Only when a platform was named. Without the guard an unmarked build would still pick up
      // the marker file a *previous* bundled build left in the build directory, and stamp one
      // platform's slug onto another platform's binaries.
      if (bundledPlatform.isPresent) {
        from(writeBundledPlatformMarker) {
          into(binDir)
        }
      }
    }
  }

  // One zip per platform, distinguishable in build/distributions and as a release asset.
  buildPlugin {
    archiveClassifier = bundledPlatform.orElse("")
  }
}
/** The PyCharm `runPyCharm` downloads when none is named. Kept beside the IDEA version it mirrors. */
val PYCHARM_VERSION = "2026.2.1"

// --- Running the plugin in PyCharm -------------------------------------------------------------
//
// `./gradlew runPyCharm` — the same sandbox launch as `runIde`, in PyCharm Professional instead of
// IntelliJ IDEA, in a sandbox of its own so the two do not share settings.
//
// Worth a task rather than a note in the README, because the two IDEs disagree about this plugin in
// ways only a launch shows, and the disagreements are invisible from the IDEA side. PyCharm ships
// none of IntelliJ IDEA's logpoints modules — they are bundled with its Java plugin, and
// `intellij.debugger.logpoints.backend` is built on `intellij.java.debugger.impl` — so the gutter
// gap, the inline "Log:" field, *Add Log Point* and log point undo are this plugin's own code there
// and the IDE's own everywhere else. Only one of those two paths runs in any given IDE, so testing
// in IDEA exercises neither the code this plugin ships for PyCharm nor the arbitration between them.
//
// `-PpycharmPath=/Applications/PyCharm.app` launches a PyCharm already installed — a nightly, say —
// instead of downloading one. `-PpycharmVersion=2026.3` picks a different published build.
intellijPlatformTesting.runIde.register("runPyCharm") {
  val installed = providers.gradleProperty("pycharmPath")
  if (installed.isPresent) {
    localPath = file(installed.get())
  } else {
    type = IntelliJPlatformType.PyCharmProfessional
    version = providers.gradleProperty("pycharmVersion").orElse(PYCHARM_VERSION)
  }
}
