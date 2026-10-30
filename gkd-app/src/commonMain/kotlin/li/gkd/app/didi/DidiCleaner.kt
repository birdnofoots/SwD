package li.gkd.app.didi

import java.io.File
import li.gkd.app.storage.appStorage

/**
 * 存档清理（P2，commonMain）：截图+记录**联动删**，防数据膨胀。
 * 不用 org.json(commonMain 平台无关, org.json 是 Android API) —— 用正则读 ts/shot 字段。
 */
object DidiCleaner {

    private fun root(): File = appStorage().didi

    private fun jsonStr(line: String, key: String): String {
        val m = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(line) ?: return ""
        return m.groupValues[1]
    }

    /**
     * 清理旧记录+截图(联动删, 不留孤儿)。
     * @param days 删除 N 天前(按记录 ts)
     * @param beforeDate 删除该日期前(yyyy-MM-dd)
     */
    fun cleanup(days: Int? = null, beforeDate: String? = null): String {
        val dir = root()
        val recFile = File(dir, "records.jsonl")
        val capDir = File(dir, "captures")
        if (!recFile.exists()) return "无记录文件"

        val cutoffEpoch = when {
            days != null -> System.currentTimeMillis() - days * 86400_000L
            beforeDate != null -> try {
                java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(beforeDate)?.time ?: 0L
            } catch (e: Exception) { 0L }
            else -> 0L
        }

        val keep = mutableListOf<String>()
        val dropShots = mutableListOf<String>()
        var dropped = 0
        recFile.readLines().forEach { line ->
            if (line.isBlank()) return@forEach
            val ts = jsonStr(line, "ts")
            val tsEpoch = try {
                java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).parse(ts)?.time ?: 0L
            } catch (e: Exception) { 0L }
            if (tsEpoch in 1 until cutoffEpoch) {
                dropped++
                jsonStr(line, "shot").takeIf { it.isNotEmpty() }?.let { dropShots.add(it) }
            } else {
                keep.add(line)
            }
        }

        var shotDel = 0
        dropShots.forEach { name ->
            val f = File(capDir, name)
            if (f.exists() && f.delete()) shotDel++
        }
        recFile.writeText(keep.joinToString("\n") + if (keep.isNotEmpty()) "\n" else "")
        return "删除 $dropped 条记录 + $shotDel 张截图"
    }
}
