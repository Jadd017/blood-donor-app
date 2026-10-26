package com.jadbarakat.blooddonor.server

import com.jadbarakat.blooddonor.server.api.clientIp
import io.netty.channel.embedded.EmbeddedChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HttpLimitsTest {

    @Test
    fun `connections beyond the limit are closed, and closing frees a slot`() {
        val limiter = ConnectionLimiter(max = 2)
        val first = EmbeddedChannel(limiter)
        val second = EmbeddedChannel(limiter)
        val third = EmbeddedChannel(limiter)
        assertTrue(first.isOpen)
        assertTrue(second.isOpen)
        assertFalse(third.isOpen)

        first.close()
        assertTrue(EmbeddedChannel(limiter).isOpen)
        assertEquals(2, limiter.openConnections)
    }

    @Test
    fun `X-Forwarded-For is only believed from a trusted proxy`() {
        val proxy = setOf("10.0.0.2")
        assertEquals("203.0.113.9", clientIp("203.0.113.9", "1.2.3.4", proxy), "spoofed header from a client")
        assertEquals("1.2.3.4", clientIp("10.0.0.2", "1.2.3.4", proxy))
        // A client-supplied first hop can't win: the right-most untrusted address is the client.
        assertEquals("1.2.3.4", clientIp("10.0.0.2", "6.6.6.6, 1.2.3.4", proxy))
        assertEquals("10.0.0.2", clientIp("10.0.0.2", null, proxy))
        assertEquals("10.0.0.2", clientIp("10.0.0.2", " , ", proxy))
    }
}
