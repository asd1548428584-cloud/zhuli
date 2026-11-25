# 会话原始存档说明

这里是 2026-11-19 那一整轮开发会话的原始记录，给「明天的你 / 另一台电脑」用。

## 文件

| 文件 | 说明 |
|---|---|
| `session-2026-11-19.jsonl` | **解压后的完整原始记录**（约 10MB / 2500+ 条）。含用户消息、助手正文、推理过程（reasoning）、工具调用与返回、任务状态等全部字段，一行一条 JSON |
| `session.v4.jsonl.zstd` | 从 DSH 会话目录直接拷来的**原始压缩档**（约 3MB），保真备份 |
| `export_session.js` | 导出脚本，可重跑（见下） |

## 可读的对话稿在上级目录

`../会话记录_2026-11-19.md` —— 已按「用户 / 助手」排好序，附精简工具流水，
过滤掉了系统注入消息（AGENTS.md 提醒、技能清单等 36 条噪声）。

## 怎么再导出一次（换成别的电脑时）

原始档是**多帧拼接的 zstd**（每帧自带 magic `28 B5 2F FD`），**不能整体解压**，
脚本里已按 magic 切帧再逐帧解。重跑：

```bash
node export_session.js <某个 session.v4.jsonl.zstd> <输出前缀>
# 产出 <输出前缀>.md（人读）与 <输出前缀>.jsonl（原始）
```

`.jsonl` 的常用字段（写脚本时照着取）：

- 顶层：`type` / `seq` / `time`（毫秒）/ `data`
- `user/message` → `data.content[]`（`{type:"text",text}`）+ `data.source.kind`（`user` 才是真人说的）
- `assistant/message` → `data.message.content[]`，元素类型有 `reasoning`（思考过程）、`text`（正文）、`tool-call`
- `tool/call` → `data.name` + `data.arguments`（JSON 字符串）
- `tool/result` → 对应 `callId` 的返回内容

## 会话在哪找（本机路径）

```
C:\Users\a\.dsh\sessions\--D-ai-gongzuo-~65B0~5EFA~6587~4EF6~5939--\
  session-72be1ce8-d692-4b5a-bdb5-474fa147e5ec\session.v4.jsonl.zstd
```

> 目录名是工作区路径的编码形式（`新建文件夹` → `~65B0~5EFA~6587~4EF6~5939`）。
> 换电脑后这个目录不存在，所以原始档已经拷进来，不必回头找。

## 注意

导出的时间点是 **11-19 22:29**，之后产生的对话（包括「导出会话」这轮本身）不在档内；
需要完整版就在收工时重跑一次脚本。
