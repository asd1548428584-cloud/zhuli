# 会话原始存档说明

> **批次 A 变更（2026-11-25）**：本目录原先存放 2026-11-19 那一整轮开发会话的原始记录
> （`session-2026-11-19.jsonl` 约 10MB / 2500+ 条，以及压缩档 `session.v4.jsonl.zstd`）。
> 这些文件包含本机绝对路径、真机命令输出、用户真实短信/取件码等个人信息，已从公开仓库移除，
> 并加入 `.gitignore`。**不要再把原始会话档提交上来。**
>
> 需要复盘时请在本机重新导出，只提交人工脱敏后的纪要（见上级目录 `../会话记录_2026-11-19.md`）。

## 保留的文件

| 文件 | 说明 |
|---|---|
| `export_session.js` | 导出脚本（纯工具，不含数据），可重跑 |
| `README.md` | 本说明 |

## 怎么再导出一次（仅在本机使用）

原始档是**多帧拼接的 zstd**（每帧自带 magic `28 B5 2F FD`），**不能整体解压**，
脚本里已按 magic 切帧再逐帧解。重跑：

```bash
node export_session.js <某个 session.v4.jsonl.zstd> <输出前缀>
# 产出 <输出前缀>.md（人读）与 <输出前缀>.jsonl（原始，禁止入库）
```

`.jsonl` 的常用字段（写脚本时照着取）：

- 顶层：`type` / `seq` / `time`（毫秒）/ `data`
- `user/message` → `data.content[]`（`{type:"text",text}`）+ `data.source.kind`（`user` 才是真人说的）
- `assistant/message` → `data.message.content[]`，元素类型有 `reasoning`（思考过程）、`text`（正文）、`tool-call`
- `tool/call` → `data.name` + `data.arguments`（JSON 字符串）
- `tool/result` → 对应 `callId` 的返回内容

## 注意

导出档属于**用户数据**：只在本机留存；如需分享，必须先按
`docs/zhuli_隐私与数据分级.md` 的口径脱敏（手机号、地址、取件码、运单号、设备名、本机路径）。
