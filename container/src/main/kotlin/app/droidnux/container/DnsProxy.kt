package app.droidnux.container

import android.net.DnsResolver
import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Answers the guest's DNS queries with Android's resolver (DnsResolver.rawQuery): Private DNS, VPN
 * DNS and the system cache apply, as for Android apps. glibc talks to the network's servers
 * directly otherwise, and some drop part of the parallel queries browsers send (a 5 s timeout
 * each). Burrow redirects 127.0.0.53:53, the guest's nameserver, to [port] (UDP and TCP).
 */
object DnsProxy {

    /** The guest's nameserver address, redirected by Burrow. */
    const val GUEST_NAMESERVER = "127.0.0.53"

    private const val TAG = "DroidNux.Dns"

    /** Runs the answer callbacks, which come on the main thread otherwise, where sockets are refused. */
    private val replies = Executors.newSingleThreadExecutor()

    /** The port on 127.0.0.1, 0 if the proxy could not start. */
    val port: Int by lazy { start() }

    private fun start(): Int = try {
        val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)) // Burrow redirects to IPv4
        val udp = DatagramSocket(InetSocketAddress(loopback, 0))
        val tcp = ServerSocket()
        tcp.bind(InetSocketAddress(loopback, udp.localPort))
        thread(name = "dns-udp", isDaemon = true) { serveUdp(udp) }
        thread(name = "dns-tcp", isDaemon = true) { serveTcp(tcp) }
        udp.localPort
    } catch (e: Exception) {
        Log.w(TAG, "cannot start the DNS proxy", e)
        0
    }

    private fun serveUdp(socket: DatagramSocket) {
        val buffer = ByteArray(65535)
        while (true) {
            val packet = DatagramPacket(buffer, buffer.size)
            socket.receive(packet)
            val query = packet.data.copyOf(packet.length)
            val client = packet.socketAddress
            resolve(query) { answer ->
                val reply = if (answer.size > udpLimit(query)) truncated(query, answer) else answer
                runCatching { socket.send(DatagramPacket(reply, reply.size, client)) }
            }
        }
    }

    private fun serveTcp(server: ServerSocket) {
        while (true) {
            val client = server.accept()
            thread(name = "dns-tcp-client", isDaemon = true) { serveTcpClient(client) }
        }
    }

    private fun serveTcpClient(client: Socket) = client.use {
        val input = DataInputStream(it.getInputStream())
        val output = DataOutputStream(it.getOutputStream())
        runCatching {
            while (true) {
                val query = ByteArray(input.readUnsignedShort()).also(input::readFully)
                val lock = Object()
                var answer: ByteArray? = null
                resolve(query) { a -> synchronized(lock) { answer = a; lock.notifyAll() } }
                val reply = synchronized(lock) {
                    while (answer == null) lock.wait()
                    answer!!
                }
                output.writeShort(reply.size)
                output.write(reply)
                output.flush()
            }
        }
    }

    /** Resolves [query] on the default network; [reply] gets the answer, or SERVFAIL. */
    private fun resolve(query: ByteArray, reply: (ByteArray) -> Unit) {
        if (query.size < HEADER) return
        try {
            DnsResolver.getInstance().rawQuery(
                null, query, DnsResolver.FLAG_EMPTY, replies, null,
                object : DnsResolver.Callback<ByteArray> {
                    override fun onAnswer(answer: ByteArray, rcode: Int) {
                        if (answer.size < HEADER) return reply(failure(query))
                        // The guest matches answers by id.
                        answer[0] = query[0]
                        answer[1] = query[1]
                        reply(answer)
                    }

                    override fun onError(error: DnsResolver.DnsException) = reply(failure(query))
                },
            )
        } catch (e: Exception) {
            reply(failure(query))
        }
    }

    /** The end of the question section of [message], or -1. */
    private fun questionEnd(message: ByteArray): Int {
        var at = HEADER
        repeat(u16(message, 4)) {
            while (at < message.size && message[at].toInt() != 0) {
                if (message[at].toInt() and 0xc0 == 0xc0) { at++; break }
                at += (message[at].toInt() and 0xff) + 1
            }
            at += 1 + 4 // the root label, type and class
        }
        return if (at <= message.size) at else -1
    }

    /** What the client takes over UDP: 512 bytes, or its EDNS buffer size. */
    private fun udpLimit(query: ByteArray): Int {
        val end = questionEnd(query)
        // An OPT record: root name, type 41, class = the buffer size.
        if (end < 0 || u16(query, 10) == 0 || end + 5 > query.size) return 512
        if (query[end].toInt() != 0 || u16(query, end + 1) != 41) return 512
        return maxOf(512, u16(query, end + 3))
    }

    /** The answer's header with TC set and the question only: the client asks again over TCP. */
    private fun truncated(query: ByteArray, answer: ByteArray): ByteArray {
        val end = questionEnd(query).takeIf { it > 0 } ?: return failure(query)
        return query.copyOf(end).also {
            it[2] = (answer[2].toInt() or 0x02).toByte() // TC
            it[3] = answer[3]
            for (i in 6 until HEADER) it[i] = 0
        }
    }

    private fun failure(query: ByteArray): ByteArray {
        val end = questionEnd(query).takeIf { it > 0 } ?: HEADER
        return query.copyOf(minOf(end, query.size)).also {
            it[2] = (0x80 or (query[2].toInt() and 0x79)).toByte() // QR, the query's opcode and RD
            it[3] = 0x82.toByte() // RA, SERVFAIL
            for (i in 6 until HEADER) it[i] = 0
        }
    }

    private fun u16(b: ByteArray, at: Int) = (b[at].toInt() and 0xff shl 8) or (b[at + 1].toInt() and 0xff)

    private const val HEADER = 12
}
