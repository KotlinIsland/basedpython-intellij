package dev.basedpython.pycharm.debug

import com.intellij.platform.dap.DapSessionContext
import com.jetbrains.dap.impl.DapClientHandlersBuilder
import com.jetbrains.dap.impl.DapClientSession
import com.jetbrains.dap.impl.withDapOverChannels
import com.jetbrains.dap.protocol.Capabilities
import com.jetbrains.dap.protocol.DapClient
import com.jetbrains.dap.protocol.DapRemoteEndpoint
import com.jetbrains.dap.protocol.DapRequestFailedException
import com.jetbrains.dap.protocol.DapServer
import com.jetbrains.dap.protocol.InitializeRequestArguments
import com.jetbrains.dap.protocol.RequestType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

/**
 * A session context whose endpoint answers every request as [answer] says, for driving code that
 * sends from inside a session command without an adapter behind it.
 */
internal fun answeringContext(
    scope: CoroutineScope,
    answer: suspend (command: String, arguments: Any?) -> Result<Any?>,
): DapSessionContext = sessionContext(
    scope,
    unusedServer(),
    object : DapRemoteEndpoint {
        @Suppress("UNCHECKED_CAST")
        override suspend fun <T, A> request(requestType: RequestType<A, T>, params: A): Result<T> =
            answer(requestType.command, params) as Result<T>
    },
)

/**
 * A [DapSessionContext] over [server] and [endpoint], as the platform makes one for each command.
 *
 * Built reflectively because its constructor is `internal` to the platform's module — a context is
 * something a plugin is handed, never makes — and the code under test is written against what it is
 * handed. The reflection is the test's, and stands in for the platform's executor only.
 */
internal fun sessionContext(scope: CoroutineScope, server: DapServer, endpoint: DapRemoteEndpoint): DapSessionContext =
    DapSessionContext::class.java
        .getConstructor(CoroutineScope::class.java, DapServer::class.java, DapRemoteEndpoint::class.java, StateFlow::class.java)
        .newInstance(scope, server, endpoint, MutableStateFlow(Capabilities()))

/** The failure the platform's endpoint completes a request with when the adapter refuses it. */
internal fun refusal(sentence: String): Result<Nothing> = Result.failure(DapRequestFailedException(sentence, null))

/** A `DapServer` for contexts nothing sends a base-protocol request through. */
internal fun unusedServer(): DapServer = Proxy.newProxyInstance(
    DapServer::class.java.classLoader,
    arrayOf(DapServer::class.java),
) { self, method, args ->
    objectMethod(self, method.name, args) ?: error("unexpected base-protocol request: ${method.name}")
} as DapServer

/** `equals`, `hashCode` and `toString` for a proxy, which a proxy is asked like any object; null for anything else. */
private fun objectMethod(self: Any, name: String, args: Array<out Any?>?): Any? = when (name) {
    "equals" -> self === args?.get(0)
    "hashCode" -> System.identityHashCode(self)
    "toString" -> "a stand-in"
    else -> null
}

/** One request as the fake adapter received it. */
internal data class Received(val command: String, val arguments: JsonObject?)

/** What the fake adapter answers a request with. */
internal sealed interface Answer {
    /** `success: true`, with [body] or no body at all. */
    data class Body(val body: JsonElement?) : Answer

    /** `success: false`, with the error bpd's adapter sends: [sentence] as both message and format, shown to the user. */
    data class Refuse(val sentence: String) : Answer
}

/** The adapter side of [withFakeAdapter]: what it was asked, and a way to send an event. */
internal class FakeAdapter(private val toClient: Channel<JsonElement>) {
    val received = CopyOnWriteArrayList<Received>()
    private var seq = 1000

    suspend fun event(name: String, body: JsonElement) {
        toClient.send(buildJsonObject {
            put("seq", seq++)
            put("type", "event")
            put("event", name)
            put("body", body)
        })
    }

    internal suspend fun answer(request: JsonObject, answer: Answer) {
        val command = request.getValue("command").jsonPrimitive.content
        toClient.send(buildJsonObject {
            put("seq", seq++)
            put("type", "response")
            put("request_seq", request.getValue("seq"))
            put("command", command)
            when (answer) {
                is Answer.Body -> {
                    put("success", true)
                    answer.body?.let { put("body", it) }
                }
                is Answer.Refuse -> {
                    put("success", false)
                    put("message", answer.sentence)
                    put("body", buildJsonObject {
                        put("error", buildJsonObject {
                            put("id", 1)
                            put("format", answer.sentence)
                            put("showUser", true)
                        })
                    })
                }
            }
        })
    }
}

/**
 * The platform's own DAP client — its message loop, its endpoint, its event dispatch — over
 * in-memory channels, against an adapter that answers each request as [respond] says.
 *
 * The point is the real wire: what a [RequestType]'s serializers make of a body, what an absent
 * body becomes, what a refusal throws, which handler an event reaches. A stand-in endpoint would
 * agree with whatever the code under test assumes.
 *
 * `initialize` is answered with empty capabilities and sent before [body] runs, because the
 * platform's endpoint holds every other request until it has been answered.
 */
internal fun <T> withFakeAdapter(
    respond: (Received) -> Answer,
    handlers: DapClientHandlersBuilder.() -> Unit = {},
    body: suspend (session: DapClientSession, adapter: FakeAdapter) -> T,
): T = runBlocking {
    withTimeout(20.seconds) {
        val toClient = Channel<JsonElement>(Channel.UNLIMITED)
        val toAdapter = Channel<JsonElement>(Channel.UNLIMITED)
        val adapter = FakeAdapter(toClient)
        val result = CompletableDeferred<T>()
        coroutineScope {
            val serving = launch {
                for (message in toAdapter) {
                    val request = message.jsonObject
                    val command = request.getValue("command").jsonPrimitive.content
                    if (command == "initialize") {
                        adapter.answer(request, Answer.Body(buildJsonObject {}))
                        continue
                    }
                    val received = Received(command, request["arguments"] as? JsonObject)
                    adapter.received += received
                    adapter.answer(request, respond(received))
                }
            }
            val closed = CompletableDeferred<Unit>()
            withDapOverChannels(
                toClient,
                toAdapter,
                { _, _ -> unusedClient() },
                handlers,
                closed,
            ) { session, _ ->
                session.server.initialize(InitializeRequestArguments(adapterID = "basedpython"))
                result.complete(body(session, adapter))
            }
            serving.cancel()
        }
        result.await()
    }
}

/** A protocol client for sessions whose base-protocol events nothing here reads. */
private fun unusedClient(): DapClient = Proxy.newProxyInstance(
    DapClient::class.java.classLoader,
    arrayOf(DapClient::class.java),
) { self, method, args -> objectMethod(self, method.name, args) } as DapClient
