![](./icon.ico)

# WebRTC Tunnel

P2P туннель: Android ↔ Python сервер через WebRTC DataChannel.
Обходит NAT, не требует открытых портов на клиенте.

## Структура

```
webrtc_tunnel/
├── server/
│   ├── server.py          # Python сигнальный + прокси сервер
│   └── requirements.txt
└── android/
    ├── WebRtcTunnelClient.kt   # WebRTC + SOCKS5 клиент
    ├── TunnelVpnService.kt     # Системный VPN перехватчик
    └── build_and_manifest.kt   # Gradle / Manifest фрагменты
```

## Быстрый старт

### Сервер
```bash
cd server
pip install -r requirements.txt
python server.py --host 0.0.0.0 --port 8080
```

### Android
1. Добавить зависимости в build.gradle (см. build_and_manifest.kt)
2. Указать URL сервера: `WebRtcTunnelClient(signalingUrl = "http://YOUR_SERVER:8080")`
3. Для SOCKS5 режима: `client.connect(); client.startLocalProxy()`
4. Для VPN режима: запустить `TunnelVpnService`

### Проверка туннеля (curl через SOCKS5)
```bash
# Через SOCKS5 прокси на порту 1080 (Android устройство)
curl --proxy socks5h://localhost:1080 http://example.com
```

## Как работает туннелирование

### Протокол поверх DataChannel
```
Android DataChannel → "CONNECT api.example.com:443\n"
Server              → "OK\n"
Android DataChannel → <raw TLS bytes>
Server TCP socket   → <forwarded to api.example.com:443>
```

### NAT Traversal
1. STUN (stun.l.google.com) — определяет публичный IP/порт
2. ICE — перебирает кандидатов (host → srflx → relay)
3. DTLS — шифрует DataChannel (встроено в WebRTC)
4. При строгом симметричном NAT — добавить TURN сервер

### Режимы работы

| Режим       | Настройка          | Охват                    |
|-------------|-------------------|--------------------------|
| SOCKS5 прокси | port 1080        | Приложения с прокси      |
| VPN Service  | системно          | Весь трафик устройства   |
| Per-app      | через ProxyInfo   | Конкретные приложения    |

## TURN сервер (для строгого NAT)

```bash
# coturn
apt install coturn
turnserver -u user:pass -r realm --no-tls
```

В Android клиенте раскомментировать:
```kotlin
PeerConnection.IceServer.builder("turn:your-server:3478")
    .setUsername("user").setPassword("pass").createIceServer()
```

## Безопасность
- DataChannel шифруется DTLS 1.2/1.3 (обязательно в WebRTC)
- Сигнальный сервер работает на HTTP — в проде добавить TLS (nginx reverse proxy)
- Аутентификацию на /offer добавить через Bearer token
"# WD-"  
"# WD-" 
