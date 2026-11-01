package com.rout.air.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Amf0Test {

    @Test
    fun roundTrip_primitivesAndObject() {
        val values = listOf<Any?>(
            "connect",
            1.0,
            true,
            null,
            mapOf("app" to "live", "port" to 1935.0),
        )
        val decoded = Amf0.decode(Amf0.encode(values))
        assertEquals("connect", decoded[0])
        assertEquals(1.0, decoded[1] as Double, 0.0)
        assertEquals(true, decoded[2])
        assertNull(decoded[3])
        val map = decoded[4] as Map<*, *>
        assertEquals("live", map["app"])
        assertEquals(1935.0, map["port"] as Double, 0.0)
    }

    @Test
    fun encode_startsWithStringMarker() {
        val bytes = Amf0.encode(listOf("publish"))
        assertEquals(0x02, bytes[0].toInt())
        assertEquals(0, bytes[1].toInt())
        assertEquals(7, bytes[2].toInt())
    }

    @Test
    fun array_roundTrip() {
        val values = listOf<Any?>("onStatus", 0.0, listOf("a", 2.0))
        val decoded = Amf0.decode(Amf0.encode(values))
        val array = decoded[2] as List<*>
        assertEquals("a", array[0])
        assertEquals(2.0, array[1] as Double, 0.0)
    }

    @Test
    fun truncatedInput_doesNotCrash() {
        val bytes = Amf0.encode(listOf("connect", 1.0))
        val decoded = Amf0.decode(bytes.copyOfRange(0, 3))
        assertTrue(decoded.firstOrNull() is String)
    }

    @Test
    fun longString_roundTrip() {
        val text = "x".repeat(70_000)
        val decoded = Amf0.decode(Amf0.encode(listOf(text)))
        assertEquals(text, decoded[0])
        assertTrue(decoded[0] is String)
    }
}
