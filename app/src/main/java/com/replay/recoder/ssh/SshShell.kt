package com.replay.recoder.ssh

import android.util.Log
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.Session
import jackpal.androidterm.emulatorview.TermSession
import java.io.InputStream
import java.io.PipedOutputStream

/**
 * SSH 交互式 Shell:桥接 JSch ChannelShell 与 jackpal TermSession。
 *
 * PipedInputStream 由调用方在主线程创建并 setTermIn(防止 TermSession reader 线程 NPE),
 * 本类接收 PipedOutputStream,把 SSH 输出写入;用户输入通过 setTermOut 直接到 SSH stdin。
 */
class SshShell(
    private val sshSession: Session,
    private val termSession: TermSession,
    private val pos: PipedOutputStream,
    private val cols: Int,
    private val rows: Int
) {

    private var channel: ChannelShell? = null
    private var readerThread: Thread? = null
    @Volatile private var running = false
    /** 已关闭:阻止 close 之后再 start 建立新通道 */
    @Volatile private var closed = false

    /** 与 [close] 互斥:避免 close 在 channel 尚未建立时执行导致泄漏/无法关闭。 */
    @Synchronized
    fun start() {
        if (running || closed) return
        // 用局部引用贯穿整个生命周期,避免 close() 把 channel 置空后 reader 线程 NPE
        val ch = sshSession.openChannel("shell") as ChannelShell
        ch.setPty(true)
        // 终端类型设为 xterm,vi/方向键等依赖此环境变量
        ch.setPtyType("xterm")
        // 同步 PTY 窗口大小,否则 vi 不知道终端尺寸,光标移动异常
        try {
            ch.setPtySize(cols, rows, 0, 0)
        } catch (t: Throwable) {
            Log.e("SshShell", "设置 PTY 大小失败", t)
        }
        ch.connect(10000)
        channel = ch

        // 用户按键 → SSH stdin
        termSession.setTermOut(ch.outputStream)
        running = true

        // 读取线程:SSH stdout → PipedOutputStream → TermSession 显示
        readerThread = Thread {
            try {
                val input: InputStream = ch.inputStream
                val buf = ByteArray(8192)
                while (running) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    pos.write(buf, 0, n)
                    pos.flush()
                }
            } catch (t: Throwable) {
                Log.e("SshShell", "读取 SSH 输出异常", t)
            } finally {
                running = false
                try { pos.close() } catch (_: Throwable) {}
            }
        }.also { it.isDaemon = true; it.name = "ssh-shell-reader" }
        readerThread?.start()

        Log.i("SshShell", "ChannelShell 已连接,终端流已绑定")
    }

    /** 动态调整 PTY 窗口大小(连接后调用,通知服务器终端尺寸变化) */
    fun resize(cols: Int, rows: Int) {
        try {
            channel?.setPtySize(cols, rows, 0, 0)
        } catch (t: Throwable) {
            Log.e("SshShell", "resize 失败", t)
        }
    }

    @Synchronized
    fun close() {
        closed = true
        running = false
        // 关闭 stdin 流,否则 JSch 通道可能残留写句柄
        runCatching { channel?.outputStream?.close() }
        runCatching { channel?.disconnect() }
        runCatching { readerThread?.interrupt() }
        runCatching { pos.close() }
        readerThread = null
        channel = null
    }

    fun isRunning(): Boolean = running && (channel?.isConnected == true)
}
