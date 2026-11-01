package com.rout.air.live

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Memastikan pembacaan respons YouTube konsisten dengan bentuk JSON aslinya. */
class YouTubeChatTest {

    @Test
    fun `siaran aktif dibaca dari liveBroadcasts`() {
        val json = JSONObject(
            """
            {"items":[{"id":"VID123","snippet":{"liveChatId":"CHAT456","title":"Tes"}}]}
            """.trimIndent(),
        )
        val info = parseActiveBroadcast(json)
        assertEquals("VID123", info?.videoId)
        assertEquals("CHAT456", info?.chatId)
    }

    @Test
    fun `siaran berstatus live dipilih lebih dulu`() {
        val json = JSONObject(
            """
            {"items":[
              {"id":"SIAP","snippet":{"liveChatId":"C1"},"status":{"lifeCycleStatus":"ready"}},
              {"id":"AKTIF","snippet":{"liveChatId":"C2"},"status":{"lifeCycleStatus":"live"}}
            ]}
            """.trimIndent(),
        )
        assertEquals("AKTIF", parseActiveBroadcast(json)?.videoId)
    }

    @Test
    fun `daftar kosong berarti tidak ada siaran`() {
        assertNull(parseActiveBroadcast(JSONObject("""{"items":[]}""")))
    }

    @Test
    fun `siaran tanpa liveChatId diabaikan`() {
        val json = JSONObject("""{"items":[{"id":"VID123","snippet":{}}]}""")
        assertNull(parseActiveBroadcast(json))
    }

    @Test
    fun `pesan chat dibaca beserta penulisnya`() {
        val json = JSONObject(
            """
            {
              "nextPageToken":"NEXT1",
              "pollingIntervalMillis":3000,
              "items":[
                {"snippet":{"displayMessage":"halo"},"authorDetails":{"displayName":"Budi"}},
                {"snippet":{"displayMessage":"  "},"authorDetails":{"displayName":"Ani"}},
                {"snippet":{"displayMessage":"hai"},"authorDetails":{}}
              ]
            }
            """.trimIndent(),
        )
        val page = parseChatPage(json)
        assertEquals(2, page.messages.size)
        assertEquals(ChatMessage("Budi", "halo"), page.messages[0])
        assertEquals("Penonton", page.messages[1].author)
        assertEquals("NEXT1", page.nextPageToken)
        assertEquals(3000L, page.intervalMs)
    }

    @Test
    fun `tanpa nextPageToken dianggap null`() {
        val page = parseChatPage(JSONObject("""{"items":[]}"""))
        assertNull(page.nextPageToken)
        assertEquals(0, page.messages.size)
        assertEquals(5000L, page.intervalMs)
    }

    @Test
    fun `stream key diambil dari ingestionInfo`() {
        val json = JSONObject(
            """
            {"items":[{
              "snippet":{"title":"Stream utama"},
              "cdn":{"ingestionInfo":{
                "ingestionAddress":"rtmp://a.rtmp.youtube.com/live2",
                "streamName":"abcd-efgh-ijkl"
              }}
            }]}
            """.trimIndent(),
        )
        val target = parseStreamTarget(json)
        assertEquals("rtmp://a.rtmp.youtube.com/live2", target?.server)
        assertEquals("abcd-efgh-ijkl", target?.key)
        assertEquals("Stream utama", target?.name)
    }

    @Test
    fun `stream key tanpa akun aktif menghasilkan null`() {
        assertNull(parseStreamTarget(JSONObject("""{"items":[]}""")))
    }

    @Test
    fun `nama channel dibaca dari channels list`() {
        val json = JSONObject("""{"items":[{"snippet":{"title":"RoutAir Channel"}}]}""")
        assertEquals("RoutAir Channel", parseChannelTitle(json))
    }
}
