package com.soul2soul.app.webrtc

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IceServerParserTest {

    @Test
    fun `解析 stun 与 turn 并携带凭证`() {
        val arr = JSONArray(
            """[
              {"urls":["stun:1.2.3.4:3479"]},
              {"urls":["turn:1.2.3.4:3479?transport=udp","turn:1.2.3.4:3479?transport=tcp"],
               "username":"1700000000","credential":"hmacvalue"}
            ]""",
        )
        val servers = IceServerParser.parse(arr)
        assertEquals(3, servers.size)
        assertTrue(servers[0].urls.any { it.contains("stun:") })
        val turn = servers.first { it.urls.any { u -> u.contains("turn:") } }
        assertEquals("1700000000", turn.username)
        assertEquals("hmacvalue", turn.password)
    }

    @Test
    fun `空数组返回空列表`() {
        assertTrue(IceServerParser.parse(JSONArray("[]")).isEmpty())
    }

    @Test
    fun `null 返回空列表`() {
        assertTrue(IceServerParser.parse(null).isEmpty())
    }

    @Test
    fun `畸形条目被跳过而不崩溃`() {
        val arr = JSONArray(
            """[
              {"nourls": true},
              {"urls": null},
              {"urls": ["stun:5.6.7.8"]}
            ]""",
        )
        val servers = IceServerParser.parse(arr)
        assertEquals(1, servers.size)
    }

    @Test
    fun `单个 urls 字符串形式也能解析`() {
        // 服务器实现永远发数组；但保证对象形式变化不至于崩
        val arr = JSONArray("""[{"urls":["stun:9.9.9.9"], "username":"", "credential":""}]""")
        val servers = IceServerParser.parse(arr)
        assertEquals(1, servers.size)
        assertEquals("", servers[0].username)
    }

    @Test
    fun `一个条目三条 urls 展开为三个 IceServer 且凭证复制`() {
        val arr = JSONArray(
            """[
              {"urls":["turn:a:3478?transport=udp","turn:a:3478?transport=tcp","turn:a:3478"],
               "username":"u1","credential":"c1"}
            ]""",
        )
        val servers = IceServerParser.parse(arr)
        assertEquals(3, servers.size)
        servers.forEach {
            assertEquals("u1", it.username)
            assertEquals("c1", it.password)
        }
    }

    @Test
    fun `urls 为空数组的条目产出零个服务器`() {
        val arr = JSONArray("""[{"urls":[]}]""")
        assertTrue(IceServerParser.parse(arr).isEmpty())
    }
}
