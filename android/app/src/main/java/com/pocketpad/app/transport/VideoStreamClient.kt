package com.pocketpad.app.transport

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.pocketpad.app.protocol.PacketCodec
import com.pocketpad.app.protocol.PocketPadConstants
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class VideoStreamSnapshot(
    val connected: Boolean = false,
    val activeFps: Int = 0,
    val isFullMenuView: Boolean = false,
    val statusText: String = "Écran désactivé",
)

/**
 * Dedicated low-latency TCP video receiver (port 27185) for PocketPad's Nintendo Switch mode.
 * Runs completely independently of ConnectionManager (port 27183) so video never slows down controls.
 * Supports USB ADB (127.0.0.1:27185), USB Tethering, Wi-Fi, AND Bluetooth Hybrid (video over LAN IP).
 */
object VideoStreamClient {
    private val workerExecutor = Executors.newSingleThreadExecutor()
    private val frameListeners = CopyOnWriteArrayList<(Bitmap, VideoStreamSnapshot) -> Unit>()
    private val statusListeners = CopyOnWriteArrayList<(VideoStreamSnapshot) -> Unit>()

    private val running = AtomicBoolean(false)
    private val sessionGen = AtomicInteger(0)

    @Volatile private var socket: Socket? = null
    @Volatile private var output: OutputStream? = null

    @Volatile private var targetHost: String = ""
    @Volatile private var targetPort: Int = PocketPadConstants.VIDEO_TCP_PORT
    @Volatile private var subscribedSlot: Int = 1
    @Volatile private var quality: Int = 62
    @Volatile private var maxWidth: Int = 854
    @Volatile private var maxHeight: Int = 480
    @Volatile private var paused: Boolean = false

    @Volatile var latestFrame: Bitmap? = null
        private set
    @Volatile var currentSnapshot: VideoStreamSnapshot = VideoStreamSnapshot()
        private set

    fun addFrameListener(listener: (Bitmap, VideoStreamSnapshot) -> Unit) {
        frameListeners.add(listener)
        latestFrame?.let { listener(it, currentSnapshot) }
    }

    fun removeFrameListener(listener: (Bitmap, VideoStreamSnapshot) -> Unit) {
        frameListeners.remove(listener)
    }

    fun addStatusListener(listener: (VideoStreamSnapshot) -> Unit) {
        statusListeners.add(listener)
        listener(currentSnapshot)
    }

    fun removeStatusListener(listener: (VideoStreamSnapshot) -> Unit) {
        statusListeners.remove(listener)
    }

    fun start(host: String, port: Int, slot: Int, jpegQuality: Int) {
        val cleanHost = host.trim()
        if (cleanHost.isBlank()) {
            stop("Vidéo indisponible (connecte en USB ou Wi-Fi)")
            return
        }

        val sameTarget = running.get() && cleanHost == targetHost && port == targetPort
        targetHost = cleanHost
        targetPort = port
        subscribedSlot = slot.coerceIn(1, PocketPadConstants.MAX_PLAYERS)
        quality = jpegQuality.coerceIn(35, 85)
        paused = false

        if (sameTarget) {
            sendControlPacketAsync(cmd = 1)
            return
        }

        val gen = sessionGen.incrementAndGet()
        running.set(true)
        closeSocketQuietly()
        publishStatus(VideoStreamSnapshot(false, 0, false, "Connexion vidéo…"))

        workerExecutor.execute {
            runReceiveLoop(gen)
        }
    }

    fun updateSubscription(slot: Int, jpegQuality: Int, isPaused: Boolean = false) {
        subscribedSlot = slot.coerceIn(1, PocketPadConstants.MAX_PLAYERS)
        quality = jpegQuality.coerceIn(35, 85)
        paused = isPaused
        if (running.get()) {
            sendControlPacketAsync(cmd = if (isPaused) 2 else 1)
        }
    }

    fun stop(reason: String = "Écran désactivé") {
        sessionGen.incrementAndGet()
        running.set(false)
        closeSocketQuietly()
        latestFrame = null
        publishStatus(VideoStreamSnapshot(false, 0, false, reason))
    }

