package com.pocketpad.app.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.Build
import com.google.gson.Gson
import com.pocketpad.app.protocol.FrameStreamReader
import com.pocketpad.app.protocol.GamepadState
import com.pocketpad.app.protocol.PacketCodec
import com.pocketpad.app.protocol.PacketType
import com.pocketpad.app.protocol.PocketPadConstants
import com.pocketpad.app.protocol.RumbleCommand
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

enum class TransportKind { NONE, USB_ADB, USB_TETHER, BLUETOOTH, WIFI }

data class DiscoveredServer(
    val pcName: String,
    val ip: String,
    val port: Int,
    val vigemReady: Boolean,
    val connectedPlayers: Int = 0,
    val maxPlayers: Int = PocketPadConstants.MAX_PLAYERS,
)

data class ConnectionState(
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val transport: TransportKind = TransportKind.NONE,
    val transportLabel: String = "Déconnecté",
    val pcName: String = "",
    val vigemReady: Boolean = false,
    val playerSlot: Int = 0,
    val maxPlayers: Int = PocketPadConstants.MAX_PLAYERS,
    val connectedPlayers: Int = 0,
    val slotColorHex: String = "#38BDF8",
    val videoHost: String = "",
    val videoPort: Int = PocketPadConstants.VIDEO_TCP_PORT,
    val serverVideoEnabled: Boolean = true,
    val splitPreset: String = "FullShared",
    val pingMs: Int = -1,
    val statusDetail: String = "Choisis Câble USB, Bluetooth ou Wi-Fi pour te connecter au PC (1 à 4 joueurs)",
    val lastError: String? = null,
)

private data class ServerInfoJson(
    val pcName: String? = null,
    val vigemReady: Boolean? = null,
    val mode: String? = null,
    val version: String? = null,
    val playerSlot: Int? = null,
    val maxPlayers: Int? = null,
    val connectedPlayers: Int? = null,
    val slotColor: String? = null,
    val videoPort: Int? = null,
    val videoEnabled: Boolean? = null,
    val splitPreset: String? = null,
    val lanIp: String? = null,
)

/**
 * Unified singleton transport manager shared across MainActivity, GamepadActivity, and EditorActivity.
 * Supports:
 *  - USB ADB Reverse (127.0.0.1:27183, < 2ms latency)
 *  - USB Tethering (UDP discovery + gateway probe)
 *  - Bluetooth Classic RFCOMM (dedicated PocketPad UUID)
 *  - Wi-Fi / LAN TCP + UDP discovery
 */
object ConnectionManager {
    private val gson = Gson()
    private val listeners = CopyOnWriteArraySet<(ConnectionState) -> Unit>()
    private val rumbleListeners = CopyOnWriteArraySet<(RumbleCommand) -> Unit>()

    @Volatile
    var currentState: ConnectionState = ConnectionState()
        private set

    private val running = AtomicBoolean(false)
    private val writeQueue = LinkedBlockingQueue<ByteArray>(64)

    @Volatile private var tcpSocket: Socket? = null
    @Volatile private var btSocket: BluetoothSocket? = null
    @Volatile private var activeOutput: OutputStream? = null
    @Volatile private var activeInput: InputStream? = null
    @Volatile private var activeProfileName: String = "Xbox 360 Classique"
    @Volatile private var clientId: String = ""
    @Volatile private var preferredSlot: Int = 0

    fun configureIdentity(deviceClientId: String, desiredSlot: Int) {
        clientId = deviceClientId
        preferredSlot = desiredSlot.coerceIn(0, PocketPadConstants.MAX_PLAYERS)
    }

    fun requestPreferredSlot(desiredSlot: Int) {
        preferredSlot = desiredSlot.coerceIn(0, PocketPadConstants.MAX_PLAYERS)
        if (currentState.connected) {
            sendHelloFrame()
        }
    }

    fun addStateListener(listener: (ConnectionState) -> Unit) {
        listeners.add(listener)
        listener(currentState)
    }

    fun removeStateListener(listener: (ConnectionState) -> Unit) {
        listeners.remove(listener)
    }

    fun addRumbleListener(listener: (RumbleCommand) -> Unit) {
        rumbleListeners.add(listener)
    }

    fun removeRumbleListener(listener: (RumbleCommand) -> Unit) {
        rumbleListeners.remove(listener)
    }

    private fun updateState(newState: ConnectionState) {
        currentState = newState
        for (l in listeners) {
            try { l(newState) } catch (_: Exception) {}
        }
    }

    fun notifyActiveProfile(profileName: String) {
        activeProfileName = profileName
        if (currentState.connected) {
            sendHelloFrame()
        }
    }

