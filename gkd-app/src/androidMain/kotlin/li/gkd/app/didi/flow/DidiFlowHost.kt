package li.gkd.app.didi.flow

import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.runBlocking
import li.gkd.app.didi.DidiArchiver
import li.gkd.app.didi.DidiCard
import li.gkd.app.didi.DidiCardExtractor
import li.gkd.app.didi.DidiTreeDump
import li.gkd.app.didi.DidiVerdict
import li.gkd.app.service.A11yService

/**
 * FlowHost 的 Android 实现 —— 把 flow.js 的原语桥接到真实动作。
 *   screenshot(verdict) → A11yService 截图 + DidiArchiver 存档(一卡一图, 记录带决策)
 *   tap(target)         → 在卡片节点树找按钮并点击(no_grab/grab)
 *   log(msg)            → 落日志
 *
 * 持有"当前卡片上下文"(root/card)，每次 onCard 前由 DidiActionPerformer 更新。
 */
class DidiFlowHost : FlowHost {

    var root: AccessibilityNodeInfo? = null
    var card: DidiCard? = null
    var tapped: Boolean = false

    private fun toVerdict(s: String): DidiVerdict = when (s) {
        "no_grab" -> DidiVerdict.NoGrab
        "grab" -> DidiVerdict.Grab
        "grab_ok" -> DidiVerdict.GrabOk
        "wait_price" -> DidiVerdict.WaitPrice
        "realtime" -> DidiVerdict.Realtime
        else -> DidiVerdict.Pass
    }

    override fun flowScreenshot(verdict: String): String {
        val c = card ?: return ""
        return try {
            val bmp: Bitmap? = runBlocking { A11yService.instance?.screenshot() }
            val v = toVerdict(verdict)
            // GrabOk 走 DidiArchiver 内部的60秒独立去重; force 仅用于绕过15秒(现不再需要)
            val name = DidiArchiver.archive(c, v, bmp, force = false)
            bmp?.recycle()
            name ?: ""
        } catch (e: Exception) {
            android.util.Log.w("SWD", "flow.screenshot 异常: $e")
            ""
        }
    }

    override fun flowTap(target: String): Boolean {
        // 影子模式: 只记录不点(开关 DidiConfig.tapEnabled, HTTP 可切)
        if (!li.gkd.app.didi.DidiConfig.tapEnabled.value) {
            android.util.Log.i("SWD", "flow.tap $target (影子模式, 不真点)")
            return true
        }
        val r = root ?: return false
        val btn = when (target) {
            "no_grab" -> DidiCardExtractor.findNoGrabButton(r)
            "grab" -> DidiCardExtractor.findGrabButton(r)
            else -> null
        } ?: return false
        val ok = btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        tapped = tapped || ok
        android.util.Log.i("SWD", "flow.tap $target → ${btn.viewIdResourceName} ok=$ok")
        return ok
    }

    override fun flowLog(msg: String) {
        android.util.Log.i("SWD", "flow.log $msg")
    }

    override fun flowWait(ms: Int) {
        try { Thread.sleep(ms.toLong()) } catch (e: Exception) {}
    }

    override fun flowReadText(vid: String): String {
        // 读实时窗口(非卡片节点), 抢单后卡片已消失, 需重新取 windowNodeInfo
        val w = A11yService.instance?.windowNodeInfo ?: return ""
        val n = DidiCardExtractor.nodeByVid(w, vid) ?: return ""
        return n.text?.toString() ?: ""
    }

    override fun flowScreenHas(text: String): Boolean {
        val w = A11yService.instance?.windowNodeInfo ?: return false
        return DidiCardExtractor.allText(w).contains(text)
    }

    override fun flowDumpTree(): String {
        val w = A11yService.instance?.windowNodeInfo ?: return ""
        return DidiTreeDump.dump(w)
    }

    // =====★2026-11-03 新增原语实现(能力增加, 不影响既有原语)=====
    private val vars = java.util.HashMap<String, String>()

    override fun flowMatch(pattern: String, scope: String): String {
        return try {
            val text = if (scope == "card") (card?.toString() ?: "") else DidiCardExtractor.allText(A11yService.instance?.windowNodeInfo)
            val m = java.util.regex.Pattern.compile(pattern).matcher(text)
            if (!m.find()) return ""
            val out = org.json.JSONObject()
            out.put("0", m.group(0) ?: "")
            vars["\$0"] = m.group(0) ?: ""
            var i = 1
            while (i <= m.groupCount()) {
                val g = m.group(i) ?: ""
                out.put(i.toString(), g)
                vars["\$" + i] = g
                i++
            }
            out.toString()
        } catch (e: Exception) {
            android.util.Log.w("SWD", "flow.match failed: $e")
            ""
        }
    }

