package dev.basedpython.pycharm.run.coverage

import com.intellij.coverage.BaseCoverageSuite
import com.intellij.coverage.CoverageEngine
import com.intellij.coverage.CoverageFileProvider
import com.intellij.coverage.CoverageLoadErrorReporter
import com.intellij.coverage.CoverageLoadingResult
import com.intellij.coverage.CoverageRunner
import com.intellij.coverage.CoverageSuite
import com.intellij.coverage.FailedCoverageLoadingResult
import com.intellij.coverage.SuccessCoverageLoadingResult
import com.intellij.coverage.lcov.LcovSerializationUtils
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.coverage.CoverageEnabledConfiguration
import com.intellij.openapi.project.Project
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Loads the LCOV report `by run --coverage` writes.
 *
 * Every file in it is already named the way the project is written — a transpiled module by its
 * `.by`, a carried-over `.py` by the file in the project rather than the copy that ran — so the
 * paths are taken as they are.
 */
class ByCoverageRunner : CoverageRunner() {

    override fun loadCoverageData(
        sessionDataFile: Path,
        baseCoverageSuite: CoverageSuite?,
        reporter: CoverageLoadErrorReporter,
    ): CoverageLoadingResult {
        if (!Files.isRegularFile(sessionDataFile)) {
            // `by run` writes no report when the program never returned to it, or when the
            // interpreter has no coverage.py — and says which in the run's console
            return FailedCoverageLoadingResult("`by run` wrote no coverage report; see the run's output")
        }
        return try {
            val report = LcovSerializationUtils.readLCOVFromPaths(listOf(sessionDataFile))
            SuccessCoverageLoadingResult(LcovSerializationUtils.convertToProjectData(report) { it })
        } catch (e: IOException) {
            FailedCoverageLoadingResult(e, true)
        }
    }

    override fun getPresentableName(): String = "basedpython"

    override fun getId(): String = ID

    override fun getDataFileExtension(): String = "lcov"

    override fun acceptsCoverageEngine(engine: CoverageEngine): Boolean = engine is ByCoverageEngine

    internal companion object {
        const val ID = "basedpython"
    }
}

class ByCoverageSuite : BaseCoverageSuite {
    /** For the platform, which reads a suite back from the project's settings. */
    constructor() : super()

    constructor(
        name: String,
        project: Project,
        runner: CoverageRunner,
        fileProvider: CoverageFileProvider,
        timestamp: Long,
    ) : super(name, project, runner, fileProvider, timestamp)

    override fun getCoverageEngine(): CoverageEngine = CoverageEngine.EP_NAME.findExtensionOrFail(ByCoverageEngine::class.java)
}

class ByCoverageEnabledConfiguration(configuration: RunConfigurationBase<*>) :
    CoverageEnabledConfiguration(configuration, CoverageRunner.getInstance(ByCoverageRunner::class.java))
