package net.isaeff.android.asproxy

import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
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
            byteArrayOf(5, 1, 0, 1) + InetAddress.getByName(host).address
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
