package com.rout.air.live

import org.junit.Assert.assertEquals
import org.junit.Test

class RtmpEndpointTest {

    @Test
    fun separatesServerAndStreamKey() {
        val endpoint = RtmpEndpoint.parse("rtmp://a.rtmp.youtube.com/live2", "abcd-1234")
        assertEquals("a.rtmp.youtube.com", endpoint.host)
        assertEquals(1935, endpoint.port)
        assertEquals("live2", endpoint.app)
        assertEquals("abcd-1234", endpoint.streamName)
        assertEquals("rtmp://a.rtmp.youtube.com/live2", endpoint.tcUrl)
    }

    @Test
    fun supportsCustomPortAndSecureScheme() {
        val endpoint = RtmpEndpoint.parse("rtmps://example.com:8443/stream", "key")
        assertEquals(8443, endpoint.port)
        assertEquals(true, endpoint.secure)
        assertEquals("stream", endpoint.app)
        assertEquals("key", endpoint.streamName)
        assertEquals("rtmps://example.com:8443/stream", endpoint.tcUrl)
    }

    @Test
    fun streamKeyCanBePartOfUrl() {
        val endpoint = RtmpEndpoint.parse("rtmp://host/app/secret-key", "")
        assertEquals("app", endpoint.app)
        assertEquals("secret-key", endpoint.streamName)
    }
}