    private fun sendControlPacketAsync(cmd: Int) {
        val out = output ?: return
        val pkt = PacketCodec.encodeVideoControl(cmd, subscribedSlot, maxWidth, maxHeight, quality)
        Thread {
            try {
                synchronized(this) {
                    out.write(pkt)
                    out.flush()
                }
            } catch (_: Exception) {
            }
        }.start()
    }

    private fun runReceiveLoop(gen: Int) {
        val decodeOpts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val headerBuf = ByteArray(12)

        while (running.get() && sessionGen.get() == gen) {
            try {
                val sock = Socket()
                sock.tcpNoDelay = true
                sock.receiveBufferSize = 131072
                sock.connect(InetSocketAddress(targetHost, targetPort), 2500)
                sock.soTimeout = 5000

                if (!running.get() || sessionGen.get() != gen) {
                    sock.close()
                    return
                }

                socket = sock
                output = sock.getOutputStream()
                val input = DataInputStream(sock.getInputStream())

                // Immediately subscribe to player slot
                val sub = PacketCodec.encodeVideoControl(
                    if (paused) 2 else 1,
                    subscribedSlot,
                    maxWidth,
                    maxHeight,
                    quality
                )
                synchronized(this) {
                    output?.write(sub)
                    output?.flush()
                }

                publishStatus(VideoStreamSnapshot(true, 0, false, "Flux vidéo actif"))

                var fpsCount = 0
                var fpsWindowStart = System.currentTimeMillis()
                var currentFps = 0

                while (running.get() && sessionGen.get() == gen) {
                    input.readFully(headerBuf)
                    if (headerBuf[0] != PocketPadConstants.VIDEO_MAGIC_0 ||
                        headerBuf[1] != PocketPadConstants.VIDEO_MAGIC_1
                    ) {
                        // Desync: reconnect cleanly
                        break
                    }

                    val bb = ByteBuffer.wrap(headerBuf).order(ByteOrder.LITTLE_ENDIAN)
                    val flags = bb.get(3).toInt() and 0xFF
                    val payloadLen = bb.getInt(8)
                    if (payloadLen <= 0 || payloadLen > PocketPadConstants.MAX_VIDEO_PAYLOAD) {
                        break
                    }

                    val jpeg = ByteArray(payloadLen)
                    input.readFully(jpeg)

                    val bmp = BitmapFactory.decodeByteArray(jpeg, 0, payloadLen, decodeOpts)
                    if (bmp != null) {
                        fpsCount++
                        val now = System.currentTimeMillis()
                        if (now - fpsWindowStart >= 1000L) {
                            currentFps = ((fpsCount * 1000L) / (now - fpsWindowStart).coerceAtLeast(1L)).toInt()
                            fpsCount = 0
                            fpsWindowStart = now
                        }

                        val isMenu = (flags and 0x01) != 0
                        val snap = VideoStreamSnapshot(
                            connected = true,
                            activeFps = currentFps,
                            isFullMenuView = isMenu,
                            statusText = if (isMenu) "Vue Menu · ${currentFps}fps" else "${currentFps}fps"
                        )
                        latestFrame = bmp
                        currentSnapshot = snap
                        for (l in frameListeners) {
                            try { l(bmp, snap) } catch (_: Exception) {}
                        }
                        for (s in statusListeners) {
                            try { s(snap) } catch (_: Exception) {}
                        }
                    }
                }
            } catch (_: Exception) {
                if (running.get() && sessionGen.get() == gen) {
                    publishStatus(VideoStreamSnapshot(false, 0, false, "Reconnexion vidéo…"))
                }
            } finally {
                closeSocketQuietly()
            }

            if (running.get() && sessionGen.get() == gen) {
                try { Thread.sleep(1200L) } catch (_: InterruptedException) { return }
            }
        }
    }

    private fun publishStatus(snap: VideoStreamSnapshot) {
        currentSnapshot = snap
        for (l in statusListeners) {
            try { l(snap) } catch (_: Exception) {}
        }
    }

    private fun closeSocketQuietly() {
        try { output?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        output = null
        socket = null
    }
}
