package com.example.webrtctunnel

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var etServerUrl: EditText
    private lateinit var btnConnect: Button
    private lateinit var btnDisconnect: Button
    private lateinit var tvStatus: TextView

    private val vpnLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) startVpn()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etServerUrl   = findViewById(R.id.etServerUrl)
        btnConnect    = findViewById(R.id.btnConnect)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        tvStatus      = findViewById(R.id.tvStatus)

        btnConnect.setOnClickListener {
            val intent = VpnService.prepare(this)
            if (intent != null) vpnLauncher.launch(intent)
            else startVpn()
        }

        btnDisconnect.setOnClickListener { stopVpn() }
    }

    private fun startVpn() {
        val url = etServerUrl.text.toString().trim().ifEmpty { "http://10.0.2.2:8080" }
        tvStatus.text = "Подключение к $url..."
        startForegroundService(
            Intent(this, TunnelVpnService::class.java).apply {
                action = TunnelVpnService.ACTION_START
                putExtra(TunnelVpnService.EXTRA_SERVER_URL, url)
            }
        )
        tvStatus.text = "Подключено ✅"
        btnConnect.isEnabled = false
        btnDisconnect.isEnabled = true
    }

    private fun stopVpn() {
        startService(Intent(this, TunnelVpnService::class.java).apply {
            action = TunnelVpnService.ACTION_STOP
        })
        tvStatus.text = "Отключено"
        btnConnect.isEnabled = true
        btnDisconnect.isEnabled = false
    }
}
