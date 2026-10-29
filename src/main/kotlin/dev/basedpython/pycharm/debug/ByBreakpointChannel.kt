package dev.basedpython.pycharm.debug

import com.intellij.icons.AllIcons
import com.intellij.openapi.diagnostic.Logger
import com.intellij.platform.dap.DapDebugSession
import com.intellij.platform.dap.DapSessionStoppedException
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.breakpoints.XBreakpoint
import dev.basedpython.pycharm.debug.bpd.ByDebugBackend
import dev.basedpython.pycharm.util.BasedPythonBundle
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What one session's breakpoints say that DAP's own requests cannot: a rule per exception class.
 * bpd's own channel for it — a field of the `launch`, and `bpd/setExceptionClasses` after it (bpd's
 * `docs/development/dap.md`, *a rule per class has a way in of its own*).
 *
 * The platform's DAP client owns `setExceptionBreakpoints`: it sends it with `filters` and never
 * `exceptionOptions`, before the program runs. So the rules go beside it, keyed by their class, so
 * that what the platform sends again does not undo them.
 *
 * ## nothing here races the platform
 *
 * The handler registers every breakpoint when the session initializes its breakpoints, before the
 * adapter is reached, and that is all this holds until the `launch`: the launch's arguments are read
 * from it ([launchArguments]), which puts the rules in force before the program runs a line. Once the
 * platform has the launch's answer — its session state leaves `Connecting` — the set is sent again
 * whole ([started]), which covers anything changed between the two and is how the launch's own rules
 * are given their verdicts, since a launch answers none. Every change after that is the whole set
 * again, sent at once.
 *
 * ## an older bpd, and debugpy
 *
 * A bpd without the request refuses it by name, and ignores the launch field. A refusal is the
 * feature being absent: nothing more is sent, and a class breakpoint says the adapter's sentence
 * where its verdict would be. debugpy has no such rule, and is sent nothing.
 */
