package io.github.aedev.flow.sharetogether.net

import android.content.Context
import android.net.wifi.WifiManager
import io.github.aedev.flow.sharetogether.model.ShareTogetherFrame
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * High-performance peer-to-peer network transport for Share Together.
 *
 * Implements:
 * 1. Zero-config LAN / Wi-Fi Hotspot discovery via UDP broadcast.
 * 2. Embedded Ktor CIO WebSocket Server on Host.
 * 3. OkHttp WebSocket Client on Participants.
 * 4. Automatic IP resolution and direct IP:port fallback.
 */
object ShareTogetherNetwork {
    private const val DEFAULT_UDP_PORT = 8988
    private const val DEFAULT_WS_PORT = 8989
    private const val WS_PATH = "/share-together"

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Host state
    private var hostServer: EmbeddedServer<*, *>? = null
    private var hostUdpSocket: DatagramSocket? = null
    private var hostAnnounceJob: Job? = null
    private var hostUdpListenerJob: Job? = null
    private val hostClientSessions = ConcurrentHashMap<String, DefaultWebSocketSession>()
    private var hostFrameCallback: ((ShareTogetherFrame, connectionId: String) -> Unit)? = null
    private var activeRoomCode: String? = null
    private var activeHostPort: Int = DEFAULT_WS_PORT

    // Client state
    private var clientWebSocket: WebSocket? = null
    private var clientFrameCallback: ((ShareTogetherFrame) -> Unit)? = null
    private var clientDisconnectedCallback: (() -> Unit)? = null

    // Multicast lock to ensure UDP broadcast works across Android OEM stacks
    private var multicastLock: WifiManager.MulticastLock? = null

    /**
     * Start hosting a Share Together room on the local network.
     */
    suspend fun startHosting(
        context: Context,
        roomCode: String,
        onFrameReceived: (ShareTogetherFrame, connectionId: String) -> Unit,
    ): Pair<String, Int> = withContext(Dispatchers.IO) {
        stopHosting()
        acquireMulticastLock(context)

        activeRoomCode = roomCode
        hostFrameCallback = onFrameReceived
        hostClientSessions.clear()

        var boundPort = DEFAULT_WS_PORT
        var serverStarted = false

        // Attempt default port, fallback to sequential ports if in use
        for (candidatePort in DEFAULT_WS_PORT..(DEFAULT_WS_PORT + 10)) {
            try {
                val server = embeddedServer(CIO, host = "0.0.0.0", port = candidatePort) {
                    install(WebSockets) {
                        pingPeriodMillis = 15_000L
                        timeoutMillis = 45_000L
                        maxFrameSize = 512 * 1024L
                    }
                    routing {
                        webSocket(WS_PATH) {
                            val connId = this.hashCode().toString()
                            hostClientSessions[connId] = this
                            try {
                                for (frame in incoming) {
                                    if (frame is Frame.Text) {
                                        val text = frame.readText()
                                        try {
                                            val parsedFrame = json.decodeFromString(ShareTogetherFrame.serializer(), text)
                                            hostFrameCallback?.invoke(parsedFrame, connId)
                                        } catch (_: Exception) {
                                        }
                                    }
                                }
                            } catch (_: Exception) {
                            } finally {
                                hostClientSessions.remove(connId)
                            }
                        }
                    }
                }
                server.start(wait = false)
                hostServer = server
                boundPort = candidatePort
                serverStarted = true
                break
            } catch (_: Exception) {
            }
        }

        if (!serverStarted) {
            // Ephemeral port fallback
            val server = embeddedServer(CIO, host = "0.0.0.0", port = 0) {
                install(WebSockets)
                routing {
                    webSocket(WS_PATH) {
                        val connId = this.hashCode().toString()
                        hostClientSessions[connId] = this
                        try {
                            for (frame in incoming) {
                                if (frame is Frame.Text) {
                                    val text = frame.readText()
                                    try {
                                        val parsedFrame = json.decodeFromString(ShareTogetherFrame.serializer(), text)
                                        hostFrameCallback?.invoke(parsedFrame, connId)
                                    } catch (_: Exception) {
                                    }
                                }
                            }
                        } catch (_: Exception) {
                        } finally {
                            hostClientSessions.remove(connId)
                        }
                    }
                }
            }
            server.start(wait = false)
            hostServer = server
            boundPort = server.engine.resolvedConnectors().first().port
        }

        activeHostPort = boundPort
        val localIp = getLocalIpAddress() ?: "127.0.0.1"

        // Start UDP broadcast beacon and responder
        startHostUdpServices(roomCode, boundPort)

        Pair(localIp, boundPort)
    }

