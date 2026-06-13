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

class WebRtcTunnelClient(
    private val signalingUrl: String,
    private val localProxyPort: Int = 1080,
    private val appContext: android.content.Context,
) {
    private val http = OkHttpClient()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val running = AtomicBoolean(false)

    private lateinit var factory: PeerConnectionFactory
    private lateinit var pc: PeerConnection
    private var sessionId: String = ""

    fun init() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext)
                .setEnableInternalTracer(true)
                .createInitializationOptions()
        )
        factory = PeerConnectionFactory.builder()
            .setOptions(PeerConnectionFactory.Options())
            .createPeerConnectionFactory()
    }

    suspend fun connect() {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        val connectedDeferred = CompletableDeferred<Unit>()

        pc = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                scope.launch { sendIceCandidate(candidate) }
            }
            override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                Log.i(TAG, "Connection state: $state")
                when (state) {
                    PeerConnection.PeerConnectionState.CONNECTED ->
                        if (!connectedDeferred.isCompleted) connectedDeferred.complete(Unit)
                    PeerConnection.PeerConnectionState.FAILED ->
                        if (!connectedDeferred.isCompleted)
                            connectedDeferred.completeExceptionally(IOException("Connection failed"))
                    else -> {}
                }
            }
            override fun onDataChannel(dc: DataChannel) {}
            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onAddStream(p0: MediaStream?) {}
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
        })!!

        pc.createDataChannel("init", DataChannel.Init())

        // --- Create Offer ---
        val offerDeferred = CompletableDeferred<SessionDescription>()
        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp?.let { offerDeferred.complete(it) }
            }
            override fun onCreateFailure(error: String?) {
                offerDeferred.completeExceptionally(IOException(error ?: "offer failed"))
            }
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {}
        }, MediaConstraints())

        val offer = offerDeferred.await()

        // --- Set Local Description ---
        val setLocalDeferred = CompletableDeferred<Unit>()
        pc.setLocalDescription(object : SdpObserver {
            override fun onSetSuccess() { setLocalDeferred.complete(Unit) }
            override fun onSetFailure(error: String?) {
                setLocalDeferred.completeExceptionally(IOException(error ?: "setLocal failed"))
            }
            override fun onCreateSuccess(sdp: SessionDescription?) {}
            override fun onCreateFailure(error: String?) {}
        }, offer)
        setLocalDeferred.await()

        // --- Exchange SDP with server ---
        val answerJson = postJson("/offer", JSONObject().apply {
            put("sdp", offer.description)
            put("type", "offer")
        })
        sessionId = answerJson.getString("session_id")

        val answerSdp = SessionDescription(
            SessionDescription.Type.fromCanonicalForm(answerJson.getString("type")),
            answerJson.getString("sdp")
        )

        // --- Set Remote Description ---
        val setRemoteDeferred = CompletableDeferred<Unit>()
        pc.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() { setRemoteDeferred.complete(Unit) }
            override fun onSetFailure(error: String?) {
                setRemoteDeferred.completeExceptionally(IOException(error ?: "setRemote failed"))
            }
            override fun onCreateSuccess(sdp: SessionDescription?) {}
            override fun onCreateFailure(error: String?) {}
        }, answerSdp)
        setRemoteDeferred.await()

        scope.launch { pollServerIceCandidates() }

        withTimeout(30_000) { connectedDeferred.await() }
        Log.i(TAG, "WebRTC connected! Session: $sessionId")
    }

    fun startLocalProxy() {
        running.set(true)
        scope.launch {
            val server = ServerSocket(localProxyPort)
            Log.i(TAG, "SOCKS5 proxy on port $localProxyPort")
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

    private suspend fun handleLocalConnection(socket: Socket) = withContext(Dispatchers.IO) {
        try {
            val inp = socket.getInputStream()
            val out = socket.getOutputStream()
            val target = SocksHandshake.negotiate(inp, out) ?: run { socket.close(); return@withContext }

            val label = "tunnel-${System.currentTimeMillis()}"
            val channel = pc.createDataChannel(label, DataChannel.Init().apply { ordered = true })
            val bridge = DataChannelBridge(channel, socket)
            bridge.start()
            channel.send(DataChannel.Buffer(
                java.nio.ByteBuffer.wrap("CONNECT $target\n".toByteArray()), false
            ))
            bridge.waitForClose()
        } catch (e: Exception) {
            Log.e(TAG, "Connection error", e)
            socket.close()
        }
    }

    private suspend fun sendIceCandidate(candidate: IceCandidate) {
        try {
            postJson("/ice", JSONObject().apply {
                put("session_id", sessionId)
                put("candidate", candidate.sdp)
                put("sdpMid", candidate.sdpMid)
                put("sdpMLineIndex", candidate.sdpMLineIndex)
            })
        } catch (e: Exception) {
            Log.w(TAG, "ICE send error: $e")
        }
    }

    private suspend fun pollServerIceCandidates() {
        while (running.get() && sessionId.isNotEmpty()) {
            try {
                val response = getJson("/ice?session_id=$sessionId")
                val arr = response.getJSONArray("candidates")
                for (i in 0 until arr.length()) {
                    val c = arr.getJSONObject(i)
                    pc.addIceCandidate(IceCandidate(
                        c.getString("sdpMid"),
                        c.getInt("sdpMLineIndex"),
                        c.getString("candidate")
                    ))
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
            http.newCall(req).execute().use { JSONObject(it.body!!.string()) }
        }

    private suspend fun getJson(path: String): JSONObject =
        withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url("$signalingUrl$path").get().build())
                .execute().use { JSONObject(it.body!!.string()) }
        }

    fun stop() {
        running.set(false)
        scope.cancel()
        if (::pc.isInitialized) pc.close()
    }
}

object SocksHandshake {
    fun negotiate(inp: java.io.InputStream, out: java.io.OutputStream): String? {
        return try {
            if (inp.read() != 5) return null
            val methods = ByteArray(inp.read()); inp.read(methods)
            out.write(byteArrayOf(0x05, 0x00)); out.flush()
            inp.read(); inp.read(); inp.read()
            val atyp = inp.read()
            val host = when (atyp) {
                0x01 -> { val b = ByteArray(4); inp.read(b); b.joinToString(".") { (it.toInt() and 0xFF).toString() } }
                0x03 -> { val b = ByteArray(inp.read()); inp.read(b); String(b) }
                else -> return null
            }
            val port = (inp.read() shl 8) or inp.read()
            out.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0)); out.flush()
            "$host:$port"
        } catch (e: Exception) { null }
    }
}

