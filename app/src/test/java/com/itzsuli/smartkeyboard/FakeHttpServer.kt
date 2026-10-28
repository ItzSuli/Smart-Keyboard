package com.itzsuli.smartkeyboard

import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/** A minimal HTTP/1.1 server for tests: one request per connection, fixed JSON reply. */
class FakeHttpServer : AutoCloseable {
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    @Volatile var lastPath = ""
    @Volatile var lastBody = ""
    @Volatile var lastHeaders = mapOf<String, String>()
    @Volatile var reply = ""
    @Volatile var requests = 0

    val baseUrl get() = "http://127.0.0.1:${server.localPort}"

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                socket.use { sock ->
                    val input = BufferedInputStream(sock.getInputStream())
                    fun readLine(): String {
                        val sb = StringBuilder()
                        while (true) {
                            val b = input.read()
                            if (b < 0 || b == '\n'.code) break
                            if (b != '\r'.code) sb.append(b.toChar())
                        }
                        return sb.toString()
                    }
                    val requestLine = readLine()
                    val headers = HashMap<String, String>()
                    while (true) {
                        val line = readLine()
                        if (line.isEmpty()) break
                        val i = line.indexOf(':')
                        if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
                    }
                    val length = headers["content-length"]?.toIntOrNull() ?: 0
                    val body = ByteArray(length)
                    var read = 0
                    while (read < length) {
                        val n = input.read(body, read, length - read)
                        if (n < 0) break
                        read += n
                    }
                    lastPath = requestLine.split(' ').getOrElse(1) { "" }
                    lastHeaders = headers
                    lastBody = String(body, Charsets.UTF_8)
                    requests++
                    val bytes = reply.toByteArray()
                    val out = sock.getOutputStream()
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    out.write(bytes)
                    out.flush()
                }
            }
        }
    }

    override fun close() = server.close()
}
