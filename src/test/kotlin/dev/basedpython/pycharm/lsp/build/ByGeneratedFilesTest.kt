package dev.basedpython.pycharm.lsp.build

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.roots.GeneratedSourcesFilter
import com.intellij.openapi.vfs.ReadonlyStatusHandler
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.junit5.fixture.TestFixtures
import com.intellij.ui.EditorNotificationProvider
import dev.basedpython.pycharm.lsp.ext.ByBuildOutput
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * What the build wrote is generated, named back to its source, and read-only until the user says to
 * edit it — and only what `by` said the build directory is.
 */
@TestFixtures
class ByGeneratedFilesTest {

    private val fixture by codeInsightFixture()

    private lateinit var source: VirtualFile
    private lateinit var transpiled: VirtualFile
    private lateinit var copied: VirtualFile
    private lateinit var outside: VirtualFile
    private lateinit var buildDirectory: String

    @BeforeEach
    fun project() = onEdt {
        val base = project.basePath!!
        buildDirectory = "$base/build"
        WriteAction.runAndWait<RuntimeException> {
            source = VfsUtil.createDirectories("$base/src/pkg").createChildData(this, "main.by")
            transpiled = VfsUtil.createDirectories("$buildDirectory/pkg").createChildData(this, "main.py")
            copied = VfsUtil.createDirectories("$buildDirectory/pkg").createChildData(this, "helper.py")
            outside = VfsUtil.createDirectories("$base/other").createChildData(this, "main.py")
        }
        // What `by/buildOutput` answered for the project's root and for the transpiled file.
        outputs.state.paths = mutableListOf(buildDirectory)
        outputs.remember(transpiled, ByBuildOutput(projectRoot = base, buildDirectory = buildDirectory, source = source.path))
    }

    @AfterEach
    fun cleanUp() = onEdt {
        outputs.state.paths = mutableListOf()
        ByGeneratedFiles.getInstance(project).forgetEditing()
        WriteAction.runAndWait<RuntimeException> {
            for (dir in listOf("src", "build", "other")) {
                VfsUtil.findFile(java.nio.file.Path.of(project.basePath!!, dir), false)?.delete(this)
            }
        }
    }

    private val project get() = fixture.project
    private val outputs get() = ByBuildOutputs.getInstance(project)

    @Test
    fun `everything in the build directory is generated, and nothing outside it`() = onEdt {
        val filter = GeneratedSourcesFilter.EP_NAME.findExtensionOrFail(ByGeneratedSourcesFilter::class.java)

        assertTrue(filter.isGeneratedSource(transpiled, project))
        // Copied in beside the transpiled modules, and taken back by the next build all the same.
        assertTrue(filter.isGeneratedSource(copied, project))
        assertFalse(filter.isGeneratedSource(outside, project))
        assertFalse(filter.isGeneratedSource(source, project))
    }

    @Test
    fun `a project by has not answered for has nothing generated`() = onEdt {
        outputs.state.paths = mutableListOf()

        assertFalse(ByGeneratedFiles.getInstance(project).isGenerated(transpiled))
    }

    @Test
    fun `the banner names the source by said, and has a link to it`() = onEdt {
        val banner = EditorNotificationProvider.EP_NAME.getExtensions(project)
            .filterIsInstance<ByGeneratedFileBanner>().single()

        assertNotNull(banner.collectNotificationData(project, transpiled))
        // By has not said where the copy came from: it is generated, but there is no source to name.
        assertNull(banner.collectNotificationData(project, copied))
        assertNull(banner.collectNotificationData(project, outside))
        assertEquals("src/pkg/main.by", ByGeneratedFiles.getInstance(project).describe(source))
    }

    @Test
    fun `editing a generated file asks, and a no keeps it read-only`() = onEdt {
        ByGeneratedWritingAccessProvider.answering(false) {
            val status = ReadonlyStatusHandler.getInstance(project).ensureFilesWritable(listOf(transpiled))
            assertTrue(status.hasReadonlyFiles())
        }
        // A file outside the build is never asked about.
        ByGeneratedWritingAccessProvider.answering(false) {
            assertFalse(ReadonlyStatusHandler.getInstance(project).ensureFilesWritable(listOf(outside)).hasReadonlyFiles())
        }
    }

    @Test
    fun `edit anyway lets the file be edited from then on, without asking again`() = onEdt {
        ByGeneratedWritingAccessProvider.answering(true) {
            assertFalse(ReadonlyStatusHandler.getInstance(project).ensureFilesWritable(listOf(transpiled)).hasReadonlyFiles())
        }
        ByGeneratedWritingAccessProvider.answering(false) {
            assertFalse(ReadonlyStatusHandler.getInstance(project).ensureFilesWritable(listOf(transpiled)).hasReadonlyFiles())
            // Only the file the answer was about.
            assertTrue(ReadonlyStatusHandler.getInstance(project).ensureFilesWritable(listOf(copied)).hasReadonlyFiles())
        }
    }
}
