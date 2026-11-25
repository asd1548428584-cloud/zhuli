# test-offline —— 暂不参与编译的旧测试

这里放的是从旧壳 `4c3f630^` 捞回来的测试中，**目标类没有随新壳迁移**的那一批。
它们不进 `src/test/java`，因此不参与 `testDevDebugUnitTest`，不会拖红构建。

| 文件 | 目标类 | 状态 |
|---|---|---|
| `BackupLegacyTest.kt` | `core/BackupLegacy`（旧 v9 备份包兼容转换） | 新壳未迁移 |
| `BackupPlanTest.kt` | `core/BackupPlan`（备份内容取舍策略） | 新壳未迁移 |
| `PinHashTest.kt` | `core/Backup.hashPin/verifyPin`（PIN 哈希与升级） | 新壳改用上游 App 锁，未迁移 |
| `VideoAgentPlanTest.kt` | `core/VideoAgent`（视频理解任务规划） | 新壳未迁移 |
| `VideoNoteSamplingTest.kt` | `core/VideoNote`（抽帧时间点计算） | 新壳未迁移 |

## 为什么留而不删

这 5 个类都是从旧壳业务里长出来的，逻辑本身有参考价值（尤其是备份策略与旧包兼容）。
删掉就只剩 git 历史里的 `4c3f630^`，以后想做回来得先考古。

## 要恢复某个测试时

1. 确认目标类已经在新壳里存在（`grep -r "object Xxx" app/src/main/java`）；
2. 把对应文件从 `test-offline/java/...` 挪回 `test/java/com/zhuli/core/`；
3. 重新跑 `:app:testDevDebugUnitTest`，按编译错误对齐签名；
4. **不要为了让测试通过去改产品代码**——如果测试暴露的是真 bug，单开一条提交。

## 顺带记录的一个疑点（2026-11-23）

`BackupLegacy` 不在新壳里，意味着**旧壳导出的 v9 JSON 备份包在新壳上没有对应的导入路径**。
新壳的备份是"上游 zip + `zhuli_inbox.db` 整库文件"，跟旧壳的 JSON 结构不同，
所以这未必是缺陷；但在"换壳后旧备份还能不能救"这件事上，目前没有证据。
→ 已记入 `docs/规划/02_执行记录.md` 的待确认项，别当成已支持。
