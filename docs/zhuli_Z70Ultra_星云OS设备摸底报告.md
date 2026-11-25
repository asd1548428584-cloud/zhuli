# zhuli × 努比亚 Z70 Ultra（星云 OS）设备摸底报告

> 本报告通过对真实设备的 ADB 只读取证生成，未修改任何系统设置、未安装/卸载应用、未授予新权限、未重启设备、未执行任何破坏性命令。
>
> - **取证时间**：2026-11-20 17:58 ~ 18:08（设备时间 `Fri Nov 20 18:07:52 CST 2026`）
> - **ADB 版本**：1.0.41 / 37.0.0-14910828（Windows 10.0.26300）
> - **被测设备**：`324450846696  device product:CN_PQ84A01 model:NX733J device:PQ84A01 transport_id:1`
> - **原始证据目录**：`./adb_evidence/`（共 28 份 ADB 原始输出，报告中以 `adb_evidence/xx_*.txt` 引用）
>
> **证据状态标记约定**：
> - 🟢 **已验证**：有本机 ADB 实际输出直接支撑
> - 🟡 **根据系统信息推断**：由已验证的系统属性/服务清单合理推断，但未做运行时验证
> - 🔴 **尚未验证**：本次静态检测无法确认，需后续真机动态测试

---

## 0. 结论速览（最高信号）

1. **系统底座**：Android 16 / API 36，星云 OS（Nebula AIOS）`2.0.14MR2`，骁龙 8 至尊版（SM8750），内核 6.6.92，16KB 内存页，**仅支持 64 位（arm64-v8a）**。
2. **GMS 完整可用**：GMS 26.43.30、Play 商店 48.5.26、Google 服务框架齐全 — 这是重大利好，FCM、位置融合、Health Connect 等依赖可正常使用。
3. **设备已安装 zhuli**：`com.zhuli.app` v0.16-full（targetSdk 35），且**大量关键权限已实际授予**：精确闹钟、忽略电池优化、开机自启、位置、日历、录音、短信读取，通知监听已启用并正在运行，多个 RTC_WAKEUP 精确闹钟已注册且被系统采纳为「下一次唤醒源」。
4. **后台是最大风险**：星云 OS 存在 `superkiller_enabled=true`、`cgroup_freezer_v2/gki=true`、`supercooler`、`DefendManagerService`、`cfreezer` 等**厂商激进冻结/查杀机制**，即使加入电池白名单，长时后台存活仍需真机长周期验证。
5. **传感器远超标准 Android**：56 个传感器，含大量 ZTE 私有类型（跌落检测、拿起、电梯检测、Activity Recognition、SAR、双光感、闪烁检测、计步/运动识别等），但**均为私有 AIDL/传感器 type，普通第三方 App 大概率无法调用**。
6. **厂商 AI 能力存在但封闭**：`AIService`、`vendor.zte.aimodel`（BERT/BGE/LLM/Voice 模型）、Coze 智能体、ZTE_LLM、系统 AI 助手 `com.zte.aiassistant`、`orchestrationtools`（自动化编排）均存在，但**未见面向普通第三方 App 的开放 SDK**。
7. **Health Connect 组件在（APEX `com.android.healthfitness@360499999`）**，但系统健康 App 是 `cn.nubia.healthy` + 华为健康；标准 Health Connect 客户端路径可用，需验证数据写入方。

---

## 1. 设备真实环境总览

| 项目 | 实测值 | 状态 |
|---|---|---|
| 连接状态 | `adb get-state = device` | 🟢 已验证 |
| 设备序列号 | `324450846696` | 🟢 已验证 |
| 产品名 (ro.product.name) | `CN_PQ84A01` | 🟢 已验证 |
| 型号 (ro.product.model) | `NX733J` | 🟢 已验证 |
| 设备代号 (ro.product.device / board) | `PQ84A01` / `sun` | 🟢 已验证 |
| 品牌 / 厂商 | `nubia` / `nubia`（build.user=`zte`） | 🟢 已验证 |
| Build.user / 构建主机 | `zte` / `scl-shr01-ironic15` | 🟢 已验证 |
| 指纹 | `nubia/CN_PQ84A01/PQ84A01:16/BQ2A.250705.001-BP2A.250605.031.A3/20260913.213406:user/release-keys` | 🟢 已验证 |

> 型号 `NX733J`、代号 `PQ84A01` 与「努比亚 Z70 Ultra」对应，此处仅作型号陈述；本次结论全部基于本机实测，未套用其他机型参数。

---

## 2. 系统版本及基础参数

### 2.1 版本信息

| 项目 | 实测值 | 证据 / 状态 |
|---|---|---|
| 星云 OS 完整版本号 | `NebulaAIOS2.0.14MR2_NX733J`（`ro.build.display.id`）；底层基线 `NebulaOS2.0`（`ro.build.MiFavor_version`、`ro.vendor.feature.baseline_name`） | 🟢 `adb_evidence/01_getprop.txt` |
| Android 版本 / API | `ro.build.version.release=16`，`ro.build.version.sdk=36` | 🟢 同上 |
| Build ID | `BQ2A.250705.001-BP2A.250605.031.A3` | 🟢 同上 |
| 系统增量版本 | `ro.system.build.version.incremental=20260913.213406` | 🟢 同上 |
| 编译日期 | `ro.build.date=Sun Sep 13 21:41:44 CST 2026`（vendor 2026-09-13 21:50:29） | 🟢 同上 |
| Android 安全补丁级别 | **系统** `ro.build.version.security_patch=2026-08-01`；**vendor** `ro.vendor.build.security_patch=2026-06-01` | 🟢 同上 |
| 内核版本 | `Linux version 6.6.92-android15-8-g3637f4904cf5-ab13944661-4k (kleaf@build-host)`，clang 18.0.0，`#1 SMP PREEMPT Sat Aug 16 00:04:49 UTC 2025` | 🟢 `adb_evidence/02_proc_version.txt`、`03_uname.txt` |
| Bootloader | `ro.bootloader=unknown` | 🟢 未解锁态特征（不做绕过尝试） |
| 构建类型 | `ro.build.type=user`，`ro.build.tags=release-keys` | 🟢 正式零售版 |

