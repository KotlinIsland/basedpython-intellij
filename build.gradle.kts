import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  id("org.jetbrains.kotlin.jvm")
  id("org.jetbrains.intellij.platform")
  id("org.jetbrains.changelog")
}

// The build everything compiles and tests against: the floor itself, a build `sinceBuild` admits.
//
// Pinned, and to the bottom of the range rather than the top. It was `263.+`, so the build would
// follow the platform, and it did — out from under everyone at once: 263.5701.9 landing in the
// snapshot repository took `intellij.platform.vcs` out of the core classloader, and every checkout
// stopped compiling with nothing changed here and nothing to bisect. Compiling against the floor is
// also what holds the floor honest: an API newer than 263.5153 is a compile error here, where
// against the newest snapshot it compiled clean and nothing checked the oldest build `sinceBuild`
// lets install the plugin.
//
// Following the platform moves out of the compile: `verifyPlugin` checks the newest 263
// snapshot as well as this build (see `ides { }`), and `-PplatformVersion=263.+` — or any build —
// compiles and tests against the top, which catches what the verifier cannot.
//
// 263.5153.20 because it is the build the floor was measured on (see `sinceBuild`).
val platformVersion: String = providers.gradleProperty("platformVersion").getOrElse("263.5153.20-EAP-CANDIDATE")

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
    intellijIdea(platformVersion) { useInstaller = false }
    testFramework(TestFrameworkType.Platform)
    // The platform's JUnit 5 support: @TestApplication, @TestFixtures, @RunInEdt, projectFixture.
    // Note it does *not* publish the junit5 `codeInsightFixture`; see testFramework/CodeInsightFixtures.kt.
    testFramework(TestFrameworkType.JUnit5)

    // Bundled plugins used by features
    bundledPlugin("org.toml.lang")
    // The SM test runner (SMTRunnerConsoleProperties, TestConsoleProperties, …) left the core
    // platform in 2026.2 and now ships as this bundled plugin.
    bundledPlugin("intellij.testRunner.plugin")
    // Spellchecker ships as a platform module, not a bundled plugin.
    bundledModule("intellij.spellchecker")
    // The platform's Debug Adapter Protocol client (DebugAdapterSupportProvider, DapProcessStarter,
    // …), also a platform module rather than a bundled plugin. Powers `.by` debugging.
    bundledModule("intellij.platform.dap")
    // What the DAP client is spoken in since 263.5153: its own protocol classes
    // (`com.jetbrains.dap.protocol`), serialised with kotlinx.serialization.
    bundledModule("intellij.platform.dap.protocol")
    bundledModule("intellij.libraries.kotlinx.serialization.json")
    // The commit workflow's API (CheckinHandlerFactory, CommitCheck, CheckinProjectPanel), which
    // the before-commit cleanup is built on. Through 263.5153 the module is embedded in the core
    // classloader and so on the compile classpath unasked; 263.5701 made it a module of its own,
    // on the classpath only when named. Naming it on the floor as well means that move is not
    // one to rediscover the next time the compile target moves. Declared in plugin.xml too.
    bundledModule("intellij.platform.vcs")
    // The Problems view (ProblemsCollector, FileProblem, the Project Errors tab), which `by`'s
    // whole-project diagnostics are listed in. Content modules of the bundled Problems View plugin;
    // declared in plugin.xml too.
    bundledModule("intellij.platform.problemView.shared")
    bundledModule("intellij.platform.problemView.ui")
    // XDebugProcess, the breakpoint types, the executors. Every one of them a product module, and a
    // snapshot artifact (`useInstaller = false`) puts on the compile classpath only what is named.
    bundledModule("intellij.platform.debugger")
    // XDebuggerExpressionEditor, which the log point field is built on.
    bundledModule("intellij.platform.debugger.impl.ui")

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

