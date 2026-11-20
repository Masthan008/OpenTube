package io.github.aedev.flow.sharetogether.net

import io.github.aedev.flow.sharetogether.crypto.Schnorr
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Nostr wire event representation following NIP-01.
 */
data class NostrEvent(
    val id: String,
    val pubkey: String,
    val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val sig: String,
) {
    fun toJson(): JsonObject =
        buildJsonObject {
            put("id", id)
            put("pubkey", pubkey)
            put("created_at", createdAt)
            put("kind", kind)
            put(
                "tags",
                buildJsonArray {
                    tags.forEach { t ->
                        add(buildJsonArray { t.forEach { add(JsonPrimitive(it)) } })
                    }
                },
            )
            put("content", content)
            put("sig", sig)
        }

    companion object {
        fun signed(
            privateKey: ByteArray,
            kind: Int,
            tags: List<List<String>>,
            content: String,
            createdAt: Long = System.currentTimeMillis() / 1000,
        ): NostrEvent {
            val pubkey = Schnorr.publicKey(privateKey).joinToString("") { "%02x".format(it) }
            val serialized = serialize(pubkey, createdAt, kind, tags, content)
            val idBytes = MessageDigest.getInstance("SHA-256").digest(serialized.toByteArray(Charsets.UTF_8))
            val idHex = idBytes.joinToString("") { "%02x".format(it) }
            val sigHex = Schnorr.sign(idBytes, privateKey).joinToString("") { "%02x".format(it) }
            return NostrEvent(idHex, pubkey, createdAt, kind, tags, content, sigHex)
        }

        private fun serialize(
            pubkey: String,
            createdAt: Long,
            kind: Int,
            tags: List<List<String>>,
            content: String,
        ): String =
            buildString {
                append("[0,\"")
                    .append(pubkey)
                    .append("\",")
                    .append(createdAt)
                    .append(',')
                    .append(kind)
                    .append(",[")
                tags.forEachIndexed { i, t ->
                    if (i > 0) append(',')
                    append('[')
                    t.forEachIndexed { j, v ->
                        if (j > 0) append(',')
                        quote(v)
                    }
                    append(']')
                }
                append("],")
                quote(content)
                append(']')
            }

        private fun StringBuilder.quote(s: String) {
            append('"')
            for (c in s) {
                when (c) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
                }
            }
            append('"')
        }

        fun parse(o: JsonObject): NostrEvent? =
            runCatching {
                NostrEvent(
                    id = (o["id"] as JsonPrimitive).content,
                    pubkey = (o["pubkey"] as JsonPrimitive).content,
                    createdAt = (o["created_at"] as JsonPrimitive).longOrNull ?: return null,
                    kind = (o["kind"] as JsonPrimitive).intOrNull ?: return null,
                    tags = (o["tags"] as JsonArray).map { t -> (t as JsonArray).map { (it as JsonPrimitive).content } },
                    content = (o["content"] as JsonPrimitive).content,
                    sig = (o["sig"] as JsonPrimitive).content,
                )
            }.getOrNull()
    }
}

/**
 * Decentralized Nostr WebSocket Relay Pool for Share Together.
 * Connects to public, free, global Nostr relays so users can co-watch & co-listen across different cities.
 */
