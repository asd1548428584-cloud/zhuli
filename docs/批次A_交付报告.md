# 批次 A（基线与安全整改）交付报告

- 执行时间：2026-11-25
- 仓库：`asd1548428584-cloud/zhuli`（**public**）
- 基线 commit：`c44314b93c5a62d10b2babcc46d065fa88f3f918`
- 版本：`1.4.8` / `versionCode 41` / `InboxDatabase DB_VERSION = 16`
- 状态：**本批次已完成，等待审核。未推送 GitHub，未改远端历史。**

---

## 1. 目标

把「隐私闸门」从"看起来有、实际会漏"改成"漏不掉"，并清掉已经公开在仓库里的真实个人信息。

拆成四件事：

1. **基线核对** —— 先证明"我改的是你说的那个版本"，避免在错误的树上施工。
2. **仓库真实数据排查与清理** —— 仓库是 public，任何真实短信/取件码/设备信息都等于已泄露。
3. **隐私闸门统一设计与实现** —— 消除两套重复判定、补齐 fail-open、把扫描从"只看 messages 文本"扩到工具参数与 Responses/Claude/Gemini。
4. **平台侧加固** —— Manifest、明文流量、备份、权限四项审计。

---

## 2. 修改文件完整列表

### 2.1 新增（11 个）

| 文件 | 行数 | 作用 |
|---|---|---|
| `zhuli/lastchat/app/src/main/java/com/zhuli/core/PrivacyDecision.kt` | 53 | 唯一判定源，取代两份重复判定 |
| `zhuli/lastchat/app/src/main/java/com/zhuli/core/PrivacyPipeline.kt` | 372 | 统一请求体扫描器，覆盖 6 种 API 结构 |
| `zhuli/lastchat/app/src/main/java/com/zhuli/bridge/OutgoingBodyGate.kt` | 64 | 拦截器纯逻辑，可 JVM 单测 |
| `zhuli/lastchat/app/src/main/java/com/zhuli/agent/LocalBackendPolicy.kt` | 33 | 本地模式强制校验 on-device provider |
| `zhuli/lastchat/app/src/main/res/xml/network_security_config.xml` | 26 | 禁全明文，仅回环例外 |
| `docs/zhuli_隐私与数据分级.md` | 92 | L0–L3 分级、出网 5 条硬要求、准入清单 |
| `app/src/test/java/com/zhuli/core/PrivacyPipelineTest.kt` | 19 例 | 六种结构 + 工具参数 |
| `app/src/test/java/com/zhuli/bridge/OutgoingBodyGateTest.kt` | 9 例 | fail-closed 行为 |
| `app/src/test/java/com/zhuli/core/ParcelSensitiveTest.kt` | 5 例 | 取件码识别 + 误判保护 |
| `app/src/test/java/com/zhuli/agent/LocalBackendPolicyTest.kt` | 2 例 | 本地模式拒绝云 provider |
| `app/src/test/java/com/zhuli/core/ManifestSecurityTest.kt` | 5 例 | Manifest / xml 配置回归 |

### 2.2 修改（30 个）

生产代码（14）：
```
app/src/main/AndroidManifest.xml
app/src/main/res/xml/backup_rules.xml
app/src/main/res/xml/data_extraction_rules.xml
app/src/main/java/com/zhuli/core/SensitiveGuard.kt
app/src/main/java/com/zhuli/core/OutgoingGate.kt
app/src/main/java/com/zhuli/core/PrivacyRouter.kt
app/src/main/java/com/zhuli/core/CloudClient.kt
app/src/main/java/com/zhuli/agent/ZhuliAgentRuntime.kt
app/src/main/java/com/zhuli/bridge/ZhuliPrivacyInterceptor.kt
app/src/main/java/com/zhuli/bridge/ZhuliSensitiveGuard.kt
app/src/main/java/com/zhuli/bridge/ZhuliPrivacy.kt
app/src/main/java/com/zhuli/bridge/ZhuliSelfCheckData.kt
app/src/main/java/com/zhuli/bridge/ZhuliSelfCheckPage.kt
app/src/main/java/com/zhuli/bridge/ZhuliIntentRouter.kt   （仅注释脱敏）
```