kotlin {
  compilerOptions {
    // Not the default, and not in the plugin template: Kotlin 2.2+ compiles in `ENABLE` mode, and
    // this deliberately leaves it. In `ENABLE` (and `DISABLE`) mode a class that implements a Kotlin
    // interface gets a generated override of every default method it does not override itself,
    // each just calling `super` — for binary compatibility with Kotlin code compiled before default
    // methods, which nothing here has. Those stubs are real overrides and calls in the bytecode, so
    // the verifier reports them as ours: `ByLogpointUndoUnloadGuard`, which overrides only
    // `beforePluginUnload`, was reported as overriding and invoking the deprecated
    // `DynamicPluginListener.checkUnloadPlugin`, and 60 of the 355 experimental API usages were
    // such stubs. No compiler warning points at them, since the source never names the method.
    // Measured on a one-class listener against 263.5153.20: `ENABLE` and `DISABLE` generate
    // `pluginsLoaded`, `beforePluginsUnloaded` and `checkUnloadPlugin`; `NO_COMPATIBILITY`
    // generates none. It also drops the `DefaultImpls` classes of this plugin's own interfaces,
    // which only matters to Kotlin code compiled against this plugin's jar, and there is none.
    // It replaces the per-class `@JvmDefaultWithoutCompatibility` that b0c3773 put on six classes
    // for the same stubs, which the next class to implement a platform interface did not get.
    jvmDefault = JvmDefaultMode.NO_COMPATIBILITY
  }
}

// The plugin and its tests use a deprecated API only when no replacement exists across the
// supported range, and then visibly: `@Suppress("DEPRECATION")` or `@Suppress("OVERRIDE_DEPRECATION")`
// at the use, with a comment saying why. As warnings, `ReadAction.run` went in with `runBlocking`
// sitting beside it in the floor build, and the tests had gathered 167 deprecated usages, 160 of them
// `@RunInEdt`. Compiling against the floor (`platformVersion`) sees the floor's deprecations; the
// newest build's are the verifier's (see `verifyPlugin` below), for the plugin's code.
tasks.withType<KotlinCompile>().configureEach {
  compilerOptions.freeCompilerArgs.addAll(
    "-Xwarning-level=DEPRECATION:error",
    "-Xwarning-level=OVERRIDE_DEPRECATION:error",
  )
}

// --- Bundled `by` / `buff` / `bpd` binaries (FEATURES.md §58) ----------------------------------
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

// --- The descriptor's 65535-character ceiling ---------------------------------------------------
//
// `<change-notes>` and `<description>` are capped at 65535 characters, by the plugin descriptor and
// by Marketplace's upload validation alike, and going over is not a warning: the verifier rejects
// the whole artifact as INVALID_PLUGIN — "The value of the '<change-notes>' parameter is too long"
// — before it verifies a single class, and an upload is refused outright. So it fails at the last
// step of a release, having built six platform artifacts first.
//
// It is reached by doing nothing wrong. Every entry ever written here lives under one `[Unreleased]`
// heading, because nothing has been released to roll it over, and `changeNotes` renders that whole
// section; markdown-to-HTML then inflates it by about 12%. 62 KB of CHANGELOG.md became 70,093
// characters of HTML, 4,558 over.
//
// Trimming here rather than editing the changelog: the file is the record and should stay whole,
// and a cap that lives in the build cannot be overrun again by writing another entry.
val DESCRIPTOR_LIMIT = 65535

/**
 * [html] trimmed to fit [DESCRIPTOR_LIMIT], dropping whole `<li>` items off the end and closing the
 * lists they leave open.
 *
 * On a `</li>` boundary because the alternative — cutting at the character the limit falls on —
 * ships a descriptor ending mid-tag or mid-entity, which is worse than the overflow it fixes: it is
 * malformed markup that renders as garbage rather than an error anyone can act on. The newest
 * entries are at the top and survive; what goes is the oldest, which is also what a reader of a
 * release's notes is least looking for. Nothing is silently lost either — the trim says it happened
 * and links the full file.
 */