    private fun sendHelloFrame() {
        val transportTag = when (currentState.transport) {
            TransportKind.BLUETOOTH -> "bluetooth"
            TransportKind.USB_ADB, TransportKind.USB_TETHER -> "usb"
            else -> "wifi"
        }
        enqueueFrame(
            PacketCodec.encodeHello(
                name = deviceModelName(),
                profile = activeProfileName,
                transport = transportTag,
                clientId = clientId,
                preferredSlot = preferredSlot,
            )
        )
    }

    fun sendGamepadState(state: GamepadState) {
        if (!currentState.connected) return
        // Drop stale queued gamepad frames if socket is momentarily busy (zero-lag guarantee!)
        if (writeQueue.size > 6) {
            writeQueue.clear()
        }
        writeQueue.offer(PacketCodec.encodeGamepadState(state))
    }

    fun sendKeyboardEvent(vkCode: Int, action: Byte) {
        if (!currentState.connected) return
        enqueueFrame(PacketCodec.encodeKeyboardEvent(vkCode, action))
    }

    fun sendMouseEvent(dx: Int, dy: Int, wheel: Int, buttonAction: Byte) {
        if (!currentState.connected) return
        enqueueFrame(PacketCodec.encodeMouseEvent(dx, dy, wheel, buttonAction))
    }

    fun sendResetInputs() {
        if (!currentState.connected) return
        enqueueFrame(PacketCodec.encodeResetInputs())
    }

    private fun enqueueFrame(frame: ByteArray) {
        if (!writeQueue.offer(frame)) {
            writeQueue.poll()
            writeQueue.offer(frame)
        }
    }

    /**
     * 1-tap USB Auto-Connect:
     *  1) Tries ADB reverse loopback (127.0.0.1:27183) -> < 2 ms!
     *  2) Broadcasts UDP discovery (27184) -> finds PC over USB Tethering or LAN
     *  3) Probes common Android USB Tethering host gateways (192.168.42.129 + active interface gateways)
     */
    fun connectUsbAuto(profileName: String, onDone: ((Boolean, String) -> Unit)? = null) {
        activeProfileName = profileName
        disconnect(silent = true)
        updateState(
            ConnectionState(
                connecting = true,
                transportLabel = "Recherche USB…",
                statusDetail = "Connexion au tunnel USB ADB puis Partage USB…"
            )
        )

        Thread {
            // Step 1: ADB Reverse loopback (127.0.0.1:27183)
            val adbSocket = tryConnectTcp("127.0.0.1", PocketPadConstants.TCP_PORT, 650)
            if (adbSocket != null) {
                startSession(adbSocket.getInputStream(), adbSocket.getOutputStream(), TransportKind.USB_ADB, "Câble USB (ADB)", "PC via USB", tcp = adbSocket)
                onDone?.invoke(true, "Connecté via câble USB (ADB < 2 ms) !")
                return@Thread
            }

            // Step 2: UDP Discovery (catches USB Tethering & LAN PCs automatically)
            val discovered = discoverServersBlocking(900)
            for (srv in discovered) {
                val sock = tryConnectTcp(srv.ip, srv.port, 900)
                if (sock != null) {
                    val isUsb = isUsbSubnet(srv.ip)
                    val kind = if (isUsb) TransportKind.USB_TETHER else TransportKind.WIFI
                    val label = if (isUsb) "Câble USB (Partage)" else "Réseau (${srv.ip})"
                    startSession(sock.getInputStream(), sock.getOutputStream(), kind, label, srv.pcName, tcp = sock)
                    onDone?.invoke(true, "Connecté à ${srv.pcName} ($label) !")
                    return@Thread
                }
            }

            // Step 3: Probe common USB Tethering host IPs
            val candidates = buildUsbGatewayCandidates()
            for (ip in candidates) {
                val sock = tryConnectTcp(ip, PocketPadConstants.TCP_PORT, 450)
                if (sock != null) {
                    startSession(sock.getInputStream(), sock.getOutputStream(), TransportKind.USB_TETHER, "Câble USB (Partage)", ip, tcp = sock)
                    onDone?.invoke(true, "Connecté via Partage USB ($ip) !")
                    return@Thread
                }
            }

            val errMsg = "PC introuvable en USB. Vérifie que PocketPad.exe est ouvert sur ton PC et active « Débogage USB » (ou « Partage de connexion USB »)."
            updateState(
                ConnectionState(
                    connected = false,
                    connecting = false,
                    transportLabel = "Déconnecté",
                    statusDetail = errMsg,
                    lastError = errMsg,
                )
            )
            onDone?.invoke(false, errMsg)
        }.start()
    }

