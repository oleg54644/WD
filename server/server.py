"""
WebRTC Tunnel Server
====================
Roles:
  1. Signaling server  — HTTP/WS endpoints for SDP offer/answer + ICE candidates
  2. Tunnel endpoint   — каждый DataChannel становится TCP-прокси к целевому хосту

Flow:
  Android  →  POST /offer        →  Server creates RTCPeerConnection
  Server   →  returns answer SDP
  Android  →  POST /ice          →  trickle ICE candidates
  WebRTC DataChannel opens
  Android sends:  CONNECT <host>:<port>\n   (SOCKS5-lite header)
  Server opens TCP socket to <host>:<port>
  All subsequent DataChannel messages are raw bytes proxied bidirectionally
"""

import asyncio
import json
import logging
import uuid
from dataclasses import dataclass, field
from typing import Dict, Optional

from aiohttp import web
import aiohttp_cors
from aiortc import RTCPeerConnection, RTCSessionDescription, RTCDataChannel
from aiortc.contrib.signaling import object_from_string, object_to_string

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
log = logging.getLogger("webrtc_tunnel")

# ──────────────────────────────────────────────
# Session state
# ──────────────────────────────────────────────

@dataclass
class Session:
    id: str
    pc: RTCPeerConnection
    ice_queue: asyncio.Queue = field(default_factory=asyncio.Queue)
    channels: Dict[str, RTCDataChannel] = field(default_factory=dict)


sessions: Dict[str, Session] = {}


# ──────────────────────────────────────────────
# TCP proxy over DataChannel
# ──────────────────────────────────────────────

HEADER_MAX = 256  # bytes for "CONNECT host:port\n"


async def proxy_tcp(channel: RTCDataChannel, host: str, port: int):
    """Bidirectional proxy between DataChannel and a TCP socket."""
    log.info("Proxy: connecting to %s:%s", host, port)
    try:
        reader, writer = await asyncio.open_connection(host, port)
    except Exception as e:
        err_msg = json.dumps({"error": f"Cannot connect to {host}:{port}: {e}"})
        channel.send(err_msg.encode())
        channel.close()
        return

    channel.send(b"OK\n")  # signal to client that tunnel is up

    stop = asyncio.Event()

    # TCP → DataChannel
    async def tcp_to_dc():
        try:
            while not stop.is_set():
                data = await reader.read(4096)
                if not data:
                    break
                channel.send(data)
        except Exception as e:
            log.warning("tcp_to_dc error: %s", e)
        finally:
            stop.set()
            channel.close()

    # DataChannel → TCP  (handled via on_message callback, stored in a queue)
    send_queue: asyncio.Queue[Optional[bytes]] = asyncio.Queue()

    original_on_message = channel.on("message", None)  # may be None

    @channel.on("message")
    def on_message(data):
        send_queue.put_nowait(data if isinstance(data, bytes) else data.encode())

    @channel.on("close")
    def on_close():
        send_queue.put_nowait(None)
        stop.set()

    async def dc_to_tcp():
        try:
            while not stop.is_set():
                data = await send_queue.get()
                if data is None:
                    break
                writer.write(data)
                await writer.drain()
        except Exception as e:
            log.warning("dc_to_tcp error: %s", e)
        finally:
            stop.set()
            writer.close()

    await asyncio.gather(tcp_to_dc(), dc_to_tcp(), return_exceptions=True)
    log.info("Proxy closed for %s:%s", host, port)


# ──────────────────────────────────────────────
# DataChannel handler — SOCKS5-lite handshake
# ──────────────────────────────────────────────

HANDSHAKE_TIMEOUT = 10  # seconds


async def handle_channel(channel: RTCDataChannel):
    """
    Wait for first message:  CONNECT <host>:<port>\n
    Then start TCP proxy.
    """
    log.info("DataChannel opened: label=%s", channel.label)
    loop = asyncio.get_event_loop()
    fut: asyncio.Future[bytes] = loop.create_future()

    @channel.on("message")
    def first_message(data):
        if not fut.done():
            fut.set_result(data if isinstance(data, bytes) else data.encode())

    try:
        raw = await asyncio.wait_for(fut, timeout=HANDSHAKE_TIMEOUT)
    except asyncio.TimeoutError:
        log.warning("Handshake timeout on channel %s", channel.label)
        channel.close()
        return

    text = raw.decode(errors="replace").strip()
    if not text.startswith("CONNECT "):
        log.warning("Bad handshake: %r", text)
        channel.send(b"ERROR bad handshake\n")
        channel.close()
        return

    target = text[len("CONNECT "):].strip()
    if ":" not in target:
        channel.send(b"ERROR missing port\n")
        channel.close()
        return

    host, port_str = target.rsplit(":", 1)
    try:
        port = int(port_str)
    except ValueError:
        channel.send(b"ERROR invalid port\n")
        channel.close()
        return

    # Remove the first_message handler; proxy_tcp will install its own
    channel._handlers.pop("message", None)

    asyncio.create_task(proxy_tcp(channel, host, port))


