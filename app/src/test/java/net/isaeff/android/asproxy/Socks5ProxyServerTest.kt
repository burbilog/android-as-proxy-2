package net.isaeff.android.asproxy

import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit

class Socks5ProxyServerTest {
    private val proxies = mutableListOf<Socks5ProxyServer>()

    @After fun tearDown() {
        proxies.forEach { it.close() }
    }

    private fun startProxy(): Socks5ProxyServer =
        Socks5ProxyServer(port = 0).also { it.start(); proxies.add(it) }

    private fun connect(proxy: Socks5ProxyServer, host: String, port: Int, domain: Boolean): Socket {
        val client = Socket("127.0.0.1", proxy.boundPort)
        client.soTimeout = 10_000
        val out = client.getOutputStream()
        val input = DataInputStream(client.getInputStream())
        out.write(byteArrayOf(5, 1, 0))
        out.flush()
        assertArrayEquals(byteArrayOf(5, 0), ByteArray(2).also { input.readFully(it) })
        val target = if (domain) {
            val bytes = host.toByteArray(Charsets.US_ASCII)
            byteArrayOf(5, 1, 0, 3, bytes.size.toByte()) + bytes
        } else {
            val raw = InetAddress.getByName(host).address
            byteArrayOf(5, 1, 0, if (raw.size == 16) 4 else 1) + raw
        }
        out.write(target + byteArrayOf((port ushr 8).toByte(), port.toByte()))
        out.flush()
        val response = ByteArray(4).also { input.readFully(it) }
        assertEquals("SOCKS CONNECT failed with code ${response[1]}", 0, response[1].toInt())
        val addressLength = when (response[3].toInt()) {
            1 -> 4
            4 -> 16
            else -> error("unexpected address type")
        }
        input.readFully(ByteArray(addressLength + 2))
        return client
    }

    @Test fun domainConnectAndLargeFullDuplexTransfer() {
        val echoServer = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val echoThread = Thread {
            echoServer.use { listener ->
                listener.accept().use { peer ->
                    val buf = ByteArray(65536)
                    while (true) {
                        val n = peer.getInputStream().read(buf)
                        if (n < 0) break
                        peer.getOutputStream().write(buf, 0, n)
                    }
                    peer.shutdownOutput()
                }
            }
        }.apply { start() }

        val payload = ByteArray(4 * 1024 * 1024) { (it % 251).toByte() }
        val proxy = startProxy()
        connect(proxy, "localhost", echoServer.localPort, true).use { proxyClient ->
            val sender = Thread {
                proxyClient.getOutputStream().write(payload)
                proxyClient.shutdownOutput()
            }.apply { start() }
            val received = proxyClient.getInputStream().readBytes()
            sender.join(10_000)
            assertFalse("upload thread hung", sender.isAlive)
            assertArrayEquals(payload, received)
        }
        echoThread.join(10_000)
        assertFalse("echo server hung", echoThread.isAlive)
        assertEquals(payload.size.toLong(), proxy.bytesToInternet)
        assertEquals(payload.size.toLong(), proxy.bytesFromInternet)
    }

    @Test fun incompleteSocksHandshakeTimesOutAndReleasesSlot() {
        val proxy = Socks5ProxyServer(port = 0, maxConnections = 1,
            handshakeTimeoutMs = 200).also { it.start(); proxies.add(it) }
        Socket("127.0.0.1", proxy.boundPort).use { client ->
            client.soTimeout = 2_000
            client.getOutputStream().write(byteArrayOf(5, 1))
            assertEquals(-1, client.getInputStream().read())
        }
        // Socket EOF and semaphore release can occur a few instructions apart.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        var reused = false
        while (!reused && System.nanoTime() < deadline) {
            try {
                Socket("127.0.0.1", proxy.boundPort).use { client ->
                    client.soTimeout = 500
                    client.getOutputStream().write(byteArrayOf(5, 1, 2))
                    if (client.getInputStream().read() == 5) {
                        assertEquals(255, client.getInputStream().read())
                        reused = true
                    }
                }
            } catch (_: java.io.IOException) { }
            if (!reused) Thread.sleep(10)
        }
        assertTrue("Incomplete handshake must free its connection slot", reused)
    }

