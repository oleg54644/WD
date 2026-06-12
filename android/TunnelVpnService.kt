// TunnelVpnService.kt
// Перехватывает весь трафик устройства через VpnService API
// и перенаправляет через WebRTC DataChannel туннель

package com.example.webrtctunnel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel

private const val TAG = "TunnelVpnService"
private const val NOTIF_ID = 1
private const val CHANNEL_ID = "webrtc_tunnel"

/**
 * VpnService перехватывает весь IP-трафик устройства.
 * Каждый TCP-поток разбирается (мини tun2socks) и туннелируется через WebRTC.
 *
 * Упрощённая архитектура (production нужен полноценный tun2socks):
 *   TUN interface → parse IP/TCP headers → WebRTC DataChannel → Server → Internet
 *
 * Для production используйте: https://github.com/eycorsican/go-tun2socks
 * или Tun2Socks library для Android.
 */
class TunnelVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var tunnelClient: WebRtcTunnelClient? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        const val ACTION_START = "START"
        const val ACTION_STOP  = "STOP"
        const val EXTRA_SERVER_URL = "server_url"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val url = intent.getStringExtra(EXTRA_SERVER_URL) ?: "http://10.0.2.2:8080"
                startForeground(NOTIF_ID, buildNotification("Connecting..."))
                scope.launch { startTunnel(url) }
            }
            ACTION_STOP -> stopTunnel()
        }
        return START_STICKY
    }

    private suspend fun startTunnel(serverUrl: String) {
        try {
            // 1. Инициализируем WebRTC клиент
            val client = WebRtcTunnelClient(
                signalingUrl = serverUrl,
                localProxyPort = 10800,
                appContext = applicationContext
            )
            client.init()
            client.connect()
            tunnelClient = client

            // 2. Поднимаем локальный SOCKS5 прокси на порту 10800
            client.startLocalProxy()

            // 3. Создаём TUN интерфейс
            val builder = Builder()
                .setSession("WebRTC Tunnel")
                .addAddress("10.8.0.1", 24)
                .addRoute("0.0.0.0", 0)           // весь IPv4 трафик
                .addDnsServer("8.8.8.8")
                .setMtu(1500)

            // Исключаем само приложение (иначе петля)
            builder.addDisallowedApplication(packageName)

            vpnInterface = builder.establish()
            updateNotification("Connected via WebRTC")

            Log.i(TAG, "VPN interface established")

            // 4. Запускаем tun2socks мост
            //    (упрощённо — в production используйте tun2socks binary или библиотеку)
            startTun2Socks()

        } catch (e: Exception) {
            Log.e(TAG, "Tunnel start failed", e)
            updateNotification("Error: ${e.message}")
        }
    }

    /**
     * Упрощённый TUN→SOCKS5 мост.
     *
     * В реальном приложении здесь нужен полноценный TCP/IP стек.
     * Рекомендую использовать:
     *   - go-tun2socks (скомпилированный .so для Android)
     *   - shadowsocks-android tun2socks
     *   - или библиотеку lwIP
     *
     * Ниже — демонстрационный код для UDP:
     */
    private suspend fun startTun2Socks() = withContext(Dispatchers.IO) {
        val tunFd = vpnInterface?.fileDescriptor ?: return@withContext
        val inputStream  = FileInputStream(tunFd)
        val outputStream = FileOutputStream(tunFd)

        val packet = ByteArray(32767)

        // В production здесь полноценный IP/TCP парсер
        // Для демонстрации логируем пакеты
        while (isActive) {
            try {
                val len = inputStream.read(packet)
                if (len > 0) {
                    val buf = ByteBuffer.wrap(packet, 0, len)
                    handleIpPacket(buf, outputStream)
                }
            } catch (e: Exception) {
                if (isActive) Log.e(TAG, "TUN read error", e)
                break
            }
        }
    }

    private fun handleIpPacket(buf: ByteBuffer, out: FileOutputStream) {
        // Минимальный IP header парсинг
        if (buf.remaining() < 20) return

        val firstByte = buf.get(0).toInt()
        val version = (firstByte shr 4) and 0xF
        if (version != 4) return  // только IPv4

        val protocol = buf.get(9).toInt() and 0xFF
        // 6 = TCP, 17 = UDP, 1 = ICMP

        val srcIp = "${buf.get(12).toInt() and 0xFF}.${buf.get(13).toInt() and 0xFF}" +
                    ".${buf.get(14).toInt() and 0xFF}.${buf.get(15).toInt() and 0xFF}"
        val dstIp = "${buf.get(16).toInt() and 0xFF}.${buf.get(17).toInt() and 0xFF}" +
                    ".${buf.get(18).toInt() and 0xFF}.${buf.get(19).toInt() and 0xFF}"

        Log.v(TAG, "IP packet: proto=$protocol $srcIp→$dstIp")

        // В полной реализации здесь TCP SYN перехватывается и
        // создаётся SOCKS5 соединение через WebRTC туннель
    }

    private fun stopTunnel() {
        tunnelClient?.stop()
        vpnInterface?.close()
        vpnInterface = null
        scope.cancel()
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        stopTunnel()
        super.onDestroy()
    }

    // ──── Notification ────

    private fun buildNotification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "WebRTC Tunnel", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("WebRTC Tunnel")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }
}
