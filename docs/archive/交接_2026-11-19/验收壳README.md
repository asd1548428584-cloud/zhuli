# zhuli-assistant-step2-build · 真机验收工程（第 2 步）

不是产品本体，是**验收构建壳**：把 `../core` 的四个输入层源码直接编进 APK，
配一个极简主界面，让你在 Z70 Ultra 上按五步清单验证「通知+分享能进库、中文能搜出来」。

## ✅ 构建记录（2026-11-19，本机跑通）

### 本机（旧）：`D:\zhuli-dev`
- 工具链：`D:\zhuli-dev`（JDK17 + Gradle 8.11.1 + Android SDK：platform-tools / android-35 / build-tools 35.0.0）
- 命令：`gradle.bat --no-daemon assembleDebug` → **BUILD SUCCESSFUL**
- 产物：`app/build/outputs/apk/debug/app-debug.apk`（约 830KB，`com.zhuli.app` v0.2-input-core，compileSdk 35）
- 踩坑备忘（均已修）：build-tools 钉死 35.0.0；srcDir 用 `../../zhuli-assistant-step2/core`；rawQuery 参数数组只传 String[]；Activity 无参 finish

### 新机复验（2026-11-19 晚，换机后重建）
- 工具链：Android Studio 自带 **JBR 21**（JAVA_HOME=`C:\Program Files\Android\Android Studio\jbr`）+ 系统 **Gradle 8.10.2** + **`D:\Android\Sdk`**（platform-tools 37 / android-35 / build-tools 35.0.0）
- 产物 916KB，`versionName=0.4-extract-chat`（versionCode 4），BUILD SUCCESSFUL（约 40s 全量 / 13~24s 增量）
- **新踩坑**：
  - 工作区路径含中文「新建文件夹」→ AGP 直接拦构建，加 `android.overridePathCheck=true` 到 gradle.properties（正式 fork 时迁纯英文路径再删）
  - 本机有两个 SDK 目录：`D:\Android\Sdk`（完整）与 `C:\Users\a\AppData\Local\Android\Sdk`（空壳，仅 sources）——用户级 `ANDROID_HOME` 指着空壳，构建靠 local.properties 指真 SDK 即可；adb 用 `D:\Android\Sdk\platform-tools`
  - 换机器 debug 签名不同，`install -r` 报 signatures do not match → 必须先 uninstall 再装
  - `pm clear` 后系统异步落盘，立即读库会得到旧文件/空文件，**等 3~4 秒再读**

## ✅ 真机验收进展（NX733J / Z70 Ultra，2026-11-19）

| # | 清单项 | 状态 |
|---|---|---|
| 0 | App 启动稳定（无「应用程序错误」窗） | ✅ 通过 |
| 0b | 通知监听权限开通（MyOS 自动放行） | ✅ 通过 |
| 2 | 真实通知 → 收件箱（华为健康「218 千米」实弹捕获 + 合成通知端到端双证据） | ✅ 通过 |
| 3 | 分享菜单 → 收件箱（组件直启注入验证落库：source=share，text UTF-8 点位完好） | ✅ 通过 |
| 4 | 中文关键词检索（LIKE「入库」「通知端到端」命中；**FTS5/trigram 在努比亚 ROM 被静音降级**，设计初衷兑现） | ✅ 通过 |
| 5 | 杀进程重启后数据不丢（force-stop 重启后行仍在库） | ✅ 通过 |

踩坑备忘（真机学习）：
- 拉库核验必须 `adb exec-out`，**不能** `adb shell`（伪终端塞填充字节会致 DB malformed）
- 注入分享意图带组件名时必须同时 `-a android.intent.action.SEND`
- MSYS 控制台打印中文乱码≠数据损坏，Python 点位核验才是真相
- **「权限已开通」≠「监听器已绑定」**：force-stop 后 am start 重开 App，系统未必自动重连服务——需开关权限重触发绑定（本机实测）。第 12 步产品化时要在 UI 提供「检查监听连接状态」入口并在断连时引导重连
- **正则游标 Bug**：`URL_RE.matcher(text)` 后先调 `find()` 再进 `while(find())` 循环，第一条 URL 被静默吞掉导致集合恒空。已修（第 18 次构建验证）

## ✅ 第 3 步模块验证（2026-11-19，v0.3-link-core）