    fun connectTcp(ip: String, port: Int = PocketPadConstants.TCP_PORT, profileName: String, onDone: ((Boolean, String) -> Unit)? = null) {
        activeProfileName = profileName
        disconnect(silent = true)
        updateState(
            ConnectionState(
                connecting = true,
                transportLabel = "Connexion à $ip…",
                statusDetail = "Connexion TCP $ip:$port en cours…"
            )
        )

        Thread {
            val sock = tryConnectTcp(ip.trim(), port, 2500)
            if (sock != null) {
                val kind = when {
                    ip.trim() == "127.0.0.1" -> TransportKind.USB_ADB
                    isUsbSubnet(ip.trim()) -> TransportKind.USB_TETHER
                    else -> TransportKind.WIFI
                }
                val label = when (kind) {
                    TransportKind.USB_ADB -> "Câble USB (ADB)"
                    TransportKind.USB_TETHER -> "Câble USB (Partage)"
                    else -> "Wi-Fi ($ip)"
                }
                startSession(sock.getInputStream(), sock.getOutputStream(), kind, label, ip.trim(), tcp = sock)
                onDone?.invoke(true, "Connecté à $ip !")
            } else {
                val errMsg = "Impossible de joindre $ip:$port. Vérifie que PocketPad.exe est lancé et que le pare-feu Windows autorise PocketPad."
                updateState(
                    ConnectionState(
                        connected = false,
                        connecting = false,
                        transportLabel = "Déconnecté",
                        statusDetail = errMsg,
                        lastError = errMsg,
                    )
                )
                onDone?.invoke(false, errMsg)
            }
        }.start()
    }

    @SuppressLint("MissingPermission")
    fun connectBluetooth(
        adapter: BluetoothAdapter,
        device: BluetoothDevice,
        profileName: String,
        onDone: ((Boolean, String) -> Unit)? = null,
    ) {
        activeProfileName = profileName
        disconnect(silent = true)
        val devName = try { device.name ?: device.address } catch (_: Exception) { device.address }
        updateState(
            ConnectionState(
                connecting = true,
                transportLabel = "Bluetooth…",
                statusDetail = "Connexion Bluetooth vers $devName…"
            )
        )

        Thread {
            try {
                try { adapter.cancelDiscovery() } catch (_: Exception) {}

                var sock: BluetoothSocket? = null
                try {
                    sock = device.createRfcommSocketToServiceRecord(PocketPadConstants.BLUETOOTH_SERVICE_UUID)
                    sock.connect()
                } catch (_: Exception) {
                    try { sock?.close() } catch (_: Exception) {}
                    sock = device.createInsecureRfcommSocketToServiceRecord(PocketPadConstants.BLUETOOTH_SERVICE_UUID)
                    sock.connect()
                }

                startSession(
                    sock!!.inputStream,
                    sock.outputStream,
                    TransportKind.BLUETOOTH,
                    "Bluetooth",
                    devName,
                    bt = sock,
                )
                onDone?.invoke(true, "Connecté en Bluetooth à $devName !")
            } catch (ex: Exception) {
                val errMsg = "Connexion Bluetooth échouée vers $devName. Vérifie que PocketPad.exe est ouvert sur le PC et que le PC est bien appairé (${ex.message})."
                updateState(
                    ConnectionState(
                        connected = false,
                        connecting = false,
                        transportLabel = "Déconnecté",
                        statusDetail = errMsg,
                        lastError = errMsg,
                    )
                )
                onDone?.invoke(false, errMsg)
            }
        }.start()
    }