> **注意版本差异**：vendor 分区仍报 `Android 15 / SDK 35`（`ro.vendor.build.release=15`），系统框架已是 Android 16。这是厂商 OTA 升级常见形态。**适配时应以 framework 的 API 36 为准，同时留意 vendor/HAL 仍带 15 的行为残留**（🟡 推断）。

### 2.2 硬件与运行时参数

| 项目 | 实测值 | 状态 |
|---|---|---|
| SoC | `ro.soc.manufacturer=QTI`，`ro.soc.model=SM8750`（骁龙 8 至尊版） | 🟢 `08_soc_display.txt` |
| CPU | 8 核，ARMv8 (AArch64)，`CPU implementer=0x51`(Qualcomm)，`CPU part=0x001`，BogoMIPS 38.40；支持 FP16/dotprod/SME 类指令（bf16/i8mm/sve2 相关 flags） | 🟢 `04_cpuinfo.txt` |
| GPU | `ro.hardware.egl=adreno`、`ro.hardware.vulkan=adreno`，GLES `0x30002`(3.2)，`ro.opengles.version=196610` | 🟢 `08_soc_display.txt` |
| ABI | `ro.product.cpu.abi=arm64-v8a`，**abilist32 为空**（`ro.product.cpu.abilist32=[]`）→ **不支持 32 位 App** | 🟢 `01_getprop.txt` |
| 内存页大小 | `ro.product.cpu.pagesize.max=16384` → **16KB 页面**（Android 15+ 新要求，第三方 so 需 16KB 兼容） | 🟢 同上 |
| 内存总量 | `MemTotal=15515308 kB ≈ 14.8 GiB`（商用标称约 16GB） | 🟢 `05_meminfo.txt` |
| 可用内存 | 检测时 `MemAvailable≈6228272 kB ≈ 5.9 GiB`，`Cached≈4.8GiB` | 🟢 同上（瞬时值） |
| Swap | `SwapTotal=12582908 kB ≈ 12 GiB` | 🟢 同上 |
| 内部存储 | `/data`：456G 总量，181G 可用（61% 已用） | 🟢 `06_df.txt` |
| 屏幕 | OLED、120Hz、lcd_density 520、支持 HDR/广色域、屏下指纹、AOD | 🟢 `08_soc_display.txt` |
| 厂商硬件版本 | `ro.vendor.build.hardware_version=zqbC` | 🟢 `01_getprop.txt` |

**对 zhuli 的直接含义**：
- 若 zhuli 包含任何 native `.so`，必须提供 **64 位 + 16KB 页对齐**版本，否则在 API 36 + 16KB 页设备上会安装/加载失败（🟡 由 pagesize 属性推断，需实测验证）。
- 内存充裕，本地端侧模型（NPU/GPU）有运行空间。

---

## 3. 硬件和传感器清单

### 3.1 传感器总数与层次

`dumpsys sensorservice` 共列出 **56 个传感器**（含 AOSP 软件传感器）。需区分三个层次：

| 层次 | 说明 |
|---|---|
| ① 硬件实际存在 | 物理芯片：STMicro LSM6DSO（加速度/陀螺）、AKM AK0991X（磁力）、Sensortek STK3A7X/STK3B6X（光/距/SAR）、Qualcomm SNES（虚拟/融合/活动类） |
| ② 系统已暴露 | `dumpsys sensorservice` 与 `pm list features` 中出现 |
| ③ 普通第三方 App 可调 | 仅标准 `android.sensor.*` type 且受权限约束；ZTE 私有 type 不可调 |

### 3.2 标准传感器（普通第三方 App 可读，需按权限）

| 传感器 | type | 厂商 | 关键参数 | 权限 |
|---|---|---|---|---|
| 加速度计 | `android.sensor.accelerometer(1)` | STMicro | 1.00–415.97 Hz，non-wakeup | 无 |
| 磁力计 | `android.sensor.magnetic_field(2)` | akm | 1–100 Hz，non-wakeup | 无 |
| 方向 | `android.sensor.orientation(3)` | akm | 100 Hz | 无 |
| 陀螺仪 | `android.sensor.gyroscope(4)` | STMicro | 1–415.97 Hz，non-wakeup | 无 |
| 光线 | `android.sensor.light(5)` | sensortek | on-change | 无 |
| 距离 | `android.sensor.proximity(8)` | sensortek | on-change，**wakeup** | 无 |
| 重力 / 线性加速度 | `gravity(9)` / `linear_acceleration(10)` | QTI | 5–200 Hz | 无 |
| 旋转矢量 | `rotation_vector(11)` | akm | 100 Hz | 无 |
| 地磁旋转矢量 | `geomagnetic_rotation_vector(20)` | QTI | 5–100 Hz | 无 |
| 计步器 | `android.sensor.step_counter(19)` | QTI | on-change，wakeup + non-wakeup | **`ACTIVITY_RECOGNITION`** |
| 步进检测 | `android.sensor.step_detector(18)` | QTI | special-trigger | **`ACTIVITY_RECOGNITION`** |
| 显著运动 | `significant_motion(17)` | QTI | one-shot，wakeup | 无 |
| 静止/运动检测 | `stationary_detect(29)` / `motion_detect(30)` | QTI | one-shot | 无 |
| 倾斜检测 | `tilt_detector(22)` | QTI | special-trigger，wakeup | 无 |
| 未校准加速度/陀螺/磁力 | `*_uncalibrated(35/16/14)` | STMicro/akm | — | 无 |

> **硬件步数统计 / 运动识别确认存在**：step_counter、step_detector 均为 QTI SNES 硬件传感器；`pm list features` 中 `android.hardware.sensor.stepcounter`、`stepdetector`、`accelerometer`、`gyroscope`、`compass`、`light`、`proximity` 全部声明。🟢 已验证（存在性）；实际数据准确性需真机走动测试 🔴。
>
> 传感器隐私开关：`Sensor Privacy: disabled`（未开启硬件传感器隐私封禁）🟢 `adb_evidence/13_sensorservice.txt`。