三链接实弹验收（NX733J）：

| 行 | URL | 路由 | 结果 |
|---|---|---|---|
| id=4 | baidu.com | 无云 Key → 本地抓取 | pending=0，摘要「百度一下，你就知道…」UTF-8 点位完好 |
| id=5 | shop.example.com/order?orderno=SF… | 敏感命中 | 只存不抓，占位说明原样落库，pending 保持 1 |
| id=6 | example.com | 无云 Key → 本地抓取 | pending=0，摘要「Example Domain…」 |

- v1→v2 迁移在真机兑现：老 3 行原样存活，`url`/`summary` 两列补齐
- 云摘要通道待用户配 Key 后由第 9 步联调；本地兜底路径是本步主验证面

## ✅ 第 4 步模块验证（2026-11-19，v0.4-extract-chat）

**构建**：`gradle --no-daemon assembleDebug` → BUILD SUCCESSFUL（约 900KB，versionCode 4）

**离线冒烟**（真实 class 文件直接跑，不依赖设备）：

- 20 条中文样本 → 分类/期限/金额/备注全表输出，暴露并修掉 4 个真实缺陷：
  1. 「下午三点半」中文数字时刻不识别 → 补 `cnNum` 中文数字解析（三点半→15:30）
  2. 「今晚8点」按 08:00 建 → `今晚/明晚/昨晚` 纳入 PM 语境（→20:00）
  3. 备注被小数点切断（「46.5 元」→「46」）→ 句子切分不再认 ASCII `.` `,`
  4. 「25号之前」不识别 → 补裸日号规则（本月内，已过顺延下月）
- 敏感闸门 8 条抽查：电话/证件/卡号/凭证/金额/带 token 链接全部命中，普通两句放行

**真机验收（NX733J）**：

| 项 | 结果 |
|---|---|
| 库升级 v2→v3 | ✅ 老行原样存活，5 个新列补齐，`chat_log` 分区建好 |
| 20 条真实消息（走分享入口注入） | ✅ 20/20 入库；快递 2/金融 7/工作 4/生活 5/其他 2；期限 9 条、金额 7 条；UTF-8 完好 |
| 历史行补分类 | ✅ 老链接行（pending 早已是 0）被 `category=''` 条件捞回补跑，最终未分类 0、待提取 0 |
| 对话·记忆（「昨天谁说了啥」） | ✅ 答「昨天我这儿没收到东西。」；带词时按时间窗+关键字命中 |
| 对话·待办（「接下来有什么事」） | ✅ 按 deadline 升序列出，含 11-19 20:00 / 11-20 15:30 等 |
| 对话·记账（「这周花了多少钱」） | ✅ 7 笔合计 ¥14678.50，明细逐条列出 |
| 对话·闲聊（「你好」） | ✅ 本地兜底并明确说明「没配云端 Key」，不装懂 |
| 人格档切换 | ✅ 点徽标即时切到「老大 ⚡」，Toast 确认，回复口吻随之变 |
| 聊天跨会话 | ✅ 杀进程重启后历史对话仍在（chat_log 落库） |
| 云设置弹窗 | ✅ 三项输入 + 保存/清空/取消，Key 走密码输入框 |

**新增真机踩坑（已修/已记）**：

- **Android 15 强制边到边**：`targetSdk 35` 下内容会画到状态栏底下，顶部人格条被压住看不见。
  改 `Theme.Material.Light.NoActionBar` + `setOnApplyWindowInsetsListener` 按系统栏高度补 padding。
- **adb 注入中文带空格会被切碎**：`adb shell am start ... --es TEXT "午饭 32 元"` 只送到「午饭」。
  必须在 shell 侧再包一层引号：`adb shell "am start ... --es android.intent.extra.TEXT '午饭 32 元'"`。
- **验收壳加了 `--es q` 参数入口**：中文输入法没法用 adb 打字，`am start ... --es q '这周花了多少钱'` 可直接触发一轮对话。

> 离线冒烟脚本在 `D:\zhuli-dev\smoke\Smoke.java`（拿 Gradle 编译出的 class 直接跑，改完逻辑可先离线核一遍再装机）。
> 界面留档见 `screenshots/`。

## ✅ 第 6 步模块验证（2026-11-19 晚，v0.6-image-core · 端到端真机通过）