class NostrRelayPool(
    private val relayUrls: List<String> = DEFAULT_RELAYS,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()

    private val sessionPrivateKey = Schnorr.newPrivateKey()
    private val sockets = mutableMapOf<String, WebSocket>()
    private val openUrls = mutableSetOf<String>()
    private val seenEventIds =
        object : LinkedHashMap<String, Unit>(256, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?) = size > 2000
        }

    private val _events = MutableSharedFlow<NostrEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<NostrEvent> = _events.asSharedFlow()

    private val _connectedCount = MutableStateFlow(0)
    val connectedCount: StateFlow<Int> = _connectedCount.asStateFlow()

    private var activeTag: String? = null

    @Volatile private var isClosed = false

    /**
     * Connect to the relay pool and subscribe to the room topic tag.
     */
    fun start(topicTag: String) {
        activeTag = topicTag
        isClosed = false
        relayUrls.forEach { url ->
            scope.launch { keepConnected(url, topicTag) }
        }
    }

    private suspend fun keepConnected(
        url: String,
        topicTag: String,
    ) {
        var backoffMs = 2000L
        while (!isClosed) {
            val done = CompletableDeferred<Unit>()
            val request = Request.Builder().url(url).build()

            val ws =
                client.newWebSocket(
                    request,
                    object : WebSocketListener() {
                        override fun onOpen(
                            webSocket: WebSocket,
                            response: Response,
                        ) {
                            synchronized(openUrls) {
                                openUrls += url
                                _connectedCount.value = openUrls.size
                            }
                            backoffMs = 2000L

                            // Send NIP-01 REQ subscription for the room tag
                            val filter =
                                buildJsonObject {
                                    put("#d", buildJsonArray { add(JsonPrimitive(topicTag)) })
                                    put("kinds", buildJsonArray { add(JsonPrimitive(KIND_EPHEMERAL)) })
                                }
                            val reqFrame =
                                buildJsonArray {
                                    add(JsonPrimitive("REQ"))
                                    add(JsonPrimitive("opentube_room"))
                                    add(filter)
                                }.toString()
                            webSocket.send(reqFrame)
                        }

                        override fun onMessage(
                            webSocket: WebSocket,
                            text: String,
                        ) {
                            handleMessage(text)
                        }

                        override fun onClosed(
                            webSocket: WebSocket,
                            code: Int,
                            reason: String,
                        ) {
                            done.complete(Unit)
                        }

                        override fun onFailure(
                            webSocket: WebSocket,
                            t: Throwable,
                            response: Response?,
                        ) {
                            done.complete(Unit)
                        }
                    },
                )

            synchronized(sockets) { sockets[url] = ws }
            done.await()
            synchronized(openUrls) {
                openUrls -= url
                _connectedCount.value = openUrls.size
            }

            if (isClosed) break
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
        }
    }

    private fun handleMessage(text: String) {
        val array = runCatching { json.parseToJsonElement(text) as? JsonArray }.getOrNull() ?: return
        val verb = (array.getOrNull(0) as? JsonPrimitive)?.contentOrNull ?: return

        if (verb == "EVENT") {
            val eventObj = array.getOrNull(2) as? JsonObject ?: return
            val event = NostrEvent.parse(eventObj) ?: return

            val isNew =
                synchronized(seenEventIds) {
                    seenEventIds.put(event.id, Unit) == null
                }
            if (isNew) {
                _events.tryEmit(event)
            }
        }
    }

    /**
     * Publish an encrypted payload to the room topic tag.
     */
    fun publish(
        encryptedContent: String,
        topicTag: String,
    ): Boolean {
        val tags = listOf(listOf("d", topicTag))
        val event = NostrEvent.signed(sessionPrivateKey, KIND_EPHEMERAL, tags, encryptedContent)

        synchronized(seenEventIds) {
            seenEventIds[event.id] = Unit // Ignore echo of our own event
        }

        val frame =
            buildJsonArray {
                add(JsonPrimitive("EVENT"))
                add(event.toJson())
            }.toString()

        val activeWebSockets =
            synchronized(openUrls) { openUrls.toList() }
                .mapNotNull { synchronized(sockets) { sockets[it] } }

        activeWebSockets.forEach { it.send(frame) }
        return activeWebSockets.isNotEmpty()
    }

    /**
     * Disconnect from all relays.
     */
    fun close() {
        isClosed = true
        synchronized(sockets) {
            sockets.values.forEach { it.close(1000, "Leaving room") }
            sockets.clear()
        }
        synchronized(openUrls) { openUrls.clear() }
        _connectedCount.value = 0
        scope.cancel()
    }

    companion object {
        const val KIND_EPHEMERAL = 21420

        val DEFAULT_RELAYS =
            listOf(
                "wss://relay.damus.io",
                "wss://relay.primal.net",
                "wss://nos.lol",
                "wss://relay.snort.social",
                "wss://nostr.mom",
                "wss://relay.nostr.net",
                "wss://nostr-pub.wellorder.net",
                "wss://offchain.pub",
            )
    }
}
