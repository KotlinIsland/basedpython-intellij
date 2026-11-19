package dev.basedpython.pycharm.lsp.counterparts

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import java.util.concurrent.TimeUnit

/** [body] as a highlighting pass or Go to Related Symbol runs it: off the EDT, in a read action. */
internal fun <T> offEdt(body: () -> T): T =
    ApplicationManager.getApplication().executeOnPooledThread<T> {
        ReadAction.computeBlocking<T, RuntimeException> { body() }
    }.get(10, TimeUnit.SECONDS)