**引擎选型血泪**：Play 服务版 ML Kit 中文（模型按需下载）在本机网络下拉不到中文模块
（`Waiting for the text optional module to be downloaded`，记录仪实证），按预案切
**bundled 离线版**（`com.google.mlkit:text-recognition-chinese:16.0.1`，APK 体积由 ML Kit 原生库决定（4 个 ABI 全打进约 +40MB；已在 build.gradle.kts 用 abiFilters 只留 arm64-v8a/armeabi-v7a，实测 22.9MB），开机即用）。

**真机验收（NX733J，合成假账单截图走分享入口注入）**：

| 项 | 结果 |
|---|---|
| 图片进分享目标 | ✅ manifest 放行 `image/*`；ShareReceiverActivity 先入库占位、Toast 秒回执 |
| 端侧 OCR | ✅ bundled 模型离线识别：`1000x1400` 图直接出文本，无任何下载等待 |
| 金额/商户/时间解析 | ✅ amount=39.0；商户「星巴克(国贸店)」（OCR 把户认成戶进了串，不影响主字段） |
| 入箱行字段 | ✅ category=金融、pending=0、text「商戶名称:星巴克(国贸店) ¥39」、note 账单摘要 |
| D3 媒体存储 | ✅ 只存 512px 缩略图 b2.png（4.5KB），media_path 落库，原图不留 |
| 对话查账复用 | ✅ 「这周花了多少钱」→「本周识别到 1 笔金额，合计 ¥39」+明细（第 4 步查询零改动） |

**新增**：`BillOcr.kt`（OCR + 三件套解析 + 缩略图）、库升 **v5**（`media_path` 列）、
manifest `image/*`、依赖一行（bundled ML Kit）。截图与测试夹具见
`screenshots/step6-bill-chat.png`、`fixtures/fake_bill.png`。

**本步踩坑（关键）**：
- **Play 服务版模型下载被墙**：换了 bundled 构件才通——坐标是 `com.google.mlkit:text-recognition-chinese`
  （不是 play-services 线）；InputImage 在 `com.google.mlkit:vision-common`；
- **App 摸不到 /sdcard 根文件**（ROM 权限）：自动化注入须把图放进 App 私有目录
  `files/` 再以 `file:///data/user/0/com.zhuli.app/files/...` 分享；真实分享走 Uri 不受影响；
  注意 **pm clear 会连 files/ 里的测试图一起清掉**，清库后要重放；
- **adb 注入 EXTRA_STREAM 是字符串形态**：`imageUri()` 加了 Uri.parse 字符串回退（真实分享为 Parcelable，不受影响）；
- **adb 控制台 GBK 编码**：Python 打印 ¥ 会 UnicodeEncodeError，核验时设 `PYTHONIOENCODING=utf-8`。

## ✅ 第 7 步模块验证（2026-11-19 夜，v0.7-ledger · 真机端到端通过）

**新增**：`Ledger.kt`（双通道入账口 + 十类双方向规则分类）；`ExtractPipeline` 与 `ShareReceiverActivity` 各挂一个 ingest 钩子；库升 **v6**（`ledger` 表，`UNIQUE(inbox_id)` 防重）；对话小结「这周花了多少钱」改账本口径；壳加「账本小结」按钮。

**真机验收（NX733J，7 条合成消息 + 假账单截图）**：

| 项 | 结果 |
|---|---|
| 文字通道入账 | ✅ 6/6：「午饭 32」→餐饮、「咖啡 28」→餐饮、「工资到账 8000」→**收·收入**、「地铁 6 元」→交通、「京东买耳机 299 元」→数码、「话费充值 50」→通讯 |
| 截图通道入账 | ✅ OCR 金额 39 走同一条口 → 餐饮（星巴克命中词表） |
| 防噪声 | ✅ 「下午 3 点开会」（无金额语境）**不入账**，账本零污染 |
| 防重复 | ✅ UNIQUE(inbox_id)：提取流水线与 OCR 回调双路径调用均幂等 |
| 对话周小结 | ✅ 「这周花了多少钱」→ 支出明细（餐饮 ¥99×3 / 通讯 ¥50 / 交通 ¥6）+ 收入 ¥8000，笔数合计正确 |
| 壳内面板 | ✅ 「账本小结」按钮弹窗同口径周明细 |

