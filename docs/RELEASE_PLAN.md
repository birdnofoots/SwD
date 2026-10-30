# 随我点 公开发行版规划

> 状态：P6-1 已完成。本文档随分支走，克隆仓库即得。
> 分支：`release/public`　起点：`579c8a0`

---

## 一、目标

把随我点分发给其他使用者，能收费，且能在服务器上按用户查看各自的日志网页。

## 二、已完成（P6-1）

| 改动 | 文件 | 说明 |
|---|---|---|
| 规则内置 | `subscription/BuiltinDidiRule.kt` | 本地订阅(LOCAL=-2)为空时写入内置规则 → **装完即用，离线** |
| 路径去硬编码 | `AppStorageLayout.kt` / `DidiArchiver.kt` / `FlowLoader.kt` / `DidiCleaner.kt` / `DidiReportPage.kt` | 4 处 `/storage/emulated/0/...` → `appStorage().didi`，内部封装 `getExternalFilesDir(null)`，自带外部不可用回退 |
| 基础版 flow.js | `didi/flow/DefaultFlow.kt` | 阈值集中 `TH` 配置块 + 使用说明；Rhino harness 20/20 通过 |
| 默认影子模式 | `didi/DidiConfig.kt` | `tapEnabled` 默认 **false**，新装用户只记录不点击 |

## 三、授权 + 登录 + 收款：一个激活码全解决

不做手机号注册/密码登录（转化率低、要管找回、要买短信服务）。

```
收款码付款 → 手工发激活码 → App 填码 → 服务器换 token → token 即身份
```

- 收款 = 钱，授权 = 激活码，登录 = token 自动下发
- 激活码 `DK-XXXX-XXXX-XXXX`，服务端只存 SHA256
- 表：`users(id, code_hash, token, expire_at, device_id, note, created_at)`
- 到期前 3 天 App 提示续费

## 四、云端：每人一个日志网页

```
手机 App                              VPS
records.jsonl ──增量──┐
captures/*.jpg ───────┼─ POST /api/v1/upload (Bearer token) → ~/didi-cloud/{uid}/
                      │                                      ├ records.jsonl
                      │                                      ├ shots/
                      │                                      └ meta.json
                      └── GET /u/{token}/ ← 服务端渲染报告页
```

1. **增量上传**：App 存游标，只传新增
2. **截图单独开关，默认关**：281 张 = 88M/月，10 用户全传 = 1G/月；且截图属敏感数据
3. **网页复用现有报告页 HTML**（`DidiWebReport.kt` 的模板抄到服务端）
4. `/u/{token}/` 里 token 就是密码（128bit 随机、URL 不可枚举），省掉 cookie 会话

## 五、反检测（随机化）

被操作的应用可用 `AccessibilityManager.getEnabledAccessibilityServiceList()` **公开 API** 读到所有已启用无障碍服务的 ComponentName 和 label，无需任何权限。所以这两项随机化有意义。

### 5.1 包名随机 —— 关键是"跨用户随机、单用户固定"

```
❌ 每次安装随机   → 同一用户无法覆盖更新(Android 靠包名识别同一 App)
✅ 每个用户分一个包名，该用户固定 → 跨用户无共同特征，同一用户可正常更新
```

- 实现：Gradle 加 `-PdidiAppId=com.x.k<random>`（manifest 已大量用 `${applicationId}` 占位符，改动小）
- 前提：**签名密钥必须全局一把**（否则该用户永远无法覆盖安装）→ 先把 easycontrol 密钥换成 DidiGKD 专用密钥
- CI 按需出变体包
- 不受影响的：内置规则里 `id: '<目标包名>'` 是**被操作的应用**，不是自己的包名

### 5.2 无障碍服务名随机

- `android:label` / `android:description` 是编译期确定，**运行时改不了**
- 建议：**不用随机乱码**，用一批看起来正常的候选名（如「智能助手」「屏幕朗读」「快捷点击」），从预设池里按包名变体挑。乱码比正常名更可疑
- 现有伪装（服务类名 `com.google.android.accessibility.selecttospeak.SelectToSpeakService`）比随机更有价值，保留

### 5.3 顺手要做的权限收敛

- `QUERY_ALL_PACKAGES` 很显眼，发行版若只操作单一应用可考虑收窄（需评估上游 GKD 依赖）

## 六、混淆与真正的护城河

现状：**R8 已开**（`gkd-android/build.gradle.kts:73 isMinifyEnabled=true`），Rhino 有 keep 规则。

两个绕不过去的事实：

1. **flow.js 天然明文** —— 就在 `/Android/data/.../files/DidiGKD/flow.js`，而"改脚本不重编译"正是产品卖点，加密会废掉热更新，key 也必然在 APK 里
2. **仓库 public（GPLv3 要求）** —— clone 即可编译出等价 App

结论：**护城河不在代码，在服务器**。

- 近期：核心决策从 flow.js 收回 Kotlin（flow.js 只做编排，调 `decide(price, dist)` 原语），阈值编译进 so，R8 混淆 → 用户能改流程，拿不到策略
- 远期：**阈值组合 / 假单关键词库 / 时段策略做成服务端下发配置**，APK 只放保守默认值。源码公开无所谓，策略按激活码分发、可随时改

## 七、收款

| 方式 | 成本 | 何时用 |
|---|---|---|
| 微信/支付宝**个人收款码** | 0 | ✅ **现在就用**，人工发激活码 |
| 聚合支付（虎皮椒/XorPay/面包多） | 低费率、免资质 | 有量后，能自动发码 |
| 微信商户号 | 要营业执照 | 规模化后 |

**先用收款码 + 人工发码跑通前 10 个用户**，验证有人真付钱，再投入做自动发码。前期不写一行支付代码。

## 八、隐私结论

发行版截图内容 = 目标应用的卡片界面：关键字段与当时的屏幕。
**接单前司机端看不到乘客手机号/昵称** → 无乘客个人信息。

⚠️ 待核查一点：**`grab_ok` 截图（操作完成后的结果页）**，该页可能显示第三方个人信息。上线前抽查几张，若有则该标签的截图默认不上传云端。

## 九、落地顺序

```
P6-1 ✅ 内置规则 + 路径去硬编码 + 基础版 flow.js + 默认影子模式
P6-2    HTTP 加 token                              （半天）
P6-3    激活码 + 上传（App 端 + 服务端）            （2~3 天）
P6-4    云端报告页（复用现有 HTML）                  （1 天）
P6-5    收款码 + 人工发码                            （0 代码，可立即开始）
P6-6    策略配置服务端下发                           ← 真正的护城河
P6-7    flow.js 瘦身（决策收回 Kotlin）
P6-8    包名/无障碍服务名变量化（见第五节）
```

**P6-5 不依赖任何开发，建议先做**：放收款码出去看有没有人真付钱，再决定后面投入多少。

## 十、测试机

一加 12（Android 16 / SDK 36）——`release/public` 分支的 APK 优先装这台验证，
与主力机（华为 Mate60，跑单生产环境）隔离。
