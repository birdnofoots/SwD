package li.gkd.app.didi.cloud

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import li.gkd.app.appScope
import li.gkd.app.didi.DidiCloudConfig
import li.gkd.app.storage.appStorage
import li.gkd.app.util.launchLogged
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 云端日志**静默后台**增量上传（公开发行版）。
 *
 * 设计要点（[stated] 用户要求：增量上传 + 非前台静默）：
 *  - **完全静默**：不发通知、不弹 Toast、不启动前台服务。App 进程因为无障碍服务常驻，
 *    所以一个后台协程就够 —— 不需要 WorkManager，也不会有前台服务通知。
 *  - **增量**：records.jsonl 用字节游标（uploads.cursor）记录已传到哪；
 *    截图用已上传名单（uploads.shots）判断，两者互不依赖，各自可自愈。
 *  - **不丢数据**：游标只在服务器返回 2xx 后才推进；失败则下一轮重试。
 *  - **退避**：连续失败时拉长间隔（60s → 最长 10min），避免空转烧流量。
 *
 * 端点（与云端 server.py 对应）：
 *   POST <baseUrl>/api/v1/records      body=NDJSON 文本        Bearer token
 *   POST <baseUrl>/api/v1/shot/<文件名>  body=原始 JPEG 字节     Bearer token
 */
object CloudSync {

    private const val TAG = "DidiCloud"
    private const val IDLE_MS = 10 * 60_000L       // 空闲巡检间隔(正式发布定为10分钟)
    private const val BACKOFF_MAX_MS = 10 * 60_000L
    private const val SHOTS_PER_CYCLE = 20         // 单轮最多传多少张图
    private const val MAX_RECORD_BYTES = 512L * 1024   // 单轮最多读多少字节记录
    private const val SHOT_MEMORY = 500            // 已上传截图名单最多记多少个

    private val started = AtomicBoolean(false)
    private val syncing = AtomicBoolean(false)

    @Volatile
    private var failures = 0


    private fun dir(): File = appStorage().didi
    private fun recordsFile() = File(dir(), "records.jsonl")
    private fun capturesDir() = File(dir(), "captures")
    private fun cursorFile() = File(dir(), "uploads.cursor")
    private fun shotsFile() = File(dir(), "uploads.shots")

