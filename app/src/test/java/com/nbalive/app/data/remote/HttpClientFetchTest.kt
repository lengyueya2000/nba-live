package com.nbalive.app.data.remote

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.runBlocking
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * `HttpClient.fetch` 的状态码 -> [FetchResult] 分类。
 *
 * ⚠️ 这条映射是整套"限流不重试"设计的**地基**：
 * 仓库层拿到 [FetchResult.Rejected] 才不重试，拿到 [FetchResult.Unreachable] 才重试。
 * 映射一旦被改坏（比如某次重构把 403 落进 Unreachable 分支），
 * 仓库层的测试**照样全绿**（它们直接构造 Rejected 喂进去），
 * 而真实行为退回"被限流还打 3 次"——恰恰是用户最关心的问题。
 *
 * 所以这里必须测真正产生 Rejected 的那一层。
 *
 * 用裸 Socket 写一个最小 HTTP 服务器（不能用 com.sun.net.httpserver：
 * Android 单元测试的 classpath 里没有它）。
 */
class HttpClientFetchTest {

    private lateinit var server: ServerSocket
    private lateinit var client: HttpClient
    private var port: Int = 0
    private var serverThread: Thread? = null

    @Before
    fun setUp() {
        client = HttpClient()
    }

    @After
    fun tearDown() {
        try {
            server.close()
        } catch (_: Exception) {
            // 可能没起过
        }
        serverThread?.interrupt()
    }

    /** 起一个只回固定状态码的最小 HTTP 服务器 */
    private fun serve(status: Int, body: String) {
        server = ServerSocket(0)
        port = server.localPort
        serverThread = Thread {
            val pool = Executors.newSingleThreadExecutor()
            try {
                while (!server.isClosed) {
                    val socket: Socket = server.accept()
                    pool.execute {
                        try {
                            socket.use { s ->
                                // 读完请求头（直到空行）
                                val input = s.getInputStream().bufferedReader()
                                while (true) {
                                    val line = input.readLine() ?: break
                                    if (line.isEmpty()) break
                                }
                                val bytes = body.toByteArray()
                                val head = buildString {
                                    append("HTTP/1.1 $status ${reasonOf(status)}\r\n")
                                    append("Content-Type: text/plain; charset=utf-8\r\n")
                                    append("Content-Length: ${bytes.size}\r\n")
                                    append("Connection: close\r\n\r\n")
                                }
                                val out: OutputStream = s.getOutputStream()
                                out.write(head.toByteArray())
                                out.write(bytes)
                                out.flush()
                            }
                        } catch (_: Exception) {
                            // 客户端断开，忽略
                        }
                    }
                }
            } catch (_: Exception) {
                // 服务器关闭
            } finally {
                pool.shutdown()
            }
        }
        serverThread?.isDaemon = true
        serverThread?.start()
    }

    private fun reasonOf(status: Int) = when (status) {
        200 -> "OK"
        403 -> "Forbidden"
        404 -> "Not Found"
        429 -> "Too Many Requests"
        500 -> "Internal Server Error"
        else -> "Unknown"
    }

    private fun url() = "http://127.0.0.1:$port/x"

    @Test
    fun `200 返回 Ok`() = runBlocking {
        serve(200, "#EXTM3U")
        val r = client.fetch(url())
        assertTrue("200 应该是 Ok，实际: $r", r is FetchResult.Ok)
        assertEquals("#EXTM3U", (r as FetchResult.Ok).body)
    }

    @Test
    fun `403 判为 Rejected 不得重试`() = runBlocking {
        serve(403, "blocked")
        val r = client.fetch(url())
        assertTrue("403 是源站明确拒绝，必须判 Rejected，实际: $r", r is FetchResult.Rejected)
        assertEquals(403, (r as FetchResult.Rejected).code)
    }

    @Test
    fun `429 判为 Rejected 不得重试`() = runBlocking {
        serve(429, "too many")
        val r = client.fetch(url())
        assertTrue("429 是限流，必须判 Rejected，实际: $r", r is FetchResult.Rejected)
        assertEquals(429, (r as FetchResult.Rejected).code)
    }

    @Test
    fun `500 判为 Unreachable 可以重试`() = runBlocking {
        serve(500, "server error")
        val r = client.fetch(url())
        assertTrue("500 是服务端故障，值得重试，实际: $r", r is FetchResult.Unreachable)
    }

    @Test
    fun `404 判为 Unreachable 可以重试`() = runBlocking {
        serve(404, "not found")
        val r = client.fetch(url())
        assertTrue("404 是线路下线，值得换一条重试，实际: $r", r is FetchResult.Unreachable)
    }

    @Test
    fun `连接失败判为 Unreachable`() = runBlocking {
        // 端口 1 基本连不上
        val r = client.fetch("http://127.0.0.1:1/x")
        assertTrue("网络不通应判 Unreachable，实际: $r", r is FetchResult.Unreachable)
    }
}