### 3.3 ZTE / 星云 OS 私有传感器（系统暴露，第三方大概率不可调）

| 私有 type | 名称 | 含义 |
|---|---|---|
| `zte.sensor.ztemotion(33172001)` | Zte Motion | 厂商运动检测 |
| `zte.sensor.ar(33172066)` | activity_recognition | **活动/运动识别**（QTI，系统 AI 引擎在使用） |
| `zte.sensor.zte_fall_detection(33172101)` | Zte Fall Detection | **跌落检测** |
| `zte.sensor.zte_pickup(33181000)` | Zte Pickup | **拿起检测** |
| `zte.sensor.elevator_detect(33172065)` | elevator_detection | 电梯检测 |
| `zte.sensor.zte_dual_light(33172037)` | Zte Dual Light | 双光感（前后环境光） |
| `zte.sensor.flicker(33172027)` | FLICKER | 屏幕闪烁检测 |
| `zte.sensor.light_second(33172007)` | Ambient Light Second | 副光感 |
| `zte.sar1.sensor(33171022)` | stk50153 SAR | SAR 传感器（ proximity 相关） |
| `zte.sensor.ztemotion_ps`、`zte_light_ps`、`zte_accel_ps`、`zte_ps_stream`、`zte_linear_acc`、`zte_motion_detect` 等 | 各种 _ps（power saving?) 变体 | 厂商省电/场景传感器 |

**运行时证据**：`dumpsys sensorservice` 的 active connections 显示 `com.zte.aiengine.algorithm.scene.SceneSensors` 正在消费计步器，`com.zte.aiproduct.libDataCollection.manager.ZteMotionSensorManager`、`com.google.android.location.os.real.RealWakeUpTiltDetector` 也在接入传感器。🟢 已验证。

> **评级**：这些私有传感器属于 **D（普通第三方 App 基本无法实现）** —— 私有 type 值（33xxxxxx）不在 Android SDK 中，普通 App 无对应常量与权限，除非厂商提供签名级 SDK。

### 3.4 定位 / 无线能力

| 能力 | 实测 | 状态 |
|---|---|---|
| 定位总开关 | `cmd location is-location-enabled = true` | 🟢 `24_locenabled.txt` |
| 定位提供方 | `passive`、`network`、`gps`、`fused`、`tencentnlp`（腾讯 NLP 定位） | 🟢 `25_location_dump.txt` |
| Fused provider | `com.android.location.fused`，Fine accuracy，含真实 GPS 定位 | 🟢 |
| GNSS 星座 | `GPS GLONASS QZSS BEIDOU GALILEO`（五星座） | 🟢 |
| GNSS 频段 | L1/L2/L5 多频（`signalTypes` 中 1.57542/1.2276/1.17645/1.602/1.561098 GHz 等） | 🟢 |
| GNSS 能力集 | `SCHEDULING MSB GEOFENCING MEASUREMENTS LOW_POWER_MODE SATELLITE_BLOCKLIST SATELLITE_PVT MEASUREMENT_CORRECTIONS …` | 🟢 |
| GNSS 硬件型号 | `qcom;MPSS.DE.7.0-02024.3-PAKALA_GEN_PACK-1.82720.141` | 🟢 |
| GNSS 实测 | fused 定位 `satellites=17`，gps provider `satellites=7`，含高度/速度/方位 | 🟢 |
| 蓝牙 | `settings bluetooth_on=1`，`state: ON`，BLE 支持（`android.hardware.bluetooth_le`） | 🟢 |
| Wi-Fi | `Wi-Fi is enabled`，2.4/5GHz 支持，6GHz 列表为空 | 🟢 |
| NFC | 硬件支持（`android.hardware.nfc/hce/ese/uicc`），当前 `mState=off`（用户关闭，非硬件缺失） | 🟢 |
| 红外 | `android.hardware.consumerir` 存在 | 🟢 |
| UWB | `/apex/com.android.uwb@360499999` 存在 | 🟡（APEX 存在，硬件待验证） |

> 定位融合同时有 GMS 的 `com.google.android.gms[fused_location_provider]` 与 ZTE 的 `tencentnlp provider` 在后台请求，说明**多定位源叠加**，zhuli 直接用标准 FusedLocationProviderClient 即可。

---

## 4. Android 标准 API 能力清单

> 每项格式：**标准能力 → 本机实测 → 所需权限 → 星云 OS 额外限制 → 评级**

### 4.1 后台定位
- **标准**：前台服务 + `ACCESS_FINE/COARSE_LOCATION`；Android 10+ 后台定位需 `ACCESS_BACKGROUND_LOCATION`。
- **实测**：定位服务开启、多 provider 在后台持续请求（`{bg}` 标记）；zhuli 已获 `ACCESS_FINE_LOCATION/COARSE_LOCATION`，**但未声明/授予 `ACCESS_BACKGROUND_LOCATION`**（appops 中 FINE/COARSE 为 `foreground` 模式）。🟢
- **权限**：前台定位 + FGS location 类型即可满足大部分场景；纯后台定位需额外授权且受厂商限制。
- **额外限制**：星云 OS `doze_speed_up=true`、`superkiller_enabled=true`、`collect_app_run_info=true`，后台位置更新在该类 ROM 上常被冻结。🟡
- **评级**：**B/C**（前台定位 B；持续后台定位 C，需长周期真机验证稳定性）。

### 4.2 前台服务（FGS）
- **标准**：Android 14+ 需声明具体 FGS 类型权限。
- **实测**：系统定义了全部 14 种 FGS 权限：`CAMERA / CONNECTED_DEVICE / DATA_SYNC / FILE_MANAGEMENT / HEALTH / LOCATION / MEDIA_PLAYBACK / MEDIA_PROCESSING / MEDIA_PROJECTION / MICROPHONE / PHONE_CALL / REMOTE_MESSAGING / SPECIAL_USE / SYSTEM_EXEMPTED`。🟢 `20_permissions.txt`
- **额外限制**：厂商有 `DefendManagerService`、`power_controller_enabled=true`、`superkiller`，FGS 通知若被用户归类为"静默"仍可能被限制。🟡
- **评级**：**B**（标准可用，选对类型 + 常驻通知）；星云 OS 保活需 C 级适配测试。