    /** 幂等启动。由 App.onCreate 调用。 */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        DidiCloudConfig.load()
        appScope.launchLogged(Dispatchers.IO) {
            Log.i(TAG, "云端同步已启动(静默后台, 巡检 ${IDLE_MS / 1000}s)")
            while (isActive) {
                val wait = try {
                    if (syncCycle()) {
                        failures = 0
                        IDLE_MS
                    } else {
                        backoffMs()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "同步异常: $e")
                    failures++
                    backoffMs()
                }
                delay(wait)
            }
        }
    }

    /**
     * 用激活码开通：POST /api/v1/activate {code, deviceId} → 拿 token 并保存。
     * @return 成功返回提示文案，失败返回 "ERR:原因"
     */
    fun activate(code: String): String {
        val base = DidiCloudConfig.baseUrl.value.trim().trimEnd('/')
        if (base.isEmpty()) return "ERR:请先填服务器地址"
        val c = code.trim().uppercase()
        if (c.isEmpty()) return "ERR:请填激活码"
        val dev = DidiCloudConfig.ensureDeviceId()
        val body = JSONObject().put("code", c).put("deviceId", dev)
            .toString().toByteArray(Charsets.UTF_8)
        val r = try {
            post("/api/v1/activate", body, "application/json; charset=utf-8")
        } catch (e: Throwable) {
            return "ERR:网络异常 ${e.javaClass.simpleName}"
        }
        if (r.code !in 200..299) {
            val msg = try {
                JSONObject(r.body).optString("error", "HTTP ${r.code}")
            } catch (e: Exception) {
                "HTTP ${r.code}"
            }
            return "ERR:$msg"
        }
        return try {
            val j = JSONObject(r.body)
            val tok = j.optString("token")
            if (tok.isEmpty()) return "ERR:服务器没返回 token"
            DidiCloudConfig.token.value = tok
            // 开通即启用: 测试者填码点一下就该开始传, 不该还让他去开第二个开关
            DidiCloudConfig.enabled.value = true
            DidiCloudConfig.save()
            val reused = j.optInt("reused", 0) == 1
            Log.i(TAG, "激活成功 uid=${j.optString("uid")} reused=$reused")
            "OK:已开通${if (reused) "(该设备已绑定, 返回原 Token)" else ""}"
        } catch (e: Exception) {
            "ERR:解析响应失败 ${e.message}"
        }
    }

    /** 上传一份界面快照（JSON 字节）。用于版本差异采集，与日志上传互不干扰。 */
    fun uploadSnap(payload: ByteArray): Boolean {
        if (!DidiCloudConfig.ready) return false
        val r = post("/api/v1/snap", payload, "application/json; charset=utf-8")
        val ok = r.code in 200..299
        if (!ok) {
            failures++
            Log.w(TAG, "上传快照失败 HTTP ${r.code} ${r.body.take(120)}")
        }
        return ok
    }

    /** 立刻同步一轮（设置页/HTTP 端点可手动触发）。返回本轮是否传了东西。 */
    fun syncNow(): Boolean = syncCycle()

    private fun backoffMs(): Long {
        if (failures == 0) return IDLE_MS
        return minOf(IDLE_MS shl minOf(failures, 6), BACKOFF_MAX_MS)
    }

    /** 一轮同步。true = 有数据传成功（下轮可马上再来），false = 无新数据或失败。 */
    private fun syncCycle(): Boolean {
        if (!DidiCloudConfig.ready) return false
        if (!syncing.compareAndSet(false, true)) return false
        return try {
            val recordsOk = postNewRecords()
            val shotsOk = if (DidiCloudConfig.uploadShots.value) postNewShots() else false
            if (recordsOk || shotsOk) {
                Log.i(TAG, "同步: records=$recordsOk shots=$shotsOk")
            }
            recordsOk || shotsOk
        } finally {
            syncing.set(false)
        }
    }

    // ---------------- 记录：字节游标增量 ----------------

    private class Chunk(val text: String, val nextOffset: Long)

    /** 从游标处读出新增的完整行。返回 null = 没有新数据。 */
    private fun readNewChunk(): Chunk? {
        val f = recordsFile()
        if (!f.isFile) return null
        val total = f.length()
        var offset = cursorFile().takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull() ?: 0L
        if (offset > total) offset = 0L        // 文件被清理或缩小（如删了30天前），从头来
        val len = total - offset
        if (len <= 0L) return null

        val take = minOf(len, MAX_RECORD_BYTES)
        val buf = ByteArray(take.toInt())
        RandomAccessFile(f, "r").use { it.seek(offset); it.readFully(buf) }

        // 末尾可能是半行，只取到最后一个完整换行为止（按字节找，避免多字节字符错位）
        var end = -1
        for (i in buf.indices) if (buf[i].toInt() == 0x0A) end = i
        if (end < 0) return null               // 整块都不完整，等下一轮
        val consumed = end + 1
        val text = String(buf, 0, consumed, StandardCharsets.UTF_8)
        return Chunk(text, offset + consumed)
    }

    private fun writeCursor(v: Long) {
        runCatching { cursorFile().writeText(v.toString()) }
    }

    private fun postNewRecords(): Boolean {
        val chunk = readNewChunk() ?: return false
        val r = post("/api/v1/records", chunk.text.toByteArray(StandardCharsets.UTF_8), "text/plain; charset=utf-8")
        if (r.code !in 200..299) {
            failures++
            Log.w(TAG, "上传记录失败 HTTP ${r.code} ${r.body.take(120)}")
            return false
        }
        writeCursor(chunk.nextOffset)      // 只有成功才推进游标
        return true
    }

    // ---------------- 截图：已上传名单 ----------------

    private fun loadShotSet(): MutableSet<String> =
        shotsFile().takeIf { it.isFile }?.readLines()?.filter { it.isNotBlank() }?.toMutableSet()
            ?: LinkedHashSet()

    private fun saveShotSet(set: MutableSet<String>) {
        runCatching {
            val keep = if (set.size > SHOT_MEMORY) set.toList().takeLast(SHOT_MEMORY) else set.toList()
            shotsFile().writeText(keep.joinToString("\n"))
        }
    }

    private fun postNewShots(): Boolean {
        val f = recordsFile()
        if (!f.isFile) return false
        val uploaded = loadShotSet()
        val names = LinkedHashSet<String>()
        f.forEachLine { line ->
            if (line.isBlank() || line in uploaded) return@forEachLine
            val n = try {
                // androidMain 可用 org.json；解析失败的行跳过，不影响别的
                org.json.JSONObject(line).optString("shot")
            } catch (e: Exception) {
                ""
            }
            if (n.isNotEmpty() && n !in uploaded) names.add(n)
        }
        if (names.isEmpty()) return false

        var ok = 0
        for (n in names.take(SHOTS_PER_CYCLE)) {
            val img = File(capturesDir(), n)
            if (!img.isFile) {
                // 不标记为已传：文件可能只是暂时不可读（写入中/刚被清理），
                // 标记了就永远补不回来。扫描很便宜，下轮再试即可。
                continue
            }
            val r = post("/api/v1/shot/" + n, img.readBytes(), "image/jpeg")
            if (r.code !in 200..299) {
                failures++
                Log.w(TAG, "上传截图 $n 失败 HTTP ${r.code}")
                break                       // 一张失败就停，下轮再来（网络八成有问题）
            }
            uploaded.add(n)
            ok++
        }
        saveShotSet(uploaded)
        return ok > 0
    }

    // ---------------- HTTP ----------------

    private class Resp(val code: Int, val body: String)

    private fun post(path: String, body: ByteArray, contentType: String): Resp {
        val base = DidiCloudConfig.baseUrl.value.trim().trimEnd('/')
        val conn = (URL(base + path).openConnection() as HttpURLConnection)
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer " + DidiCloudConfig.token.value.trim())
            conn.setRequestProperty("Content-Type", contentType)
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            Resp(code, stream?.bufferedReader()?.use { it.readText() } ?: "")
        } finally {
            conn.disconnect()
        }
    }
}
