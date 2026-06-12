// ════════════════════════════════════════════
// app/build.gradle
// ════════════════════════════════════════════
android {
    compileSdk 34
    defaultConfig {
        applicationId "com.example.webrtctunnel"
        minSdk 26
        targetSdk 34
    }
    buildFeatures { viewBinding true }
    packagingOptions {
        // WebRTC .so конфликты
        pickFirst "lib/x86/libjingle_peerconnection_so.so"
        pickFirst "lib/x86_64/libjingle_peerconnection_so.so"
        pickFirst "lib/armeabi-v7a/libjingle_peerconnection_so.so"
        pickFirst "lib/arm64-v8a/libjingle_peerconnection_so.so"
    }
}

dependencies {
    // WebRTC
    implementation 'org.webrtc:google-webrtc:1.0.32006'

    // HTTP клиент (сигнализация)
    implementation 'com.squareup.okhttp3:okhttp:4.12.0'

    // Coroutines
    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3'
    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3'

    // Lifecycle
    implementation 'androidx.lifecycle:lifecycle-runtime-ktx:2.7.0'
    implementation 'androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0'
}


// ════════════════════════════════════════════
// AndroidManifest.xml — необходимые разрешения
// ════════════════════════════════════════════
/*
<manifest ...>
    <uses-permission android:name="android.permission.INTERNET"/>
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
    <uses-permission android:name="android.permission.BIND_VPN_SERVICE"/>

    <application ...>

        <!-- VPN Service -->
        <service
            android:name=".TunnelVpnService"
            android:permission="android.permission.BIND_VPN_SERVICE"
            android:exported="false">
            <intent-filter>
                <action android:name="android.net.VpnService"/>
            </intent-filter>
        </service>

    </application>
</manifest>
*/


// ════════════════════════════════════════════
// MainActivity.kt — как запустить
// ════════════════════════════════════════════
/*
class MainActivity : AppCompatActivity() {

    private val vpnLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) startVpn()
    }

    fun connectTunnel(serverUrl: String) {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            // Запрашиваем разрешение на VPN
            vpnLauncher.launch(intent)
        } else {
            startVpn(serverUrl)
        }
    }

    private fun startVpn(serverUrl: String = "http://your-server:8080") {
        val intent = Intent(this, TunnelVpnService::class.java).apply {
            action = TunnelVpnService.ACTION_START
            putExtra(TunnelVpnService.EXTRA_SERVER_URL, serverUrl)
        }
        startForegroundService(intent)
    }

    fun disconnectTunnel() {
        startService(Intent(this, TunnelVpnService::class.java).apply {
            action = TunnelVpnService.ACTION_STOP
        })
    }
}
*/
