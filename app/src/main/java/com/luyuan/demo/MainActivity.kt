package com.luyuan.demo

import android.app.Activity
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 胶囊展开姿势空 demo（2026-10-06 立项单）：路远主工程 9 连推"展开塌 71×262 细柱"后拍板
 * "重做前先拿空 demo 摸系统行为"。本 App 只做一件事——验证偷闲 AttentionOverlayController
 * 的四招在本机（荣耀平板 HE4-W19 / vivo V2509A）是否不塌：
 *   ①单窗常驻：WindowManager 只挂一个 host，状态切换=child 热替换，永不 removeWindow/addWindow
 *   ②显式 measure：宽度定值 EXACTLY + 高度 AT_MOST 可用屏高，不让系统对 wrap_content 自由发挥
 *   ③状态去重：内容没变不重渲染
 *   ④所有挂窗/换内容异步 post，不在触摸事件内动窗树
 * 界面上实时显示当前窗的实测 W×H——塌了当场可见；logcat tag=IslandDemo。
 */
class MainActivity : Activity() {

    private enum class Size(val wDp: Int, val title: String) { BALL(56, "小球"), BAR(200, "工具条"), PANEL(320, "面板") }

    private var wm: WindowManager? = null
    private var stress = 0

    companion object {
        // 进程级持有窗引用：Activity 被系统重建后字段归零会让旧窗变孤儿、越叠越多
        // （demo 实证：平板 dumpsys 见 6 个泄漏窗）——引用存活于进程才守得住"单窗常驻"
        private var host: FrameLayout? = null
        private var curSize: Size? = null
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 48, 24, 24) }
        val info = TextView(this).apply { text = "先授权悬浮窗，再切形态" }
        fun btn(label: String, action: () -> Unit) =
            Button(this).apply { text = label; setOnClickListener {
                try { action() } catch (e: Throwable) { info.text = "ERR ${e.javaClass.simpleName}: ${e.message}" }
            } }
        root.addView(info)
        root.addView(btn("①授权悬浮窗") {
            startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName")))
        })
        root.addView(btn("②小球") { render(Size.BALL) })
        root.addView(btn("③工具条") { render(Size.BAR) })
        root.addView(btn("④面板") { render(Size.PANEL) })
        root.addView(btn("⑤压测：连切20次") {
            stress = 20
            info.text = "压测中..."
            window.decorView.postDelayed(object : Runnable {
                override fun run() {
                    if (stress <= 0) { info.text = "压测完成，看窗体是否塌"; return }
                    render(if (stress % 2 == 0) Size.BALL else Size.PANEL)
                    stress--
                    window.decorView.postDelayed(this, 250)
                }
            }, 100)
        })
        root.addView(btn("⑥收起") { hide() })
        setContentView(root)
    }

    private fun hide() {
        host?.let { runCatching { wm!!.removeView(it) } }
        host = null; curSize = null
    }

    private fun render(size: Size) {
        if (!android.provider.Settings.canDrawOverlays(this)) return
        val manager = wm ?: (getSystemService(WINDOW_SERVICE) as WindowManager).also { wm = it }
        val metrics = manager.currentWindowMetrics
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
        val availH = metrics.bounds.height() - insets.top - insets.bottom
        val wPx = dp(size.wDp)

        // 全新离屏构建 child（不碰已挂窗的旧树）
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply { setColor(Color.rgb(27, 34, 32)); cornerRadius = dp(16).toFloat() }
            addView(TextView(this@MainActivity).apply {
                setTextColor(Color.WHITE); textSize = 14f; text = "${size.title} · ${size.wDp}dp"
            })
            addView(TextView(this@MainActivity).apply {
                setTextColor(Color.rgb(131, 232, 197)); textSize = 12f; id = 0x1001; text = "实测尺寸待量"
            })
            if (size == Size.PANEL) {
                for (i in 1..6) addView(TextView(this@MainActivity).apply {
                    setTextColor(Color.WHITE); textSize = 12f; text = "面板行 $i —— 内容行内容行"
                })
            }
        }
        // ②显式 measure：宽 EXACTLY，高 AT_MOST 可用屏高
        val wraped = FrameLayout(this)
        wraped.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        wraped.measure(View.MeasureSpec.makeMeasureSpec(wPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(availH, View.MeasureSpec.AT_MOST))
        view.findViewById<TextView>(0x1001).text = "实测 ${wraped.measuredWidth}×${wraped.measuredHeight}"

        // ③去重
        if (host != null && curSize == size) return

        // ①单窗常驻 + child 热替换；④异步 post（不在触摸回调里动窗树）
        window.decorView.post {
            try {
                val lp = WindowManager.LayoutParams(
                    wPx, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    x = 0; y = insets.top + dp(48)
                }
                val mounted = host
                if (mounted == null) {
                    val h = FrameLayout(this)
                    h.addView(wraped, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
                    manager.addView(h, lp)
                    host = h
                } else if (mounted.windowToken == null) {
                    // 防失同步：引用还在但窗已不在（异常路径）→ 摘引用重新挂
                    host = null
                    val h = FrameLayout(this)
                    h.addView(wraped, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
                    manager.addView(h, lp)
                    host = h
                } else {
                    val fresh = FrameLayout(this)
                    fresh.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
                    val old = mounted.getChildAt(0)
                    mounted.addView(fresh, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
                    if (old != null) mounted.removeView(old)
                    manager.updateViewLayout(mounted, lp)
                }
                curSize = size
                android.util.Log.d("IslandDemo", "render ${size.title} lp.w=$wPx measured=${wraped.measuredWidth}x${wraped.measuredHeight}")
            } catch (e: Throwable) {
                android.util.Log.e("IslandDemo", "render fail", e)
            }
        }
    }
}