fun capToDescriptorLimit(html: String, repositoryUrl: String?): String {
  if (html.length <= DESCRIPTOR_LIMIT) return html

  val fullChangelog = repositoryUrl?.let { "$it/blob/main/CHANGELOG.md" }
  val notice = buildString {
    append("\n<p><em>Older entries trimmed to fit the plugin descriptor's ")
    append(DESCRIPTOR_LIMIT)
    append("-character limit.")
    if (fullChangelog != null) append(" <a href=\"$fullChangelog\">Full changelog</a>.")
    append("</em></p>\n")
  }

  // Every `</li>` that starts within budget; the last one is the deepest cut that still fits.
  val budget = DESCRIPTOR_LIMIT - notice.length
  val cut = html.lastIndexOf("</li>", startIndex = budget - "</li>".length)
  if (cut < 0) {
    // No list item fits at all, so there is no honest boundary to cut on and the shape of the
    // rendered notes is not what this assumes. Better to stop the build than to guess.
    throw GradleException(
      "Change notes are ${html.length} characters, over the $DESCRIPTOR_LIMIT-character descriptor " +
        "limit, and hold no <li> boundary within budget to trim at. Shorten the changelog's " +
        "latest section by hand.",
    )
  }

  val kept = html.substring(0, cut + "</li>".length)
  // Close the lists the cut left open, innermost first. Counting rather than parsing is enough:
  // the renderer emits only flat `<ul>`s, and a stray `</ul>` would be the visible failure of that
  // assumption rather than a silent one.
  val unclosed = Regex("<ul[ >]").findAll(kept).count() - Regex("</ul>").findAll(kept).count()
  return kept + "</ul>".repeat(maxOf(unclosed, 0)) + notice
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
    // Fail on things that actually break at runtime, and on internal API.
    //
    // INTERNAL_API_USAGES is here because the count is zero and has to stay there. JetBrains
    // Marketplace declined this plugin's first submission for internal API usage, and the Plugin
    // Verifier never fails on it by itself — every report, theirs and ours, says *Compatible* while
    // listing the usages — so nothing but this line stands between a single convenient import and
    // finding out from a moderator weeks later. It is a build error here instead, at the keystroke
    // that introduces it. When something genuinely has no public equivalent, the answer is an IJPL
    // issue and an entry in docs/internal-api.md, not quietly relaxing this.
    //
    // Deprecated usages fail the build too, but not through this list: DEPRECATED_API_USAGES fails
    // on any, and some are forced — `breakpointsDescription` is deprecated at the floor and abstract
    // at the top, so overriding it is a deprecated usage at one end or a missing one at the other —
    // and `-ignored-problems` does not filter deprecated usages (measured on 263.5153.20: still 4
    // with a pattern matching one). The check after `verifyPlugin` below fails on any the report
    // lists that `allowedDeprecatedUsages` does not explain. Experimental usages stay informational:
    // the DAP client and the Problems view have no non-experimental API to use instead.
    //
    // MISSING_DEPENDENCIES is deliberately *not* here, though the optional dependency that used to
    // be the reason — `org.intellij.plugins.markdown` — is gone with the fence suggester. The level
    // cannot distinguish an optional dependency legitimately absent from an IDE from a required one
    // that is missing, and little is lost by leaving it off: a missing *required* dependency takes
    // its classes with it, so it still fails as COMPATIBILITY_PROBLEMS — which is exactly how the
    // undeclared test runner showed up on 2026.2, as 19 unresolved classes rather than a note.
    failureLevel = listOf(
      VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
      VerifyPluginTask.FailureLevel.INVALID_PLUGIN,
      VerifyPluginTask.FailureLevel.INTERNAL_API_USAGES,
    )
    // The snapshot repository, not Marketplace's release feed. `recommended()` asks that feed what to
    // verify against, and it has lagged the range this plugin claims: on 2026-09-03 it listed no 263
    // build at all, so a plugin claiming 262 through 263.* was verified against IU-262.10315.69 and
    // nothing else, and 2026.3's lsp4j swap — `Diagnostic.getMessage()` returning
    // `Either<String, MarkupContent>` — reached a running IDE as a NoSuchMethodError on every
    // diagnostic rather than a red build here. On 2026-09-23 it offered IU-263.5153.40, the public
    // 2026.3 EAP: inside the range, but neither end of it. It cannot offer anything the two ends below
    // miss either — `untilBuild` holds it to 263, and the snapshot top is at least as new as any 263
    // release — and once 2026.3 shipped it named the top's own build: on 2026-09-26 `recommended()`
    // gave IU-263.5701.42 and `263.+` gave 263.5701.42-EAP, the verifier wrote both reports to
    // `pluginVerifier/IU-263.5701.42/`, and the second failed the task with a
    // FileAlreadyExistsException after all three verdicts had come back Compatible.
    //
    // So the snapshot repository supplies the 263 builds, as it did before the floor moved —
    // `defaultRepositories()` already declares it. `useInstaller = false` because
    // those are Maven artifacts rather than installers, and the verifier wants an unpacked
    // distribution, which is what the artifact is.
    //
    // Then two 263 builds, the two ends of the range as far as a repository can supply them. The
    // floor is the pinned build everything compiles against (`platformVersion`), verified so the
    // artifact is checked against the oldest IDE `sinceBuild` lets install it — nothing else checks
    // that. The top is dynamic on purpose, and is the one place in the
    // build that is: pinning it would freeze the check at whatever platform existed the day it was
    // pinned. It means a JetBrains change can turn this red without a change here — that is the
    // signal, not noise: 263.4732 to 263.5153 rewrote the DAP client and turned it red in 70 places.
    //
    // What it cannot see is a module leaving the core classloader. The verifier resolves every
    // product module as part of the core, so 263.5701 taking `intellij.platform.vcs` out of it —
    // which broke compilation outright — verifies Compatible with or without the plugin.xml
    // dependency that makes it right. That one is caught by `-PplatformVersion=263.+`, compiling
    // against the top, and by running there.
    ides {
      create(IntelliJPlatformType.IntellijIdea, platformVersion) { useInstaller = false }
      create(IntelliJPlatformType.IntellijIdea, "263.+") { useInstaller = false }

      // `-PverifyIde=<path to an IDE>` verifies against one more, a local installation. The public
      // 263 snapshots trail the internal nightlies by some weeks — 263.3889.65-EAP-CANDIDATE still
      // had the old `String getMessage()` when 263.4388 had already swapped it — so the build a
      // 2026.3 user is actually running is often one no repository can hand a CI job.
      providers.gradleProperty("verifyIde").orNull?.let { local(it) }
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
      // 263.5153, not 262. Between IU-263.4732.28 and IU-263.5153.20 `intellij.platform.dap` was
      // rewritten rather than evolved: lsp4j's `org.eclipse.lsp4j.debug` protocol became
      // `com.jetbrains.dap.protocol` (kotlinx.serialization, suspend functions), `DapCommandProcessor`
      // and `CommandScope` became `DapSessionExecutor` and `DapSessionContext`, `DapClient` and
      // `DapEventConsumer` became registered observers, and thread and frame ids became value
      // classes. None of the old classes survive and none of the new ones exist on 262, so no
      // build of the debugger links against both: against 263.5153 the 262-compiled plugin had 70
      // unresolved references. The floor went where the platform is going, 2026.3.
      sinceBuild = "263.5153"
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
    val repositoryUrl = providers.gradleProperty("pluginRepositoryUrl")
    // The release's section, looked up by the project version: the descriptor's `version` above
    // carries the bundled platform's suffix (`0.0.2-mac-arm64`), which names no section, and
    // looking that up fell back to an empty Unreleased and shipped every bundle without notes.
    val releaseVersion = project.version.toString()
    changeNotes = providers.provider {
      with(changelog) {
        renderItem(
          (getOrNull(releaseVersion) ?: getUnreleased())
            .withHeader(false)
            .withEmptySections(false),
          Changelog.OutputType.HTML,
        )
      }.let { capToDescriptorLimit(it, repositoryUrl.orNull) }
    }
  }
}

