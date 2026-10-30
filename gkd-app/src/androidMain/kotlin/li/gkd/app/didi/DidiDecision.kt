package li.gkd.app.didi

/**
 * 决策核心（薄补丁的业务部分）。
 *
 * 设计原则：本文件是**唯一**承载业务逻辑的地方，GKD 核心不掺任何平台相关 if-else。
 * 上游 rebase 时这里几乎无冲突。
 *
 * 移植自 DidiWatch 的 decide() + 价格稳定状态机，规则见 ~/didi-watch/didi_monitor.py。
 */

/** 从卡片节点树提取出的结构化字段（与 DidiWatch 事件字段同名，便于对账） */
data class DidiCard(
    val price: Double?,          // 价格
    val priceText: String?,      // 价格原文字
    val distMe: Double?,         // 距您(km)
    val distAll: Double?,        // 全程(km)
    val timeText: String?,       // 出发时间
    val addrFrom: String?,       // 起点
    val addrTo: String?,         // 终点
    val tagText: String?,        // 抬头(含"预约单"/"实时单"字样)
    val kind: String,            // 预约单 / 实时单 / 未知
    val hasLeftBtn: Boolean,     // 有"不抢"按钮 → 预约单
)

/** 决策结果 */
sealed class DidiVerdict {
    /** 抢：点击右侧抢单按钮 */
    data object Grab : DidiVerdict()
    /** 不抢：点击左侧"不抢"按钮 */
    data object NoGrab : DidiVerdict()
    /** 等价格稳定（价格滚动中，暂不动） */
    data object WaitPrice : DidiVerdict()
    /** 不处理（实时单只记录不点 / 价格缺失 / 条件不满足） */
    data object Pass : DidiVerdict()
    /** 抢单确认成功（点击后进入接乘客页）→ 抢中状态 */
    data object GrabOk : DidiVerdict()

    val name: String get() = when (this) {
        Grab -> "grab"; NoGrab -> "no_grab"; WaitPrice -> "wait_price"; Pass -> "pass"
        GrabOk -> "grab_ok"
    }
}

/**
 * 价格稳定状态机。
 *
 * 目标应用的价格字段是滚动动画，卡片刚出现时可能读到中间值（实测 64.46 → 2 秒后 50.05）。
 * 只有**连续两次读到同一个价**才认为稳定，稳定后才允许决策。
 * 换了一张卡（价格签名变化）就重置。
 */
class PriceStableTracker {
    private var lastPrice: Double? = null
    private var lastSignature: String? = null

    /**
     * @param price 本次读到的价格
     * @param signature 卡片身份签名（价格+距离+时间），用于区分"同一张卡的连续读数" vs "换了一张卡"
     * @return true = 价格已稳定，可以决策
     */
    @Synchronized
    fun update(price: Double?, signature: String): Boolean {
        if (price == null) return false
        // 换卡了 → 重置，本轮不稳定
        if (signature != lastSignature) {
            lastSignature = signature
            lastPrice = price
            return false
        }
        // 同一张卡：价格没变 → 稳定；变了 → 说明还在滚动，记录新值继续等
        val stable = (price == lastPrice)
        lastPrice = price
        return stable
    }

    @Synchronized
    fun reset() { lastPrice = null; lastSignature = null }
}

/**
 * 决策阈值（集中在这里，将来要调参只改这一处）。
 * 规则沿用 DidiWatch 验证过的口径。
 */
object DidiThresholds {
    const val NO_GRAB_BELOW = 80.0     // 预约单 <80 不抢
    const val GRAB_ABOVE = 200.0       // 条件A：价 >200 抢（含距离不限）
    const val GRAB_PRICE = 99.0        // 条件B：价 >99 且 距您<20km 抢
    const val GRAB_DIST_KM = 20.0      // 条件B 距离门槛
}

/**
 * 主决策链（移植自 DidiWatch decide()）。
 *
 * - 实时单：来了必须做，没有"不抢"选项 → 恒 Pass（只记录，不点）
 * - 预约单：<80 不抢；≥80 走抢单条件
 * - 抢单条件A：价 >200 → 抢
 * - 抢单条件B：价 >99 且 距您 <20km → 抢
 *
 * @param stable 价格是否已稳定；未稳定一律 WaitPrice
 */
fun decide(card: DidiCard, stable: Boolean): DidiVerdict {
    // 实时单恒记录不点
    if (card.kind == "实时单") return DidiVerdict.Pass

    val price = card.price ?: return DidiVerdict.Pass   // 无价格不处理
    if (!stable) return DidiVerdict.WaitPrice

    // 预约单：低于门槛 → 不抢
    if (price < DidiThresholds.NO_GRAB_BELOW) return DidiVerdict.NoGrab

    // 抢单条件A：高价
    if (price > DidiThresholds.GRAB_ABOVE) return DidiVerdict.Grab

    // 抢单条件B：价 + 距离都达标
    val dist = card.distMe
    if (price > DidiThresholds.GRAB_PRICE && dist != null && dist < DidiThresholds.GRAB_DIST_KM) {
        return DidiVerdict.Grab
    }

    return DidiVerdict.Pass
}
