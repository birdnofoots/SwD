package li.gkd.app.didi

import kotlinx.coroutines.flow.MutableStateFlow
import li.gkd.app.storage.appStorage
import java.io.File

/**
 * 云端日志上传配置（公开发行版）。
 *
 * 存成纯文本小文件，不改 GKD 的 SettingsStore 结构（薄补丁纪律，便于 rebase 上游）。
 * 文件: <appStorage().didi>/cloud.conf   格式: 每行 key=value
 *
 * token 由激活码在服务器换取；用户在设置页填「服务器地址」和「token」即可。
 */
object DidiCloudConfig {

    private const val FILE = "cloud.conf"

    /** 总开关：开 = 静默后台上传（无通知、无弹窗、不进前台） */
    val enabled = MutableStateFlow(false)

    /** 服务器地址，例：http://47.106.242.125:8790 */
    val baseUrl = MutableStateFlow("")

    /** 用户 token（Bearer），同时是报告页地址的一部分。由激活码换取。 */
    val token = MutableStateFlow("")

    /** 设备唯一标识：首次启动生成的随机 UUID，持久化，永不改变。
     *  ⚠️ 刻意**不用设备串码**(IMEI/AndroidID) —— 那是敏感个人信息，
     *  收集它需要单独授权且会被应用市场审核卡；随机 UUID 唯一性完全够用。 */
    val deviceId = MutableStateFlow("")

    /** 是否上传截图（截图含出发地/目的地，默认开；流量紧张可关，只传文字记录） */
    val uploadShots = MutableStateFlow(true)

    @Volatile
    private var loaded = false

    private fun file(): File = File(appStorage().didi, FILE)

    /** 幂等加载 */
    @Synchronized
    fun load() {
        if (loaded) return
        loaded = true
        val f = file()
        if (!f.isFile) return
        runCatching {
            f.readLines().forEach { line ->
                val i = line.indexOf('=')
                if (i <= 0) return@forEach
                val k = line.substring(0, i)
                val v = line.substring(i + 1)
                when (k) {
                    "enabled" -> enabled.value = v == "1"
                    "baseUrl" -> baseUrl.value = v
                    "token" -> token.value = v
                    "deviceId" -> deviceId.value = v
                    "uploadShots" -> uploadShots.value = v == "1"
                }
            }
        }.onFailure {
            android.util.Log.w("SWD.Cloud", "读取 cloud.conf 失败: $it")
        }
    }

    @Synchronized
    fun save() {
        runCatching {
            val f = file()
            f.parentFile?.mkdirs()
            f.writeText(
                "enabled=" + (if (enabled.value) "1" else "0") + "\n" +
                    "baseUrl=" + baseUrl.value + "\n" +
                    "token=" + token.value + "\n" +
                    "deviceId=" + deviceId.value + "\n" +
                    "uploadShots=" + (if (uploadShots.value) "1" else "0") + "\n"
            )
        }.onFailure {
            android.util.Log.w("SWD.Cloud", "写入 cloud.conf 失败: $it")
        }
    }

    /** 三项齐备才算可上传 */
    val ready: Boolean
        get() = enabled.value && baseUrl.value.isNotBlank() && token.value.isNotBlank()

    /** 首次调用时生成并持久化 deviceId（UUID v4 风格）。幂等。 */
    @Synchronized
    fun ensureDeviceId(): String {
        if (deviceId.value.isBlank()) {
            deviceId.value = java.util.UUID.randomUUID().toString()
            save()
        }
        return deviceId.value
    }
}