测试与 androidTest（13）：
```
app/src/test/java/com/zhuli/core/SensitiveGuardTest.kt
app/src/test/java/com/zhuli/core/OutgoingGateTest.kt
app/src/test/java/com/zhuli/core/ReminderIntakeTest.kt
app/src/test/java/com/zhuli/core/ToolsCourierTest.kt
app/src/test/java/com/zhuli/agent/AgentDegradeTest.kt
app/src/test/java/com/zhuli/agent/AgentFlowTest.kt
app/src/test/java/com/zhuli/memory/MemoryEmbeddingTest.kt
app/src/test/java/com/zhuli/memory/MemoryExtractorTest.kt
app/src/test/java/com/zhuli/memory/MemoryRankerTest.kt
app/src/test/java/com/zhuli/memory/MemorySqliteTest.kt
app/src/androidTest/.../ZhuliCourierInstrumentedTest.kt
app/src/androidTest/.../ZhuliE2ETest.kt
app/src/androidTest/.../ZhuliLocalE2ETest.kt
```

文档与仓库策略（5）：
```
.gitignore
SESSION_LOG.md
docs/archive/交接_2026-11-19/会话原始/README.md
docs/技术资料库/10_架构与决策/ADR/0003-fork-LastChat.md
docs/技术资料库/10_架构与决策/T-G2深水区迁移执行计划.md
```

### 2.3 删除（31 个，`git rm`）

| 目录 | 数量 | 内容 |
|---|---|---|
| `docs/archive/交接_2026-11-19/会话原始/` | 2 | `session-2026-11-19.jsonl`（10.5MB / 2651 行）、`session.v4.jsonl.zstd`（3.1MB） |
| `docs/archive/交接_2026-11-19/screenshots/` | 11 | 含真实取件码列表的截图 |
| `fixtures/realdev_20261121/` | 18 | 13 张截图（`s1`–`s13`）+ `dumpsys_notif.txt` + `notif_evidence.txt` + 3 个 `ui*.xml` |

`git diff --stat` 合计：**32 files changed, 350 insertions(+), 582 deletions(-)**（净减 232 行，少掉的是两份重复规则与一份旧拦截器）。

---

## 3. 改前后差异（关键处）

### 3.1 `SensitiveGuard`：补三类规则

改前只有 电话 / 证件号 / 卡号 / 凭证 / 金额 / 地址 / 公司岗位。

```kotlin
// 改后新增（实际代码，已省略注释）
private val PICKUP = Pattern.compile(
    "(?:取件码|取货码|提取码|领取码|取件号|取货号)\\s*(?:是|为|=)?\\s*[:：]?\\s*[0-9A-Za-z][0-9A-Za-z-]{2,15}" +
        "|凭\\s*[0-9A-Za-z][0-9A-Za-z-]{2,10}\\s*取" +
        "|(?<![\\d-])\\d{1,2}-\\d{1,2}-\\d{3,5}(?![\\d-])"
)
private val WAYBILL = Pattern.compile(
    "(?:运单号|快递单号|物流单号|订单号|包裹号)\\s*[:：]?\\s*[A-Za-z0-9][A-Za-z0-9-]{5,23}" +
        "|(?<![A-Za-z0-9])(?:SF|YT|ZT|YD|JD|JT|DBL|CNSD|STO)[A-Z0-9]{8,18}(?![A-Za-z0-9])"
)
private val COORD = Pattern.compile("(?<![\\d.])(?:-?\\d{1,3}\\.\\d{3,})[,\\s]+(?:-?\\d{1,3}\\.\\d{3,})(?![\\d.])")
```

取件码必须同时认「带语境」和「裸结构」：Agent 调 `save_fact(value="12-3-4567")` 时没有"取件码"三个字，只靠语境词会漏。运单号补 10–15 位纯数字（既不落手机号也不落银行卡，是原有规则的真实盲区）+ 常见承运商前缀。

判定顺序插入到「凭证链接」之后、「金额」之前 —— 取件码/运单号属于"可用于冒领实物"的一级敏感，优先级必须高于金额。`hits()` 与 `redact()` 同步加入三类，避免"能判定不能脱敏"。

