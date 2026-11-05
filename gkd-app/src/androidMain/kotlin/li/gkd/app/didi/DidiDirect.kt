package li.gkd.app.didi

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.runBlocking
import li.gkd.app.service.A11yService
import li.gkd.app.storage.appStorage
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 直派单检测：滴滴不弹卡片、直接把司机拉进 OrderServingActivity(行程中页) 的派单方式。
 *
 * 判据（与 DidiWatch 侧同口径, 2026-11-01 实测 4 单验证）：
 *   行程页 OrderServingActivity 出现 且 距上一张卡片 >= 120 秒 且 本趟单尚未记录过
 *   → 记 kind=直派单 + 截图 + 节点树。TripEndActivity 为趟边界, 过趟重置。
 *
 * 为什么在事件钩子里而不是 flow.js：直派单没有卡片, GKD 规则不匹配, flow.js 收不到 onCard。
 * 薄补丁纪律：只观测记录, 不点击不决策。
 */
object DidiDirect {

    private const val TAG = "SWD"
    private const val PKG = "com.sdu.didi.gsui"
    private const val GAP_MS = 120_000L          // 距最近卡片超过 120s 才算"没弹卡"

    @Volatile private var lastCardAt = 0L        // 最近一次卡片处理时刻(performer 打点)
    @Volatile private var doneThisTrip = false   // 本趟单是否已记录过直派
    @Volatile private var inTrip = false         // 已在行程中 -> 行程中切聊天/地图再回来不算新单
    @Volatile private var lastHeadAt = 0L         // 最近一次在树上看到"抬头"的时刻(不论规则是否命中)
    @Volatile private var lastProbeAt = 0L        // 上次探测抬头的时间(节流)

    /** 卡片路径打点：DidiActionPerformer 每次成功提取卡片后调用 */
    fun noteCard() {
        lastCardAt = SystemClock.elapsedRealtime()
    }

    /**
     * ★2026-11-03: 轻量探针 —— 任何事件都探一次"抬头在不在树上"(节流 150ms)。
     * 用途: 实时单卡片渲染过但规则没命中时, 这里能留痕, 让直派单判定不再误判。
     */
    fun probeHead(pkg: CharSequence?) {
        if (pkg?.toString() != PKG) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastProbeAt < 150) return
        lastProbeAt = now
        try {
            val svc = A11yService.instance ?: return
            val roots = ArrayList<AccessibilityNodeInfo?>()
            roots.add(svc.windowNodeInfo)
            for (w in svc.windowInfos.orEmpty()) {
                roots.add(try { w.root } catch (_: Throwable) { null })
            }
            for (r in roots) {
                if (DidiCardExtractor.hasHeadTag(r)) {
                    lastHeadAt = now
                    return
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "probeHead failed: $e")
        }
    }

    /** 事件钩子：窗口状态变化时调用(轻量, 只做字符串判断, 重活丢线程) */
    fun onWindow(pkg: CharSequence?, cls: CharSequence?) {
        if (pkg?.toString() != PKG) return
        val c = cls?.toString() ?: return
        if (c.endsWith("TripEndActivity")) {     // 趟边界: 上一单结束, 允许下一单再判
            doneThisTrip = false
            inTrip = false
            return
        }
        if (!c.contains("OrderServingActivity")) return
        if (inTrip) return                        // 行程中切回行程页(聊天/地图), 不是新单
        inTrip = true                             // 新进入行程页
        val now = SystemClock.elapsedRealtime()
        val last = lastCardAt
        if (last == 0L) return                   // 启动后还没见过卡片, 无基线不判
        val gap = now - last
        if (gap < GAP_MS || doneThisTrip) return // 刚处理过卡片=正常接单流程 / 本趟已记过
        // ★2026-11-03 漏抓兜底: 卡片渲染过(树上见过抬头)但规则没命中 -> 不是直派单, 记一条"漏抓"
        val headGap = now - lastHeadAt
        if (headGap in 1L until GAP_MS) {
            doneThisTrip = true
            Log.w(TAG, "missed card: head seen ${headGap / 1000}s ago but rule did not match -> not a direct order")
            Thread { captureMissed(headGap / 1000) }.start()
            return
        }
        doneThisTrip = true
        Log.i(TAG, "direct order: no card for ${gap / 1000}s, entering trip page -> capture")
        Thread { capture(gap / 1000) }.start()
    }

