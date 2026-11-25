# zhuli · 本地优先的 Android 个人助理

在 [LastChat](https://github.com/cocolalilal/LastChat)（其本身是 [RikkaHub](https://github.com/re-ovo/RikkaHub) 的 fork）之上做的个人助理分支：
**短信/通知采集 → 结构化记忆 → Agent 执行**，数据默认留在本机，出网前过隐私闸门。

| 项 | 当前值 |
|---|---|
| 包名 | `lastchat.rikkafork.cocolal`（dev 变体加 `.dev`） |
| 应用版本 | `1.4.8`（versionCode 41） |
| 数据库 | `zhuli_inbox.db` **v16** |
| 工程根 | `zhuli/lastchat`（Gradle 多模块） |
| 验收设备 | 努比亚 Z70 Ultra（NX733J / Android 16 / arm64-v8a） |
| 许可证 | AGPL-3.0（继承上游，见 `zhuli/lastchat/LICENSE`） |

## 状态

- **已通过**：编译、`:app:testDevDebugUnitTest` **27 套件 / 219 例全绿**、Z70 Ultra 真机覆盖安装、
  DB v15→v16 迁移（779 条存量短信 0 丢失）、隐私闸门与采集链路真机验证。证据见 `CURRENT_STATE.md`。
- **未真机验证**：Agent 任务端到端（adb 无法注入中文）、云端工具调用、Gemma 语义检索、24h 后台浸泡。
  JVM/Robolectric 层已覆盖，但不宣称真机通过。
- **已知缺陷**：v17 开发中对「全库输入框重复」做了定位但**未修**，本快照包含该未完成状态。

> 状态只有一个权威来源：根目录 `CURRENT_STATE.md`。README 与任何历史文档冲突时，以它为准。

## 目录

```
.
├── CURRENT_STATE.md      # 唯一权威状态（版本/DB/验收证据/未验证项）
├── DECISIONS.md          # 技术决策与取舍记录
├── NEXT_STEP.md          # 下一批待办（2026-11-23 起以 docs/规划 为准）
├── BLOCKERS.md           # 阻塞项与外部依赖
├── SESSION_LOG.md        # 逐轮工作日志
├── docs/                 # 需求、选型、规划、审核包、技术资料库
├── docs/archive/         # 历史版本：V1.0/V1.1/V2.0 计划、11-19 与 11-20 交接包
├── fixtures/             # 测试夹具与评测脚本（备份演练、隐私闸门、检索基线）
└── zhuli/lastchat/       # Android 工程，构建入口在这里
```

`zhuli/lastchat` 下的模块：`app`（壳与业务）、`ai`（模型接入）、`common` / `shared` / `ui-core`、
`local-llm`（本地推理与 NPU 回落）、`speech` / `tts`、`search`、`highlight`、`document`、`workspace`，以及 `web-ui`。

## 构建

前置：JDK 21、Android SDK、Node 22 / npm 10。

```bash
# 1) 前端产物（缺这一步 :app:buildWebUi 会失败）
cd zhuli/lastchat/web-ui && npm ci

# 2) 出包
cd zhuli/lastchat
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew --no-daemon :app:assembleDevDebug

# 3) 单测（不需要设备）
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew --no-daemon \
  :app:testDevDebugUnitTest -x :app:buildWebUi -x :speech:downloadSherpaOnnxAar

# 4) 装机
adb install -r app/build/outputs/apk/dev/debug/*.apk
```

`local.properties` 需指向本机 SDK；Gradle wrapper 走腾讯镜像（官方源在本机超时）。

## 不入库的东西

密钥、数据库、keystore、真机截图、编译日志、补丁包一律排除在版本库外（见 `.gitignore`）。
`zhuli/lastchat/app/build.gradle.kts` 的 `preBuild` 还会扫 `assets` / `raw`，命中 `sk-` 形态的密钥直接中断构建。

## 关于本仓库的历史

2026-11-25 按项目所有者要求重建为**单提交快照**，远端原有的多条提交历史已移除。
旧历史在本机 `.git` 与 `C:\Users\a\.zhuli\backup\*.bundle` 中仍有冷备。