    @Test fun ipv6LiteralConnectWhenLoopbackSupported() {
        val address = InetAddress.getByName("::1")
        val echoServer = try {
            ServerSocket(0, 16, address)
        } catch (e: java.io.IOException) {
            org.junit.Assume.assumeNoException(e)
            return
        }
        echoServer.use { listener ->
            listener.soTimeout = 5_000
            val echoThread = Thread {
                listener.accept().use { peer ->
                    peer.soTimeout = 5_000
                    val data = ByteArray(4)
                    DataInputStream(peer.getInputStream()).readFully(data)
                    peer.getOutputStream().write(data)
                }
            }.apply { isDaemon = true; start() }
            val proxy = startProxy()
            connect(proxy, "::1", listener.localPort, domain = false).use { client ->
                client.getOutputStream().write(byteArrayOf(1, 2, 3, 4))
                assertArrayEquals(byteArrayOf(1, 2, 3, 4),
                    ByteArray(4).also { DataInputStream(client.getInputStream()).readFully(it) })
            }
            echoThread.join(5_000)
            assertFalse(echoThread.isAlive)
        }
    }

    @Test fun rejectsUnsupportedCommands() {
        val proxy = startProxy()
        Socket("127.0.0.1", proxy.boundPort).use { client ->
            client.soTimeout = 5_000
            val input = DataInputStream(client.getInputStream())
            val output = client.getOutputStream()
            output.write(byteArrayOf(5, 1, 0))
            output.flush()
            assertArrayEquals(byteArrayOf(5, 0), ByteArray(2).also(input::readFully))
            // UDP ASSOCIATE is unsupported and must not create any listener.
            output.write(byteArrayOf(5, 3, 0, 1, 127, 0, 0, 1, 0, 53))
            output.flush()
            val reply = ByteArray(10).also(input::readFully)
            assertEquals(5, reply[0].toInt())
            assertEquals(7, reply[1].toInt())
        }
    }

    @Test fun rejectsUnknownAuthenticationMethod() {
        val proxy = startProxy()
        Socket("127.0.0.1", proxy.boundPort).use { client ->
            client.soTimeout = 5_000
            client.getOutputStream().write(byteArrayOf(5, 1, 2))
            val response = ByteArray(2).also { DataInputStream(client.getInputStream()).readFully(it) }
            assertArrayEquals(byteArrayOf(5, 0xff.toByte()), response)
        }
    }

    @Test fun stalledResponseAfterRequestEofReleasesConnectionSlot() {
        val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val peerThread = Thread {
            server.use { listener ->
                listener.accept().use { peer ->
                    // Read the client's clean half-close, then deliberately
                    // stall the reply without any bytes until proxy times out.
                    while (peer.getInputStream().read() >= 0) { }
                    Thread.sleep(2_000)
                }
            }
        }.apply { isDaemon = true; start() }
        val proxy = Socks5ProxyServer(port = 0, maxConnections = 1,
            halfCloseIdleMs = 300L, readPollMs = 100).also {
                it.start(); proxies.add(it)
            }
        connect(proxy, "127.0.0.1", server.localPort, domain = false).use { client ->
            client.shutdownOutput()
            val limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (System.nanoTime() < limit && proxy.activeConnections > 0) Thread.sleep(25)
            assertEquals("Stalled half-closed stream must free SOCKS slot", 0, proxy.activeConnections)
        }
        peerThread.join(4_000)
        assertFalse(peerThread.isAlive)
    }

    @Test fun overCapacityConnectionsAreCountedAndDoNotCrashAcceptLoop() {
        val warnings = mutableListOf<Long>()
        val proxy = Socks5ProxyServer(port = 0, maxConnections = 1,
            onConnectionLimit = { synchronized(warnings) { warnings.add(it) } })
            .also { it.start(); proxies.add(it) }
        Socket("127.0.0.1", proxy.boundPort).use { first ->
            // Hold the only semaphore slot in the 15-second SOCKS handshake.
            first.soTimeout = 2_000
            val limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (System.nanoTime() < limit && proxy.activeConnections != 0) Thread.sleep(10)
            Socket("127.0.0.1", proxy.boundPort).use { second ->
                second.soTimeout = 2_000
                val byte = second.getInputStream().read()
                assertEquals("Over-capacity connection must be closed", -1, byte)
            }
            assertEquals(1L, proxy.rejectedConnections)
            assertEquals(listOf(1L), synchronized(warnings) { warnings.toList() })
        }
    }

