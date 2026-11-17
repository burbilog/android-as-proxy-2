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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
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
class Socks5ProxyServer internal constructor(
    private val port: Int = 1080,
    private val maxConnections: Int = 64,
    private val connectTimeoutMs: Int = 15_000,
    private val onConnectionLimit: ((Long) -> Unit)? = null,
    private val halfCloseIdleMs: Long = 180_000L,
    private val readPollMs: Int = 30_000,
    private val handshakeTimeoutMs: Int = 15_000,
    workerExecutor: ExecutorService? = null
) : AutoCloseable {
    private val limit = Semaphore(maxConnections)
    private val rejectedCounter = AtomicLong()
    val rejectedConnections: Long get() = rejectedCounter.get()

    /** Actual payload bytes sent towards the destination and back to the SOCKS client. */
    private val uplinkCounter = AtomicLong()
    private val downlinkCounter = AtomicLong()
    private val activeCounter = AtomicInteger()
    val bytesToInternet: Long get() = uplinkCounter.get()
    val bytesFromInternet: Long get() = downlinkCounter.get()
    val activeConnections: Int get() = activeCounter.get()
    private val openSockets = ConcurrentHashMap.newKeySet<Socket>()
    private val threadNumber = AtomicInteger()
    private val workers = workerExecutor ?: Executors.newCachedThreadPool { runnable ->
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
                    if (running) {
                        val rejected = rejectedCounter.incrementAndGet()
                        if (rejected == 1L || rejected % 32L == 0L) {
                            try { onConnectionLimit?.invoke(rejected) }
                            catch (_: RuntimeException) { /* logging cannot break accept */ }
                        }
                    }
                    client.close()
                    continue
                }
                openSockets.add(client)
                try {
                    workers.execute {
                        try {
                            handleClient(client)
                        } catch (e: Exception) {
                            // A worker-side submit can race close()/shutdownNow().
                            // Uncaught RuntimeExceptions on Android can kill the
                            // entire process, not just the connection.
                            if (running) java.util.logging.Logger.getLogger("AAP2-SOCKS")
                                .warning("SOCKS worker failed: " + e.javaClass.simpleName)
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
            client.keepAlive = true
            client.soTimeout = handshakeTimeoutMs // Handshake only; not a relay idle timeout.
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

            val peer = try {
                connectToTarget(host, targetPort)
            } catch (e: IOException) {
                sendFailure(output, when (e) {
                    is ConnectException -> 0x05
                    is NoRouteToHostException, is UnknownHostException -> 0x04
                    is SocketTimeoutException -> 0x06
                    else -> 0x01
                })
                return
            }
            upstream = peer

            val address = peer.localAddress.address
            val type = if (address.size == 4) 0x01 else 0x04
            output.write(byteArrayOf(0x05, 0x00, 0x00, type.toByte()))
            output.write(address)
            output.write((peer.localPort ushr 8) and 255)
            output.write(peer.localPort and 255)
            output.flush()

            // Timeouts only check for a *stalled direction after the other
            // direction reached EOF*. Idle but fully open TCP stays alive.
            client.soTimeout = readPollMs
            peer.soTimeout = readPollMs
            activeCounter.incrementAndGet()
            try {
                val uplinkDoneAt = AtomicLong(0L)
                val downlinkDoneAt = AtomicLong(0L)
                val responsePump = workers.submit {
                    try {
                        copy(peer, client, downlinkCounter, uplinkDoneAt)
                    } finally {
                        downlinkDoneAt.set(System.nanoTime())
                        try { client.shutdownOutput() } catch (_: IOException) { }
                    }
                }
                try {
                    copy(client, peer, uplinkCounter, downlinkDoneAt)
                } finally {
                    uplinkDoneAt.set(System.nanoTime())
                    try { peer.shutdownOutput() } catch (_: IOException) { }
                }
                try {
                    responsePump.get()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                } catch (_: java.util.concurrent.ExecutionException) {
                    // Peer closed during shutdown/transfer.
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

    /** DNS is resolved on Android; try all returned IPs (IPv6/IPv4 fallback).
     *  DNS lookup itself uses Android's resolver and is not covered by TCP timeout.
     */
    private fun connectToTarget(host: String, port: Int): Socket {
        val addresses = InetAddress.getAllByName(host)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(connectTimeoutMs.toLong())
        var last: IOException? = null
        for (address in addresses) {
            val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (remainingMs <= 0L) break
            val peer = Socket()
            openSockets.add(peer)
            try {
                if (!running) throw SocketException("SOCKS server stopped")
                peer.tcpNoDelay = true
                peer.keepAlive = true
                // Limit an unusable first IPv6 address to 5 seconds.
                peer.connect(InetSocketAddress(address, port),
                    minOf(5000L, remainingMs).coerceAtLeast(1L).toInt())
                return peer
            } catch (e: IOException) {
                last = e
                closeTracked(peer)
                if (!running) throw e
            } catch (e: Exception) {
                // The upstream may not yet have been handed to handleClient().
                closeTracked(peer)
                throw e
            }
        }
        throw last ?: SocketTimeoutException("No usable address before TCP deadline")
    }

    private fun copy(
        source: Socket, destination: Socket, byteCount: AtomicLong,
        oppositeDoneAtNs: AtomicLong
    ) {
        val buffer = ByteArray(64 * 1024)
        var lastProgressNs = System.nanoTime()
        try {
            val input = source.getInputStream()
            val output = destination.getOutputStream()
            while (running) {
                val count = try {
                    input.read(buffer)
                } catch (_: SocketTimeoutException) {
                    val otherFinished = oppositeDoneAtNs.get()
                    if (otherFinished != 0L &&
                        TimeUnit.NANOSECONDS.toMillis(
                            System.nanoTime() - maxOf(lastProgressNs, otherFinished)
                        ) >= halfCloseIdleMs) break
                    continue
                }
                if (count == -1) break
                output.write(buffer, 0, count)
                lastProgressNs = System.nanoTime()
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
