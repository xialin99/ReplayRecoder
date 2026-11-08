package com.replay.recoder.ui

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.replay.recoder.R
import com.replay.recoder.ssh.SshCommand
import com.replay.recoder.ssh.SshConfigStore
import com.replay.recoder.ssh.SshConnection
import com.replay.recoder.ssh.SshShell
import jackpal.androidterm.emulatorview.EmulatorView
import jackpal.androidterm.emulatorview.TermSession

/**
 * SSH 交互式终端页:ChannelShell + PTY + VT100 终端模拟器。
 * 输入方案:底部终端功能键栏(Esc/Tab/Ctrl/方向键等)+ 自建终端键盘(字母/数字/符号)。
 */
class SshTerminalActivity : AppCompatActivity() {

    private lateinit var store: SshConfigStore
    private lateinit var conn: SshConnection
    private lateinit var sshSession: Session
    private lateinit var termSession: TermSession
    private lateinit var sshShell: SshShell
    private lateinit var emulatorView: EmulatorView
    private lateinit var terminalContainer: FrameLayout
    private lateinit var cmdContainer: LinearLayout
    private lateinit var termKeysBar: LinearLayout
    private lateinit var keyboardContainer: LinearLayout
    private lateinit var tvStatus: TextView

    // 连接线程写、UI 线程读,必须 volatile,否则可能一直读到 false
    @Volatile private var connected = false
    /** Activity 已销毁:迟到的连接回调/UI 刷新必须丢弃,避免操作已销毁的视图。 */
    @Volatile private var destroyed = false
    /** 单线程串行执行所有 socket 写入,避免弱网时主线程阻塞写 socket 造成 ANR。 */
    private val ioExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var ctrlSticky = false
    private var shiftOn = false      // Shift 临时大写
    private var page = PAGE_LETTERS  // 当前键盘页
    private var keyboardCollapsed = false
    private var dirPanelVisible = false  // 方向键面板是否展开
    private var termCols = 80
    private var termRows = 30
    private val repeatHandler = Handler(Looper.getMainLooper())
    private var repeatRunnable: Runnable? = null
    private lateinit var btnCtrl: MaterialButton
    private lateinit var btnShift: MaterialButton
    private lateinit var btnPage: MaterialButton

    companion object {
        private const val PAGE_LETTERS = 0
        private const val PAGE_SYMBOLS = 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ssh_terminal)

        store = SshConfigStore(this)
        val connId = intent.getLongExtra("id", -1)
        val found = store.loadConnections().find { it.id == connId }
        if (found == null) {
            Toast.makeText(this, R.string.ssh_conn_not_found, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        conn = found

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.tvSessionTitle).text = conn.name
        findViewById<TextView>(R.id.tvSessionHost).text = "${conn.username}@${conn.host}:${conn.port}"
        tvStatus = findViewById(R.id.tvConnStatus)
        cmdContainer = findViewById(R.id.cmdButtonsContainer)
        termKeysBar = findViewById(R.id.termKeysBar)
        keyboardContainer = findViewById(R.id.keyboardContainer)
        terminalContainer = findViewById(R.id.terminalContainer)

        tvStatus.text = getString(R.string.ssh_connecting)
        connect()
    }

    override fun onDestroy() {
        destroyed = true
        super.onDestroy()
        repeatRunnable?.let { repeatHandler.removeCallbacks(it) }
        repeatRunnable = null
        runCatching { ioExecutor.shutdownNow() }
        try { if (::emulatorView.isInitialized) emulatorView.onPause() } catch (_: Throwable) {}
        try { if (::sshShell.isInitialized) sshShell.close() } catch (_: Throwable) {}
        try { if (::termSession.isInitialized) termSession.finish() } catch (_: Throwable) {}
        // 连接线程不可取消:即使还没连上,只要 session 已创建就 disconnect,避免线程泄漏
        try { if (::sshSession.isInitialized) sshSession.disconnect() } catch (_: Throwable) {}
    }