    fun discoverServersBlocking(timeoutMs: Int = 1100): List<DiscoveredServer> {
        val found = linkedMapOf<String, DiscoveredServer>()
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket().apply {
                broadcast = true
                soTimeout = 300
            }
            val reqBytes = PocketPadConstants.DISCOVERY_REQUEST.toByteArray(Charsets.UTF_8)
            val targets = linkedSetOf("255.255.255.255")
            for (bcast in getInterfaceBroadcastAddresses()) targets.add(bcast)

            for (addr in targets) {
                try {
                    val p = DatagramPacket(reqBytes, reqBytes.size, InetAddress.getByName(addr), PocketPadConstants.UDP_DISCOVERY_PORT)
                    socket.send(p)
                } catch (_: Exception) {}
            }

            val deadline = System.currentTimeMillis() + timeoutMs
            val recvBuf = ByteArray(512)
            while (System.currentTimeMillis() < deadline) {
                try {
                    val dp = DatagramPacket(recvBuf, recvBuf.size)
                    socket.receive(dp)
                    val text = String(dp.data, 0, dp.length, Charsets.UTF_8).trim()
                    if (text.startsWith(PocketPadConstants.DISCOVERY_RESPONSE_PREFIX)) {
                        val parts = text.split("|")
                        val pcName = parts.getOrNull(1) ?: dp.address.hostAddress ?: "PC"
                        val port = parts.getOrNull(2)?.toIntOrNull() ?: PocketPadConstants.TCP_PORT
                        val vigem = parts.getOrNull(3) == "1"
                        val connectedCount = parts.getOrNull(4)?.toIntOrNull() ?: 0
                        val maxCount = parts.getOrNull(5)?.toIntOrNull() ?: PocketPadConstants.MAX_PLAYERS
                        val ip = dp.address.hostAddress ?: continue
                        found[ip] = DiscoveredServer(pcName, ip, port, vigem, connectedCount, maxCount)
                    }
                } catch (_: Exception) {
                    // Timeout slice
                }
            }
        } catch (_: Exception) {
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
        return found.values.toList()
    }

