package li.gkd.app.subscription

import li.gkd.db.SubscriptionIds

/**
 * 内置规则（公开发行版）—— 装完即用，**不依赖任何联网订阅**。
 *
 * 背景：原分发方案把规则挂在本机 Termux 的 webserve 上(127.0.0.1:8765)，
 * 外部用户装了拿不到规则 = 空壳。GKD 本身有「本地订阅」(LOCAL = -2)，
 * 新装时会创建一个空订阅，这里在它为空时写入内置规则。
 *
 * 只负责「触发」：匹配目标卡片抬头 → 调 didi 动作。
 * 所有阈值(价格/距离/时段/关键词)都在 flow.js 里 → 规则基本不用更新，
 * 以后改配置只改 flow.js(网页编辑器 /didi/flow 或 App 内入口)。
 *
 * ⚠️ 这条规则只能装在 DidiGKD 里 —— 官方 GKD 不认识 action:"didi"，
 *    会 fallback 成 click 造成误点。
 */
object BuiltinDidiRule {

    private const val JSON5 = """{
  id: 20241025,
  name: '随我点 决策',
  version: 1,
  author: 'DidiGKD',
  categories: [],
  globalGroups: [],
  apps: [
    {
      id: 'com.sdu.didi.gsui',
      name: '目标应用',
      groups: [
        {
          key: 0,
          name: '决策(不抢/抢单/等价稳)',
          desc: '匹配抬头 order_review_head_tag_view(预约单+实时单都有, 卡片专属) → didi 动作',
          enable: true,
          actionCd: 0,
          actionDelay: 50,
          rules: [
            {
              matches: ['[vid="order_review_head_tag_view"]'],
              action: 'didi',
            },
          ],
        },
      ],
    },
  ],
}"""

    /**
     * 构造内置规则；解析失败时退回空订阅(保证订阅初始化不会崩)。
     * @param name 本地订阅显示名(随系统语言)
     */
    fun build(name: String): RawSubscription = try {
        RawSubscription.parse(JSON5, json5 = true)
            .copy(id = SubscriptionIds.LOCAL, name = name)
    } catch (e: Exception) {
        RawSubscription(id = SubscriptionIds.LOCAL, name = name, version = 0)
    }
}