    private fun connect() {
        Thread {
            try {
                val jsch = JSch()
                // 主机密钥校验:首次连接记录指纹(TOFU),后续必须一致,变更直接拒绝连接,防中间人。
                jsch.hostKeyRepository = store.hostKeyRepository()
                sshSession = jsch.getSession(conn.username, conn.host, conn.port)
                sshSession.setPassword(conn.password)
                sshSession.setConfig("StrictHostKeyChecking", "yes")
                sshSession.setConfig("PreferredAuthentications", "password,publickey")
                sshSession.connect(10000)
                connected = true

                runOnUiThread {
                    if (destroyed || isFinishing || isDestroyed) return@runOnUiThread
                    tvStatus.text = getString(R.string.ssh_connected)
                    tvStatus.setTextColor(getColor(R.color.success_green))
                    startTerminal()
                    buildTermKeysBar()
                    buildKeyboard()
                    buildCommandButtons()
                }
            } catch (t: Throwable) {
                android.util.Log.e("SshTerminal", "SSH 连接失败", t)
                runOnUiThread {
                    tvStatus.text = getString(R.string.ssh_connect_failed, t.message ?: "")
                    tvStatus.setTextColor(getColor(R.color.recording_red))
                }
            }
        }.also { it.isDaemon = true; it.name = "ssh-term-connect" }.start()
    }

    private fun startTerminal() {
        val pis = java.io.PipedInputStream(8192)
        val pos = java.io.PipedOutputStream(pis)
        termSession = TermSession()
        termSession.setTermIn(pis)
        termSession.initializeEmulator(termCols, termRows)

        val metrics = DisplayMetrics().also { windowManager.defaultDisplay.getMetrics(it) }
        emulatorView = EmulatorView(this, termSession, metrics)
        emulatorView.setTextSize(16)
        emulatorView.setBackgroundColor(0xFF1A1A1A.toInt())
        emulatorView.isFocusable = false
        terminalContainer.removeAllViews()
        terminalContainer.addView(
            emulatorView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        emulatorView.onResume()
        setupTerminalTouch()

        // 尺寸变化(旋转/键盘弹出/分屏)都要重新同步 PTY;旧实现只在首次布局同步一次,
        // 之后 vim/top 等全屏程序会因终端尺寸不对而错行。
        emulatorView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            syncTerminalSize()
        }
        emulatorView.post { syncTerminalSize() }

        sshShell = SshShell(sshSession, termSession, pos, termCols, termRows)
        Thread {
            try {
                sshShell.start()
            } catch (t: Throwable) {
                android.util.Log.e("SshTerminal", "启动 shell 失败", t)
                runOnUiThread {
                    if (destroyed || isFinishing || isDestroyed) return@runOnUiThread
                    tvStatus.text = getString(R.string.ssh_connect_failed, t.message ?: "")
                    tvStatus.setTextColor(getColor(R.color.recording_red))
                }
            }
        }.also { it.isDaemon = true; it.name = "ssh-shell-start" }.start()
    }