class DataChannelBridge(private val channel: DataChannel, private val socket: Socket) {
    private val latch = java.util.concurrent.CountDownLatch(1)
    private val scope = CoroutineScope(Dispatchers.IO)
    private var ready = false
    private val pending = ArrayDeque<ByteArray>()

    fun start() {
        val out = socket.getOutputStream()
        channel.registerObserver(object : DataChannel.Observer {
            override fun onMessage(buffer: DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining()).also { buffer.data.get(it) }
                if (!ready) {
                    if (String(bytes).trim().startsWith("OK")) {
                        ready = true
                        pending.forEach { channel.send(DataChannel.Buffer(java.nio.ByteBuffer.wrap(it), true)) }
                        pending.clear()
                    } else close()
                    return
                }
                try { out.write(bytes); out.flush() } catch (e: Exception) { close() }
            }
            override fun onStateChange() {
                if (channel.state() == DataChannel.State.CLOSED) close()
            }
            override fun onBufferedAmountChange(p0: Long) {}
        })
        scope.launch {
            val buf = ByteArray(4096)
            try {
                while (true) {
                    val n = socket.getInputStream().read(buf)
                    if (n < 0) break
                    val data = buf.copyOf(n)
                    if (ready) channel.send(DataChannel.Buffer(java.nio.ByteBuffer.wrap(data), true))
                    else pending.add(data)
                }
            } catch (_: Exception) {}
            close()
        }
    }

    private fun close() {
        try { socket.close() } catch (_: Exception) {}
        try { channel.close() } catch (_: Exception) {}
        scope.cancel(); latch.countDown()
    }

    suspend fun waitForClose() = withContext(Dispatchers.IO) { latch.await() }
}
