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
}