    private fun startSession(
        input: InputStream,
        output: OutputStream,
        kind: TransportKind,
        label: String,
        pcFallbackName: String,
        tcp: Socket? = null,
        bt: BluetoothSocket? = null,
    ) {
        tcpSocket = tcp
        btSocket = bt
        activeInput = input
        activeOutput = output
        writeQueue.clear()
        running.set(true)

        val resolvedHost = when (kind) {
            TransportKind.USB_ADB -> "127.0.0.1"
            else -> tcp?.inetAddress?.hostAddress ?: ""
        }

        updateState(
            ConnectionState(
                connected = true,
                connecting = false,
                transport = kind,
                transportLabel = label,
                pcName = pcFallbackName,
                vigemReady = false,
                playerSlot = if (preferredSlot in 1..PocketPadConstants.MAX_PLAYERS) preferredSlot else 1,
                maxPlayers = PocketPadConstants.MAX_PLAYERS,
                connectedPlayers = 1,
                slotColorHex = PocketPadConstants.slotColorHex(if (preferredSlot in 1..PocketPadConstants.MAX_PLAYERS) preferredSlot else 1),
                videoHost = resolvedHost,
                videoPort = PocketPadConstants.VIDEO_TCP_PORT,
                serverVideoEnabled = true,
                pingMs = -1,
                statusDetail = "Connecté à $pcFallbackName ($label)",
                lastError = null,
            )
        )

        sendHelloFrame()

        // Dedicated non-blocking writer thread
        Thread({
            try {
                while (running.get()) {
                    val frame = writeQueue.take()
                    val out = activeOutput ?: break
                    out.write(frame)
                    out.flush()
                }
            } catch (_: Exception) {
                if (running.get()) {
                    disconnect(silent = false, reason = "Connexion interrompue avec le PC")
                }
            }
        }, "PocketPad-Writer").start()

        // Dedicated ping thread (every 2 seconds)
        Thread({
            try {
                while (running.get()) {
                    enqueueFrame(PacketCodec.encodePing(System.currentTimeMillis()))
                    Thread.sleep(2000)
                }
            } catch (_: Exception) {
            }
        }, "PocketPad-Ping").start()

        // Dedicated reader thread (Pong, Rumble, ServerInfo)
        Thread({
            val reader = FrameStreamReader()
            val buf = ByteArray(1024)
            try {
                while (running.get()) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    reader.feed(buf, read) { pkt ->
                        when (pkt.type) {
                            PacketType.PONG -> {
                                val sent = PacketCodec.decodeTimestamp(pkt.payload)
                                if (sent != null) {
                                    val rtt = (System.currentTimeMillis() - sent).coerceIn(0, 9999).toInt()
                                    val cur = currentState
                                    if (cur.connected) {
                                        updateState(cur.copy(pingMs = rtt))
                                    }
                                }
                            }
                            PacketType.RUMBLE -> {
                                val rumble = PacketCodec.decodeRumble(pkt.payload)
                                if (rumble != null) {
                                    for (rl in rumbleListeners) {
                                        try { rl(rumble) } catch (_: Exception) {}
                                    }
                                }
                            }
                            PacketType.SERVER_INFO -> {
                                try {
                                    val info = gson.fromJson(String(pkt.payload, Charsets.UTF_8), ServerInfoJson::class.java)
                                    val cur = currentState
                                    if (cur.connected && info != null) {
                                        val name = info.pcName?.takeIf { it.isNotBlank() } ?: cur.pcName
                                        val vigem = info.vigemReady ?: cur.vigemReady
                                        val slot = info.playerSlot?.takeIf { it in 1..PocketPadConstants.MAX_PLAYERS } ?: cur.playerSlot.coerceAtLeast(1)
                                        val maxP = info.maxPlayers ?: PocketPadConstants.MAX_PLAYERS
                                        val connP = info.connectedPlayers ?: cur.connectedPlayers.coerceAtLeast(1)
                                        val sColor = info.slotColor?.takeIf { it.startsWith("#") } ?: PocketPadConstants.slotColorHex(slot)
                                        val vPort = info.videoPort?.takeIf { it > 0 } ?: PocketPadConstants.VIDEO_TCP_PORT
                                        val vEnabled = info.videoEnabled ?: cur.serverVideoEnabled
                                        val vSplit = info.splitPreset?.takeIf { it.isNotBlank() } ?: cur.splitPreset
                                        val vHost = cur.videoHost.ifBlank { info.lanIp?.trim().orEmpty() }
                                        val vigemText = if (vigem) "Xbox 360 J$slot + Clavier/Souris" else "Clavier/Souris actif"
                                        updateState(
                                            cur.copy(
                                                pcName = name,
                                                vigemReady = vigem,
                                                playerSlot = slot,
                                                maxPlayers = maxP,
                                                connectedPlayers = connP,
                                                slotColorHex = sColor,
                                                videoHost = vHost,
                                                videoPort = vPort,
                                                serverVideoEnabled = vEnabled,
                                                splitPreset = vSplit,
                                                statusDetail = "$name · ${cur.transportLabel} · $connP/$maxP joueurs · $vigemText"
                                            )
                                        )
                                    }
                                } catch (_: Exception) {}
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                if (running.get()) {
                    disconnect(silent = false, reason = "Déconnecté du PC")
                }
            }
        }, "PocketPad-Reader").start()
    }

    fun disconnect(silent: Boolean = false, reason: String? = null) {
        val wasConnected = running.getAndSet(false)
        if (wasConnected) {
            try {
                activeOutput?.write(PacketCodec.encodeResetInputs())
                activeOutput?.flush()
            } catch (_: Exception) {}
        }

        writeQueue.clear()
        VideoStreamClient.stop("Déconnecté")
        try { tcpSocket?.close() } catch (_: Exception) {}
        try { btSocket?.close() } catch (_: Exception) {}
        tcpSocket = null
        btSocket = null
        activeInput = null
        activeOutput = null

        if (!silent) {
            updateState(
                ConnectionState(
                    connected = false,
                    connecting = false,
                    transport = TransportKind.NONE,
                    transportLabel = "Déconnecté",
                    statusDetail = reason ?: "Déconnecté. Branche en USB ou choisis Bluetooth.",
                )
            )
        }
    }

    private fun tryConnectTcp(host: String, port: Int, timeoutMs: Int): Socket? {
        return try {
            val s = Socket()
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(host, port), timeoutMs)
            s
        } catch (_: Exception) {
            null
        }
    }

    private fun isUsbSubnet(ip: String): Boolean =
        ip.startsWith("192.168.42.") || ip.startsWith("192.168.43.") || ip.startsWith("172.20.10.")

    private fun buildUsbGatewayCandidates(): List<String> {
        val set = linkedSetOf("192.168.42.129")
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces()
            while (ifaces != null && ifaces.hasMoreElements()) {
                val ni = ifaces.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                val nName = ni.name.lowercase()
                if (nName.contains("rndis") || nName.contains("usb") || nName.contains("eth")) {
                    for (ia in ni.interfaceAddresses) {
                        val addr = ia.address
                        if (addr is Inet4Address) {
                            val parts = addr.hostAddress?.split(".") ?: continue
                            if (parts.size == 4) {
                                val prefix = "${parts[0]}.${parts[1]}.${parts[2]}"
                                set.add("$prefix.1")
                                set.add("$prefix.129")
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return set.toList()
    }

    private fun getInterfaceBroadcastAddresses(): List<String> {
        val list = mutableListOf<String>()
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces()
            while (ifaces != null && ifaces.hasMoreElements()) {
                val ni = ifaces.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) {
                    val b = ia.broadcast?.hostAddress
                    if (!b.isNullOrBlank() && b != "0.0.0.0") list.add(b)
                }
            }
        } catch (_: Exception) {}
        return list
    }

    private fun deviceModelName(): String {
        val m = Build.MANUFACTURER?.trim().orEmpty()
        val model = Build.MODEL?.trim().orEmpty()
        return if (model.startsWith(m, ignoreCase = true)) model else "$m $model".trim().ifBlank { "Android" }
    }
}
