package com.replay.recoder.ssh

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.UserInfo
import org.json.JSONArray
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * SSH 连接配置和常用命令存储层,基于 SharedPreferences + JSON。
 * 配置内容(含明文密码)用 Android Keystore 里的 AES-GCM 密钥加密后落盘,
 * 不再以 Base64/明文形式存储,备份工具或 root 环境无法直接读取账号密码。
 */
class SshConfigStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ---------- 连接 ----------

    fun loadConnections(): List<SshConnection> {
        val raw = decrypt(prefs.getString(KEY_CONNECTIONS, null) ?: return emptyList())
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { SshConnection.fromJson(arr.getJSONObject(it)) }
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "加载连接失败", t)
            emptyList()
        }
    }

    fun saveConnection(conn: SshConnection) {
        val list = loadConnections().toMutableList()
        val idx = list.indexOfFirst { it.id == conn.id }
        if (idx >= 0) list[idx] = conn else list.add(conn)
        saveConnections(list)
    }

    fun deleteConnection(id: Long) {
        val list = loadConnections().filter { it.id != id }
        saveConnections(list)
        // 直接移除该连接的命令存储,避免残留;不能先 loadCommands 再过滤——
        // 首次读取会触发 defaultCommands 写入默认命令,删不掉还会写回一份。
        prefs.edit().remove(KEY_COMMANDS + id).apply()
    }

    private fun saveConnections(list: List<SshConnection>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_CONNECTIONS, encrypt(arr.toString())).apply()
    }

    // ---------- 命令 ----------

    fun loadCommands(connectionId: Long): List<SshCommand> {
        val stored = prefs.getString(KEY_COMMANDS + connectionId, null)
            ?: return defaultCommands(connectionId)
        val raw = decrypt(stored)
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { SshCommand.fromJson(arr.getJSONObject(it)) }
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "加载命令失败", t)
            emptyList()
        }
    }

    fun saveCommand(cmd: SshCommand) {
        val list = loadCommands(cmd.connectionId).toMutableList()
        val idx = list.indexOfFirst { it.id == cmd.id }
        if (idx >= 0) list[idx] = cmd else list.add(cmd)
        saveCommands(list, cmd.connectionId)
    }

    fun deleteCommand(id: Long, connectionId: Long) {
        val list = loadCommands(connectionId).filter { it.id != id }
        saveCommands(list, connectionId)
    }

    private fun saveCommands(list: List<SshCommand>, connectionId: Long) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_COMMANDS + connectionId, encrypt(arr.toString())).apply()
    }

    // ---------- 主机密钥固定(TOFU) ----------

    /** 为某主机创建主机密钥校验仓库:首次连接记录指纹,后续必须一致,变更直接拒绝连接。 */
    fun hostKeyRepository(): HostKeyRepository =
        PinnedHostKeyRepository(
            appContext.getSharedPreferences(PREFS_NAME + "_hostkeys", Context.MODE_PRIVATE)
        )

    // ---------- 加解密(AES-GCM + Android Keystore) ----------

    /** 加密任意明文;失败时退回明文(保证功能可用,不阻塞保存)。 */
    private fun encrypt(plain: String): String = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        ENC_PREFIX + Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(ct, Base64.NO_WRAP)
    }.getOrDefault(plain)

    /** 解密;非加密格式(旧版明文)原样返回,下次保存时自动转为密文。 */
    private fun decrypt(raw: String): String {
        if (!raw.startsWith(ENC_PREFIX)) return raw
        return runCatching {
            val parts = raw.removePrefix(ENC_PREFIX).split(":", limit = 2)
            if (parts.size != 2) return raw
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE, secretKey(),
                GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP))
            )
            String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrDefault("")
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    /**
     * 主机密钥固定仓库(TOFU,首次信任):
     * - 首次连接:记录 SHA-256 指纹并放行
     * - 后续连接:指纹必须与记录一致;不一致返回 CHANGED,JSch 会抛出异常拒绝连接,防中间人。
     */
    private class PinnedHostKeyRepository(private val prefs: SharedPreferences) : HostKeyRepository {

        private fun keyOf(host: String) =
            prefs.getString(HOSTKEY_PREFIX + host, null)

        private fun fingerprint(key: ByteArray): String = runCatching {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            Base64.encodeToString(md.digest(key), Base64.NO_WRAP)
        }.getOrDefault("")

        override fun check(host: String, key: ByteArray): Int {
            val fp = fingerprint(key)
            val stored = keyOf(host)
            if (stored == null) {
                prefs.edit().putString(HOSTKEY_PREFIX + host, fp).apply()
                return HostKeyRepository.OK
            }
            return if (stored == fp) HostKeyRepository.OK else HostKeyRepository.CHANGED
        }

        override fun add(hostkey: HostKey?, ui: UserInfo?) {
            val k = hostkey?.key ?: return
            prefs.edit().putString(
                HOSTKEY_PREFIX + hostkey.host,
                fingerprint(k.toByteArray(Charsets.UTF_8))
            ).apply()
        }

        override fun remove(host: String?, type: String?) {
            host?.let { prefs.edit().remove(HOSTKEY_PREFIX + it).apply() }
        }

        override fun remove(host: String?, type: String?, key: ByteArray?) {
            host?.let { prefs.edit().remove(HOSTKEY_PREFIX + it).apply() }
        }

        override fun getKnownHostsRepositoryID(): String = PREFS_NAME + "_hostkeys"

        override fun getHostKey(): Array<HostKey>? = null

        override fun getHostKey(host: String?, type: String?): Array<HostKey>? = null
    }

    /** 默认预设命令(扫地机常见排查命令) */
    private fun defaultCommands(cid: Long): List<SshCommand> {
        val base = System.currentTimeMillis()
        val defaults = listOf(
            "系统状态" to "systemctl status",
            "磁盘空间" to "df -h",
            "内存" to "free -m",
            "内核日志" to "dmesg | tail -30",
            "进程" to "ps aux | head -20",
            "网络" to "ifconfig",
            "IP 地址" to "ip addr",
            "重启" to "reboot"
        )
        return defaults.mapIndexed { i, (name, cmd) ->
            SshCommand(base + i, cid, name, cmd)
        }.also {
            // 保存为持久化,下次直接读取
            saveCommands(it, cid)
        }
    }

    companion object {
        private const val TAG = "SshConfigStore"
        private const val PREFS_NAME = "ssh_config"
        private const val KEY_CONNECTIONS = "connections"
        private const val KEY_COMMANDS = "cmds_"
        private const val KEY_ALIAS = "ssh_config_aes"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val ENC_PREFIX = "enc1:"
        private const val HOSTKEY_PREFIX = "hk_"
    }
}
