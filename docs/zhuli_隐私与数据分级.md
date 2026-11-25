# zhuli 隐私与数据分级（批次 A 基线）

> 目的：把「什么数据、能不能出网、能不能进仓库」写成一张可执行的表，
> 让新增功能时不用重新讨论，也避免「这条路径忘了过闸」。
>
> 本文只写规则，不写任何真实样本；示例一律使用合成值。

## 1. 数据分级

| 级别 | 名称 | 典型内容 | 出网规则 | 入库规则 |
|---|---|---|---|---|
| L0 | 公开 | 代码、文档、构建脚本、合成的测试样本 | 可自由出网 | 可提交 |
| L1 | 本机业务数据 | 待办标题、分类、提醒时间、聚合计数 | 经隐私管线判定后可出网 | 只允许**合成值**进仓库 |
| L2 | 个人敏感 | 手机号、住址、金额、取件码、运单号、公司+岗位、聊天原文 | 默认本地；"脱敏上云"模式下打码后出网；strict 模式一律不出 | 禁止提交（含截图、dump、日志） |
| L3 | 硬红线 | 证件号、银行卡号、密码/验证码/token/密钥 | **任何模式都不出网**（脱敏也不发） | 禁止提交 |

判定实现：`com.zhuli.core.SensitiveGuard`（唯一规则源）。
分级决策：`com.zhuli.core.PrivacyDecision`（唯一判定源）。
请求体扫描：`com.zhuli.core.PrivacyPipeline`（唯一扫描源）。

## 2. 出网链路要求

任何会把用户内容发到设备外的代码，必须满足：

1. **走过 `PrivacyPipeline`**：不允许自己写正则、自己判断"这段应该不敏感"。
2. **失败即拒绝**：请求体读不出、不是 JSON、结构不认识、超过扫描上限 —— 一律不发，
   并写一条 `cloud_log`。禁止 `catch { proceed(request) }` 式的 fail-open。
3. **硬红线整轮中止**：命中 L3 时不能"换个字段继续发"，也不能"换个模型重试"。
4. **本机回环例外**：`127.0.0.1` / `localhost` / `::1` / `10.0.2.2` 视为未出网，
   但仅限本地模型网关与 adb reverse 自测。
5. **本地模式不外溢**：声明 `leavesDevice = false` 的后端，其实际执行必须校验
   provider 是 `ProviderSetting.LiteRtLocal`（见 `LocalBackendPolicy`）。

当前覆盖的请求体结构（`PrivacyPipeline.Shape`）：

- `chat/completions`：`messages[*].content`、`tool_calls[*].function.arguments`、`function_call.arguments`；
- `responses`：`instructions`、`input`（字符串 / 分段 / `arguments` / `output`）；
- `claude/messages`：`system`、`messages[*].content[*].text`、`tool_use.input`、`tool_result.content`；
- `gemini/generateContent`：`systemInstruction.parts[*].text`、`contents[*].parts[*].text`、`functionCall.args`、`functionResponse.response`；
- `embeddings`：`input`（字符串或字符串数组）；
- `legacy completions`：`prompt`。

新增一种请求格式时，必须同时补 `PrivacyPipeline` 与 `PrivacyPipelineTest`。

## 3. 仓库准入规则

以下内容**永远不提交**：

- 真实短信 / 通知原文、通话记录、聊天记录；
- 取件码、提取码、运单号、订单号；
- 手机号、住址、身份证、银行卡、车牌；
- 设备标识（IMEI / 序列号 / Android ID / Wi-Fi SSID / 蓝牙名）；
- 账号信息（邮箱、用户名、Cookie、API Key、PAT）；
- 本机绝对路径（含用户名）；
- 真机截图、`uiautomator dump`、`dumpsys` 输出、数据库文件；
- 原始会话导出（`.jsonl` / `.jsonl.zstd`）。

测试夹具只能使用**虚构且明显虚构**的值，例如：

- 手机号 `13800001111`（保留号段，非真实用户）；
- 取件码 `1234` / `8-1-2233`；
- 地址 `示例市示例区示例路 1 号`；
- 驿站 `示例驿站`。

`.gitignore` 已对真机取证目录、会话导出、截图目录做兜底，但**忽略规则不是审计**：
提交前仍需 `git diff --cached` 逐条看。

## 4. 提交前自检清单

```bash
# 1) 暂存区里有没有数据库/截图/会话导出
git diff --cached --name-only | grep -Ei '\.(db|png|jpg|jsonl|zstd|zip|jks|keystore)$'

# 2) 敏感形态扫一遍（只列文件名，不打印内容）
git grep -lP '(?<!\d)1[3-9]\d{9}(?!\d)' --cached
git grep -lP '(?<!\d)\d{17}[\dXx](?!\d)' --cached
git grep -l '取件码\|运单号\|提取码' --cached

# 3) 本机绝对路径
git grep -lP '[A-Za-z]:\\\\Users\\\\' --cached
```

命中不代表一定有问题（文档里讨论规则也会命中），但必须逐条确认是合成值还是真实值。

## 5. 已知限制（诚实标注）

- 图片 / 音频**无法在文本层脱敏**：视觉与语音请求只能靠"用户显式同意"把关，
  管线不扫描 base64。
- 纯数字取件码（如 `6825`）若脱离语境且不含横杠，可能漏判；
  带语境（`取件码 6825`）或带横杠（`8-1-2233`）的形态已覆盖。
- 正则判定天然有误伤与漏判，所以策略是「宁可多拦」；被误拦的用户可以改走本地模式。
- **历史提交**里的真实数据不会因为本次删除而消失，需要单独授权后做历史清理。
