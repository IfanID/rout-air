package com.rout.air.live

import org.junit.Assert.assertEquals
import org.junit.Test
import com.rout.air.live.youtube.ChatRole
import com.rout.air.live.youtube.bubbleDurationMs
import com.rout.air.live.youtube.emoji
import com.rout.air.live.youtube.nameColor

/** Lencana peran pada bubble chat overlay Live. */
class ChatPresentationTest {

    @Test
    fun `lencana emoji mengikuti peran`() {
        assertEquals("👑", ChatRole.Owner.emoji())
        assertEquals("🛡", ChatRole.Moderator.emoji())
        assertEquals("⭐", ChatRole.Member.emoji())
        assertEquals("", ChatRole.Viewer.emoji())
    }

    @Test
    fun `warna nama mengikuti peran`() {
        assertEquals(0xFF00E5FF.toInt(), ChatRole.Owner.nameColor())
        assertEquals(0xFF4CD964.toInt(), ChatRole.Moderator.nameColor())
        assertEquals(0xFF4A9EFF.toInt(), ChatRole.Member.nameColor())
        assertEquals(0xFFFFFFFF.toInt(), ChatRole.Viewer.nameColor())
    }

    @Test
    fun `durasi bubble tiga sampai enam detik`() {
        assertEquals(3_000L, bubbleDurationMs(""))
        assertEquals(3_500L, bubbleDurationMs("a".repeat(20)))
        assertEquals(6_000L, bubbleDurationMs("a".repeat(120)))
        assertEquals(6_000L, bubbleDurationMs("a".repeat(500)))
    }
}