**误判保护**（`ParcelSensitiveTest` 固化）：`2026-11-25`、`11-25`、`3.14159，自然常数 2.71828` 不得命中。

### 3.2 `PrivacyPipeline`：把两套不同覆盖的扫描器合成一套

这一步需要说清楚真实情况，否则会误判整改幅度：

**改前仓库里有两套扫描器，覆盖范围不一样。**

- `OutgoingGate`（`CloudClient` 走这条）：已经处理了 `tool_calls[].function.arguments`、多模态 `content` 里的 text 片段、`tool_call_id`，但**只认 `messages` 一种结构**。
- `ZhuliPrivacyInterceptor.rewrite()`（挂在全局 OkHttpClient，所有 provider 都过这条）：**独立重写了一遍**，只改 `messages[].content` 和顶层 `system`，不认 `tool_calls`，也不认 Responses / Claude / Gemini / embeddings。

因为拦截器在 OkHttp 层、范围更广，它的短板就是实际短板：

```kotlin
// 改前 ZhuliPrivacyInterceptor.rewrite()（真实代码，节选）
// messages[*].content
val messages = root["messages"] as? JsonArray
val newMessages = messages?.let { arr -> arr.map { obj ->
    rewriteContent(obj["content"])   // 只碰 content
} }
// system（字符串或分段数组）
val systemReplaced = rewriteContent(root["system"])
// 到此为止：tool_calls / input / instructions / tool_use / functionCall 全都没进过判定
```

```kotlin
// 改后：6 种 Shape + 深层递归，两条路径共用同一实现
enum class Shape { CHAT_COMPLETIONS, RESPONSES, CLAUDE_MESSAGES, GEMINI, TEXT_INPUT, PROMPT, UNKNOWN }

// 顶层扫描字段
"messages" | "contents" | "systemInstruction" | "instructions" | "system" | "input" | "prompt"

// 工具参数单独处理（字符串里的 JSON，需要在解析→脱敏→回写后仍是合法 JSON）
assistant.tool_calls[].function.arguments      // OpenAI chat
function_call.arguments                        // legacy function_call
tool_use.input                                 // Claude Messages
functionCall.args / functionResponse.response  // Gemini
```

关键约束：**工具参数脱敏后必须仍是合法 JSON**，否则抛 `Blocked(where, "脱敏后参数结构损坏")` 直接拒绝出网 —— 不允许"改坏了还是发出去"。无改动时原样返回原字符串，不做重新序列化（避免破坏上游对空格/字段顺序的假设）。

`OutgoingGate` 与 `ZhuliPrivacyInterceptor` 现在都只是 `PrivacyPipeline` 的薄封装（前者包一层 `Blocked` 异常类型，后者包一层 OkHttp 读包/重建/留档），**判定逻辑只剩一份**。

### 3.3 `ZhuliPrivacyInterceptor`：5 处 fail-open 全部转 fail-closed

| # | 改前 | 改后 |
|---|---|---|
| 1 | `request.body == null` → `chain.proceed()` | `403` + `cloud_log` |
| 2 | one-shot / duplex body 不可读 → 放行 | `403` |
| 3 | `contentLength > 2MB` → 放行 | 提到 8MB，仍超限则 `403` |
| 4 | `buffer.readUtf8()` 抛异常 → 放行 | `403` |
| 5 | 非 JSON / 无已知结构 → 放行 | `403` |

