# 资料卡片 · Android Keystore

| 项 | 内容 |
|---|---|
| 标题 | Android Keystore 系统（密钥生成、不可导出、随应用卸载销毁） |
| 官方来源 | https://developer.android.com/privacy-and-security/keystore |
| 适用 | Android 6.0+（本项目 minSdk 24）；本机 Android 16 |
| 对应模块 / 阶段 | `SecureKeyStore.kt`、`Backup.kt`；阶段 B（D6 已修）、阶段 E（T-E2/T-E3） |
| 核验状态 | 🟢 官方文档 + 本项目实测（卸载重装后旧密文不可解，见 SESSION_LOG 2026-11-20 19:30） |
| 采用结论 | **用**，但只用于"本机本地加密"，**不得作为跨设备/重装迁移手段** |

## 关键限制（决定了几件事）

1. **密钥不可导出**：`KeyGenParameterSpec` 生成的 AES 密钥只存在于系统密钥库，应用只能"用"，拿不到原始字节。所以任何"把密钥一起备份走"的想法都不成立。
2. **随应用卸载销毁**：卸载即删除该应用名下所有 Keystore 条目。本项目实证：卸载重装后 `zhuli_secure.xml` 里的 `api_key_enc` 仍在，但解密必然失败 —— 密文成了废纸。
3. **跨设备不可迁移**：密钥与本设备 TEE/StrongBox 绑定，换机后同样解不开。
4. **`setUserAuthenticationRequired(false)`** 才允许后台读取（本项目这样设，因为提醒链路要在锁屏后台读 Key）；代价是设备解锁密码不参与保护。
5. **API 30+ 有 `setUnlockedDeviceRequired`**，更严格但会导致锁屏后台读不到 Key —— 本项目不采用。

## 对项目的直接影响

| 场景 | 结论 |
|---|---|
| 云端 API Key 存储 | 用 Keystore（D6 已改为 fail-closed：加密不可用就拒存，不写明文） |
| 加密备份（Backup.kt） | 现状用 Keystore 密钥 → **换机/重装后必然解不开**，D7 的根因 |
| 换机迁移 | 只能走「明文拉库 + run-as 回灌」（T-A1 已建立），不能靠 backups 目录 |
| 若要可迁移的加密备份（T-E2） | 必须换成**口令派生密钥**（PBKDF2/Argon2 + 随机盐 + 高迭代），口令由用户自管、丢失不可恢复 |

## 影响的判断

- 否决了「把 Keystore 密钥导出随备份走」这条路（技术上不成立，不是没试）；
- 决定了 §11-6 的候选方案只有「口令派生」一种现实解，剩下的只是参数与体验取舍。
