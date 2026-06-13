// WebRtcTunnelClient.kt
// Зависимости (build.gradle):
//   implementation 'org.webrtc:google-webrtc:1.0.32006'
//   implementation 'com.squareup.okhttp3:okhttp:4.12.0'
//   implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3'

package com.example.webrtctunnel

import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.webrtc.*
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "WebRtcTunnel"
private val JSON_MT = "application/json; charset=utf-8".toMediaType()

/**
 * WebRTC Tunnel Client
 *
 * Принцип работы:
 *  1. Устанавливает WebRTC соединение с сервером (SDP offer/answer + ICE)
 *  2. Создаёт DataChannel для каждого туннельного соединения
 *  3. Поднимает локальный SOCKS5-прокси / TCP listener
 *  4. Каждое входящее TCP соединение → новый DataChannel → сервер → целевой хост
 */
class WebRtcTunnelClient(
    private val signalingUrl: String,    // "http://your-server:8080"
    private val localProxyPort: Int = 1080,
    private val appContext: android.content.Context,
) {
    private val http = OkHttpClient()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val running = AtomicBoolean(false)

    private lateinit var factory: PeerConnectionFactory
    private lateinit var pc: PeerConnection
    private var sessionId: String = ""

    // ──────────────────────────────────────────
    // Инициализация WebRTC
    // ──────────────────────────────────────────

    fun init() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext)
                .setEnableInternalTracer(true)
                .createInitializationOptions()
        )

        val options = PeerConnectionFactory.Options()
        factory = PeerConnectionFactory.builder()
            .setOptions(options)
            .createPeerConnectionFactory()
    }

    // ──────────────────────────────────────────
    // Установка соединения
    // ──────────────────────────────────────────

    suspend fun connect() {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            // Добавьте TURN-сервер для прохождения строгих NAT:
            // PeerConnection.IceServer.builder("turn:your-turn-server:3478")
            //     .setUsername("user").setPassword("pass").createIceServer(),
        )

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        val iceCandidates = mutableListOf<IceCandidate>()
        val connectedDeferred = CompletableDeferred<Unit>()

        pc = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                iceCandidates.add(candidate)
                // Отправляем кандидат серверу (trickle ICE)
                scope.launch { sendIceCandidate(candidate) }
            }
            override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                Log.i(TAG, "Connection state: $state")
                when (state) {
                    PeerConnection.PeerConnectionState.CONNECTED -> connectedDeferred.complete(Unit)
                    PeerConnection.PeerConnectionState.FAILED,
                    PeerConnection.PeerConnectionState.DISCONNECTED -> {
                        if (!connectedDeferred.isCompleted)
                            connectedDeferred.completeExceptionally(IOException("Connection failed"))
                    }
                    else -> {}
                }
            }
            override fun onDataChannel(dc: DataChannel) {}
            override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {}
            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onAddStream(p0: MediaStream?) {}
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
        })!!

        // Создаём служебный DataChannel (нужен чтобы сервер тоже был готов)
        val initChannel = pc.createDataChannel("init", DataChannel.Init())

        // SDP Offer
        val offerDeferred = CompletableDeferred<SessionDescription>()
        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) = offerDeferred.complete(sdp)
            override fun onCreateFailure(e: String) = offerDeferred.completeExceptionally(IOException(e))
            override fun onSetSuccess() {}
            override fun onSetFailure(e: String) {}
        }, MediaConstraints())

        val offer = offerDeferred.await()

        val setLocalDeferred = CompletableDeferred<Unit>()
        pc.setLocalDescription(object : SdpObserver {
            override fun onSetSuccess() = setLocalDeferred.complete(Unit)
            override fun onSetFailure(e: String) = setLocalDeferred.completeExceptionally(IOException(e))
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }, offer)
        setLocalDeferred.await()

        // Отправляем offer на сигнальный сервер
        val answerJson = postJson("/offer", JSONObject().apply {
            put("sdp", offer.description)
            put("type", "offer")
        })

        sessionId = answerJson.getString("session_id")
        Log.i(TAG, "Session ID: $sessionId")

        // Устанавливаем remote description (answer)
        val answerSdp = SessionDescription(
            SessionDescription.Type.fromCanonicalForm(answerJson.getString("type")),
            answerJson.getString("sdp")
        )

        val setRemoteDeferred = CompletableDeferred<Unit>()
        pc.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() = setRemoteDeferred.complete(Unit)
            override fun onSetFailure(e: String) = setRemoteDeferred.completeExceptionally(IOException(e))
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }, answerSdp)
        setRemoteDeferred.await()

        // Опрашиваем ICE кандидаты сервера
        scope.launch { pollServerIceCandidates() }

        Log.i(TAG, "Waiting for WebRTC connection...")
        withTimeout(30_000) { connectedDeferred.await() }
        Log.i(TAG, "WebRTC connected!")
    }

    // ──────────────────────────────────────────
    // SOCKS5-lite локальный прокси
    // ──────────────────────────────────────────

    /**
     * Запускает локальный TCP listener.
     * Принимает подключения вида: CONNECT <host>:<port>
     * и туннелирует через WebRTC DataChannel.
     *
     * Совместим с curl --proxy socks5h://localhost:1080
     * (с небольшим адаптером — см. SocksHandshake ниже)
     */
    fun startLocalProxy() {
        running.set(true)
        scope.launch {
            val server = ServerSocket(localProxyPort)
            Log.i(TAG, "Local proxy listening on port $localProxyPort")
            while (running.get()) {
                try {
                    val socket = server.accept()
                    launch { handleLocalConnection(socket) }
                } catch (e: Exception) {
                    if (running.get()) Log.e(TAG, "Accept error", e)
                }
            }
        }
    }

    private suspend fun handleLocalConnection(socket: Socket) {
        withContext(Dispatchers.IO) {
            try {
                val inp = socket.getInputStream()
                val out = socket.getOutputStream()

                // Простой SOCKS5 handshake
                val target = SocksHandshake.negotiate(inp, out) ?: run {
                    socket.close(); return@withContext
                }

                Log.i(TAG, "Tunneling to $target")

                // Создаём DataChannel с уникальным именем
                val label = "tunnel-${System.currentTimeMillis()}"
                val init = DataChannel.Init().apply { ordered = true }
                val channel = pc.createDataChannel(label, init)

                val bridge = DataChannelBridge(channel, socket)
                bridge.start()

                // Отправляем CONNECT-команду серверу
                channel.send(DataChannel.Buffer(
                    java.nio.ByteBuffer.wrap("CONNECT $target\n".toByteArray()),
                    false
                ))

                bridge.waitForClose()
            } catch (e: Exception) {
                Log.e(TAG, "Connection error", e)
                socket.close()
            }
        }
    }

    // ──────────────────────────────────────────
    // Вспомогательные методы
    // ──────────────────────────────────────────

    private suspend fun sendIceCandidate(candidate: IceCandidate) {
        try {
            postJson("/ice", JSONObject().apply {
                put("session_id", sessionId)
                put("candidate", candidate.sdp)
                put("sdpMid", candidate.sdpMid)
                put("sdpMLineIndex", candidate.sdpMLineIndex)
            })
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send ICE candidate: $e")
        }
    }

    private suspend fun pollServerIceCandidates() {
        while (running.get() && sessionId.isNotEmpty()) {
            try {
                val response = getJson("/ice?session_id=$sessionId")
                val arr = response.getJSONArray("candidates")
                for (i in 0 until arr.length()) {
                    val c = arr.getJSONObject(i)
                    val candidate = IceCandidate(
                        c.getString("sdpMid"),
                        c.getInt("sdpMLineIndex"),
                        c.getString("candidate")
                    )
                    pc.addIceCandidate(candidate)
                }
            } catch (e: Exception) {
                Log.w(TAG, "ICE poll error: $e")
            }
            delay(500)
        }
    }

    private suspend fun postJson(path: String, body: JSONObject): JSONObject =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url("$signalingUrl$path")
                .post(body.toString().toRequestBody(JSON_MT))
                .build()
            http.newCall(req).execute().use { resp ->
                JSONObject(resp.body!!.string())
            }
        }

    private suspend fun getJson(path: String): JSONObject =
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url("$signalingUrl$path").get().build()
            http.newCall(req).execute().use { resp ->
                JSONObject(resp.body!!.string())
            }
        }

    fun stop() {
        running.set(false)
        scope.cancel()
        if (::pc.isInitialized) pc.close()
    }
}