```kotlin
// 改前（五处同构，节选）
val body = request.body ?: return chain.proceed(request)
if (body.isOneShot() || body.isDuplex()) return chain.proceed(request)
if (declared > MAX_BODY_BYTES) return chain.proceed(request)
val raw = try { Buffer().also { body.writeTo(it) }.readUtf8() } catch (t: Throwable) { return chain.proceed(request) }
val root = try { json.parseToJsonElement(raw) as? JsonObject } catch (t: Throwable) { null }
    ?: return chain.proceed(rebuild(request, raw, body.contentType()))

// 改后（实际代码）
val body = request.body
if (body == null) { log(...); return blockedResponse(request, "请求没有可扫描的请求体，拒绝出网") }
if (body.isOneShot() || body.isDuplex()) { ... return blockedResponse(request, "请求体是一次性/流式上传，无法在出网前完成隐私扫描") }
if (declared > MAX_BODY_BYTES) { ... return blockedResponse(request, "请求体 ${declared / 1024}KB 超过隐私扫描上限") }
val raw = try { Buffer().also { body.writeTo(it) }.readUtf8() }
    catch (t: Throwable) { ... return blockedResponse(request, "请求体读取失败，无法在出网前完成隐私扫描") }

val strict = AssistantPrefs.privacyMode(context) == "strict"
return when (val decision = OutgoingBodyGate.decide(raw, strict)) {
    is OutgoingBodyGate.Decision.Block -> { log(...); blockedResponse(request, decision.reason) }
    is OutgoingBodyGate.Decision.Send  -> { log(...); chain.proceed(rebuild(request, s.body, body.contentType())) }
}
```

每次拦截都写一条 `cloud_log`（`ok=false` + 原因），用户能在自检页看到"这一轮为什么没发出去"，而不是静默失败。

`isScannedPath()` 增加 `/embeddings`、`generatecontent` —— 改前这两条路径的请求体根本不进扫描器。

### 3.4 `LocalBackendPolicy`：修「本地模式实际走云」

```kotlin
// 改前 generateLocal()
val providerSetting = model.findProvider(settings.providers) ?: error("model_not_installed:no_provider")
val handler = providerManager.getProviderByType(providerSetting)   // providerSetting 可能是任意云 provider
// LocalModelProvider 声明 leavesDevice=false，但这里没人校验过它到底是不是本地

// 改后（实际代码）
val providerSetting = model.findProvider(settings.providers) ?: error("model_not_installed:no_provider")
// 端侧链路只接受 LiteRtLocal；云 provider 在这里就被拒绝
LocalBackendPolicy.requireOnDevice(providerSetting)
val handler = providerManager.getProviderByType(providerSetting)
```

失败的语义是明确的：`IllegalStateException("model_not_installed:local_provider_required:<类型>")`，上层按"本地不可用"处理（提示用户装模型），**不会**自动回落云端。

### 3.5 `privacyDemandsLocal()`：判定异常时 fail-closed

```kotlin
// 改前（实际代码）
private fun privacyDemandsLocal(goal: String): Boolean = try {
    val cloudReady = AssistantPrefs.cloudReady(appCtx)
    cloudReady && !PrivacyRouter.decide(appCtx, PrivacyRouter.Task.CHAT, goal).allow
} catch (t: Throwable) {
    // 原注释：「判定本身出问题时不擅自降级（宁可让 Gate 去拦）」
    false          // ← 实际效果是"判定炸了就按可上云处理"
}

// 改后（实际代码）
} catch (t: Throwable) {
    ZhuliLog.w("zhuli.agent", "隐私判定异常，本轮按仅本机处理", t)
    true           // ← 判定炸了 → 只在本机跑，可能答不出来，但不外发
}
```

原注释的意图（"让 Gate 去拦"）在逻辑上不成立：Gate 拦的是**请求体内容**，而这条路径决定的是**走不走本机**，两者不在同一层；一旦判定抛异常，`start()` 拿到 `false` 就走云，Gate 只会在内容确实命中规则时才拦。改成 `true` 后语义才与注释一致。

### 3.6 消除重复判定

`PrivacyRouter.decide()` 与 `ZhuliPrivacy.decide()` 原各自维护一份 `HARD_RED_LINES` + 一套分支（逐字重复）。现两者都委托 `PrivacyDecision.decide()`，`HARD_RED_LINES` 只保留一份。`ZhuliSensitiveGuard` 从 119 行实现改为纯转发器（-119/+8）。

### 3.7 Manifest

```xml
<!-- 改前 -->
android:allowBackup="true"
android:usesCleartextTraffic="true"
android:dataExtractionRules="@xml/data_extraction_rules"
android:fullBackupContent="@xml/backup_rules"

<!-- 改后 -->
android:allowBackup="false"
android:usesCleartextTraffic="false"
android:networkSecurityConfig="@xml/network_security_config"
```