### 4.3 AlarmManager（精确闹钟）
- **标准**：`USE_EXACT_ALARM`（闹钟/日历类 App）或 `SCHEDULE_EXACT_ALARM` + 用户授权。
- **实测**：系统权限定义了 `USE_EXACT_ALARM`、`SCHEDULE_EXACT_ALARM`；zhuli 两者均已 `granted=true`，appops `SCHEDULE_EXACT_ALARM: allow`；**AlarmManager 中已有 zhuli 的 3 个 RTC_WAKEUP 闹钟**（`HEARTBEAT`、`brief.DAILY`、`reminder.FIRE`），且 `Next wake from idle` 正是 zhuli 的 `reminder.FIRE`。🟢 `17_alarm.txt`
- **额外限制**：厂商有 `ztealarmcontroller`（`com.zte.power.IZteAlarmControllerManager`）、`alarm_controller_enabled=true` —— 说明 ROM 对闹钟有自有调度层，但**当前实测闹钟被系统采纳为唤醒源，工作正常**。🟢
- **评级**：**A**（已验证可准时注册并被系统采纳；准时触发精度仍需长时间观察，但基础能力成立）。

### 4.4 WorkManager
- **标准**：基于 JobScheduler，受 Doze/待机桶约束。
- **实测**：Doze 参数标准（`inactive_to=15s`、`light_idle`、`motion_inactive_to=30s` 等）；appops/待机桶机制正常（`usagestats` 有 `STANDBY_BUCKET_CHANGED` 记录）。🟢
- **额外限制**：`cgroup_freezer_v2/gki=true`、`superkiller_enabled=true` → JobScheduler 任务在冻结态可能延迟。🟡
- **评级**：**B/C**（非紧急任务可用；关键定时用 AlarmManager + FGS 兜底）。

### 4.5 NotificationListenerService
- **标准**：`BIND_NOTIFICATION_LISTENER_SERVICE` + 用户在"通知使用权"设置中开启。
- **实测**：**zhuli 的 `com.zhuli.app/com.zhuli.core.NotificationsListenerService` 已出现在 `enabled_notification_listeners` 中，且 `dumpsys activity services` 显示该服务正在运行（`c:android` = 系统绑定）**。通知渠道 `zhuli_geo`、`zhuli_reminder` 已建立。🟢
- **额外限制**：appops 中 `RECEIVE_SENSITIVE_NOTIFICATIONS: ignore`（敏感通知默认忽略，Android 13+ 通用行为，非 ZTE 特有）。🟢
- **评级**：**A**（已验证在星云 OS 上可授权并稳定运行）。

### 4.6 AccessibilityService
- **标准**：`BIND_ACCESSIBILITY_SERVICE` + 用户手动开启，可实现窗口识别/点击/滑动。
- **实测**：`accessibility_enabled=1`；当前启用的服务是 **ZTE 自身的 `cn.zte.aigcinput/.accessibility.InputAutomationAccessibilityService`（AI 输入自动化服务）**；zhuli appops 中 `BIND_ACCESSIBILITY_SERVICE: allow`、`ACCESS_ACCESSIBILITY: allow`（有近期访问时间记录，说明 zhuli 曾启用过无障碍）。🟢
- **额外限制**：厂商自占一个无障碍服务做自动化；同一时刻可存在多个无障碍服务，但厂商可能对"模拟点击"类做限制。🟡
- **评级**：**B/C**（能力存在且 zhuli 曾被授权；能否做全局自动点击/窗口读取需真机验证是否被星云 OS 二次限制）。

### 4.7 UsageStats
- **标准**：`PACKAGE_USAGE_STATS`（用户授予"使用情况访问"）。
- **实测**：`cmd usagestats` 服务正常响应，`dumpsys usagestats` 有完整事件记录（SCREEN_INTERACTIVE、STANDBY_BUCKET_CHANGED 等）。🟢
- **额外限制**：`ro.vendor.feature.zte_collect_app_run_info=true`（厂商自行收集 App 运行信息，不影响第三方接口）。🟡
- **评级**：**B**（需用户授权，接口本身正常）。

### 4.8 MediaProjection（屏幕捕获）
- **标准**：用户授权后投屏/截图，需 FGS mediaProjection 类型。
- **实测**：`dumpsys media_projection` 正常返回 `MEDIA PROJECTION MANAGER`，当前 `null`（无活动会话）；FGS `MEDIA_PROJECTION` 权限存在。🟢
- **额外限制**：🔴 未做实际授权捕获测试。
- **评级**：**B**（需用户每次授权；功能成立性高，实测待补）。

### 4.9 文件访问（SAF / MediaStore / 共享）
- **标准**：SAF（`ACTION_OPEN_DOCUMENT`）、MediaStore、`MANAGE_EXTERNAL_STORAGE`（特殊权限）。
- **实测**：`ro.vendor.feature.zte_enable_filebrowser=true`，`com.android.providers.downloads`、`com.google.android.projection.gearhead` 等存在；`android.software.webview`、`adoptable_storage` 声明齐全。🟢
- **额外限制**：`install_non_market_apps=1`（允许未知来源安装）；厂商文件浏览器存在，不影响标准 API。
- **评级**：**A/B**（SAF/MediaStore 为 A；`MANAGE_EXTERNAL_STORAGE` 需用户手动授权为 B）。