# ──────────────────────────────────────────────
# HTTP handlers
# ──────────────────────────────────────────────

async def handle_offer(request: web.Request) -> web.Response:
    """
    POST /offer
    Body: { "sdp": "...", "type": "offer" }
    Returns: { "sdp": "...", "type": "answer", "session_id": "..." }
    """
    body = await request.json()
    session_id = str(uuid.uuid4())

    pc = RTCPeerConnection()
    session = Session(id=session_id, pc=pc)
    sessions[session_id] = session

    @pc.on("datachannel")
    def on_datachannel(channel: RTCDataChannel):
        session.channels[channel.label] = channel
        asyncio.ensure_future(handle_channel(channel))

    @pc.on("icecandidate")
    async def on_icecandidate(candidate):
        if candidate:
            await session.ice_queue.put({
                "candidate": candidate.to_sdp(),
                "sdpMid": candidate.sdpMid,
                "sdpMLineIndex": candidate.sdpMLineIndex,
            })

    @pc.on("connectionstatechange")
    async def on_state_change():
        log.info("Session %s state: %s", session_id, pc.connectionState)
        if pc.connectionState in ("failed", "closed"):
            sessions.pop(session_id, None)
            await pc.close()

    offer = RTCSessionDescription(sdp=body["sdp"], type=body["type"])
    await pc.setRemoteDescription(offer)
    answer = await pc.createAnswer()
    await pc.setLocalDescription(answer)

    return web.json_response({
        "sdp": pc.localDescription.sdp,
        "type": pc.localDescription.type,
        "session_id": session_id,
    })


async def handle_ice(request: web.Request) -> web.Response:
    """
    POST /ice
    Body: { "session_id": "...", "candidate": "...", "sdpMid": "...", "sdpMLineIndex": 0 }
    """
    body = await request.json()
    session_id = body.get("session_id")
    session = sessions.get(session_id)
    if not session:
        raise web.HTTPNotFound(text="Session not found")

    from aiortc.sdp import candidate_from_sdp
    from aiortc import RTCIceCandidate

    candidate_str = body.get("candidate", "")
    if candidate_str:
        candidate = candidate_from_sdp(candidate_str.split("candidate:")[-1])
        candidate.sdpMid = body.get("sdpMid")
        candidate.sdpMLineIndex = body.get("sdpMLineIndex", 0)
        await session.pc.addIceCandidate(candidate)

    return web.json_response({"ok": True})


async def handle_ice_poll(request: web.Request) -> web.Response:
    """
    GET /ice?session_id=...
    Returns queued server-side ICE candidates (long-poll style).
    """
    session_id = request.rel_url.query.get("session_id")
    session = sessions.get(session_id)
    if not session:
        raise web.HTTPNotFound(text="Session not found")

    candidates = []
    try:
        # drain up to 20 candidates with a short wait
        for _ in range(20):
            c = await asyncio.wait_for(session.ice_queue.get(), timeout=1.0)
            candidates.append(c)
    except asyncio.TimeoutError:
        pass

    return web.json_response({"candidates": candidates})


async def handle_status(request: web.Request) -> web.Response:
    return web.json_response({
        "sessions": len(sessions),
        "session_ids": list(sessions.keys()),
    })


# ──────────────────────────────────────────────
# App factory
# ──────────────────────────────────────────────

def create_app() -> web.Application:
    app = web.Application()

    cors = aiohttp_cors.setup(app, defaults={
        "*": aiohttp_cors.ResourceOptions(
            allow_credentials=True,
            expose_headers="*",
            allow_headers="*",
            allow_methods=["GET", "POST", "OPTIONS"],
        )
    })

    routes = [
        app.router.add_post("/offer", handle_offer),
        app.router.add_post("/ice", handle_ice),
        app.router.add_get("/ice", handle_ice_poll),
        app.router.add_get("/status", handle_status),
    ]
    for route in routes:
        cors.add(route)

    return app


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser(description="WebRTC Tunnel Server")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8080)
    args = parser.parse_args()

    app = create_app()
    log.info("Starting WebRTC Tunnel Server on %s:%s", args.host, args.port)
    web.run_app(app, host=args.host, port=args.port)