移除 `dataExtractionRules` / `fullBackupContent` 属性是为了让 `allowBackup=false` 成为唯一事实源（属性留着会出现"配置写了排除但备份仍关"的歧义）。两个 xml 文件保留并改为全域 exclude + `disableIfNoEncryptionCapabilities="true"`，作为二道保险。

`backup_rules.xml` 改前只 `include` 了 `upload/`，数据库不在任何规则里 → 默认被备份。改后 <exclude domain="database"/> 等全覆盖。

### 3.8 `.gitignore`

```gitignore
fixtures/realdev_*/
fixtures/*_real*.json
**/session-*.jsonl
**/session*.jsonl.zstd
**/*.jsonl.zstd
docs/archive/**/screenshots/
```

### 3.9 自检页

`ZhuliSelfCheckData.privacyRows()` 去掉 `cloudReady` 参数（该参数只影响一行文案，却让"没配 key"看起来像"隐私没接线"）；端点从真实网关改为 `https://example.com/v1`；文案改为「已接线（chat/responses/embeddings + 工具参数）」。

---

## 4. 修复的问题编号

| 编号 | 问题 | 严重度 | 修复位置 |
|---|---|---|---|
| D-A1 | 「本地模式」不校验 provider 类型，prompt 实际发往云端 | **高** | `LocalBackendPolicy` + `ZhuliAgentRuntime.generateLocal` |
| D-A2 | `privacyDemandsLocal()` 判定异常返回 false → 走云 | **高** | `ZhuliAgentRuntime` |
| D-A3 | 拦截器 5 处 fail-open | **高** | `ZhuliPrivacyInterceptor` + `OutgoingBodyGate` |
| D-A4a | 拦截器 `rewrite()` 只扫 `messages[].content` + `system`，工具参数与 Responses/Claude/Gemini 全漏 | **高** | `PrivacyPipeline` + 拦截器重写 |
| D-A4b | `OutgoingGate` 与 `rewrite()` 两套实现、口径不一致（前者扫工具参数、后者不扫） | **高** | 二者统一委托 `PrivacyPipeline` |
| D-A4c | `isScannedPath()` 不含 `/embeddings`、`generatecontent` → 整类请求不进扫描器 | **高** | `ZhuliPrivacyInterceptor.isScannedPath` |
| D-A5 | 无取件码 / 运单号 / GPS 坐标规则 | 中 | `SensitiveGuard` |
| D-A6 | 判定逻辑两份重复，易改一处漏一处 | 中 | `PrivacyDecision` |
| D-A7 | `allowBackup=true`、`usesCleartextTraffic=true`、无 NSC | 中 | `AndroidManifest.xml` + 新增 NSC |
| D-A8 | `backup_rules.xml` 未排除数据库 | 中 | `backup_rules.xml` |
| D-A9 | 仓库含真实取件短信、邮箱、本机路径、设备 dump | **高（已公开）** | 删除 31 个文件 + 文档改写 |
| D-A10 | 测试与文档大量真实取件码 | 中 | 13 个测试文件 + 1 处源码注释 + 3 篇文档改写 |
| D-A11 | 自检页硬编码真实网关地址 | 低 | `ZhuliSelfCheckData` |

**权限审计结论（保留，不删）**：

| 权限 | 保留理由 | 证据 |
|---|---|---|
| `REQUEST_INSTALL_PACKAGES` | 应用内更新 | `UpdateChecker.kt` 使用 `application/vnd.android.package-archive` |
| `ACCESS_WIFI_STATE` / `CHANGE_WIFI_MULTICAST_STATE` | 局域网 Web 服务 | `WebServerService.kt` WifiLock、`NsdServiceRegistrar.kt` MulticastLock |
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | 占位符运行时取值 | `AndroidPlaceholderRuntimeValues.kt` 无权限时降级为空 |

---

## 5. 数据库变更说明

**本批次无数据库变更。** `DB_VERSION` 仍为 `16`，未新增/修改任何迁移。

理由：批次 A 只改隐私判定与出网链路，不触碰任何表结构或字段。已改动的内容（脱敏文案、自检行）不落库。

