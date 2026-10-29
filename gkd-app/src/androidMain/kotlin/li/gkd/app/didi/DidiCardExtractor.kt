package li.gkd.app.didi

import android.view.accessibility.AccessibilityNodeInfo

/**
 * 从滴滴卡片的无障碍节点树，按 viewId 提取结构化字段 → [DidiCard]。
 *
 * 这是把 DidiWatch 的 findCard() 移植成 Kotlin。viewId 与 DidiWatch 完全一致，
 * 便于影子对账（P4）逐字段比对。
 *
 * 薄补丁原则：只读节点，不做决策，不做点击。决策在 [DidiDecision.kt]。
 */
object DidiCardExtractor {

    // 滴滴车主 包名（提取时只认这个包的节点）
    private const val PKG = "com.sdu.didi.gsui"

    // 关键 viewId（与 DidiWatch didi_tree.py 一致）
    private const val VID_FRAGMENT = "order_review_fragment"   // 卡片容器(预约单)
    private const val VID_FRAGMENT_RT = "order_card_show_layout" // 卡片容器(实时单, 结构不同)
    /** 卡片锚点 = 抬头(order_review_head_tag_view): 预约单+实时单都有, 且是卡片专属。
     *  ⚠️ 不用 order_card_show_layout —— 行程中/收款页也有它, 会误抓(实测教训)。
     *  卡种判据(照 didi_tree.py): 有"不抢"按钮=预约单, 只有抬头=实时单。 */
    private fun findFragment(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? =
        // 抬头存在 = 是订单卡(预约单/实时单都有); 返回 root 让字段从整棵树找(抬头是叶子, 字段不在它子树)
        if (findByVid(root, VID_HEAD_TAG) != null) root else findByVid(root, VID_FRAGMENT)
    private const val VID_PRICE = "price_info_card_title"      // 价格
    private const val VID_LEFT_BTN = "order_review_left_btn"   // "不抢"按钮容器
    private const val VID_RIGHT_BTN = "order_review_right_btn" // 抢单按钮容器
    private const val VID_HEAD_TAG = "order_review_head_tag_view" // 抬头

    /**
     * 判断当前树上有没有滴滴卡片。
     * 口径与 DidiWatch 一致：必须有卡片容器，且有 价格 或 抬头 之一。
     */
    fun hasCard(root: AccessibilityNodeInfo?): Boolean {
        val frag = findFragment(root) ?: return false
        return findByVid(frag, VID_PRICE) != null || findByVid(frag, VID_HEAD_TAG) != null
    }

    /** 提取卡片字段；没有卡片返回 null */
    fun extract(root: AccessibilityNodeInfo?): DidiCard? {
        val frag = findFragment(root) ?: return null
        val priceNode = findByVid(frag, VID_PRICE)
        val headNode = findByVid(frag, VID_HEAD_TAG)
        if (priceNode == null && headNode == null) return null

        val priceText = priceNode?.text?.toString()
        val price = parseDouble(priceText)

        // 距离/时间：卡片里 journey_value 节点按 y 坐标排序，预约单 3 个(时间/距您/全程)，实时单 2 个
        val journeys = findAllByVid(frag, "journey_value")
            .sortedWith(compareBy({ topOf(it) }, { leftOf(it) }))
        val jTexts = journeys.mapNotNull { it.text?.toString() }
        val timeText = jTexts.getOrNull(0)?.takeIf { it.contains(":") || it.contains("天") || it.contains("月") }
        // ★关键: 必须先剔除时间类文本再取距离 —— 否则"今天18:30"会被parseKm抠出数字8混入, 导致距我/行程错位一位
        // (实测: DidGKD 距我8/行程17.8 vs DidWatch 距我17.8/全程14.6, 就是时间混入导致的)
        val kmTexts = jTexts.filter { !it.contains(":") && !it.contains("天") && !it.contains("月") }
        val distMe = kmTexts.mapNotNull { parseKm(it) }.getOrNull(0)
        val distAll = kmTexts.mapNotNull { parseKm(it) }.getOrNull(1)

        // 起终点：address_name 节点按 y 排序，最上=出发地，最下=目的地
        // ⚠️ 不能取 [0]/[1]：实测 252 张真实卡片里有 **5 张出现 3 个 address_name**
        //    (同层级 d17, y 等距 99px)，取前两个就会把途经点当成目的地。
        //    改成 first()/last() 后，2 个或 3 个都正确。
        // 实测(Mate60/滴滴9.3.6): 两个 address_name 是纵向排列(top 2118/2217, left 相同)。
        // 但**不能只按 top 排** —— 万一同 top(横向布局/RTL/其它 ROM) 会退化成遍历顺序。
        // 加 left 作次级键: 纵向时 left 相同无影响, 横向时按从左到右。
        val addrs = findAllByVid(frag, "address_name")
            .sortedWith(compareBy({ topOf(it) }, { leftOf(it) }))
            .mapNotNull { it.text?.toString()?.trim()?.takeIf { s -> s.isNotEmpty() } }
        val addrFrom = addrs.firstOrNull()
        val addrTo = addrs.lastOrNull()

        val tagText = headNode?.text?.toString()
        val hasLeftBtn = findByVid(frag, VID_LEFT_BTN) != null
        // 卡种：有"不抢"按钮 → 预约单；抬头文字兜底
        // 卡种判据 = 抬头文本(最直接可靠): 含"预约单"→预约单, 含"实时单"→实时单
        // (实测抬头: '专车舒适·预约单' vs '专车舒适·实时单' / '专车舒适·送站·实时单')
        val kind = when {
            tagText?.contains("实时单") == true -> "实时单"
            tagText?.contains("预约单") == true -> "预约单"
            hasLeftBtn -> "预约单"          // 兜底: 有"不抢"按钮=预约单
            else -> "未知"
        }

        return DidiCard(
            price = price,
            priceText = priceText,
            distMe = distMe,
            distAll = distAll,
            timeText = timeText,
            addrFrom = addrFrom,
            addrTo = addrTo,
            tagText = tagText,
            kind = kind,
            hasLeftBtn = hasLeftBtn,
        )
    }

    /** 找"不抢"按钮容器（可点击的那个，title 文本节点本身不可点） */
    fun findNoGrabButton(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? =
        findByVid(root, VID_LEFT_BTN)

    /** 找抢单按钮容器 */
    fun findGrabButton(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? =
        findByVid(root, VID_RIGHT_BTN)

    // ---------- 坐标 ----------
    // 用标准 API getBoundsInScreen(Rect)，不用 GKD 的 .toHidden.boundsInScreen
    // (那是 hidden API 扩展，直接对 AccessibilityNodeInfo 调会 Unresolved reference)
    private fun topOf(n: AccessibilityNodeInfo): Int {
        val r = android.graphics.Rect(); n.getBoundsInScreen(r); return r.top
    }
    private fun leftOf(n: AccessibilityNodeInfo): Int {
        val r = android.graphics.Rect(); n.getBoundsInScreen(r); return r.left
    }

    // ---------- 节点查找 ----------

    /** 公开: 按 viewId 后缀找节点(供 flow.js readText 原语用) */
    fun nodeByVid(root: AccessibilityNodeInfo?, vid: String): AccessibilityNodeInfo? = findByVid(root, vid)

    /** 公开: 拼接整棵节点树所有文字(含 contentDescription), 供 flow.js screenHas 判断当前页面 */
    fun allText(root: AccessibilityNodeInfo?): String {
        val sb = StringBuilder()
        fun walk(n: AccessibilityNodeInfo?) {
            n ?: return
            n.text?.let { sb.append(it).append(' ') }
            n.contentDescription?.let { sb.append(it).append(' ') }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        return sb.toString()
    }

    private fun findByVid(root: AccessibilityNodeInfo?, vidSuffix: String): AccessibilityNodeInfo? {
        root ?: return null
        if (matchesVid(root, vidSuffix)) return root
        for (i in 0 until root.childCount) {
            val r = findByVid(root.getChild(i), vidSuffix)
            if (r != null) return r
        }
        return null
    }

    private fun findAllByVid(root: AccessibilityNodeInfo?, vidSuffix: String): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo?) {
            n ?: return
            if (matchesVid(n, vidSuffix)) out.add(n)
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        return out
    }

    /** viewId 形如 "com.sdu.didi.gsui:id/order_review_fragment"，按后缀匹配 */
    private fun matchesVid(n: AccessibilityNodeInfo, vidSuffix: String): Boolean {
        val v = n.viewIdResourceName ?: return false
        return v == "$PKG:id/$vidSuffix" || v.endsWith(":id/$vidSuffix")
    }

    // ---------- 数值解析 ----------

    /** "107.88" / "¥107.88" / "107" → 107.88；解析失败 null */
    private fun parseDouble(s: String?): Double? {
        s ?: return null
        val m = Regex("""(\d+(?:\.\d+)?)""").find(s) ?: return null
        return m.groupValues[1].toDoubleOrNull()
    }

    /**
     * 距离解析（单位感知，移植自 DidiWatch 的 km/m 判断）：
     *  "12.5km" → 12.5   "907m" → 0.907   "12km" → 12.0
     *  无单位的纯数字（如 "12.5"）→ 12.5
     */
    private fun parseKm(s: String?): Double? {
        s ?: return null
        val num = parseDouble(s) ?: return null
        return when {
            s.endsWith("m") && !s.endsWith("km") -> num / 1000.0   // 米制折算
            else -> num                                              // km 或无单位
        }
    }
}