    @Test fun rejectedResponsePumpDoesNotEscapeWorkerOrLeakConnection() {
        val submissions = AtomicInteger()
        val firstWorkerDone = CountDownLatch(1)
        val uncaught = LinkedBlockingQueue<Throwable>()
        // The first execute accepts the client; the second is the response
        // pump's submit. Reject it deterministically, as shutdownNow() can.
        val executor = object : ThreadPoolExecutor(
            0, Int.MAX_VALUE, 60L, TimeUnit.SECONDS, SynchronousQueue<Runnable>(),
            { task -> Thread(task, "AAP2-test-socks").apply {
                isDaemon = true
                uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, e ->
                    uncaught.offer(e)
                }
            } }
        ) {
            override fun execute(command: Runnable) {
                if (submissions.incrementAndGet() == 2)
                    throw RejectedExecutionException("simulated concurrent shutdown")
                super.execute(command)
            }
            override fun afterExecute(r: Runnable, t: Throwable?) {
                super.afterExecute(r, t)
                firstWorkerDone.countDown()
            }
        }
        ServerSocket(0, 16, InetAddress.getByName("127.0.0.1")).use { listener ->
            listener.soTimeout = 5_000
            val proxy = Socks5ProxyServer(port = 0, maxConnections = 1,
                workerExecutor = executor).also { it.start(); proxies.add(it) }
            try {
                connect(proxy, "127.0.0.1", listener.localPort, false).use { client ->
                    listener.accept().use { peer ->
                        peer.soTimeout = 5_000
                        assertEquals(-1, client.getInputStream().read())
                        assertEquals(-1, peer.getInputStream().read())
                    }
                }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (proxy.activeConnections != 0 && System.nanoTime() < deadline)
                    Thread.sleep(10)
                assertEquals(0, proxy.activeConnections)
                assertTrue(firstWorkerDone.await(5, TimeUnit.SECONDS))
                // The worker's finally must have returned the semaphore slot.
                Socket("127.0.0.1", proxy.boundPort).use { client ->
                    client.soTimeout = 5_000
                    client.getOutputStream().write(byteArrayOf(5, 1, 0))
                    assertArrayEquals(byteArrayOf(5, 0),
                        ByteArray(2).also(DataInputStream(client.getInputStream())::readFully))
                }
            } finally {
                proxy.close()
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            }
            assertNull("Worker exception must not reach Android's uncaught handler",
                uncaught.poll(500, TimeUnit.MILLISECONDS))
            assertEquals(0, proxy.activeConnections)
        }
    }

    @Test fun closeDuringActiveTransferClosesBothSides() {
        ServerSocket(0, 16, InetAddress.getByName("127.0.0.1")).use { listener ->
            val proxy = startProxy()
            listener.soTimeout = 5_000
            connect(proxy, "127.0.0.1", listener.localPort, false).use { client ->
                listener.accept().use { peer ->
                    peer.soTimeout = 5_000
                    client.getOutputStream().write(42)
                    assertEquals(42, peer.getInputStream().read())
                    proxy.close()
                    assertEquals(-1, client.getInputStream().read())
                    assertEquals(-1, peer.getInputStream().read())
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    while (proxy.activeConnections != 0 && System.nanoTime() < deadline)
                        Thread.sleep(10)
                    assertEquals(0, proxy.activeConnections)
                }
            }
        }
    }

    @Test fun multipleConnectionsDoNotShareAThroughputCap() {
        val echoServer = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val executor = Executors.newFixedThreadPool(8)
        try {
            val serverJob = executor.submit {
                repeat(4) {
                    val peer = echoServer.accept()
                    executor.execute {
                        peer.use {
                            val buffer = ByteArray(16384)
                            while (true) {
                                val len = it.getInputStream().read(buffer)
                                if (len == -1) break
                                it.getOutputStream().write(buffer, 0, len)
                            }
                            it.shutdownOutput()
                        }
                    }
                }
            }
            val proxy = startProxy()
            val futures = (0 until 4).map { idx ->
                executor.submit<Boolean> {
                    val bytes = ByteArray(128 * 1024) { (it + idx).toByte() }
                    connect(proxy, "127.0.0.1", echoServer.localPort, false).use { client ->
                        val sender = Thread {
                            client.getOutputStream().write(bytes)
                            client.shutdownOutput()
                        }.apply { start() }
                        val echoed = client.getInputStream().readBytes()
                        sender.join(5000)
                        !sender.isAlive && bytes.contentEquals(echoed)
                    }
                }
            }
            for (future in futures) assertTrue(future.get(15, TimeUnit.SECONDS))
            serverJob.get(5, TimeUnit.SECONDS)
        } finally {
            echoServer.close()
            executor.shutdownNow()
        }
    }
}