    private fun capture(gapS: Long) {
        val tsFull = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val tsHms = SimpleDateFormat("HHmmss", Locale.US).format(Date())
        val base = "direct_$tsHms"
        var bmp: Bitmap? = null
        try {
            bmp = runBlocking { A11yService.instance?.screenshot() }
        } catch (e: Exception) {
            Log.w(TAG, "direct screenshot failed: $e")
        }
        var treeName = ""
        var nodeCount = 0
        try {
            val root = A11yService.instance?.windowNodeInfo
            val tree = DidiTreeDump.dump(root)
            if (tree.isNotEmpty()) {
                nodeCount = tree.count { it == ',' } + 1
                val capDir = File(appStorage().didi, "captures").apply { mkdirs() }
                treeName = "$base.txt"
                File(capDir, treeName).writeText(tree)
            }
        } catch (e: Exception) {
            Log.w(TAG, "direct tree dump failed: $e")
        }
        var shotName = ""
        try {
            bmp?.let {
                val capDir = File(appStorage().didi, "captures").apply { mkdirs() }
                shotName = "$base.jpg"
                File(capDir, shotName).outputStream().use { out ->
                    it.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "direct shot save failed: $e")
        }
        try {
            val rec = org.json.JSONObject().apply {
                put("ts", tsFull)
                put("price", org.json.JSONObject.NULL)
                put("dist_me", org.json.JSONObject.NULL)
                put("dist_all", org.json.JSONObject.NULL)
                put("time_text", org.json.JSONObject.NULL)
                put("addr_from", org.json.JSONObject.NULL)
                put("addr_to", org.json.JSONObject.NULL)
                put("kind", "直派单")
                put("verdict", "direct")
                put("shot", shotName)
                put("direct_gap_s", gapS)
                put("direct_tree", treeName)
                put("direct_nodes", nodeCount)
            }
            FileWriter(File(appStorage().didi, "records.jsonl"), true).use {
                it.write(rec.toString() + "\n")
            }
            Log.i(TAG, "direct order archived: gap=${gapS}s shot=${shotName.ifEmpty { "none" }} tree=${treeName.ifEmpty { "none" }}")
        } catch (e: Exception) {
            Log.w(TAG, "direct archive failed: $e")
        }
    }

    /**
     * ★2026-11-03: 记录"漏抓的卡片"(卡片渲染过但规则没命中)。
     * 与直派单分开记账, 避免污染直派单统计; 同时留下截图+节点树便于复盘。
     */
    private fun captureMissed(gapS: Long) {
        val tsFull = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val tsHms = SimpleDateFormat("HHmmss", Locale.US).format(Date())
        val base = "missed_$tsHms"
        var bmp: Bitmap? = null
        try {
            bmp = runBlocking { A11yService.instance?.screenshot() }
        } catch (e: Exception) {
            Log.w(TAG, "missed screenshot failed: $e")
        }
        var treeName = ""
        var nodeCount = 0
        try {
            val root = A11yService.instance?.windowNodeInfo
            val tree = DidiTreeDump.dump(root)
            if (tree.isNotEmpty()) {
                nodeCount = tree.count { it == ',' } + 1
                val capDir = File(appStorage().didi, "captures").apply { mkdirs() }
                treeName = "$base.txt"
                File(capDir, treeName).writeText(tree)
            }
        } catch (e: Exception) {
            Log.w(TAG, "missed tree dump failed: $e")
        }
        var shotName = ""
        try {
            bmp?.let {
                val capDir = File(appStorage().didi, "captures").apply { mkdirs() }
                shotName = "$base.jpg"
                File(capDir, shotName).outputStream().use { out ->
                    it.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "missed shot save failed: $e")
        }
        try {
            val rec = org.json.JSONObject().apply {
                put("ts", tsFull)
                put("price", org.json.JSONObject.NULL)
                put("dist_me", org.json.JSONObject.NULL)
                put("dist_all", org.json.JSONObject.NULL)
                put("time_text", org.json.JSONObject.NULL)
                put("addr_from", org.json.JSONObject.NULL)
                put("addr_to", org.json.JSONObject.NULL)
                put("kind", "实时单")
                put("verdict", "realtime")
                put("shot", shotName)
                put("missed", true)
                put("miss_gap_s", gapS)
                put("miss_tree", treeName)
                put("miss_nodes", nodeCount)
            }
            FileWriter(File(appStorage().didi, "records.jsonl"), true).use {
                it.write(rec.toString() + "\n")
            }
            Log.i(TAG, "missed card archived: shot=${shotName.ifEmpty { "none" }} tree=${treeName.ifEmpty { "none" }}")
        } catch (e: Exception) {
            Log.w(TAG, "missed archive failed: $e")
        }
    }
}