// Configure Gradle Changelog Plugin - read more: https://github.com/JetBrains/gradle-changelog-plugin
changelog {
  groups.empty()
  repositoryUrl = providers.gradleProperty("pluginRepositoryUrl")
  versionPrefix = ""
}

// Deprecated API the verifier may report, each a substring of one line of its
// `deprecated-usages.txt`, with why no replacement is usable across the supported range. The
// verifier's side of `-Xwarning-level=DEPRECATION:error` above: it also sees the newest build's
// deprecations, which compiling against the floor cannot, and bytecode the compiler generated.
// An entry that stops matching fails the build too, so the list cannot outlive its reasons.
val allowedDeprecatedUsages: Map<String, String> = mapOf(
  "DebugAdapterDescriptor.getBreakpointsDescription() : com.intellij.platform.dap.DapBreakpointsDescription is overridden in class dev.basedpython.pycharm.debug.ByDebugAdapterDescriptor"
    to "deprecated in 263.5153 in favour of the customization's DapBreakpointsSupport, and abstract " +
    "on the descriptor in 263.5701, where nothing else declares it (see ByDebugAdapter)",
)

// A task of its own, run after every `verifyPlugin`, so it can also be run alone against the
// reports a verification already wrote.
val checkDeprecatedApiUsages = tasks.register("checkDeprecatedApiUsages") {
  description = "Fails on deprecated API usages in the verifier's reports that allowedDeprecatedUsages does not explain."
  val reports = tasks.verifyPlugin.flatMap { it.verificationReportsDirectory }
  val allowed = allowedDeprecatedUsages
  doLast {
    val root = reports.get().asFile
    val usages = root.walk()
      .filter { it.name == "deprecated-usages.txt" }
      .flatMap { file ->
        val ide = file.relativeTo(root).path.substringBefore(File.separatorChar)
        file.readLines().filter { it.isNotBlank() }.map { "$ide: $it" }
      }
      .toList()
    val unexplained = usages.filter { usage -> allowed.keys.none { usage.contains(it) } }
    val stale = allowed.keys.filter { entry -> usages.none { it.contains(entry) } }
    if (unexplained.isNotEmpty() || stale.isNotEmpty()) {
      throw GradleException(
        buildString {
          if (unexplained.isNotEmpty()) {
            appendLine("Deprecated API used without a reason in allowedDeprecatedUsages (build.gradle.kts):")
            unexplained.forEach { appendLine("  $it") }
          }
          if (stale.isNotEmpty()) {
            appendLine("allowedDeprecatedUsages entries the verifier no longer reports; remove them:")
            stale.forEach { appendLine("  $it") }
          }
        },
      )
    }
  }
}

