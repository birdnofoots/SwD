package li.gkd.app.didi.cloud

import android.os.Build
import android.util.Log
import li.gkd.app.app
import li.gkd.app.didi.DidiCloudConfig
import li.gkd.app.didi.DidiTreeDump
import li.gkd.app.service.A11yService
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import li.gkd.app.storage.appStorage

/**
 * 界面快照采集（S3：按版本触发，不按天）。
 *
 * 为什么要按版本而不是定时：**有价值的快照只在卡片弹出的那一瞬间存在**，
 * 定时拍到的基本是主屏/行程页，没用。而我们要回答的问题是
 * "别人机器上的这个滴滴版本，卡片长什么样、viewId 还在不在、层级差几层" ——
 * 每个 (appId, versionCode) 有一份样本就够了。
 *
 * 行为：
 *   卡片出现 → 查本地已采集集合 → 没见过这个版本才同步抓树 → 异步上传 → 记下
 *   见过就什么都不做（流量几乎为零）
 *
 * ⚠️ **抓树必须同步**（卡片只活 138~294ms），丢后台线程会抓到底下的首页；
 *    只有网络上传可以后台跑，那部分不影响抢单。
 * ⚠️ 只在用户开了「静默上传」时才采集 —— 没开上传就不该产生流量。
 */
object DidiSnap {

    private const val TAG = "DidiGKD"
    private const val SEEN_FILE = "snaps.seen"

    @Volatile
    private var running = false

    /**
     * 卡片处理完后**立刻同步调用**（不能丢后台线程）。
     *
     * 教训：原来整段放后台线程，等线程调度到时卡片已经消失（只活 138~294ms），
     * 抓到的是底下的滴滴首页 —— 183 个节点里一个卡片 viewId 都没有，完全没用。
     * 现在：**树和元数据同步抓（卡片还在），只有网络上传放后台**。
     */
    fun maybeCapture(root: android.view.accessibility.AccessibilityNodeInfo?) {
        if (running) return
        if (!DidiCloudConfig.enabled.value) return
        val payload = try {
            buildPayload(root)
        } catch (e: Throwable) {
            Log.w(TAG, "快照构建失败: $e")
            return
        } ?: return
        running = true            // 在起线程前置位, 避免两次调用同时通过检查
        Thread {
            try {
                if (CloudSync.uploadSnap(payload.bytes)) {
                    val s = loadSeen()          // buildPayload 里的 seen 是局部变量, 这里重读
                    s.add(payload.key)
                    saveSeen(s)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "快照上传异常: $e")
            } finally {
                running = false
            }
        }.start()
    }

    private fun seenFile(): File = File(appStorage().didi, SEEN_FILE)

    /** regionOnScreen 是 API 34+, minSdk 26 用标准 getBoundsInScreen */
    private fun screenOf(n: android.view.accessibility.AccessibilityNodeInfo): String = try {
        val r = android.graphics.Rect()
        n.getBoundsInScreen(r)
        "${r.width()}x${r.height()}"
    } catch (e: Exception) {
        ""
    }

    private fun loadSeen(): MutableSet<String> =
        seenFile().takeIf { it.isFile }?.readLines()?.filter { it.isNotBlank() }?.toMutableSet()
            ?: LinkedHashSet()

    private fun saveSeen(set: MutableSet<String>) {
        runCatching { seenFile().writeText(set.joinToString("\n")) }
    }

    /** 同步构建快照 payload；没见过这个版本才返回，否则返回 null。 */
    private fun buildPayload(root: android.view.accessibility.AccessibilityNodeInfo?): Payload? {
        val w = root ?: A11yService.instance?.windowNodeInfo ?: return null
        val pkg = w.packageName?.toString() ?: return null
        if (pkg == app.packageName) return null          // 别抓自己

        val info = try {
            app.packageManager.getPackageInfo(pkg, 0)
        } catch (e: Exception) {
            return null
        }
        // longVersionCode 是 API 28+, minSdk 26 要兜底
        @Suppress("DEPRECATION")
        val verCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt()
        else info.versionCode
        val verName = info.versionName ?: ""
        val key = "$pkg:$verCode"

        val seen = loadSeen()
        if (key in seen) return null                      // ★核心: 每个版本只采一次

        val treeText = DidiTreeDump.dump(w)               // ★同步: 卡片此刻还在
        if (treeText.isEmpty()) return null

        // 抓到的树里必须有卡片锚点, 否则说明拍到的是底下的首页(卡片已消失/在别的窗口)。
        // 这种废样本既不上传、也不记为"已采", 让下一张卡重试 —— 否则会把该版本的
        // 采集名额占死, 之后永远只有一份无用快照。
        if (!treeText.contains("order_review_head_tag_view")) {
            Log.w(TAG, "快照无卡片锚点(${treeText.length}字符疑似首页), 本次不记账, 下张卡重试")
            return null
        }

        val tree = JSONArray(treeText)
        val body = JSONObject()
            .put("appId", pkg)
            .put("versionCode", verCode)
            .put("versionName", verName)
            .put("model", Build.MODEL)
            .put("device", Build.MANUFACTURER + " " + Build.DEVICE)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("screen", screenOf(w))
            .put("ts", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date()))
            .put("tag", "card")
            .put("tree", tree)
        Log.i(TAG, "采集快照 $pkg $verName($verCode) 节点=${tree.length()} -> 上传")
        return Payload(key = key, bytes = body.toString().toByteArray(Charsets.UTF_8))
    }

    private class Payload(val key: String, val bytes: ByteArray)

}
