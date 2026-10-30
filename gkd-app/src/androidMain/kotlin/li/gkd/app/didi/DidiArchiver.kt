package li.gkd.app.didi

import android.graphics.Bitmap
import android.os.Environment
import java.io.File
import java.io.FileWriter
import li.gkd.app.storage.appStorage

/**
 * 决策存档（P2）：决策后保存 截图 + 结构化记录。
 *
 * 存储位置: 外部存储 /Android/data/<pkg>/files/DidiGKD/
 *   captures/didi_HHMMSS_<price>.jpg   截图(决策那一刻的屏幕)
 *   records.jsonl                       每行一条决策记录(含卡片字段+决策+截图名)
 *
 * 为什么存外部 filesDir: 无需额外权限(Android 11+ 作用域存储)、adb 可直接读取
 * 便于 P4 对账脚本读; P3 的 HTTP 端点也从这里出报告。
 *
 * 薄补丁纪律: 只做"存档"，不做决策、不做点击。
 */
object DidiArchiver {

    // 去重: 同一张卡(价格|距您|时间签名)15秒内不重复存档(actionCd:0 时同卡会反复触发, 曾致 10 倍重复)
    private val lastSig = java.util.concurrent.atomic.AtomicReference<Pair<String, Long>?>(null)
    private fun dup(sig: String): Boolean {
        val now = System.currentTimeMillis()
        val prev = lastSig.get()
        if (prev != null && prev.first == sig && now - prev.second < 15000) return true
        lastSig.set(Pair(sig, now))
        return false
    }

    // 抢中截图(grab_ok)独立去重: 同卡 60 秒内只存一张
    // (actionCd:0 时同卡会反复触发, 若 grab_ok 不去重会连出多张抢中截图 —— 实测 218.45 单连出2张)
    private val lastGrabOkSig = java.util.concurrent.atomic.AtomicReference<Pair<String, Long>?>(null)
    private fun dupGrabOk(sig: String): Boolean {
        val now = System.currentTimeMillis()
        val prev = lastGrabOkSig.get()
        if (prev != null && prev.first == sig && now - prev.second < 60000) return true
        lastGrabOkSig.set(Pair(sig, now))
        return false
    }

    private fun root(): File = appStorage().didi

    /**
     * 存一条决策记录 + 截图。
     * @return 记录的截图文件名(空=无图)
     */
    fun archive(card: DidiCard, verdict: DidiVerdict, bmp: Bitmap?, force: Boolean = false): String {
        val sig = "${card.price}|${card.distMe}|${card.timeText}"
        if (verdict == DidiVerdict.GrabOk) {
            // 抢中截图: 独立60秒去重(同卡反复触发只存一张)
            if (dupGrabOk(sig)) return ""
        } else {
            if (!force && dup(sig)) return ""      // 同卡 15s 内重复触发 -> 跳过存档
        }
        return try {
            val dir = root()
            val capDir = File(dir, "captures"); capDir.mkdirs()
            val ts = java.text.SimpleDateFormat("HHmmss", java.util.Locale.US)
                .format(java.util.Date())
            val priceTag = card.price?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "NA"
            val shotName = "didi_${ts}_${priceTag}.jpg"
            bmp?.let {
                File(capDir, shotName).outputStream().use { out ->
                    it.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }
            }
            val rec = org.json.JSONObject().apply {
                put("ts", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date()))
                put("price", card.price ?: org.json.JSONObject.NULL)
                put("dist_me", card.distMe ?: org.json.JSONObject.NULL)
                put("dist_all", card.distAll ?: org.json.JSONObject.NULL)
                put("time_text", card.timeText ?: org.json.JSONObject.NULL)
                put("addr_from", card.addrFrom ?: org.json.JSONObject.NULL)
                put("addr_to", card.addrTo ?: org.json.JSONObject.NULL)
                put("kind", card.kind)
                put("verdict", verdict.name)
                put("shot", if (bmp != null) shotName else "")
            }
            FileWriter(File(dir, "records.jsonl"), true).use { it.write(rec.toString() + "\n") }
            shotName
        } catch (e: Exception) {
            android.util.Log.w("DidiGKD", "存档失败: $e")
            ""
        }
    }
}