    /** 根据 EmulatorView 实际像素尺寸计算 cols/rows,同步 TermSession 和 SSH PTY */
    private fun syncTerminalSize() {
        try {
            val w = emulatorView.width
            val h = emulatorView.height
            if (w <= 0 || h <= 0) return
            // textSize=16sp,等宽字体:字符宽≈0.6*字号,行高≈1.2*字号
            val textSizePx = android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_SP, 16f, resources.displayMetrics
            )
            val charW = textSizePx * 0.6f
            val charH = textSizePx * 1.2f
            val newCols = (w / charW).toInt().coerceIn(40, 200)
            val newRows = (h / charH).toInt().coerceIn(10, 80)
            if (newCols != termCols || newRows != termRows) {
                termCols = newCols
                termRows = newRows
                termSession.updateSize(newCols, newRows)
                if (::sshShell.isInitialized) sshShell.resize(newCols, newRows)
                android.util.Log.e("SshTerminal", "终端尺寸同步 cols=$newCols rows=$newRows")
            }
        } catch (t: Throwable) {
            android.util.Log.e("SshTerminal", "syncTerminalSize 异常", t)
        }
    }

    /** 终端触摸:单指滑动预览终端内容(不缩放,消费事件防 EmulatorView 内部崩溃) */
    private fun setupTerminalTouch() {
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                emulatorView.scrollTo(0, 0)
                return true
            }
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                // 单指滑动:调用 EmulatorView 内部滚动
                emulatorView.scrollBy(distanceX.toInt(), distanceY.toInt())
                return true
            }
        })
        emulatorView.setOnTouchListener { _, event ->
            try {
                gestureDetector.onTouchEvent(event)
            } catch (t: Throwable) {
                android.util.Log.e("SshTerminal", "终端触摸异常", t)
            }
            true
        }
    }

    // ========== 终端功能键栏(含 vi 常用快捷键) ==========
    private fun buildTermKeysBar() {
        termKeysBar.removeAllViews()
        val keys = listOf(
            "⌄" to { toggleKeyboard() },  // 折叠按钮放最前
            "粘贴" to { pasteFromClipboard() },
            "Esc" to { sendRaw(0x1B) },
            "Tab" to { sendRaw(0x09) },
            "保存" to { sendRaw(0x1B); sendRaw(":w\n") },
            "存退" to { sendRaw(0x1B); sendRaw(":wq\n") },
            "退出" to { sendRaw(0x1B); sendRaw(":q\n") },
            "强退" to { sendRaw(0x1B); sendRaw(":q!\n") },
            "撤销" to { sendRaw(0x1B); sendRaw("u") },
            "搜索" to { sendRaw(0x1B); sendRaw("/") }
        )
        for ((label, action) in keys) {
            val btn = MaterialButton(this).apply {
                text = label
                textSize = 12f
                setPadding(14, 8, 14, 8)
                setOnClickListener { action() }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = 8 }
            termKeysBar.addView(btn, lp)
        }
    }

    // ========== 自建终端键盘 ==========
    private fun buildKeyboard() {
        keyboardContainer.removeAllViews()
        if (dirPanelVisible) {
            // 方向键面板模式:覆盖字母键,显示方向键和不常用快捷键
            addDirectionPanel()
        } else {
            // 正常字母键盘
            addCharRow(if (page == PAGE_LETTERS) listOf("q","w","e","r","t","y","u","i","o","p")
                       else listOf("1","2","3","4","5","6","7","8","9","0"))
            addCharRow(if (page == PAGE_LETTERS) listOf("a","s","d","f","g","h","j","k","l")
                       else listOf("-","=","[","]",";","'",",",".","/"))
            addShiftRow(if (page == PAGE_LETTERS) listOf("z","x","c","v","b","n","m")
                        else listOf("!","@","#","$","%","^","&","*","(",")"))
        }
        // 底部行:Ctrl + 切页 + 方向面板按钮 + 空格 + 回车
        addBottomRow()
    }

    /** 纯字符行 */
    private fun addCharRow(keys: List<String>) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = matchW() }
        for (k in keys) {
            val display = if (shiftOn && page == PAGE_LETTERS) k.uppercase() else k
            val btn = MaterialButton(this).apply {
                text = display; textSize = 14f; setPadding(0, 12, 0, 12)
                setOnClickListener { onCharKey(k) }
            }.styled()
            row.addView(btn, lp(1f))
        }
        keyboardContainer.addView(row)
    }

    /** Shift + 字符 + 退格 行 */
    private fun addShiftRow(keys: List<String>) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = matchW() }
        btnShift = MaterialButton(this).apply {
            text = "⇧"; textSize = 16f; setPadding(0, 12, 0, 12)
            setTextColor(if (shiftOn) getColor(R.color.brand_blue) else getColor(R.color.text_primary))
            setOnClickListener { shiftOn = !shiftOn; buildKeyboard() }
        }.styled()
        row.addView(btnShift, lp(1.3f))
        for (k in keys) {
            val display = if (shiftOn && page == PAGE_LETTERS) k.uppercase() else k
            val btn = MaterialButton(this).apply {
                text = display; textSize = 14f; setPadding(0, 12, 0, 12)
                setOnClickListener { onCharKey(k) }
            }.styled()
            row.addView(btn, lp(1f))
        }
        val btnBack = MaterialButton(this).apply {
            text = "⌫"; textSize = 14f; setPadding(0, 12, 0, 12)
            setOnClickListener { sendRaw(0x7F) }
        }.styled()
        row.addView(btnBack, lp(1.3f))
        keyboardContainer.addView(row)
    }

    /** 底部行:Ctrl + 切页 + 方向面板按钮 + 空格 + 回车 */
    private fun addBottomRow() {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = matchW() }
        btnCtrl = MaterialButton(this).apply {
            text = "Ctrl"; textSize = 10f; setPadding(0, 14, 0, 14)
            setOnClickListener { toggleCtrl() }
        }.styled()
        btnPage = MaterialButton(this).apply {
            text = if (page == PAGE_LETTERS) "123" else "ABC"; textSize = 10f; setPadding(0, 14, 0, 14)
            setOnClickListener { page = if (page == PAGE_LETTERS) PAGE_SYMBOLS else PAGE_LETTERS; buildKeyboard() }
        }.styled()
        val btnDir = MaterialButton(this).apply {
            text = if (dirPanelVisible) "◆✓" else "◆"; textSize = 14f; setPadding(0, 14, 0, 14)
            setTextColor(if (dirPanelVisible) getColor(R.color.brand_blue) else getColor(R.color.text_primary))
            setOnClickListener { dirPanelVisible = !dirPanelVisible; buildKeyboard() }
        }.styled()
        val btnSpace = MaterialButton(this).apply { text = "空格"; textSize = 11f; setPadding(0, 14, 0, 14); setOnClickListener { sendRaw(" ") } }.styled()
        val btnEnter = MaterialButton(this).apply { text = "回车"; textSize = 11f; setPadding(0, 14, 0, 14); setOnClickListener { sendRaw("\n") } }.styled()

        row.addView(btnCtrl, lp(1.1f))
        row.addView(btnPage, lp(0.9f))
        row.addView(btnDir, lp(1.2f))
        row.addView(btnSpace, lp(2.5f))
        row.addView(btnEnter, lp(1.2f))
        keyboardContainer.addView(row)
    }

    /** 方向键面板:倒T型方向键(仿电脑键盘) + 不常用快捷键 */
    private fun addDirectionPanel() {
        // 行1: 空 ↑ 空 Home End
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = matchW() }
        row1.addView(spacer(), lp(1f))
        row1.addView(repeatButton("↑", "\u001B[A"), lp(1f))
        row1.addView(spacer(), lp(1f))
        row1.addView(repeatButton("Home", "\u001B[H"), lp(1.2f))
        row1.addView(repeatButton("End", "\u001B[F"), lp(1.2f))
        // 行2: ← ↓ → PgUp PgDn
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = matchW() }
        row2.addView(repeatButton("←", "\u001B[D"), lp(1f))
        row2.addView(repeatButton("↓", "\u001B[B"), lp(1f))
        row2.addView(repeatButton("→", "\u001B[C"), lp(1f))
        row2.addView(repeatButton("PgUp", "\u001B[5~"), lp(1.2f))
        row2.addView(repeatButton("PgDn", "\u001B[6~"), lp(1.2f))
        // 行3: Ins Del ^ $ F1~F4
        val row3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = matchW() }
        row3.addView(repeatButton("Ins", "\u001B[2~"), lp(1f))
        row3.addView(repeatButton("Del", "\u007F"), lp(1f))
        row3.addView(charBtn("^"), lp(1f))
        row3.addView(charBtn("$"), lp(1f))
        for (i in 1..4) {
            row3.addView(repeatButton("F$i", "\u001B[${11 + i}~"), lp(1f))
        }
        // 行4: F5~F8
        val row4 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = matchW() }
        for (i in 5..8) {
            row4.addView(repeatButton("F$i", "\u001B[${11 + i}~"), lp(1f))
        }
        row4.addView(spacer(), lp(4f))
        keyboardContainer.addView(row1)
        keyboardContainer.addView(row2)
        keyboardContainer.addView(row3)
        keyboardContainer.addView(row4)
    }

    /** 占位空白按钮 */
    private fun spacer(): MaterialButton {
        return MaterialButton(this).apply { text = ""; isEnabled = false; alpha = 0f }
    }

    /** 普通字符按键 */
    private fun charBtn(c: String): MaterialButton {
        return MaterialButton(this).apply {
            text = c; textSize = 14f; setPadding(0, 12, 0, 12)
            setOnClickListener { sendRaw(c) }
        }
    }

    /** 统一配置按键样式(保持 MaterialButton 默认大小) */
    private fun MaterialButton.styled(): MaterialButton = this

    /** 创建支持长按连发的按键 */
    private fun repeatButton(label: String, seq: String): MaterialButton {
        return MaterialButton(this).apply {
            text = label; textSize = 13f; setPadding(0, 12, 0, 12)
            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        sendRaw(seq)
                        // 400ms 后开始连发,每 80ms 一次
                        repeatRunnable = object : Runnable {
                            override fun run() {
                                sendRaw(seq)
                                repeatHandler.postDelayed(this, 80)
                            }
                        }
                        repeatHandler.postDelayed(repeatRunnable!!, 400)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        repeatRunnable?.let { repeatHandler.removeCallbacks(it) }
                        repeatRunnable = null
                    }
                }
                false
            }
        }
    }

    private fun matchW() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun lp(weight: Float) = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight).apply {
        marginStart = 1; marginEnd = 1
    }

    /** 折叠/展开键盘 */
    private fun toggleKeyboard() {
        keyboardCollapsed = !keyboardCollapsed
        keyboardContainer.visibility = if (keyboardCollapsed) android.view.View.GONE else android.view.View.VISIBLE
        // 更新折叠按钮文字
        val lastIdx = termKeysBar.childCount - 1
        if (lastIdx >= 0) {
            (termKeysBar.getChildAt(lastIdx) as? MaterialButton)?.text = if (keyboardCollapsed) "⌃" else "⌄"
        }
    }

    /** 字符键点击:Ctrl 粘滞态转控制字符,否则发送原字符(受 Shift 影响大小写) */
    private fun onCharKey(c: String) {
        if (!connected) {
            Toast.makeText(this, R.string.ssh_not_connected, Toast.LENGTH_SHORT).show()
            return
        }
        val ch = if (shiftOn) c.uppercase() else c
        if (ctrlSticky) {
            val code = when {
                ch[0] in 'a'..'z' -> ch[0].code - 'a'.code + 1
                ch[0] in 'A'..'Z' -> ch[0].code - 'A'.code + 1
                ch == "[" || ch == "{" -> 0x1B
                else -> ch[0].code
            }
            sendRaw(code)
            toggleCtrl()
        } else {
            sendRaw(ch)
        }
        // Shift 临时用一次后关闭(字母页)
        if (shiftOn && page == PAGE_LETTERS) {
            shiftOn = false
            buildKeyboard()
        }
    }

    // ========== Ctrl 粘滞键 ==========
    private fun toggleCtrl() {
        ctrlSticky = !ctrlSticky
        if (ctrlSticky) {
            btnCtrl.setBackgroundColor(getColor(R.color.brand_blue))
            btnCtrl.setTextColor(getColor(android.R.color.white))
        } else {
            btnCtrl.setBackgroundColor(getColor(R.color.bg_card))
            btnCtrl.setTextColor(getColor(R.color.text_primary))
        }
    }

    // ========== 粘贴 ==========
    private fun pasteFromClipboard() {
        if (!connected) {
            Toast.makeText(this, R.string.ssh_not_connected, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).text?.toString() ?: ""
                if (text.isNotEmpty()) sendBytes(text.toByteArray())
            }
        } catch (t: Throwable) {
            android.util.Log.e("SshTerminal", "粘贴失败", t)
        }
    }

    // ========== 发送原始数据 ==========
    private fun sendRaw(code: Int) {
        if (!connected) {
            Toast.makeText(this, R.string.ssh_not_connected, Toast.LENGTH_SHORT).show()
            return
        }
        sendBytes(byteArrayOf(code.toByte()))
    }

    private fun sendRaw(seq: String) {
        if (!connected) {
            Toast.makeText(this, R.string.ssh_not_connected, Toast.LENGTH_SHORT).show()
            return
        }
        sendBytes(seq.toByteArray())
    }

    /** 所有按键/粘贴/快捷命令统一走单线程串行写 socket:弱网下 write 会阻塞,
     *  旧实现直接在主线程写,容易 ANR。 */
    private fun sendBytes(data: ByteArray) {
        ioExecutor.execute {
            runCatching { termSession.write(data, 0, data.size) }
                .onFailure { android.util.Log.e("SshTerminal", "写终端失败", it) }
        }
    }

    // ========== 快捷命令 ==========
    private fun buildCommandButtons() {
        cmdContainer.removeAllViews()
        val cmds = store.loadCommands(conn.id)
        for (cmd in cmds) {
            val btn = layoutInflater.inflate(
                R.layout.item_ssh_command_button,
                cmdContainer,
                false
            ) as MaterialButton
            btn.text = cmd.name
            btn.setOnClickListener { sendCommand(cmd.command) }
            btn.setOnLongClickListener { showEditCommandDialog(cmd); true }
            cmdContainer.addView(btn)
        }
        val addBtn = layoutInflater.inflate(
            R.layout.item_ssh_command_button,
            cmdContainer,
            false
        ) as MaterialButton
        addBtn.text = "＋ 添加"
        addBtn.setOnClickListener { showEditCommandDialog(null) }
        cmdContainer.addView(addBtn)
    }

    private fun showEditCommandDialog(cmd: SshCommand?) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_ssh_command, null)
        val etName = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etCmdName)
        val etCmd = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etCmdText)
        if (cmd != null) { etName.setText(cmd.name); etCmd.setText(cmd.command) }
        val builder = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(if (cmd == null) "添加快捷命令" else "编辑快捷命令")
            .setView(dialogView)
            .setPositiveButton("保存") { _, _ ->
                val name = etName.text?.toString()?.trim() ?: return@setPositiveButton
                val command = etCmd.text?.toString()?.trim() ?: return@setPositiveButton
                if (name.isEmpty() || command.isEmpty()) return@setPositiveButton
                if (cmd != null) store.saveCommand(cmd.copy(name = name, command = command))
                else store.saveCommand(SshCommand(id = System.currentTimeMillis(), connectionId = conn.id, name = name, command = command))
                buildCommandButtons()
            }
            .setNegativeButton("取消", null)
        if (cmd != null) {
            builder.setNeutralButton("删除") { _, _ ->
                store.deleteCommand(cmd.id, conn.id)
                buildCommandButtons()
            }
        }
        builder.show()
    }

    private fun sendCommand(command: String) {
        if (!connected) {
            Toast.makeText(this, R.string.ssh_not_connected, Toast.LENGTH_SHORT).show()
            return
        }
        sendBytes("$command\n".toByteArray())
    }
}