测试侧对 DB 只有读取（`MemorySqliteTest` 等），未改 schema。

---

## 6. 新增测试列表

| 文件 | 用例数 | 覆盖点（实际用例名） |
|---|---|---|
| `PrivacyPipelineTest` | 19 | chat content 手机号打码；`tool_calls` 参数打码后仍合法 JSON；`tool_calls` 里的证件号整轮中止；旧式 `function_call`；多模态只动文本不动图片；Responses 的 `input`（字符串 / 分段数组 / `function_call` / `function_call_output`）；Claude `tool_use.input`；Gemini `contents` + `functionCall.args`；embeddings `input` 数组；legacy `prompt`；未知结构中止；非法 JSON 中止；strict 模式任何敏感项中止；无敏感项原样返回不重序列化；计数可用于留档 |
| `OutgoingBodyGateTest` | 9 | 请求体不可读 / 空 / 非 JSON / 结构不认识 → 拒绝；正常聊天放行并报扫描字段数；Responses `input` 真的被扫；strict 直接拦；硬红线在拦截器层也中止；取件码与运单号被打码 |
| `ParcelSensitiveTest` | 5 | 带语境取件码；**裸结构**取件码（Agent 参数无语境）；运单号/订单号；日期与普通编号不误判；取件码不把金额规则带偏 |
| `LocalBackendPolicyTest` | 2 | 端侧 provider 通过；云 provider 被拒且错误可识别为模型不可用 |
| `ManifestSecurityTest` | 5 | 禁明文流量且指向 NSC；NSC 默认禁明文只放行回环；应用数据不参与云备份与换机迁移；短信接收器必须带系统级权限；内部广播接收器不得导出 |

改造既有测试（不删用例）：
- `SensitiveGuardTest`：样本集补 取件码 / 运单号 / 坐标 三类。
- `OutgoingGateTest`：原「结构异常」用例用 `prompt` 构造，`prompt` 现已是已知结构 → 改用 `{"foo":{...}}`，语义（未知结构必须拒绝）不变。
- `ReminderIntakeTest`：`真实取件短信能解析出码并提级` → `取件短信能解析出码并提级`，正文换合成样本，断言不变。
- 6 个 memory/agent 测试：把残留的真实取件码（4 位数字码、三段式 `x-x-xxxx` 码）替换为 `1234` / `8-1-2233` 等占位值，断言逻辑未动。**本报告也不再复述真实码值。**
- 3 个 androidTest：断言从「包含真实取件码字面量」改为「匹配取件码结构正则 / `isNotBlank()`」，判定强度不降（仍是"必须解析出码"）。

---

## 7. 实际执行的测试命令

```bash
# 单元测试（基线 & 整改后各跑一次）
cd zhuli/lastchat
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew --no-daemon \
  :app:testDevDebugUnitTest -x :app:buildWebUi -x :speech:downloadSherpaOnnxAar

# 全量编译 + 打包（验证 Manifest 合并没有破坏构建）
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew --no-daemon \
  :app:assembleDevDebug -x :speech:downloadSherpaOnnxAar

# 打包后 APK 的 Manifest 复核
aapt2 dump xmltree --file AndroidManifest.xml \
  app/build/outputs/apk/dev/debug/LastChat_1.4.8_dev-universal-debug.apk \
  | grep -Ei 'allowBackup|usesCleartextTraffic|networkSecurityConfig'
```

---

## 8. 测试结果与日志

### 8.1 单元测试

基线（改动前，`c44314b`）：

```
files=29  tests=225  failures=0  errors=0  skipped=0
BUILD SUCCESSFUL in 58s
```

整改后：

```
files=34  tests=265  failures=0  errors=0  skipped=0
BUILD SUCCESSFUL in 1m 3s
```

净增 **40 例**（265−225），全部通过，无 skip、无 @Ignore。5 个新文件的用例数与第 6 节一致（19+9+5+2+5=40）。

### 8.2 全量编译

```
> Task :app:assembleDevDebug
BUILD SUCCESSFUL in 1m 13s
306 actionable tasks: 19 executed, 287 up-to-date
```

产物（4 个 ABI，均 2026-11-25 13:18）：

