package com.replay.recoder.ssh

import org.json.JSONArray
import org.json.JSONObject

/**
 * SSH 连接配置:名称 / 主机 / 端口 / 用户名 / 密码。
 * 用 JSON 序列化存入 SharedPreferences。
 */
data class SshConnection(
    val id: Long,
    val name: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val password: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("host", host)
        put("port", port)
        put("username", username)
        put("password", password)
    }

    companion object {
        fun fromJson(o: JSONObject): SshConnection = SshConnection(
            id = o.optLong("id", System.currentTimeMillis()),
            name = o.optString("name", ""),
            host = o.optString("host", ""),
            port = o.optInt("port", 22),
            username = o.optString("username", ""),
            password = o.optString("password", "")
        )
    }
}

/**
 * 常用命令:名称 + 命令文本,按连接 ID 分组保存。
 */
data class SshCommand(
    val id: Long,
    val connectionId: Long,
    val name: String,
    val command: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("cid", connectionId)
        put("name", name)
        put("cmd", command)
    }

    companion object {
        fun fromJson(o: JSONObject): SshCommand = SshCommand(
            id = o.optLong("id", System.currentTimeMillis()),
            connectionId = o.optLong("cid", 0),
            name = o.optString("name", ""),
            command = o.optString("cmd", "")
        )
    }
}
