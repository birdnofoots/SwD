package li.gkd.app.didi.flow

import android.util.Log
import java.io.File
import java.security.MessageDigest
import li.gkd.app.storage.appStorage

/**
 * P3 热更新: flow.js 从外部文件读, 改完不重编译不重启, 下一单即生效。
 *
 * 路径(与 P2 存档同目录, adb 可直接读写):
 *   <appStorage().didi>/flow.js  (= getExternalFilesDir(null)/DidiGKD/flow.js)
 *
 * 策略:
 *  - 文件不存在 -> 自动写入内置默认 flow.js(方便直接编辑)
 *  - 每次收到卡片算一次 md5, 变了就重新 load
 *  - 新脚本有错 -> **继续用上一版好的**(防一次改错把抢单搞挂), 只记一次日志
 */
object FlowLoader {
    private const val TAG = "SWD"
    val flowFile: File get() = appStorage().didi.resolve("flow.js")

    private var loadedHash: String? = null   // 当前引擎里跑的版本
    private var badHash: String? = null      // 已知有错的版本(不重复报)

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

    /** 读外部 flow.js; 失败返回 null */
    private fun readDisk(): String? = try {
        val f = flowFile
        if (!f.exists()) {
            try {
                f.parentFile?.mkdirs()
                f.writeText(DefaultFlow.CODE)
            } catch (e: Exception) {
                try { f.delete() } catch (_: Exception) {}
                f.parentFile?.mkdirs()
                f.writeText(DefaultFlow.CODE)
            }
            Log.i(TAG, "flow.js 不存在, 已生成默认脚本: ${f.absolutePath}")
        }
        f.readText()
    } catch (e: Exception) {
        Log.w(TAG, "flow.js 读取失败, 退回内置: $e")
        null
    }

    /**
     * 确保引擎里跑的是最新且可用的 flow.js。
     * @return true = 有可用脚本可执行
     */
    fun ensure(engine: FlowEngine): Boolean {
        val disk = readDisk()
        val target = disk ?: DefaultFlow.CODE
        val h = md5(target)

        if (h == loadedHash) return true                 // 没改动
        if (h == badHash && loadedHash != null) return true  // 已知坏版本, 上一版仍在跑

        if (engine.load(target)) {
            loadedHash = h
            badHash = null
            Log.i(TAG, "flow.js 热加载成功(${target.length}B, ${if (disk != null) "外部" else "内置"}, hash=${h.take(8)})")
            return true
        }

        // 加载失败 -> 兜底
        badHash = h
        if (loadedHash != null) {
            Log.e(TAG, "flow.js 新版有错(${engine.lastError}), 继续用上一版(hash=${loadedHash!!.take(8)})")
            return true
        }
        Log.e(TAG, "flow.js 首次加载失败: ${engine.lastError}")
        return false
    }
}