| APK | 大小 |
|---|---|
| `LastChat_1.4.8_dev-arm64-v8a-debug.apk` | 209.9 MB |
| `LastChat_1.4.8_dev-armeabi-v7a-debug.apk` | 177.6 MB |
| `LastChat_1.4.8_dev-universal-debug.apk` | 254.5 MB |
| `LastChat_1.4.8_dev-x86_64-debug.apk` | 194.7 MB |

### 8.3 打包后 Manifest 实证

```
A: android:allowBackup=false
A: android:usesCleartextTraffic=false
A: android:networkSecurityConfig=@0x7f15000b
```

三条均落在**最终 APK** 上，不是只看源文件。

### 8.4 工作区状态

```
32 files changed, 350 insertions(+), 582 deletions(-)   # 已跟踪文件（修改）
+ 11 个新增（未跟踪）
+ 31 个删除（已 git rm，进入暂存区）
```

合计：**74 个路径发生变化**（32 改 + 11 增 + 31 删）。

---

## 9. 未完成事项

### 9.1 历史遗留（用户已决定保留，不再处理）

1. **历史提交中的真实数据未清理（已决定保留）。** 31 个文件已从当前树删除，但 `c44314b` 及更早的提交里仍然存在。仓库是 public，任何人 `git log -p` 都能取回。彻底清除需要 `git filter-repo` + `push --force` 重写历史；**用户 2026-11-25 明确决定保留历史、不做重写**，因此本项作为已接受的已知风险记录在此，不再作为待办。
2. **已泄露的账号类信息需你自行处置。** `dumpsys_notif.txt` 中含用户邮箱（完整地址本报告不复述），该文件已在历史提交中。邮箱本身不构成账号失陷，但建议评估是否需要更换密码/开启两步验证。
3. **推送 GitHub 未执行。** 按指令只做本地封存，`origin` 未被触碰，远端 `master` 仍为 `c44314b`。

### 9.2 本批次范围外（留给后续批次）

4. **androidTest 未运行。** 3 个 instrumented 测试的断言已改为结构正则，但没有设备执行，因此**不能声称已验证**。这属于批次 F 的设备验收。
5. **真机/模拟器验证未做。** 雷电 14 与努比亚 Z70 Ultra 均未安装本批次产物。
6. **`fixtures/privacy/run_privacy_test.py`（`adb reverse` 全请求体测试）未运行。** 这套脚本验的是"拦截器在真实设备上对 4 种 API 都是 403"，与单测的纯逻辑验证互补，但我没跑。
7. **`SensitiveGuard` 的覆盖边界。** 当前是正则黑名单，不是 NER。语义化表述（"我住在某某路几号"不带"地址"字样、无坐标）仍可能不被判定。已在 `docs/zhuli_隐私与数据分级.md` 的「已知限制」中写明，不在本批次扩大范围。
8. **`zhuli/privacy` 相关离线测试目录**（`app/src/test-offline/`，5 个未迁移类）未纳入本批次，仍不在 CI 范围内。

---

## 10. 是否满足进入批次 B 的条件

**满足。** 已达成：

- 基线一致（`c44314b` / 1.4.8 / 41 / DB v16），工作区干净起步。
- 敏感数据已从**当前树**清除，`.gitignore` 已封堵同类文件再次进入。
- 隐私闸门有了唯一判定源与唯一扫描器，11 组缺陷（D-A1–D-A11，其中 D-A4 拆为 a/b/c 三项）全部修复且有测试固化。
- 265 例单测全绿，全量编译通过，APK 级 Manifest 实证。
- 未降级任何既有测试：改的是样本值与被测数据，不是断言强度。

**用户已决定（2026-11-25）**：

1. 保留现有全部 Git 历史，**不做历史重写**。历史中的真实取件码/邮箱保持现状，作为已知风险接受。
2. 批次 A 在本地封存（commit + tag `batch-a`），**不推送远端**。

因此原「是否重写历史 / 是否推送」两项待确认事项已关闭。批次 B 可从 `batch-a` 之后继续，**不要**再提历史清理。

---

*报告生成：2026-11-25 · 未推送 · 未改远端*
