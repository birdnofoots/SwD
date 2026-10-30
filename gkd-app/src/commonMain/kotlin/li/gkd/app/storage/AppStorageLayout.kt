package li.gkd.app.storage

import java.io.File

/** Android-compatible relative paths. Hosts supply external files/cache and private files roots. */
class AppStorageLayout(
    private val files: File,
    private val cache: File,
    private val privateFiles: File,
) {
    private fun directory(root: File, path: String) = root.resolve(path).apply {
        check(isDirectory || mkdirs()) { "Cannot create directory: $absolutePath" }
    }

    val db get() = directory(files, "db")
    val database get() = db.resolve("gkd.db")
    val store get() = directory(files, "store")
    val subscription get() = directory(files, "subscription")
    /** DidiGKD 业务目录(截图/记录/flow.js) — 分发版统一走这里, 不再硬编码路径 */
    val didi get() = directory(files, "SWD")
    val snapshot get() = directory(files, "snapshot")
    val log get() = directory(files, "log")
    val crash get() = directory(files, "crash")
    val crashTemp get() = directory(files, "crash/temp")
    val privateStore get() = directory(privateFiles, "private-store")
    val startupLog get() = privateFiles.resolve("startup-log")
    val coilCache get() = directory(cache, "coil")
    val webViewCache get() = directory(cache, "webview")
    val sharedCache get() = directory(cache, "shared")
    val tempCache get() = directory(cache, "temp")
}
