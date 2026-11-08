package com.replay.recoder.ui

import android.app.Activity
import android.app.Dialog
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.replay.recoder.R

/**
 * 统一进度弹窗(极简版)。
 *
 * 结构:标题 + 单条进度条 + 百分比 + 底部按钮。
 * - 导出中:按钮显示"取消",点击关闭弹窗(导出在后台继续)。
 * - 完成后:按钮显示"完成",点击关闭弹窗,触发 [onClose] 回调。
 *
 * 修复二次保存失败:[show] 重置 isDismissed,避免上次 dismiss 后无法再次弹出。
 *
 * 使用示例:
 * ```
 * val dialog = ExportProgressDialog(activity).show("保存剪辑")
 * dialog.setOnCancel { /* 用户点取消 */ }
 * VideoEditor.trimWithFFmpeg(input, out, start, end, volume) { pct ->
 *     runOnUiThread { dialog.setProgress(pct) }
 * }
 * dialog.dismiss(true) // 成功,按钮变"完成"
 * dialog.setOnClose { finish() } // 点"完成"后退出
 * ```
 */
class ExportProgressDialog(private val activity: Activity) {

    private val handler = Handler(Looper.getMainLooper())
    private var dialog: Dialog? = null
    private var isDismissed = false
    /** 完成后点"完成"按钮的回调。 */
    private var onClose: (() -> Unit)? = null
    /** 导出中点"取消"按钮的回调。 */
    private var onCancel: (() -> Unit)? = null
    /** 当前是否已完成(成功/失败),用于决定按钮文案。 */
    private var isFinished = false

    private var tvTitle: TextView? = null
    private var tvPct: TextView? = null
    private var progressBar: LinearProgressIndicator? = null
    private var btnAction: TextView? = null

    /** 显示弹窗。[title] 顶部标题(如"保存剪辑"/"导出")。 */
    fun show(title: String): ExportProgressDialog {
        // 重置状态,允许重复 show(修复二次保存失败)
        isDismissed = false
        isFinished = false
        cancelOnOldDialog()

        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_export_progress, null)
        tvTitle = view.findViewById(R.id.tvProgressTitle)
        tvPct = view.findViewById(R.id.tvProgressPct)
        progressBar = view.findViewById(R.id.progressBar)
        btnAction = view.findViewById(R.id.btnAction)

        tvTitle?.text = title
        tvPct?.text = "0%"
        // 导出中:按钮显示"取消"
        btnAction?.text = activity.getString(android.R.string.cancel)
        btnAction?.setOnClickListener {
            if (isFinished) {
                // 完成态:关闭弹窗 + 触发 onClose
                reallyDismiss()
                onClose?.invoke()
            } else {
                // 进行中:关闭弹窗 + 触发 onCancel(导出在后台继续)
                reallyDismiss()
                onCancel?.invoke()
            }
        }

        progressBar?.max = 100
        progressBar?.progress = 0
        progressBar?.show()

        val d = Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(view)
            setCancelable(false)
            setCanceledOnTouchOutside(false)
        }
        d.window?.setBackgroundDrawableResource(android.R.color.transparent)
        d.show()

        // 固定宽高:宽度 = 屏幕 85%,高度固定 175dp(内容:标题+进度条+按钮,无多余空余)
        val dm: DisplayMetrics = activity.resources.displayMetrics
        val lp = d.window?.attributes
        lp?.width = (dm.widthPixels * 0.85f).toInt()
        lp?.height = (175 * dm.density).toInt()
        d.window?.attributes = lp

        dialog = d
        return this
    }

    /** 设置导出中点"取消"的回调。 */
    fun setOnCancel(callback: () -> Unit): ExportProgressDialog {
        onCancel = callback
        return this
    }

    /** 设置完成后点"完成"的回调。 */
    fun setOnClose(callback: () -> Unit): ExportProgressDialog {
        onClose = callback
        return this
    }

    /** 更新进度(0..100)。 */
    fun setProgress(pct: Int) {
        if (isDismissed) return
        val p = pct.coerceIn(0, 100)
        handler.post {
            tvPct?.text = "$p%"
            progressBar?.setProgress(p, true)
        }
    }

    /**
     * 标记导出完成,切换按钮为"完成"。
     * @param success true=成功(进度条满);false=失败
     */
    fun dismiss(success: Boolean, message: String? = null) {
        if (isDismissed) return
        isDismissed = true
        isFinished = true
        handler.post {
            if (success) {
                tvPct?.text = "100%"
                progressBar?.setProgress(100, true)
                btnAction?.setTextColor(ContextCompat.getColor(activity, R.color.success_green))
            } else {
                // 失败原因必须展示,否则用户只看到"完成"却不知道哪一步失败了
                tvPct?.text = "失败"
                tvTitle?.text = message ?: tvTitle?.text
                btnAction?.setTextColor(ContextCompat.getColor(activity, R.color.recording_red))
            }
            // 完成后:按钮变"完成"
            btnAction?.text = "完成"
            dialog?.setCanceledOnTouchOutside(true)
        }
    }

    private fun reallyDismiss() {
        dialog?.let {
            if (it.isShowing) runCatching { it.dismiss() }
        }
        dialog = null
    }

    private fun cancelOnOldDialog() {
        dialog?.let { runCatching { it.dismiss() } }
        dialog = null
        handler.removeCallbacksAndMessages(null)
    }
}