    override fun flowFind(target: String): String {
        val r = root ?: A11yService.instance?.windowNodeInfo ?: return ""
        val n = DidiCardExtractor.findGeneric(r, target) ?: return ""
        val b = android.graphics.Rect()
        try {
            n.getBoundsInScreen(b)
        } catch (e: Exception) {
        }
        return try {
            org.json.JSONObject().apply {
                put("id", n.viewIdResourceName ?: "")
                put("text", n.text?.toString() ?: "")
                put("desc", n.contentDescription?.toString() ?: "")
                put("x", (b.left + b.right) / 2)
                put("y", (b.top + b.bottom) / 2)
                put("bounds", "${b.left},${b.top},${b.right},${b.bottom}")
                put("clickable", n.isClickable)
            }.toString()
        } catch (e: Exception) {
            ""
        }
    }

    private fun flowTapGate(): Boolean {
        if (!li.gkd.app.didi.DidiConfig.tapEnabled.value) {
            android.util.Log.i("SWD", "flow.click/back (影子模式, 不真点)")
            return false
        }
        return true
    }

    private fun gestureTap(x: Float, y: Float): Boolean {
        return try {
            val gd = android.accessibilityservice.GestureDescription.Builder()
            val path = android.graphics.Path()
            path.moveTo(x, y)
            gd.addStroke(
                android.accessibilityservice.GestureDescription.StrokeDescription(
                    path, 0, android.view.ViewConfiguration.getTapTimeout().toLong()
                )
            )
            A11yService.instance?.dispatchGesture(gd.build(), null, null) == true
        } catch (e: Exception) {
            android.util.Log.w("SWD", "gestureTap failed: $e")
            false
        }
    }

    override fun flowClick(target: String): Boolean {
        if (!flowTapGate()) return true
        val t = target.trim()
        if (!t.startsWith("{")) {
            val nums = t.split(",").mapNotNull { it.trim().toFloatOrNull() }
            if (nums.size == 2) return gestureTap(nums[0], nums[1])
            if (nums.size == 4) return gestureTap((nums[0] + nums[2]) / 2f, (nums[1] + nums[3]) / 2f)
        }
        val r = root ?: A11yService.instance?.windowNodeInfo ?: return false
        val n = DidiCardExtractor.findGeneric(r, t) ?: return false
        if (n.isClickable) return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        var p = n.parent
        while (p != null) {
            if (p.isClickable) return p.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            p = p.parent
        }
        val b = android.graphics.Rect()
        try {
            n.getBoundsInScreen(b)
        } catch (e: Exception) {
        }
        return gestureTap((b.left + b.right) / 2f, (b.top + b.bottom) / 2f)
    }

    override fun flowClickRect(rect: String): Boolean {
        if (!flowTapGate()) return true
        val v = rect.split(",").mapNotNull { it.trim().toFloatOrNull() }
        return if (v.size == 4) gestureTap((v[0] + v[2]) / 2f, (v[1] + v[3]) / 2f)
        else if (v.size == 2) gestureTap(v[0], v[1])
        else false
    }

    override fun flowBack(): Boolean {
        if (!flowTapGate()) return true
        return try {
            A11yService.instance?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK) == true
        } catch (e: Exception) {
            false
        }
    }

    override fun flowSnapshot(tag: String): String {
        return try {
            val ts = java.text.SimpleDateFormat("HHmmss", java.util.Locale.US).format(java.util.Date())
            val safeTag = tag.replace(Regex("[^A-Za-z0-9_-]"), "")
            val base = "snap_${safeTag}_$ts"
            val svc = A11yService.instance ?: return ""
            val bmp = kotlinx.coroutines.runBlocking { svc.screenshot() }
            val capDir = java.io.File(li.gkd.app.storage.appStorage().didi, "captures").apply { mkdirs() }
            var shot = ""
            bmp?.let {
                shot = "$base.jpg"
                java.io.File(capDir, shot).outputStream().use { o ->
                    it.compress(Bitmap.CompressFormat.JPEG, 85, o)
                }
                it.recycle()
            }
            val tree = DidiTreeDump.dump(svc.windowNodeInfo)
            var treeName = ""
            if (tree.isNotEmpty()) {
                treeName = "$base.txt"
                java.io.File(capDir, treeName).writeText(tree + "\n--vars--\n" + vars.toString())
            }
            android.util.Log.i("SWD", "flow.snapshot $base shot=$shot tree=$treeName")
            base
        } catch (e: Exception) {
            android.util.Log.w("SWD", "flow.snapshot failed: $e")
            ""
        }
    }

    override fun flowSetVar(k: String, v: String) {
        vars[k] = v
    }

    override fun flowGetVar(k: String): String = vars[k] ?: ""
}
