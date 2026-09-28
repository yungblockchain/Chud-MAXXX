package dev.oxyroid.parser.xtream

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class XtreamParserImplTest {
    @Test
    fun parseAndInfo_shouldDecodeRealisticXtreamPayloads() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "user_info": {
                    "username": "demo",
                    "status": "Active",
                    "allowed_output_formats": ["ts", "m3u8"]
                  },
                  "server_info": {
                    "server_protocol": "http",
                    "port": "80",
                    "https_port": "443"
                  }
                }
                """.trimIndent()
            )
        )
        server.enqueue(
            MockResponse().setBody(
                """
                [
                  {
                    "name": "CCTV-1",
                    "stream_id": 1001,
                    "stream_icon": "https://img/live.png",
                    "epg_channel_id": "cctv1",
                    "category_id": 1,
                    "stream_type": "live"
                  }
                ]
                """.trimIndent()
            )
        )
        server.enqueue(
            MockResponse().setBody("[]")
        )
        server.enqueue(
            MockResponse().setBody("[]")
        )

        server.start()
        try {
            val parser = XtreamParserImpl(OkHttpClient())
            val input = XtreamInput(
                basicUrl = server.url("/").toString().removeSuffix("/"),
                username = "demo",
                password = "pwd",
                type = null,
            )

            val info = runBlockingOrThrow {
                parser.getInfo(input)
            }
            assertEquals("demo", info.userInfo.username)
            assertEquals(listOf("ts", "m3u8"), info.userInfo.allowedOutputFormats)

            val parsed = parser.parse(input).toList()
            assertEquals(1, parsed.size)
            val live = parsed.first() as XtreamLive
            assertEquals("CCTV-1", live.name)
            assertEquals(1001, live.streamId)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun createUrlHelpers_shouldGenerateExpectedEndpoints() {
        val infoUrl = XtreamParser.createInfoUrl(
            basicUrl = "https://example.com:8080",
            username = "u",
            password = "p",
        )
        assertTrue(infoUrl.contains("player_api.php"))
        assertTrue(infoUrl.contains("username=u"))

        val xmlUrl = XtreamParser.createXmlUrl(
            basicUrl = "https://example.com:8080",
            username = "u",
            password = "p",
        )
        assertTrue(xmlUrl.contains("xmltv.php"))
    }

    private fun <T> runBlockingOrThrow(block: suspend () -> T): T {
        return kotlinx.coroutines.runBlocking { block() }
    }
}
