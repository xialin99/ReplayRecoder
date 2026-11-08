package com.replay.recoder.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.replay.recoder.R
import com.replay.recoder.ssh.SshConfigStore
import com.replay.recoder.ssh.SshConnection

/**
 * SSH 工具入口页:管理保存的 SSH 连接配置。
 *
 * 点击连接 → SshSessionActivity;长按 → 编辑/删除。
 * 底部"新建连接"弹出对话框填写名称/host/port/用户/密码。
 */
class SshToolActivity : AppCompatActivity() {

    private lateinit var store: SshConfigStore
    private lateinit var adapter: ConnAdapter
    private lateinit var rvList: RecyclerView
    private lateinit var tvEmpty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ssh)

        store = SshConfigStore(this)
        findViewById<android.widget.ImageView>(R.id.btnBack).setOnClickListener { finish() }
        rvList = findViewById(R.id.connectionsList)
        tvEmpty = findViewById(R.id.tvEmpty)

        adapter = ConnAdapter(
            onClick = { conn ->
                startActivity(Intent(this, SshTerminalActivity::class.java).apply {
                    putExtra("id", conn.id)
                })
            },
            onLongClick = { conn -> showEditDialog(conn) }
        )
        rvList.layoutManager = LinearLayoutManager(this)
        rvList.adapter = adapter

        findViewById<MaterialButton>(R.id.btnAddConnection).setOnClickListener {
            showEditDialog(null)
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val list = store.loadConnections()
        adapter.submit(list)
        tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        rvList.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showEditDialog(existing: SshConnection?) {
        val view = layoutInflater.inflate(R.layout.dialog_ssh_edit, null)
        val etName = view.findViewById<TextInputEditText>(R.id.etName)
        val etHost = view.findViewById<TextInputEditText>(R.id.etHost)
        val etPort = view.findViewById<TextInputEditText>(R.id.etPort)
        val etUser = view.findViewById<TextInputEditText>(R.id.etUser)
        val etPass = view.findViewById<TextInputEditText>(R.id.etPass)

        existing?.let {
            etName.setText(it.name)
            etHost.setText(it.host)
            etPort.setText(it.port.toString())
            etUser.setText(it.username)
            etPass.setText(it.password)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) R.string.ssh_add_connection else R.string.ssh_edit_connection)
            .setView(view)
            .setNegativeButton(R.string.ssh_cancel, null)
            .also { builder ->
                if (existing != null) {
                    builder.setNeutralButton(R.string.ssh_delete) { _, _ ->
                        store.deleteConnection(existing.id)
                        refresh()
                    }
                }
            }
            .create()
        // 用 setOnShowListener 接管保存按钮:host/username 为空时不能直接关闭对话框,
        // 否则会存入一条必然连不上的配置且无任何提示。
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val host = etHost.text.toString().trim()
                val username = etUser.text.toString().trim()
                if (host.isEmpty()) {
                    Toast.makeText(this, "请填写主机地址", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (username.isEmpty()) {
                    Toast.makeText(this, "请填写用户名", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val port = etPort.text.toString().trim().toIntOrNull() ?: 22
                if (port !in 1..65535) {
                    Toast.makeText(this, "端口需在 1~65535", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val id = existing?.id ?: System.currentTimeMillis()
                store.saveConnection(
                    SshConnection(
                        id = id,
                        name = etName.text.toString().trim().ifBlank { host },
                        host = host,
                        port = port,
                        username = username,
                        password = etPass.text.toString()
                    )
                )
                dialog.dismiss()
                refresh()
            }
        }
        dialog.show()
    }

    private inner class ConnAdapter(
        val onClick: (SshConnection) -> Unit,
        val onLongClick: (SshConnection) -> Unit
    ) : RecyclerView.Adapter<ConnAdapter.VH>() {

        private val items = ArrayList<SshConnection>()

        fun submit(list: List<SshConnection>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_ssh_connection, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.tvName.text = item.name
            holder.tvHost.text = "${item.username}@${item.host}:${item.port}"
            holder.itemView.setOnClickListener { onClick(item) }
            holder.itemView.setOnLongClickListener { onLongClick(item); true }
        }

        override fun getItemCount() = items.size

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvName: TextView = v.findViewById(R.id.tvConnName)
            val tvHost: TextView = v.findViewById(R.id.tvConnHost)
        }
    }
}