### 4.10 开机启动 / 后台保活 / 自启动
- **标准**：`RECEIVE_BOOT_COMPLETED`；国内 ROM 普遍有"自启动"管理。
- **实测**：zhuli 已授予 `RECEIVE_BOOT_COMPLETED`，并注册 `BootReceiver`（监听 `BOOT_COMPLETED`、`MY_PACKAGE_REPLACED`、`TIME_SET`、`TIMEZONE_CHANGED`）；系统有 `AutoLaunch`（`android.app.IAutoLaunchManager`）服务。🟢
- **额外限制**：**这是星云 OS 的重点限制区** —— `AutoLaunch` 服务 + `superkiller` + `cgroup_freezer` + `DefendManagerService` + `power_controller` 构成多层后台管控。**开机广播能否送达、进程能否长期保活，强依赖 ROM 的"自启动/后台运行"开关**。
- **评级**：**C**（接口存在且已注册，但保活/自启需在系统设置中手动放行，且需长周期验证；无法保证稳定）。

### 4.11 电池优化白名单
- **标准**：`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` + 用户授权，加入 Doze 白名单。
- **实测**：zhuli 已授予 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`，且 **`cmd deviceidle whitelist` 中存在 `user,com.zhuli.app,10724`** —— 已实际加入用户白名单。🟢
- **评级**：**A**（已验证生效）。

### 4.12 应用安装 / 更新 / 调用其他 App
- **实测**：`install_non_market_apps=1`（允许安装未知来源）；zhuli 声明了大量 `<queries>`（微信、淘宝、百度地图、ChatGPT、GMS、ZTE 系统等），说明已适配包可见性。Play 商店存在。🟢
- **评级**：**A/B**（安装/更新自由；调用/拉起其他 App 依赖对方导出组件与目标 App 限制，为 B）。

---

## 5. 星云 OS 厂商特有机制（重点）

### 5.1 厂商系统服务清单（`dumpsys service list`，共 481 项）

| 服务名 | AIDL 接口 | 推测用途 |
|---|---|---|
| `AIService` | `com.zte.ai.IAIManager` | 系统级 AI 能力总入口 |
| `AutoLaunch` | `android.app.IAutoLaunchManager` | 自启动管理 |
| `ZtePermissionCheckService` | `android.app.IZtePermissionCheckManager` | 厂商权限校验 |
| `ZteDeviceIdentifyService` | `android.app.IZteDeviceIdentifyManager` | 设备身份识别 |
| `DefendManagerService` | `com.zte.performance.defend.IDefendManager` | 后台防护/查杀 |
| `cfreezer` | `com.zte.performance.cfreezer.ICpuFreezerManager` | CPU 冻结 |
| `performance` | `com.zte.performance.IZtePerformanceManager` | 性能调度 |
| `ztepowercontroller` | `com.zte.power.IZtePowerControllerManager` | 电源控制 |
| `ztealarmcontroller` | `com.zte.power.IZteAlarmControllerManager` | 闹钟调度 |
| `powertracker` | `com.zte.power.IZtePowerTrackerManager` | 功耗追踪 |
| `privacy` | `com.zte.privacy.IZTEPrivacyManager` | 隐私管理 |
| `scenedecision` | `com.zte.performance.scene.IZteSceneDecisionManager` | 场景识别决策 |
| `activityevent` | `com.zte.activityevent.IActivityEventsServer` | Activity 事件 |
| `nubia.system_healthy` | `com.zte.healthy.INubiaSystemHealthy` | 健康数据系统服务 |
| `mindsyncservice` | `com.zte.performance.mindsync.IMindSyncManager` | 跨端/记忆同步（推测） |
| `vendor.zte.aimodel.IBertModel / IBgeModel / ILLModel / IVoiceModel` | — | **端侧 BERT / BGE(向量) / LLM / 语音模型** HAL |
| `long_screen_shot` | `android.mifavor.ILongScreenShotServiceZTE` | 长截图 |
| `ZteLddCmdService` | `com.zte.ldd.cmd.IZteLddCmdService` | 底层命令服务 |

> 以上服务均为系统/签名级 AIDL，`dumpsys <name>` 多数返回空（未实现 dump），**普通第三方 App 无法直接绑定**。仅作"存在性"证据。🟢 存在性已验证；可调用性 🔴 未验证且大概率为 D。

### 5.2 厂商 AI / 星云特性属性（388 条 `ro.vendor.feature.zte_*`，节选）

| 属性 | 值 | 含义 |
|---|---|---|
| `ro.vendor.feature.zte_feature_ai_coze_agents` | true | **Coze 智能体框架** |
| `ro.vendor.feature.zte_feature_ai_llm_name` | `ZTE_LLM` | 端侧大模型（日期 202504） |
| `ro.vendor.feature.zte_feature_ai_model_service` | true | AI 模型服务 |
| `ro.vendor.feature.zte_feature_ai_engine` | true | AI 引擎 |
| `ro.vendor.feature.zte_feature_ai_personal` | false | 个性化 AI（当前关闭） |
| `ro.vendor.feature.zte_feature_aiassistant_replace_halo` | true | 星云 AI 助手替代 Halo |
| `ro.vendor.feature.zte_feature_alarm_controller_enabled` | true | 厂商闹钟控制器 |
| `ro.vendor.feature.zte_feature_power_controller_enabled` | true | 厂商电源控制器 |
| `ro.vendor.feature.zte_feature_defend` / `defend_binder` / `defend_mem_check` | true | 后台防护/内存检查 |
| `ro.vendor.feature.zte_feature_cgroup_freezer_v2` / `freezer_gki` / `cgroup_gms_freezer` | true | cgroup 冻结（含 GMS） |
| `ro.vendor.feature.zte_feature_superkiller_enabled` | true | **超级查杀** |
| `ro.vendor.feature.zte_feature_doze_speed_up` | true | Doze 加速 |
| `ro.vendor.feature.zte_collect_app_run_info` | true | 收集 App 运行信息 |
| `ro.vendor.feature.zte_app_relay_enabled` | true | App 接力（跨设备） |
| `persist.sys.zte_control_mode` | false | 终端管控模式 |
| `ro.vendor.feature.zte_feature_privacy_protection` | true | 隐私保护机制 |
| `ro.build.display.id` | NebulaAIOS2.0.14MR2 | 星云 OS 版本 |

### 5.3 厂商应用与 Provider

- **系统 AI 助手**：`com.zte.aiassistant`（AIChatActivity、数字人 `DigitalHumanSettingActivity`、电源键唤醒、语音唤醒、耳机唤醒、自定义唤醒词）。🟢
- **AI 生成/输入**：`com.zte.aigc`、`com.zte.aigc.overlay`、`cn.zte.aigcinput`（其无障碍服务已启用）、`com.zte.aigeneration`（自定义权限 `com.zte.aigeneration.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`）。
- **自动化编排**：`com.zte.orchestrationtools`（含 `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`）、`com.zte.aiengine.algorithm.scene.SceneSensors`、`com.zte.activityevent`。
- **AI 模型**：`vendor.zte.aimodel.*` HAL、`com.zte.nebulagravitation`（含 `com.zte.nebulagravitation.ACCESS_PROVIDER` 权限 → **存在 Provider**）、`com.zte.nebulatranslation`。
- **健康**：`cn.nubia.healthy`、`com.zte.heartrate`、`nubia.system_healthy` 服务。
- **其他**：`com.zte.carmateservice`（车机，AI 助手监听其 `CAST_STATUS_CHANGE` 广播）、`com.zte.faceverify`（Provider/Receiver 权限）、`com.zte.retrieve`（读写设置权限）、`zte.com.cn.alarmclock`（`READ_STRATEGY` 权限）。

> **是否存在"隐藏但可通过正常权限申请使用的系统能力"**：本次检测**未发现**面向普通第三方 App 的厂商公开 SDK 文档或开放权限（开放权限多标注 DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION 或签名级）。`com.zte.nebulagravitation.ACCESS_PROVIDER` 是签名级 Provider 权限，普通 App 无法获取。🔴 需向努比亚开发者渠道确认是否有对外 SDK。

---

## 6. 后台运行与权限限制

| 机制 | 证据 | 对 zhuli 的影响 |
|---|---|---|
| Doze / App Standby | 标准 Doze 参数，`inactive_to=15s`、`motion_inactive_to=30s`、待机桶变更记录 | 标准行为，白名单可豁免 🟢 |
| 电池优化白名单 | zhuli 已在 `user` 白名单 | 已豁免 Doze 🟢 |
| SuperKiller / cgroup freezer | `superkiller_enabled`、`cgroup_freezer_v2/gki`、`supercooler` | **后台进程易被冻结/查杀**，FGS + 白名单也未必完全免疫 🟡/🔴 |
| DefendManager / cfreezer | 服务存在 | 厂商性能防护层，可能影响后台唤醒 🟡 |
| AutoLaunch 自启动 | `AutoLaunch` 服务；zhuli 已注册 BOOT_COMPLETED | 能否开机自启依赖系统"自启动"开关 🟡 |
| Alarm 控制器 | zhuli 闹钟被系统采纳为 next wake | 精确闹钟当前正常 🟢 |
| 厂商权限校验 | `ZtePermissionCheckService`、`persist.sys.zte_control_mode=false` | 可能有额外权限拦截，当前管控模式关闭 🟡 |

**结论**：zhuli 已在**权限层面**做到位（白名单、闹钟、通知监听、开机广播全部就绪），但**进程存活层面**仍受星云 OS 激进冻结机制威胁。这是下一阶段最需要真机长周期测试的点。

---

## 7. GMS、健康数据及替代方案

### 7.1 GMS 状态（🟢 完整可用）

| 组件 | 版本 / 状态 |
|---|---|
| Google Play 服务 (`com.google.android.gms`) | 26.43.30（versionCode 264330035，minSdk 35，targetSdk 37） |
| Google 服务框架 (`com.google.android.gsf`) | 存在 |
| Play 商店 (`com.android.vending`) | 48.5.26 |
| Google 搜索 (`com.google.android.googlequicksearchbox`) | 存在 |
| Gmail / YouTube / Translate / Gearhead | 存在 |

**影响评估**：GMS 完整意味着：
- FCM 推送可用（对"主动提醒"是重大利好，可弥补后台保活不足）🟡（需接入验证）
- Fused Location、Geofencing、SafetyNet/Play Integrity 正常
- Health Connect 依赖的 GMS 基础设施存在

### 7.2 Health Connect（组件在，数据接入待验证）

- `com.android.healthconnect.controller` 存在，`versionName=16`，含 `PermissionsActivity`、`PermissionControllerEntryPoint` 等标准入口。🟢
- APEX `/apex/com.android.healthfitness@360499999` 挂载存在。🟢
- 系统健康 App：`cn.nubia.healthy`（努比亚健康）、`com.huawei.health`（华为健康）、`com.zte.heartrate`（心率）、`nubia.system_healthy` 服务。🟢
- ⚠️ `dumpsys package com.android.healthconnect.controller` 显示 `enabled=0`（该字段在某些 ROM/版本中表示"默认未激活"，需实际启动确认）。🔴

**健康/运动数据替代方案**：
| 方案 | 可行性 |
|---|---|
| Health Connect 标准 API（读写步数、心率、睡眠、运动） | B（组件在，需用户授权 + 验证数据提供方是否写入） |
| 直接用 `SensorManager` 的 step_counter / step_detector | A（已验证传感器存在，需 ACTIVITY_RECOGNITION） |
| `ActivityRecognitionClient`（GMS）活动识别 | B（GMS 在，需授权） |
| ZTE 私有 `zte.sensor.ar` / `INubiaSystemHealthy` | D（私有接口，普通 App 不可调） |

---

## 8. zhuli 各项功能的可实现性评级

> 评级：**A** 标准 API 直接实现 ｜ **B** 需用户授权/设置 ｜ **C** 需星云 OS 适配/额外测试 ｜ **D** 第三方基本无法实现 ｜ **E** 证据不足

| # | zhuli 功能 | 评级 | 关键依据 / 备注 |
|---|---|---|---|
| 1 | 前台定位 / 位置获取 | **A** | Fused provider + 已授予 FINE/COARSE；GMS 定位在 |
| 2 | 持续后台定位 | **C** | 需 ACCESS_BACKGROUND_LOCATION + FGS；受 superkiller/freezer 影响，需长测 |
| 3 | 运动状态 / 计步 / 活动识别 | **A/B** | step_counter/detector 硬件存在（A）；ACTIVITY_RECOGNITION 需授权（B） |
| 4 | 传感器数据（标准类） | **A** | 加速度/陀螺/磁力/光/距等齐全，56 个传感器 |
| 5 | 厂商私有传感器（跌落/拿起/电梯等） | **D** | 私有 type，第三方不可调 |
| 6 | 读取通知 | **A** | 已验证：NLS 已授权且服务运行中 |
| 7 | 读取日历 | **A** | READ/WRITE_CALENDAR 已授予 |
| 8 | 读取短信 | **A/B** | READ_SMS 已授予（本设备）；Google Play 政策会限制上架，个人使用 OK |
| 9 | 读取文件 / 用户分享 | **A/B** | SAF/MediaStore 标准；MANAGE_EXTERNAL_STORAGE 需授权 |
| 10 | 长期记忆 / 习惯学习 | **A** | 纯应用层逻辑，无系统障碍（本地存储/端侧模型可行） |
| 11 | 日程/闹钟主动提醒 | **A** | 精确闹钟已验证工作，被系统采纳为唤醒源 |
| 12 | 通知提醒 | **A** | 通知渠道已建立，POST_NOTIFICATIONS 已授予 |
| 13 | 开机后恢复任务 | **B/C** | BootReceiver 已注册、权限已授；能否真正自启依赖 ROM 自启动设置（C） |
| 14 | 后台服务保活 | **C** | superkiller + cgroup freezer 威胁最大；白名单已加但仍需长测 |
| 15 | AI 对话执行手机操作（无障碍点击/滑动） | **B/C** | 无障碍能力存在、zhuli 曾被授权；厂商自占无障碍服务，模拟点击需实测 |
| 16 | MediaProjection 屏幕捕获 | **B** | 服务正常，需用户每次授权；未做实捕获测试 |
| 17 | UsageStats 应用使用统计 | **B** | 服务正常，需用户授权 |
| 18 | 推送通知（FCM 兜底） | **B** | GMS 完整可用，需接入验证 |
| 19 | 健康数据（Health Connect） | **B** | 组件在，数据提供与授权待验证 |
| 20 | 调用厂商 AI 引擎 / 端侧大模型 | **D** | 系统服务/HAL 存在但无公开第三方 SDK |
| 21 | 24h 连续心率/血氧等深度健康 | **E** | 未检测到标准第三方可读的实时心率数据源；需真机验证 |
| 22 | 32 位 native 组件 | **D** | ABI 仅 arm64-v8a，32 位 so 无法运行 |

### 重点关注项回答

- **后台定位是否可靠**：前台可靠（A），持续后台不可靠（C），受超级查杀影响。
- **闹钟是否准时**：基础能力已验证（A）；精度需长周期观察。
- **后台服务是否容易被杀**：是，星云 OS 有 superkiller/freezer，风险高（C）。
- **通知监听是否稳定**：已授权且运行中，稳定（A）。
- **无障碍能否正常工作**：能力在，厂商自占一个服务，需实测（B/C）。
- **开机后能否恢复任务**：已注册且授权，但受自启动策略约束（B/C）。
- **无 GMS 影响**：本设备**有完整 GMS**，无此问题。
- **健康数据替代**：Health Connect + 标准计步传感器 + GMS 活动识别三条路可行（A/B）。

---

## 9. 已确认的问题和未知项

### 已确认（🟢）
1. 系统为 Android 16 / API 36 / 星云 OS 2.0.14MR2，vendor 仍是 Android 15 基线。
2. GMS / Play 商店 / GSF 完整可用。
3. zhuli 已安装且核心权限（精确闹钟、忽略电池优化、开机、位置、日历、录音、通知监听）均已授予并生效。
4. zhuli 闹钟已被系统采纳为唤醒源，通知监听服务正在运行。
5. 56 个传感器，含完整标准传感器 + 大量 ZTE 私有传感器。
6. 多星座多频 GNSS（含 L5）、蓝牙/WiFi/NFC 硬件齐全。
7. 厂商存在大量后台冻结/查杀/AI 系统服务与特性开关。

### 尚未验证（🔴，需后续测试）
1. zhuli 后台进程在熄屏 1h/4h/8h 后是否存活（superkiller/freezer 实际效果）。
2. 持续后台定位的真实可靠性与耗电。
3. 无障碍模拟点击/滑动是否被星云 OS 二次拦截。
4. 开机自启后 BootReceiver 是否实际送达（需重启验证 — 本次按要求未重启）。
5. Health Connect 是否默认激活、努比亚健康/华为健康是否向其写入数据。
6. MediaProjection 实际授权捕获流程。
7. 14 种 FGS 类型在星云 OS 上是否全部真正可用（尤其 `SPECIAL_USE`、`SYSTEM_EXEMPTED`）。
8. 厂商是否有对外开放的 AI SDK / Provider（需官方渠道确认）。
9. `RECEIVE_SENSITIVE_NOTIFICATIONS: ignore` 是否导致部分敏感通知读不到。
10. 精确闹钟的实际触发误差（毫秒级 vs 分钟级）。

### 状态说明
- 本报告所有"存在性"结论基于 `getprop`/`pm`/`dumpsys`/`service list`/`cmd` 只读命令。
- 未对任何厂商私有服务做绑定/调用测试（超出只读检测范围，且大概率签名受限）。
- 未查阅努比亚官方开发者文档，故"厂商 SDK"部分为证据缺口而非否定结论。

---

## 10. 后续需要进行的真实设备测试

| # | 测试项 | 方法 | 预期判定 |
|---|---|---|---|
| 1 | 后台存活长测 | 授权后前台启动 zhuli → 熄屏静置 1/4/8/24h → `adb shell ps -A \| grep zhuli`、`dumpsys activity processes` 查进程与 adj | 是否被 freezer/superkiller 杀死 |
| 2 | 后台定位可靠性 | 前台服务持续定位，移动中熄屏，比对轨迹断点 | 定位是否连续 |
| 3 | 精确闹钟准点率 | 设多个不同时间 RTC_WAKEUP 闹钟，记录实际触发 vs 设定 | 误差分布 |
| 4 | 开机自启恢复 | 开启自启动开关 → 重启 → 查 BootReceiver 日志与任务恢复 | 是否送达 |
| 5 | 无障碍自动化 | 自写测试用 AccessibilityService，执行点击/滑动/窗口读取 | 是否被 ROM 拦截 |
| 6 | 通知监听完整性 | 对比系统通知栏与 NLS 收到内容 | 敏感通知是否缺失 |
| 7 | Health Connect 读写 | 写入测试步数再读回；检查 nubia.healthy/华为健康数据同步 | 数据链路是否通 |
| 8 | MediaProjection 捕获 | 授权后捕获 10s 屏幕，校验图像 | 是否成功 |
| 9 | FGS 类型兼容 | 逐类型启动 FGS，观察是否崩溃/被拒 | 哪些类型可用 |
| 10 | FCM 推送兜底 | 接入 FCM，熄屏深睡状态推送测试 | 到达率与时延 |
| 11 | 16KB / 64 位兼容 | 打包含 so 的测试 APK 安装运行 | native 是否加载成功 |
| 12 | 厂商 SDK 调研 | 联系努比亚开发者/查官方文档 | 是否有可用开放接口 |

---

## 11. 对 zhuli 技术架构的适配建议

1. **坚持"标准 API 优先"**：位置用 FusedLocationProvider、计步用 `SensorManager`、通知用 NLS、闹钟用 `AlarmManager.setExactAndAllowWhileIdle`、后台用 FGS。这些在本机已验证成立，不依赖厂商私有接口。

2. **前台服务 + 电池白名单 + FCM 三重保活**：
   - 关键持续任务放 FGS（选对 `foregroundServiceType`），常驻通知不可关；
   - 引导用户将 zhuli 加入电池优化白名单（**已生效**）与"自启动/后台运行"白名单；
   - 用 FCM（GMS 完整）作为**离线唤醒兜底**，弥补星云 OS 冻结导致的本地定时失效。

3. **不要依赖厂商私有接口**：ZTE 的 `AIService`、`vendor.zte.aimodel`（端侧 LLM/BGE）、`INubiaSystemHealthy`、私有传感器虽有强大能力，但均为签名/系统级，普通 App 不可调。端侧 AI 建议自行集成（如通过 GMS 或自带轻量模型，注意 16KB/64 位 so 兼容）。

4. **把精确闹钟作为提醒主通道**：本机已验证 `RTC_WAKEUP` 被系统采纳为唤醒源。日程/提醒/心跳用精确闹钟 + 开机 `BootReceiver` 重建；`WorkManager` 仅用于非紧急任务。

5. **无障碍谨慎设计**：厂商已占用一个无障碍服务（AI 输入自动化），zhuli 若需自动化操作，需引导用户手动开启，并做好"被 ROM 限制"的降级方案（如跳转 Intent、分享、通知操作代替模拟点击）。

6. **健康数据走 Health Connect 主路径**：以标准 Health Connect 为抽象层，屏蔽 `cn.nubia.healthy`/华为健康的差异；同时用标准计步传感器作为即时运动数据源。

7. **32 位与 16KB 兼容检查纳入 CI**：设备仅 arm64-v8a + 16KB 页，所有 native 依赖需 64 位且页对齐，否则直接不可用。

8. **权限引导顺序建议**（基于本机已验证可用项）：位置（前台）→ 通知访问 → 精确闹钟 → 忽略电池优化 → 自启动/后台运行 → 健康连接 → 无障碍（按需）→ 敏感通知。

9. **建立真机监控**：把本报告第 10 节的 12 项测试做成可重复的 ADB 检查脚本，每次 ROM 升级（`NebulaAIOS2.0.14MR2` 后续版本）后回归，因为厂商策略可能随版本变化。

---

## 附录 A：核心 ADB 证据索引

| 文件 | 内容 | 对应章节 |
|---|---|---|
| `01_getprop.txt` | 全部系统属性（版本/型号/ABI/厂商特性） | §1 §2 §5 |
| `02_proc_version.txt` / `03_uname.txt` | 内核版本 | §2 |
| `04_cpuinfo.txt` / `05_meminfo.txt` / `06_df.txt` | CPU / 内存 / 存储 | §2 |
| `08_soc_display.txt` | SoC(GPU)/显示 | §2 |
| `09_features.txt` | 系统硬件特性 | §3 |
| `12_pkg_all.txt` | 全部包列表（GMS/厂商/健康） | §5 §7 |
| `13_sensorservice.txt` | 56 个传感器详情 | §3 |
| `14_servicelist.txt` | 481 项系统服务 | §5 |
| `15_deviceidle.txt` / `16_power.txt` | Doze / 省电 | §6 |
| `17_alarm.txt` | AlarmManager（zhuli 闹钟） | §4.3 §8 |
| `18_aiassistant_pkg.txt` | 系统 AI 助手组件 | §5 |
| `20_permissions.txt` | 权限定义（FGS/精确闹钟/ZTE 权限） | §4 §5 |
| `25_location_dump.txt` | 定位 provider / GNSS 能力 | §3.4 |
| `26_zhuli_pkg.txt` / `27_zhuli_appops.txt` | zhuli 权限与 appops 状态 | §4 §8 |
| `28_zte_props.txt` | 388 条厂商特性属性 | §5 |

## 附录 B：本次未执行的命令类别（合规声明）

按要求未执行：任何 `settings put`、`pm install/uninstall`、`pm grant/revoke`、`appops set`、`cmd deviceidle add`、`reboot`、修改系统属性、清除数据、root/提权、刷机、绕过权限等操作。所有厂商私有服务仅做存在性读取，未尝试绑定或调用。