// ──────────────────────────────────────────────
// SOCKS5 Handshake helper
// ──────────────────────────────────────────────

object SocksHandshake {
    /** Returns "host:port" or null on failure */
    fun negotiate(inp: java.io.InputStream, out: java.io.OutputStream): String? {
        return try {
            val ver = inp.read()
            if (ver != 5) return null  // только SOCKS5

            val nMethods = inp.read()
            val methods = ByteArray(nMethods)
            inp.read(methods)

            // Принимаем без аутентификации
            out.write(byteArrayOf(0x05, 0x00))
            out.flush()

            // Читаем запрос
            inp.read() // VER
            val cmd = inp.read()
            inp.read() // RSV
            val atyp = inp.read()

            val host = when (atyp) {
                0x01 -> { // IPv4
                    val b = ByteArray(4); inp.read(b)
                    b.joinToString(".") { it.toInt().and(0xFF).toString() }
                }
                0x03 -> { // Domain
                    val len = inp.read()
                    val b = ByteArray(len); inp.read(b)
                    String(b)
                }
                0x04 -> { // IPv6 — упрощённо
                    val b = ByteArray(16); inp.read(b)
                    "[IPv6]"
                }
                else -> return null
            }

            val portHigh = inp.read()
            val portLow = inp.read()
            val port = (portHigh shl 8) or portLow

            // Успешный ответ
            out.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            out.flush()

            "$host:$port"
        } catch (e: Exception) {
            Log.e("SOCKS5", "Handshake failed", e)
            null
        }
    }
}