**本步踩坑**：
- **裸数字饭钱抽不出金额**：「午饭 32」（需求文档原例）在单位/语境双重识别下都会漏 —— 给 `AMOUNT_CTX` 补饭钱类词（饭/餐/外卖/咖啡/奶茶/饮料），刻意不加「买」这类宽词；
- 可空类型安全：`Ledger.ingest` 原写在 OCR 结果可空分支外，编译报 nullable receiver —— 挪进「识别成功」else 分支。

## ✅ 第 13 步 B4 模块验证（2026-11-19 深夜，v0.8-keepalive · 真机通过）

**新增**：`StartupGuard.kt`（保活引导：电池豁免直连 + 应用详情兜底 + 手动指引复制）；
壳加「白名单引导」按钮与面板；manifest 增 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`（普通权限）。

**真机验收（NX733J）**：

| 项 | 结果 |
|---|---|
| 面板状态检测 | ✅ 电池优化实时显示「❌ 仍受限」（真实取值，非写死）；自启动诚实标「⚠️ 需手动确认」 |
| 电池豁免直连 | ✅ 点按钮直达系统弹窗 `com.android.settings/.fuelgauge.RequestIgnoreBatteryOptimizations`，出现「允许/拒绝」——一次点击完成 |
| 应用详情兜底 | ✅ 落到 `com.android.settings/.applications.InstalledAppDetails` |
| 复制指引 | ✅ 剪贴板写入 + 聊天区回执「已复制保活设置指引，照着点一遍即可」 |
| 不代授权 | ✅ 验证时弹窗点「拒绝」退出，授权动作留给用户本人 |

**本步摸底结论（关键，避免白走）**：
厂商省电页 `com.zte.powersavemode` 的 `AppManagerActivity` / `SettingsActivity` / `PowerSaveModeActivity`
全部受 **`zte.permission.LAUNCH_POWER_SAVER_ACTIVITY`** 签名级权限保护，
`am start` 直接报 `SecurityException: Permission Denial ... requires zte.permission.LAUNCH_POWER_SAVER_ACTIVITY`。
**第三方应用与 shell 均不可达** → 代码里不保留这些候选（跳了只会白弹失败），
改为「电池豁免（能直连）+ 应用详情（必有）+ 复制指引（自启动只能手动）」三条真路。

**本步踩坑**：
- `cmd package resolve-activity` 能解析 ≠ 能启动：解析只看 intent-filter，`exported`/签名权限是另一回事，
  必须真 `startActivity` 才知道；
- `AppManualOptimizeActivity` 的动作名会落到 `ResolverActivity` 弹选择器（多匹配），
  幸好本步最终没走厂商页，否则要改用显式组件点名。

## 🔄 第 8 步模块验证（2026-11-19 深夜，v0.9-voice · 链路通过，转写受本机限制）

**新增**：`VoiceNote.kt`（系统 RecognitionService 文件音源转写 + 多候选遍历 + 云端兜底）；
`ShareReceiverActivity` 加 `audio/*` 分支；库 `applyVoice()`；manifest 加 `audio/*` 与 `RECORD_AUDIO`；
云设置面板加「允许语音上云」勾选（默认关）。

**真机验收（NX733J）**：

| 项 | 结果 |
|---|---|
| 语音进分享目标 | ✅ `audio/*` 已放行，Toast「已收下语音，正在本机转写…」秒回执 |
| 音频私有化 + 元数据 | ✅ 复制进 `files/zhuli_media/a<N>.wav`，时长解析正确（3s 的 wav 读出 3s） |
| 本地识别尝试 | ✅ 先试默认、再逐个试注册服务（含显式 `createSpeechRecognizer(ctx, ComponentName)`），失败不崩 |
| 诚实降级 | ✅ 落库 `[语音（3s）] 未转写：本机所有语音识别服务都不可用（…可在云设置里开启「允许语音上云」）`，音频保留待重试 |
| 云端兜底 | ⚠️ 代码已实现（multipart → `/audio/transcriptions`），**未验证**（无可用 Key） |

**本机硬结论**：`pm query-services -a android.speech.RecognitionService` 仅 1 个第三方服务，
默认与显式点名均 `ERROR_CLIENT` —— **该 ROM 没有可供第三方使用的语音识别服务**
（Google 语音未注册该接口；ZTE/高通的包是唤醒词服务）。所以「转写后入库建提醒」的验收标准
在**纯本地**条件下无法达成，必须走云端（需用户 Key）或端侧模型（需打包，体积大）。

## 打开方式

## 文件结构

```
zhuli-assistant-step2-build/
├── settings.gradle.kts          # 阿里云镜像优先的依赖仓库
├── build.gradle.kts             # AGP 8.7.3 / Kotlin 2.0.21
├── gradle.properties
└── app/
    ├── build.gradle.kts         # srcDir 直接指 ../core，零拷贝
    └── src/main/
        ├── AndroidManifest.xml  # 服务 + 分享目标 + 主界面注册
        └── java/com/zhuli/app/MainActivity.kt   # 搜索 / 最近 / 通知授权跳转
```

## 与后续步骤的关系

- core 四文件随第 4 步起逐步加料（分类抽取等），本壳无需改动即可同图复验
- 第 12 步起合并进 RikkaHub fork 时，本壳退役，主界面由 fork 的聊天 UI 承担
## ✅ 第 5 步模块验证（2026-11-19，v0.5-remind · 新机复验后全部闭环）

**已验证（真机 NX733J，新旧两轮）**：

| 项 | 结果 |
|---|---|
| 准点触发 | ✅ `due→fired` 偏差 **0 秒**，十余次实证（含息屏） |
| 日历双写 | ✅ 系统日历事件生成，`dtstart` 与 deadline 一致；完成时联动删除 |
| 「稍后10分钟」按钮 | ✅ 真实点击：`due +10min`（Δ18ms）、状态回 scheduled、FIRE 重排、通知撤除 |
| 「完成」按钮 | ✅ 人工点击：status=done、日历事件删除、通知撤除 |
| 漏响兜底 | ✅ （旧机证据）Doze 吃掉闹钟后开 App 由 sweep 补响 |

**重启实测（结论：系统拦截，非代码缺陷）**：
`adb reboot` 后全部 zhuli 闹钟（含心跳）被清空，**MyOS 未把 BOOT_COMPLETED 投递给本应用**
——显式补发被系统 SecurityException 拒绝；`install -r` 触发 MY_PACKAGE_REPLACED 亦未重建
（努比亚自启动管理默认拦截第三方开机广播）。
兜底实证有效：**任意一次打开 App，`sweepAsync → scan` 立即把全部活跃提醒重挂闹钟**
（实测 FIRE 精确归位到次日 09:30，心跳同时恢复）。
→ 第 13 步 B4「App 内引导加入 MyOS 自启动 + 电池白名单」升级为**必要项**。

**排查方法备忘（这台机器 logcat 恒为空）**：
读库核验用 `adb exec-out run-as com.zhuli.app cat databases/zhuli_inbox.db`（勿用 adb shell，
伪终端会塞填充字节致 DB malformed）；报警钟用 `dumpsys alarm` 的 Alarm{} 行内 origWhen；
横幅按钮坐标靠 detect_elements 抓拍定位（横幅仅存活数秒，手动点击最可靠）。

**本步新增文件**：`Reminder.kt`、`ReminderEngine.kt`、`ReminderNotifier.kt`、`AlarmReceiver.kt`、`ReminderActionReceiver.kt`、`BootReceiver.kt`、`CalendarWriter.kt`；库升 v4（`reminders` 表）；manifest 增加 POST_NOTIFICATIONS / SCHEDULE_EXACT_ALARM / USE_EXACT_ALARM / RECEIVE_BOOT_COMPLETED / READ_CALENDAR / WRITE_CALENDAR 与三个 receiver。

**真机踩坑（关键）**：
- `setExactAndAllowWhileIdle` 在努比亚 MyOS 息屏后被限流（due 17:26:21，到 17:26:55 未响）→ 改 `setAlarmClock` 后偏差 0 秒；状态栏会出现闹钟图标，属正常代价。
- 这台机器 `adb logcat -d` 恒为 0 行（ROM 限制），排障改走「读库 + dumpsys alarm + content query + 壳内体检面板」。
- 过期 >1 分钟的提醒不要再重排（会被排成「现在+5秒」→ 连环响）；>1 小时直接标 canceled。
- 通知栏动作接收器必须保持 `exported="false"` 且不加 intent-filter（仅 PendingIntent 内部投递；
  曾为广播排障临时放开，验证后已逐行比对还原，与交接包源码 0 差异）。
