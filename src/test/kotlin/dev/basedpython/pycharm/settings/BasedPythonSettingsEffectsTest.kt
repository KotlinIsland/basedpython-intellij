package dev.basedpython.pycharm.settings

import com.intellij.openapi.fileTypes.FileTypeEvent
import com.intellij.openapi.fileTypes.FileTypeListener
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.roots.ModuleRootEvent
import com.intellij.openapi.roots.ModuleRootListener
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lang.dialect.PyFileHandling
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Settings the IDE caches an answer to — who owns `.py`, what is indexed — are announced wherever
 * they change. *Import Settings* used to copy them in and say nothing, so open `.py` files kept their
 * type and `out/` stayed indexed or excluded until the project was reopened.
 */
@TestFixtures
class BasedPythonSettingsEffectsTest {

  private val fixture by codeInsightFixture()

  private val settings get() = BasedPythonSettings.getInstance(fixture.project)

  private var fileTypeChanges = 0
  private var rootChanges = 0

  private fun announceAfter(change: () -> Unit) {
    val disposable = Disposer.newDisposable()
    try {
      fixture.project.messageBus.connect(disposable).apply {
        subscribe(FileTypeManager.TOPIC, object : FileTypeListener {
          override fun fileTypesChanged(event: FileTypeEvent) { fileTypeChanges++ }
        })
        subscribe(ModuleRootListener.TOPIC, object : ModuleRootListener {
          override fun rootsChanged(event: ModuleRootEvent) { rootChanges++ }
        })
      }
      val before = BasedPythonSettingsEffects.snapshot(settings)
      change()
      BasedPythonSettingsEffects.announce(fixture.project, before)
      repeat(20) { PlatformTestUtil.dispatchAllEventsInIdeEventQueue() }
    } finally {
      Disposer.dispose(disposable)
    }
  }

  @AfterEach
  fun reset() = onEdt {
    settings.loadState(BasedPythonSettings.State())
  }

  @Test
  fun `changing who owns py retypes files`() = onEdt {
    announceAfter { settings.pyFileHandling = PyFileHandling.NEVER }
    assertEquals(true, fileTypeChanges > 0)
  }

  @Test
  fun `changing whether generated python is indexed rescans roots`() = onEdt {
    announceAfter { settings.indexGeneratedPython = !settings.indexGeneratedPython }
    assertEquals(true, rootChanges > 0)
  }

  @Test
  fun `changing nothing announces nothing`() = onEdt {
    announceAfter { settings.fixAllOnSave = !settings.fixAllOnSave }
    assertEquals(0, fileTypeChanges)
    assertEquals(0, rootChanges)
  }
}
