package dev.basedpython.pycharm.debug

import com.intellij.icons.AllIcons
import com.intellij.openapi.diagnostic.Logger
import com.intellij.platform.dap.DapDebugSession
import com.intellij.platform.dap.DapSessionStoppedException
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.breakpoints.SuspendPolicy
import com.intellij.xdebugger.breakpoints.XBreakpoint
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import dev.basedpython.pycharm.debug.bpd.ByDebugBackend
import dev.basedpython.pycharm.util.BasedPythonBundle
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What one session's breakpoints say that DAP's own requests cannot: a rule per exception class, and
 * what a line breakpoint's hit holds. bpd's own channel for both — fields of the `launch`, and
 * `bpd/setExceptionClasses` and `bpd/setSuspendPolicies` after it (bpd's `docs/development/dap.md`,
 * *what a hit holds is said by location* and *a rule per class has a way in of its own*).
 *
 * The platform's DAP client owns `setExceptionBreakpoints` and `setBreakpoints`: it sends the first
 * with `filters` and never `exceptionOptions`, the second with no field for a suspend policy, and
 * both before the program runs. So these go beside them, keyed so that what the platform sends again
 * does not undo them — a rule by its class, a policy by its file and line.
 *
 * ## nothing here races the platform
 *
 * The handlers register every breakpoint when the session initializes its breakpoints, before the
 * adapter is reached, and that is all this holds until the `launch`: the launch's arguments are read
 * from it ([launchArguments]), which puts the rules and the policies in force before the program runs
 * a line. Once the platform has the launch's answer — its session state leaves `Connecting` — both
 * sets are sent again whole ([started]), which covers anything changed between the two and is how the
 * launch's own rules are given their verdicts, since a launch answers none. Every change after that
 * is the whole set again, sent at once.
 *
 * ## an older bpd, and debugpy
 *
 * A bpd without the requests refuses them by name, and ignores the launch fields. A refusal is the
 * feature being absent: nothing more is sent, a policy is left to `stopTheWorld` as before, and a
 * class breakpoint says the adapter's sentence where its verdict would be. debugpy has neither, and is
 * sent nothing.
 */
internal class ByBreakpointChannel(
    private val dapSession: DapDebugSession,
    private val session: XDebugSession,
    private val backend: ByDebugBackend?,
) {

    /** A line breakpoint's hit, by where `setBreakpoints` says it is. */
    data class Policy(val path: String, val line: Int, val suspend: String)

    /** One class's rule, exactly bpd's `ExceptionClass`. */
    data class ClassRule(val name: String, val raised: Boolean, val uncaught: Boolean, val libraries: Boolean)

    private val lock = Any()
    private val policies = LinkedHashMap<XLineBreakpoint<*>, Policy>()
    private val classes = LinkedHashMap<XBreakpoint<ByExceptionBreakpointProperties>, ClassRule>()
    private var started = false

    /** What a bpd that refused a request said, per request; that half is sent no more. */
    private var policiesRefused: String? = null
    private var classesRefused: String? = null

    /** Whether some rule's class was not in the program when bpd last answered. */
    @Volatile
    private var pending = false

    private val sends = backend == ByDebugBackend.BPD

    fun setPolicy(breakpoint: XLineBreakpoint<*>, policy: Policy?) {
        val changed = synchronized(lock) {
            val before = if (policy == null) policies.remove(breakpoint) else policies.put(breakpoint, policy)
            before != policy && started
        }
        if (changed) sendPolicies()
    }

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

    /**
     * The `launch` fields: every rule and every policy the handlers hold now. Empty for debugpy, which
     * reads neither.
     */
    fun launchArguments(): Map<String, Any> {
        if (!sends) return emptyMap()
        return synchronized(lock) {
            mapOf(
                EXCEPTION_CLASSES to classes.values.distinct().map(::ruleMap),
                SUSPEND_POLICIES to policies.values.distinct().map(::policyMap),
            )
        }
    }

    /** The platform has the launch's answer: from here on every change is sent, starting with all of it. */
    fun started() {
        if (!sends) return
        synchronized(lock) { started = true }
        sendPolicies()
        sendClasses()
    }

    /**
     * At a stop: a rule whose class the program did not have yet is asked about again, since bpd reads
     * whether it has the class without importing anything, and only an answer says it now does.
     */
    fun paused() {
        if (pending) sendClasses()
    }

    private fun sendPolicies() {
        if (!sends) return
        dapSession.post {
            val asked = synchronized(lock) { policies.values.distinct().takeIf { policiesRefused == null } } ?: return@post
            val arguments = buildJsonObject {
                put("policies", buildJsonArray { asked.forEach { add(json(policyMap(it))) } })
            }
            try {
                send(ByDapRequests.setSuspendPolicies, arguments)
            } catch (e: CancellationException) {
                throw e
            } catch (_: DapSessionStoppedException) {
            } catch (e: Exception) {
                val refusal = ByDapRequests.refusalOf(e) ?: return@post LOG.warn("bpd/setSuspendPolicies failed", e)
                LOG.info("this bpd holds no suspend policies: $refusal")
                synchronized(lock) { policiesRefused = refusal }
            }
        }
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
        const val SUSPEND_POLICIES: String = "suspendPolicies"

        fun ruleMap(rule: ClassRule): Map<String, Any> =
            mapOf("name" to rule.name, "raised" to rule.raised, "uncaught" to rule.uncaught, "libraries" to rule.libraries)

        fun policyMap(policy: Policy): Map<String, Any> =
            mapOf("path" to policy.path, "line" to policy.line, "suspend" to policy.suspend)

        private fun json(map: Map<String, Any>): JsonObject = buildJsonObject {
            for ((key, value) in map) when (value) {
                is String -> put(key, value)
                is Int -> put(key, value)
                is Boolean -> put(key, value)
                else -> error("$key is not a launch field's kind: $value")
            }
        }

        /**
         * What a line breakpoint's hit is said to hold: its Suspend setting, and for one that suspends
         * nothing, `none` only when bpd can do everything it asks — count it, log its expression as a
         * DAP log message ([adapterLogMessage]) — so that the IDE never needs to hear of the hit. One
         * that asks the IDE to say it was hit, print the stack or remove itself holds its thread for
         * the moment the IDE takes to do that and resume it, which is what the platform does with any
         * `NONE` breakpoint it is told of.
         */
        fun suspendOf(breakpoint: XLineBreakpoint<*>): String = when (breakpoint.suspendPolicy) {
            SuspendPolicy.ALL -> "all"
            SuspendPolicy.THREAD -> "thread"
            SuspendPolicy.NONE -> {
                val idleOnHit = !breakpoint.isLogMessage && !breakpoint.isLogStack && !breakpoint.isTemporary &&
                    (breakpoint.logExpressionObject == null || adapterLogMessage(breakpoint) != null)
                if (idleOnHit) "none" else "thread"
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