// ──────────────────────────────────────────────
// DataChannel ↔ TCP Socket bridge
// ──────────────────────────────────────────────

class DataChannelBridge(
    private val channel: DataChannel,
    private val socket: Socket,
) {
    private val closedLatch = java.util.concurrent.CountDownLatch(1)
    private val scope = CoroutineScope(Dispatchers.IO)
    private var tunnelReady = false
    private val pendingQueue = ArrayDeque<ByteArray>()

    fun start() {
        val out = socket.getOutputStream()
        val inp = socket.getInputStream()

        channel.registerObserver(object : DataChannel.Observer {
            override fun onMessage(buffer: DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)

                if (!tunnelReady) {
                    val msg = String(bytes).trim()
                    if (msg.startsWith("OK")) {
                        tunnelReady = true
                        // Отправляем накопленные данные
                        pendingQueue.forEach { channel.send(DataChannel.Buffer(
                            java.nio.ByteBuffer.wrap(it), true)) }
                        pendingQueue.clear()
                    } else if (msg.startsWith("ERROR")) {
                        Log.e("Bridge", "Server error: $msg")
                        close()
                    }
                    return
                }

                try { out.write(bytes); out.flush() }
                catch (e: Exception) { close() }
            }

            override fun onStateChange() {
                if (channel.state() == DataChannel.State.CLOSED) close()
            }
            override fun onBufferedAmountChange(p0: Long) {}
        })

        // Socket → DataChannel
        scope.launch {
            val buf = ByteArray(4096)
            try {
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    val data = buf.copyOf(n)
                    if (tunnelReady) {
                        channel.send(DataChannel.Buffer(java.nio.ByteBuffer.wrap(data), true))
                    } else {
                        pendingQueue.add(data)
                    }
                }
            } catch (e: Exception) { /* socket closed */ }
            close()
        }
    }

    private fun close() {
        try { socket.close() } catch (_: Exception) {}
        try { channel.close() } catch (_: Exception) {}
        scope.cancel()
        closedLatch.countDown()
    }

    suspend fun waitForClose() = withContext(Dispatchers.IO) {
        closedLatch.await()
    }
}
