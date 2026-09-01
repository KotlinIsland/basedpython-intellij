package dev.basedpython.pycharm.testFramework

/**
 * Waits out the platform's own reaction to a document a test edited.
 *
 * The platform hashes changed content on a shared coroutine dispatcher, and the first hash of a JVM
 * run also loads a native xxhash library there. The fixture's thread-leak check runs immediately
 * after each test and fails on any pool thread still RUNNABLE, so whichever test edits a document
 * first can fail for the platform's lazy initialisation rather than for anything it asserted. It is
 * a race, and one that unrelated work tips over: adding a second tool window to `plugin.xml` was
 * enough to make it land inside one test's window every time, and a test that asserts and ends the
 * moment after its edit loses it every time.
 *
 * Waiting for the coroutine's own name to leave the worker thread is what makes that deterministic
 * without touching the leak check itself. Call it from `@AfterEach` in any test that edits a
 * document.
 */
fun letContentHashingFinish() {
    val deadline = System.currentTimeMillis() + HASH_TIMEOUT_MILLIS
    while (System.currentTimeMillis() < deadline && isHashing()) {
        Thread.sleep(POLL_MILLIS)
    }
}

/** True while a pool thread is running the platform's content-hashing coroutine. */
private fun isHashing(): Boolean = Thread.getAllStackTraces().keys.any {
    it.isAlive && it.state == Thread.State.RUNNABLE && it.name.contains(HASHING_COROUTINE)
}

/** The coroutine whose name a worker thread carries while it hashes changed content. */
private const val HASHING_COROUTINE = "ProvenanceEvents"

/** Long enough for a native library load on a cold, loaded machine; short of a hung build. */
private const val HASH_TIMEOUT_MILLIS = 30_000L
private const val POLL_MILLIS = 20L
