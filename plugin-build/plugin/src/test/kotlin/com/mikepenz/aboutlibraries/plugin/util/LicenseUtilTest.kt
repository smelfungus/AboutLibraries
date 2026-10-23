package com.mikepenz.aboutlibraries.plugin.util

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.UUID

/**
 * How [LicenseUtil.loadLicenseCached] tells a license text that couldn't be downloaded apart from
 * one the server doesn't have. Each test uses a URL of its own, since the cache is process-wide.
 */
class LicenseUtilTest {

    private lateinit var server: HttpServer
    private lateinit var baseUrl: String

    @BeforeEach
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/text") { exchange ->
            val body = "License text".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/missing") { exchange ->
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @AfterEach
    fun stopServer() {
        server.stop(0)
    }

    @Test
    fun `a downloaded text is returned and not reported`() {
        val url = "$baseUrl/text?${UUID.randomUUID()}"
        assertEquals("License text", LicenseUtil.loadLicenseCached(url))
        assertFalse(LicenseUtil.isUnreachable(url))
    }

    /** SPDX answers 404 for the licenses it has no text for, such as the non-SPDX `ASDKL`. */
    @Test
    fun `a text the server doesn't have is not reported`() {
        val url = "$baseUrl/missing?${UUID.randomUUID()}"
        assertNull(LicenseUtil.loadLicenseCached(url))
        assertFalse(LicenseUtil.isUnreachable(url))
    }

    @Test
    fun `a request that gets no response is reported`() {
        val closedPort = ServerSocket(0).use { it.localPort }
        val url = "http://127.0.0.1:$closedPort/text?${UUID.randomUUID()}"
        assertNull(LicenseUtil.loadLicenseCached(url))
        assertTrue(LicenseUtil.isUnreachable(url))
        // A cached failure stays reported, so later licenses with the same text are reported too.
        assertNull(LicenseUtil.loadLicenseCached(url))
        assertTrue(LicenseUtil.isUnreachable(url))
    }
}
