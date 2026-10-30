package li.gkd.app.didi

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 无障碍节点树导出（S1：版本差异采集）。
 *
 * 为什么要这个：不同设备/安卓版本上，同一个应用版本的卡片**节点名基本一致，
 * 但所处层级会变**（厂商 ROM 的容器层数不同）。要判断一条规则在别人机器上是否成立，
 * 必须拿到对方真实的树 —— 尤其是 **d(层级)** 和 **b(坐标)** 两列。
 *
 * 关键列：
 *   d   层级深度   ← 用户最关心的：同名节点在不同设备上第几层
 *   vid viewId     ← 规则实际匹配的东西（我们只用它 + 文本）
 *   b   坐标 [l,t,r,b] ← 消歧依据：同名多实例靠坐标排序区分（出发地/目的地）
 *   t   文本 / cd  描述 / cl 可点击 / en 可用
 *
 * 限制：最多 1500 个节点、文本截断 40 字符 —— 完整树可能上万节点，
 * 那么大的 payload 上传没意义，且会拖慢卡片路径（卡片只活 138~294ms）。
 * 不调用 recycle()：walk 期间父节点被回收会让子节点失效。
 */
object DidiTreeDump {

    private const val MAX_NODES = 1500
    private const val MAX_TEXT = 40

    /** 导出为 JSON 数组字符串；失败返回空串 */
    fun dump(root: AccessibilityNodeInfo?): String {
        if (root == null) return ""
        return try {
            val sb = StringBuilder(24 * 1024)
            sb.append('[')
            var count = 0
            var truncated = false

            fun esc(s: String): String = buildString(s.length + 8) {
                for (ch in s) when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
                }
            }

            fun walk(n: AccessibilityNodeInfo?, depth: Int) {
                if (n == null) return
                if (count >= MAX_NODES) {
                    truncated = true
                    return
                }
                val r = Rect()
                n.getBoundsInScreen(r)
                val vid = n.viewIdResourceName?.substringAfter(':') ?: ""
                val cls = n.className?.toString()?.substringAfterLast('.') ?: ""
                val txt = n.text?.toString()?.take(MAX_TEXT) ?: ""
                val cd = n.contentDescription?.toString()?.take(MAX_TEXT) ?: ""

                if (count > 0) sb.append(',')
                sb.append("{\"d\":").append(depth)
                if (vid.isNotEmpty()) sb.append(",\"vid\":\"").append(esc(vid)).append('"')
                if (cls.isNotEmpty()) sb.append(",\"cls\":\"").append(esc(cls)).append('"')
                if (txt.isNotEmpty()) sb.append(",\"t\":\"").append(esc(txt)).append('"')
                if (cd.isNotEmpty()) sb.append(",\"cd\":\"").append(esc(cd)).append('"')
                if (n.isClickable) sb.append(",\"cl\":1")
                if (n.isEnabled) sb.append(",\"en\":1")
                if (n.isScrollable) sb.append(",\"sc\":1")
                sb.append(",\"b\":[").append(r.left).append(',').append(r.top)
                    .append(',').append(r.right).append(',').append(r.bottom).append(']')
                sb.append('}')
                count++

                for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
            }

            walk(root, 0)
            sb.append(']')
            sb.toString()
        } catch (e: Throwable) {
            android.util.Log.w("SWD", "dumpTree 失败: $e")
            ""
        }
    }
}