    /**
     * Broadcasts UDP announcements and responds to incoming discovery probes.
     */
    private fun startHostUdpServices(roomCode: String, port: Int) {
        try {
            val udpSocket = DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(DEFAULT_UDP_PORT))
                broadcast = true
            }
            hostUdpSocket = udpSocket

            // 1. Periodic announcement beacon every 1.5s
            hostAnnounceJob = scope.launch {
                val announceMsg = "OPENTUBE_ROOM|$roomCode|$port".toByteArray(Charsets.UTF_8)
                val broadcastAddr = InetAddress.getByName("255.255.255.255")
                val packet = DatagramPacket(announceMsg, announceMsg.size, broadcastAddr, DEFAULT_UDP_PORT)
                while (isActive) {
                    try {
                        udpSocket.send(packet)
                    } catch (_: Exception) {
                    }
                    delay(1500)
                }
            }

            // 2. Incoming probe responder
            hostUdpListenerJob = scope.launch {
                val buffer = ByteArray(1024)
                while (isActive) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        udpSocket.receive(packet)
                        val text = String(packet.data, 0, packet.length, Charsets.UTF_8).trim()
                        val parts = text.split("|")
                        if (parts.size >= 2 && parts[0] == "OPENTUBE_DISCOVER") {
                            val requestedCode = parts[1].trim().uppercase()
                            if (requestedCode == roomCode.uppercase()) {
                                val reply = "OPENTUBE_OFFER|$roomCode|$port".toByteArray(Charsets.UTF_8)
                                val replyPacket = DatagramPacket(reply, reply.size, packet.address, packet.port)
                                udpSocket.send(replyPacket)
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Broadcast a frame to all connected participants.
     */
    fun broadcastToParticipants(frame: ShareTogetherFrame) {
        scope.launch {
            try {
                val text = json.encodeToString(ShareTogetherFrame.serializer(), frame)
                val wsFrame = Frame.Text(text)
                for (session in hostClientSessions.values) {
                    try {
                        session.send(wsFrame)
                    } catch (_: Exception) {
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Send a frame to a specific participant connection.
     */
    fun sendToParticipant(connectionId: String, frame: ShareTogetherFrame) {
        scope.launch {
            try {
                val session = hostClientSessions[connectionId] ?: return@launch
                val text = json.encodeToString(ShareTogetherFrame.serializer(), frame)
                session.send(Frame.Text(text))
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Stop hosting and clean up resources.
     */
    fun stopHosting() {
        hostAnnounceJob?.cancel()
        hostAnnounceJob = null
        hostUdpListenerJob?.cancel()
        hostUdpListenerJob = null

        try {
            hostUdpSocket?.close()
        } catch (_: Exception) {
        }
        hostUdpSocket = null

        try {
            hostServer?.stop(0, 0)
        } catch (_: Exception) {
        }
        hostServer = null

        hostClientSessions.clear()
        hostFrameCallback = null
        activeRoomCode = null

        releaseMulticastLock()
    }

    /**
     * Connect to a host room as a participant.
     */
    suspend fun joinHost(
        context: Context,
        inputCodeOrAddress: String,
        onFrameReceived: (ShareTogetherFrame) -> Unit,
        onDisconnected: () -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        disconnectClient()
        acquireMulticastLock(context)

        clientFrameCallback = onFrameReceived
        clientDisconnectedCallback = onDisconnected

        var targetHost: String? = null
        var targetPort = DEFAULT_WS_PORT
        val cleanInput = inputCodeOrAddress.trim()

        // 1. Direct IP Check (e.g. 192.168.1.5:8989, 192.168.1.5, or OT-1234@192.168.1.5:8989)
        if (cleanInput.contains("@")) {
            val addrPart = cleanInput.substringAfter("@")
            if (addrPart.contains(":")) {
                targetHost = addrPart.substringBefore(":")
                targetPort = addrPart.substringAfter(":").toIntOrNull() ?: DEFAULT_WS_PORT
            } else {
                targetHost = addrPart
            }
        } else if (cleanInput.contains(":") && cleanInput.split(".").size == 4) {
            targetHost = cleanInput.substringBefore(":")
            targetPort = cleanInput.substringAfter(":").toIntOrNull() ?: DEFAULT_WS_PORT
        } else if (cleanInput.split(".").size == 4) {
            targetHost = cleanInput
        }

        // 2. UDP Discovery Probe
        val roomCodeToFind = if (cleanInput.contains("@")) cleanInput.substringBefore("@") else cleanInput
        if (targetHost == null) {
            targetHost = discoverHostViaUdp(roomCodeToFind)
        }

        // 3. Fallback: Common Hotspot / Wi-Fi Gateway IP probe
        if (targetHost == null) {
            val localIp = getLocalIpAddress()
            if (localIp != null) {
                val subnetPrefix = localIp.substringBeforeLast(".")
                val gatewayIp = "$subnetPrefix.1"
                targetHost = gatewayIp
            }
        }

        if (targetHost == null) {
            releaseMulticastLock()
            return@withContext false
        }

        // 4. Open OkHttp WebSocket connection
        connectWebSocket(targetHost, targetPort)
    }

    /**
     * Send UDP discovery probes to find the host for the given room code.
     */
    private suspend fun discoverHostViaUdp(roomCode: String): String? = withTimeoutOrNull(2500) {
        var discoveredHost: String? = null
        try {
            val socket = DatagramSocket().apply {
                broadcast = true
                soTimeout = 2000
            }
            val probeMsg = "OPENTUBE_DISCOVER|${roomCode.uppercase()}".toByteArray(Charsets.UTF_8)
            val broadcastAddr = InetAddress.getByName("255.255.255.255")
            val probePacket = DatagramPacket(probeMsg, probeMsg.size, broadcastAddr, DEFAULT_UDP_PORT)
            socket.send(probePacket)

            val buffer = ByteArray(1024)
            val responsePacket = DatagramPacket(buffer, buffer.size)

            while (isActive && discoveredHost == null) {
                try {
                    socket.receive(responsePacket)
                    val reply = String(responsePacket.data, 0, responsePacket.length, Charsets.UTF_8).trim()
                    val parts = reply.split("|")
                    if (parts.size >= 3 && parts[0] == "OPENTUBE_OFFER" && parts[1].equals(roomCode, ignoreCase = true)) {
                        discoveredHost = responsePacket.address.hostAddress
                        val portFromHost = parts[2].toIntOrNull()
                        if (portFromHost != null) {
                            activeHostPort = portFromHost
                        }
                        break
                    }
                } catch (_: Exception) {
                    break
                }
            }
            socket.close()
        } catch (_: Exception) {
        }
        discoveredHost
    }

    /**
     * Open WebSocket connection to the host.
     */
    private suspend fun connectWebSocket(host: String, port: Int): Boolean = withContext(Dispatchers.IO) {
        val result = CompletableDeferred<Boolean>()
        val url = "ws://$host:$port$WS_PATH"

        val client = OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder().url(url).build()

        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                clientWebSocket = webSocket
                result.complete(true)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val frame = json.decodeFromString(ShareTogetherFrame.serializer(), text)
                    clientFrameCallback?.invoke(frame)
                } catch (_: Exception) {
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                clientDisconnectedCallback?.invoke()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                clientDisconnectedCallback?.invoke()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!result.isCompleted) {
                    result.complete(false)
                }
                clientDisconnectedCallback?.invoke()
            }
        })

        val connected = withTimeoutOrNull(4500) { result.await() } ?: false
        if (!connected) {
            try {
                ws.cancel()
            } catch (_: Exception) {
            }
        }
        connected
    }

    /**
     * Send a frame from participant to host.
     */
    fun sendToHost(frame: ShareTogetherFrame) {
        scope.launch {
            try {
                val text = json.encodeToString(ShareTogetherFrame.serializer(), frame)
                clientWebSocket?.send(text)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Disconnect client session.
     */
    fun disconnectClient() {
        try {
            clientWebSocket?.close(1000, "Leaving room")
        } catch (_: Exception) {
        }
        clientWebSocket = null
        clientFrameCallback = null
        clientDisconnectedCallback = null
        releaseMulticastLock()
    }

    /**
     * Resolve the current device's local IPv4 network address (Wi-Fi or Hotspot).
     */
    fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (nIf in interfaces) {
                if (nIf.isLoopback || !nIf.isUp) continue
                val addresses = nIf.inetAddresses
                for (addr in addresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress
                        if (ip != null && (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172."))) {
                            return ip
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    private fun acquireMulticastLock(context: Context) {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val lock = wifiManager?.createMulticastLock("OpenTubeShareTogether")
            lock?.setReferenceCounted(false)
            lock?.acquire()
            multicastLock = lock
        } catch (_: Exception) {
        }
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.release()
        } catch (_: Exception) {
        }
        multicastLock = null
    }
}
