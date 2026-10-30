package li.gkd.app.didi.flow

import li.gkd.app.didi.DidiCard
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined

/**
 * flow.js 宿主接口 —— JS 调用的原语，由 Android 侧实现。
 * P3 控制流层的核心：JS 决定"何时截图/记录/点击"，实现一卡一图精准控制。
 */
interface FlowHost {
    /** 点击: target = "no_grab" / "grab"；返回是否点到 */
    fun flowTap(target: String): Boolean
    /** 截图+存档(带决策)；返回截图文件名(空=失败或去重跳过) */
    fun flowScreenshot(verdict: String): String
    /** 写日志 */
    fun flowLog(msg: String)
    /** 延时 ms(阻塞), 用于抢中检测等 */
    fun flowWait(ms: Int)
    /** 读当前窗口某 viewId 的文字(非卡片节点, 是实时屏幕), 找不到返回 "" */
    fun flowReadText(vid: String): String
    /** 当前窗口全部文字是否含某字符串(判断是否进入某页面, 如"接乘客") */
    fun flowScreenHas(text: String): Boolean
    /** 导出当前窗口的无障碍节点树为 JSON(含层级 d/坐标 b/viewId)。用于版本差异采集。 */
    fun flowDumpTree(): String
}

/**
 * P3 控制流引擎（Rhino，纯 JVM，KMP 双端可用）。
 *
 * flow.js 用 `onCard(card => {...})` 注册卡片处理器，收到卡片后自由决定：
 *   screenshot()  一卡一图(脚本控制时机, 天然不重复)
 *   log(msg)      记录
 *   tap("no_grab"|"grab")  点击
 *
 * 换场景 = 换 flow.js（热更新，不重编译）——这正是"控制流可定制"的落地。
 */
class FlowEngine(private val host: FlowHost) {

    private var cx: Context? = null
    private var scope: Scriptable? = null
    private var onCardFn: Function? = null
    var lastError: String = ""
        private set

    /** 加载 flow.js（重新加载 = 热更新）。失败时恢复上一版现场(事务性) */
    fun load(code: String): Boolean {
        // 事务: 先存现场, 加载失败要能回滚(否则坏脚本会污染正在跑的引擎)
        val prevFn = onCardFn; val prevCx = cx; val prevScope = scope
        return try {
            val c = Context.enter()
            // 默认 languageVersion=1.7 不认 let/const/箭头函数(missing ; before statement #6)
            c.setLanguageVersion(Context.VERSION_ES6)
            // Android ART 不认 Rhino 生成的 JVM 字节码类(can't load this type of class file) → 纯解释执行
            c.setOptimizationLevel(-1)
            val sc = c.initStandardObjects()
            val so = sc as ScriptableObject

            so.put("onCard", sc, object : BaseFunction() {
                override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any>): Any? {
                    if (args.isNotEmpty() && args[0] is Function) onCardFn = args[0] as Function
                    return Undefined.instance
                }
            })
            so.put("tap", sc, object : BaseFunction() {
                override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any>): Any? {
                    val t = if (args.isNotEmpty()) Context.toString(args[0]) else ""
                    return host.flowTap(t)
                }
            })
            so.put("screenshot", sc, object : BaseFunction() {
                override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any>): Any? {
                    val v = if (args.isNotEmpty()) Context.toString(args[0]) else ""
                    return host.flowScreenshot(v)
                }
            })
            so.put("log", sc, object : BaseFunction() {
                override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any>): Any? {
                    val m = if (args.isNotEmpty()) Context.toString(args[0]) else ""
                    host.flowLog(m)
                    return Undefined.instance
                }
            })
            so.put("wait", sc, object : BaseFunction() {
                override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any>): Any? {
                    val ms = if (args.isNotEmpty()) Context.toNumber(args[0]).toInt() else 0
                    host.flowWait(ms.coerceIn(0, 15000))
                    return Undefined.instance
                }
            })
            so.put("readText", sc, object : BaseFunction() {
                override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any>): Any? {
                    val vid = if (args.isNotEmpty()) Context.toString(args[0]) else ""
                    return host.flowReadText(vid)
                }
            })
            so.put("screenHas", sc, object : BaseFunction() {
                override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any>): Any? {
                    val t = if (args.isNotEmpty()) Context.toString(args[0]) else ""
                    return host.flowScreenHas(t)
                }
            })
            so.put("dumpTree", sc, object : BaseFunction() {
                override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any>): Any? =
                    host.flowDumpTree()
            })

            c.evaluateString(sc, code, "flow.js", 1, null)
            cx = c; scope = sc
            lastError = ""
            true
        } catch (e: Throwable) {
            lastError = e.message ?: "flow.js 加载失败"
            android.util.Log.e("SWD", "flow.js 加载失败: $lastError")
            // 回滚到上一版(若解析失败在 onCard 注册前, 这些本就没变)
            onCardFn = prevFn; cx = prevCx; scope = prevScope
            false
        }
    }

    /** 收到卡片 → 调 JS 的 onCard(card) */
    fun onCard(card: DidiCard) {
        val fn = onCardFn ?: return
        val c = cx ?: return
        val sc = scope ?: return
        Context.enter()
        try {
            val obj = c.newObject(sc) as ScriptableObject
            obj.put("price", obj, card.price ?: "")
            obj.put("distMe", obj, card.distMe ?: "")
            obj.put("distAll", obj, card.distAll ?: "")
            obj.put("timeText", obj, card.timeText ?: "")
            obj.put("addrFrom", obj, card.addrFrom ?: "")
            obj.put("addrTo", obj, card.addrTo ?: "")
            obj.put("kind", obj, card.kind)
            obj.put("hasLeftBtn", obj, card.hasLeftBtn)
            fn.call(c, sc, sc, arrayOf(obj))
        } catch (e: Throwable) {
            lastError = e.message ?: "flow.js 执行失败"
            android.util.Log.e("SWD", "flow.js onCard 异常: $lastError")
        } finally {
            Context.exit()
        }
    }
}