tasks {
  verifyPlugin {
    finalizedBy(checkDeprecatedApiUsages)
  }

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
    // A temp directory of the run's own, emptied before every run. A light fixture project's base
    // directory is the JVM's temp directory, and the project detector lists that base for its
    // markers: left at the machine-wide one, a `.by` or `api.lock` any other process put there —
    // a test JVM in another checkout, a crashed run of this one — made the fixture project
    // basedpython, and the tests that assert it is not failed for the state of the machine.
    val jvmTemp = temporaryDir.resolve("jvm")
    systemProperty("java.io.tmpdir", jvmTemp.absolutePath)
    doFirst {
      jvmTemp.deleteRecursively()
      jvmTemp.mkdirs()
    }
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

  // Code Provenance hashes every document a test edits, and the first hash of a JVM extracts lz4's
  // native library to the temp directory and loads it: seconds on macOS, where a freshly written
  // dylib is assessed before it may load, and more on a loaded machine. A pool thread doing that
  // outlives the edit that started it, so whichever test edited a document first failed the
  // fixture's thread-leak check for the platform's lazy initialisation. The plugin records how code
  // was written for AI analytics and nothing here touches it.
  prepareTestSandbox {
    disabledPlugins.add("com.intellij.code.provenance")
  }

  // One zip per platform, distinguishable in build/distributions and as a release asset.
  buildPlugin {
    archiveClassifier = bundledPlatform.orElse("")
  }
}
/**
 * The PyCharm `runPyCharm` downloads when none is named: the newest 2026.3 snapshot, because no
 * release is new enough for the floor. Dynamic, unlike the IDEA platform the build compiles against:
 * this only launches an IDE to look at, and the newest is the one worth looking at.
 */
val PYCHARM_VERSION = "263.+"

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
// instead of downloading one. `-PpycharmVersion=263.5153.2-EAP-CANDIDATE` picks a particular snapshot.
intellijPlatformTesting.runIde.register("runPyCharm") {
  val installed = providers.gradleProperty("pycharmPath")
  if (installed.isPresent) {
    localPath = file(installed.get())
  } else {
    type = IntelliJPlatformType.PyCharmProfessional
    version = providers.gradleProperty("pycharmVersion").orElse(PYCHARM_VERSION)
    // a snapshot is a Maven artifact rather than an installer, as the IDEA platform's is
    useInstaller = false
  }
}