internal class ByBreakpointChannel(
    private val dapSession: DapDebugSession,
    private val session: XDebugSession,
    private val backend: ByDebugBackend?,
) {

    /** One class's rule, exactly bpd's `ExceptionClass`. */
    data class ClassRule(val name: String, val raised: Boolean, val uncaught: Boolean, val libraries: Boolean)

    private val lock = Any()
    private val classes = LinkedHashMap<XBreakpoint<ByExceptionBreakpointProperties>, ClassRule>()
    private var started = false

    /** What a bpd that refused the request said; nothing is sent after it. */
    private var classesRefused: String? = null

    /** Whether some rule's class was not in the program when bpd last answered. */
    @Volatile
    private var pending = false

    private val sends = backend == ByDebugBackend.BPD

    fun setClass(breakpoint: XBreakpoint<ByExceptionBreakpointProperties>, rule: ClassRule?) {
        if (!sends) {
            if (rule != null) {
                session.updateBreakpointPresentation(breakpoint, AllIcons.Debugger.Db_invalid_breakpoint, BasedPythonBundle.message("debug.breakpoint.exception.class.debugpy"))
            }
            return
        }
        val (changed, refused) = synchronized(lock) {
            val before = if (rule == null) classes.remove(breakpoint) else classes.put(breakpoint, rule)
            (before != rule && started) to classesRefused
        }
        if (refused != null && rule != null) {
            session.updateBreakpointPresentation(breakpoint, AllIcons.Debugger.Db_invalid_breakpoint, refused)
        } else if (changed) {
            sendClasses()
        }
    }

    /** The `launch` field: every rule the handler holds now. Empty for debugpy, which reads none. */
    fun launchArguments(): Map<String, Any> {
        if (!sends) return emptyMap()
        return synchronized(lock) {
            mapOf(EXCEPTION_CLASSES to classes.values.distinct().map(::ruleMap))
        }
    }

    /** The platform has the launch's answer: from here on every change is sent, starting with all of it. */
    fun started() {
        if (!sends) return
        synchronized(lock) { started = true }
        sendClasses()
    }

    /**
     * At a stop: a rule whose class the program did not have yet is asked about again, since bpd reads
     * whether it has the class without importing anything, and only an answer says it now does.
     */
    fun paused() {
        if (pending) sendClasses()
    }

    private fun sendClasses() {
        if (!sends) return
        dapSession.post {
            val asked = synchronized(lock) { classes.toList().takeIf { classesRefused == null } } ?: return@post
            val rules = asked.map { it.second }.distinct()
            val arguments = buildJsonObject {
                put("classes", buildJsonArray { rules.forEach { add(json(ruleMap(it))) } })
            }
            val answer = try {
                send(ByDapRequests.setExceptionClasses, arguments)
            } catch (e: CancellationException) {
                throw e
            } catch (_: DapSessionStoppedException) {
                return@post
            } catch (e: Exception) {
                val refusal = ByDapRequests.refusalOf(e) ?: return@post LOG.warn("bpd/setExceptionClasses failed", e)
                synchronized(lock) { classesRefused = refusal }
                for ((breakpoint, _) in asked) {
                    session.updateBreakpointPresentation(breakpoint, AllIcons.Debugger.Db_invalid_breakpoint, refusal)
                }
                return@post
            }
            val verdicts = ByClassVerdict.read(answer, rules)
            pending = verdicts.values.any { it is ByClassVerdict.Pending }
            for ((breakpoint, rule) in asked) {
                val verdict = verdicts[rule] ?: continue
                // a rule that is held keeps the breakpoint's own icon — it decides the moment the class
                // exists — and says why it has not yet; one that will not decide is drawn invalid
                val icon = if (verdict is ByClassVerdict.Failed) AllIcons.Debugger.Db_invalid_breakpoint else null
                session.updateBreakpointPresentation(breakpoint, icon, verdict.message)
            }
        }
    }

    companion object {
        private val LOG = Logger.getInstance(ByBreakpointChannel::class.java)

        const val EXCEPTION_CLASSES: String = "exceptionClasses"

        fun ruleMap(rule: ClassRule): Map<String, Any> =
            mapOf("name" to rule.name, "raised" to rule.raised, "uncaught" to rule.uncaught, "libraries" to rule.libraries)

        private fun json(map: Map<String, Any>): JsonObject = buildJsonObject {
            for ((key, value) in map) when (value) {
                is String -> put(key, value)
                is Boolean -> put(key, value)
                else -> error("$key is not a launch field's kind: $value")
            }
        }
    }
}

/** What bpd answered for one rule of `bpd/setExceptionClasses`. */
internal sealed interface ByClassVerdict {
    val message: String?

    data object Verified : ByClassVerdict {
        override val message: String? get() = null
    }

    /** The program does not have the class yet; the rule is held and decides the moment it does. */
    data class Pending(override val message: String?) : ByClassVerdict

    /** The rule will not decide as asked: an alias, not an exception class, or shadowed. */
    data class Failed(override val message: String?) : ByClassVerdict

    companion object {
        /**
         * Each rule's verdict, matched by position — bpd answers one entry per rule in the order asked —
         * and checked by name. An answer that does not read as one is no verdict at all.
         */
        fun read(answer: JsonObject?, rules: List<ByBreakpointChannel.ClassRule>): Map<ByBreakpointChannel.ClassRule, ByClassVerdict> {
            val entries = answer?.array("classes") ?: return emptyMap()
            return rules.zip(entries).mapNotNull { (rule, entry) ->
                val obj = entry.objOrNull() ?: return@mapNotNull null
                if (obj.string("name") != rule.name) return@mapNotNull null
                val message = obj.string("message")
                rule to when {
                    obj.bool("verified") == true -> Verified
                    obj.string("reason") == "pending" -> Pending(message)
                    else -> Failed(message)
                }
            }.toMap()
        }
    }
}
