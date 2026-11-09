package net.isaeff.android.asproxy

import java.io.DataInputStream
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Minimal SOCKS5 CONNECT proxy (RFC 1928), intentionally bound to IPv4 loopback.
 *
 * It has no authentication: only the SSH client's local-forwarded connections may reach it.
 * No SOCKS4, BIND, UDP ASSOCIATE, DNS interception or Android-specific dependencies.
 *
 * Each established connection uses two independent, blocking 64 KiB relays. A socket read
 * in one direction never delays the other direction (unlike the old polling SOCKS library).
 */
class Socks5ProxyServer(
    private val port: Int = 1080,
    private val maxConnections: Int = 64,
    private val connectTimeoutMs: Int = 15_000
) : AutoCloseable {
    private val limit = Semaphore(maxConnections)

    /** Actual payload bytes sent towards the destination and back to the SOCKS client. */
    private val uplinkCounter = AtomicLong()
    private val downlinkCounter = AtomicLong()
    private val activeCounter = AtomicInteger()
    val bytesToInternet: Long get() = uplinkCounter.get()
    val bytesFromInternet: Long get() = downlinkCounter.get()
    val activeConnections: Int get() = activeCounter.get()
    private val openSockets = ConcurrentHashMap.newKeySet<Socket>()
    private val threadNumber = AtomicInteger()
    private val workers = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "AAP2-socks-${threadNumber.incrementAndGet()}").apply { isDaemon = true }
    }

    @Volatile private var listener: ServerSocket? = null
    @Volatile private var running = false
    private var acceptThread: Thread? = null

    val boundPort: Int get() = listener?.localPort ?: throw IllegalStateException("Not started")

    @Synchronized
    fun start() {
        if (running) return
        check(!workers.isShutdown) { "Stopped SOCKS server cannot be restarted; create another instance" }
        val socket = ServerSocket()
        try {
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
        } catch (e: Exception) {
            socket.close()
            throw e
        }
        listener = socket
        running = true
        acceptThread = Thread({ acceptLoop(socket) }, "AAP2-socks-accept").apply {
            isDaemon = true
            start()
        }
    }

    private fun acceptLoop(server: ServerSocket) {
        while (running) {
            try {
                val client = server.accept()
                if (!running || !limit.tryAcquire()) {
                    client.close()
                    continue
                }
                openSockets.add(client)
                try {
                    workers.execute {
                        try {
                            handleClient(client)
                        } finally {
                            closeTracked(client)
                            limit.release()
                        }
                    }
                } catch (e: RuntimeException) {
                    closeTracked(client)
                    limit.release()
                    if (!running) return
                }
            } catch (_: SocketException) {
                if (!running) return
            } catch (_: IOException) {
                if (!running) return
            }
        }
    }

    private fun handleClient(client: Socket) {
        var upstream: Socket? = null
        try {
            client.tcpNoDelay = true
            client.soTimeout = 15_000 // Handshake only; established tunnels have no idle timeout.
            val input = DataInputStream(client.getInputStream())
            val output = client.getOutputStream()

            if (input.readUnsignedByte() != 0x05) return
            val methodCount = input.readUnsignedByte()
            if (methodCount == 0) return
            val methods = ByteArray(methodCount)
            input.readFully(methods)
            if (methods.none { (it.toInt() and 255) == 0x00 }) {
                output.write(byteArrayOf(0x05, 0xff.toByte()))
                output.flush()
                return
            }
            output.write(byteArrayOf(0x05, 0x00))
            output.flush()

            if (input.readUnsignedByte() != 0x05) return
            val command = input.readUnsignedByte()
            input.readUnsignedByte() // RSV
            val addressType = input.readUnsignedByte()
            val host = when (addressType) {
                0x01 -> ByteArray(4).also(input::readFully).let { InetAddress.getByAddress(it).hostAddress }
                0x04 -> ByteArray(16).also(input::readFully).let { InetAddress.getByAddress(it).hostAddress }
                0x03 -> {
                    val length = input.readUnsignedByte()
                    if (length == 0) {
                        sendFailure(output, 0x08)
                        return
                    }
                    ByteArray(length).also(input::readFully).toString(StandardCharsets.US_ASCII)
                }
                else -> {
                    sendFailure(output, 0x08)
                    return
                }
            }
            val targetPort = input.readUnsignedShort()
            if (command != 0x01) {
                sendFailure(output, 0x07)
                return
            }
            if (targetPort == 0) {
                sendFailure(output, 0x01)
                return
            }

            val peer = Socket()
            upstream = peer
            openSockets.add(peer)
            try {
                peer.tcpNoDelay = true
                peer.connect(InetSocketAddress(host, targetPort), connectTimeoutMs)
            } catch (e: IOException) {
                sendFailure(output, when (e) {
                    is ConnectException -> 0x05
                    is NoRouteToHostException, is UnknownHostException -> 0x04
                    is SocketTimeoutException -> 0x06
                    else -> 0x01
                })
                return
            }

            val address = peer.localAddress.address
            val type = if (address.size == 4) 0x01 else 0x04
            output.write(byteArrayOf(0x05, 0x00, 0x00, type.toByte()))
            output.write(address)
            output.write((peer.localPort ushr 8) and 255)
            output.write(peer.localPort and 255)
            output.flush()

            client.soTimeout = 0
            activeCounter.incrementAndGet()
            try {
            // One side may be idle for hours; never poll it before reading the other direction.
            // Shutdown only the corresponding output after EOF to preserve TCP half-close.
            val responsePump = workers.submit {
                try {
                    copy(peer, client, downlinkCounter)
                } finally {
                    try { client.shutdownOutput() } catch (_: IOException) { }
                }
            }
            try {
                copy(client, peer, uplinkCounter)
            } finally {
                try { peer.shutdownOutput() } catch (_: IOException) { }
            }
            try {
                responsePump.get()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (_: java.util.concurrent.ExecutionException) {
                // Peer closed during shutdown/transfer; socket cleanup below is sufficient.
            }
            } finally {
                activeCounter.decrementAndGet()
            }
        } catch (_: IOException) {
            // Connections may disappear normally at any point during SOCKS negotiation.
        } finally {
            if (upstream != null) closeTracked(upstream)
        }
    }

    private fun copy(source: Socket, destination: Socket, byteCount: AtomicLong) {
        val buffer = ByteArray(64 * 1024)
        try {
            val input = source.getInputStream()
            val output = destination.getOutputStream()
            while (running) {
                val count = input.read(buffer)
                if (count == -1) break
                output.write(buffer, 0, count)
                byteCount.addAndGet(count.toLong())
            }
            output.flush()
        } catch (_: IOException) {
            // Remote closed/reset, or stop() closed sockets.
        }
    }

    private fun sendFailure(output: java.io.OutputStream, code: Int) {
        output.write(byteArrayOf(0x05, code.toByte(), 0, 0x01, 0, 0, 0, 0, 0, 0))
        output.flush()
    }

    private fun closeTracked(socket: Socket) {
        openSockets.remove(socket)
        try { socket.close() } catch (_: IOException) { }
    }

    @Synchronized
    override fun close() {
        running = false
        try { listener?.close() } catch (_: IOException) { }
        listener = null
        openSockets.toList().forEach(::closeTracked)
        workers.shutdownNow()
        acceptThread?.interrupt()
        acceptThread = null
    }
}
