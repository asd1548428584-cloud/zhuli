#!/usr/bin/env node
/**
 * 把 DSH 会话存档（多帧 zstd + jsonl）导出成可读 Markdown + 原始 jsonl。
 *
 * 用法：
 *   node export_session.js <session.v4.jsonl.zstd> <输出前缀>
 * 产出：
 *   <输出前缀>.md    —— 人读的对话稿（用户/助手正文 + 精简工具调用流水）
 *   <输出前缀>.jsonl —— 解压后的完整原始记录（含 reasoning / tool 结果，供程序再处理）
 *
 * 备注：DSH 的 .jsonl.zstd 是**逐帧拼接**的 zstd 流（每帧自带 magic 28 B5 2F FD），
 * 不能整体解压，必须先按 magic 切帧再逐帧 zstdDecompressSync。
 */
const fs = require('fs');
const zlib = require('zlib');

const src = process.argv[2];
const outPrefix = process.argv[3];
if (!src || !outPrefix) {
  console.error('用法: node export_session.js <in.zstd> <outPrefix>');
  process.exit(1);
}

// ---------- 1. 解帧 ----------
const buf = fs.readFileSync(src);
const MAGIC = [0x28, 0xb5, 0x2f, 0xfd];
const starts = [];
for (let i = 0; i + 4 <= buf.length; i++) {
  if (buf[i] === MAGIC[0] && buf[i + 1] === MAGIC[1] && buf[i + 2] === MAGIC[2] && buf[i + 3] === MAGIC[3]) {
    starts.push(i);
  }
}
starts.push(buf.length);
const parts = [];
for (let k = 0; k < starts.length - 1; k++) {
  try {
    parts.push(zlib.zstdDecompressSync(buf.subarray(starts[k], starts[k + 1])));
  } catch (e) {
    // 单帧损坏不影响其余帧
  }
}
const jsonl = Buffer.concat(parts).toString('utf8');
fs.writeFileSync(outPrefix + '.jsonl', jsonl);

// ---------- 2. 解析 ----------
const records = [];
for (const line of jsonl.split('\n')) {
  if (!line.trim()) continue;
  try { records.push(JSON.parse(line)); } catch (e) { /* 跳过坏行 */ }
}

const pad = (n) => String(n).padStart(2, '0');
const hhmm = (ms) => {
  const d = new Date(ms);
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
};

function textOf(content) {
  if (!Array.isArray(content)) return '';
  return content
    .filter((c) => c && c.type === 'text' && typeof c.text === 'string')
    .map((c) => stripSystemBlocks(c.text.trim()))
    .filter(Boolean)
    .join('\n\n');
}

/** 去掉 <system-reminder> / <available_skills> 这类系统注入块，只留人说话的部分 */
function stripSystemBlocks(s) {
  return s
    .replace(/<system-reminder>[\s\S]*?<\/system-reminder>/gi, '')
    .replace(/<available[_-]skills>[\s\S]*?<\/available[_-]skills>/gi, '')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
}

function reasoningOf(content) {
  if (!Array.isArray(content)) return '';
  return content
    .filter((c) => c && c.type === 'reasoning' && typeof c.text === 'string')
    .map((c) => c.text.trim())
    .filter(Boolean)
    .join('\n\n');
}

/** 工具调用精简描述：优先取 description / 关键参数，避免整段 JSON 塞满文档 */
function briefArgs(name, raw) {
  let args = {};
  try { args = JSON.parse(raw || '{}'); } catch (e) { return (raw || '').slice(0, 80); }
  if (args.description) return args.description;
  if (args.command) return String(args.command).replace(/\s+/g, ' ').slice(0, 110);
  if (args.file_path) return String(args.file_path).split(/[\\/]/).pop();
  if (args.path && args.pattern) return `${args.pattern} @ ${String(args.path).split(/[\\/]/).pop()}`;
  if (args.question) return String(args.question).slice(0, 90);
  if (args.url) return args.url;
  if (args.queries) return JSON.stringify(args.queries).slice(0, 90);
  const s = JSON.stringify(args);
  return s === '{}' ? '' : s.slice(0, 90);
}

// ---------- 3. 渲染 Markdown ----------
const out = [];
const meta = records.find((r) => r.type === 'session');
const title = records.filter((r) => r.type === 'session/title').pop();
const first = records.find((r) => r.time);
const last = [...records].reverse().find((r) => r.time);

out.push(`# 会话记录 · ${first ? hhmm(first.time) : ''} ~ ${last ? hhmm(last.time) : ''}`);
out.push('');
if (title && title.data && (title.data.title || title.data.text)) {
  out.push(`**标题**：${title.data.title || title.data.text}`);
}
if (meta && meta.data) out.push(`**会话 ID**：\`${meta.data.id}\``);
if (meta && meta.cwd) out.push(`**工作目录**：\`${meta.cwd}\``);
out.push('');
out.push('> 本文件由 `export_session.js` 从 DSH 会话存档自动导出：只保留对话正文与精简工具流水，');
out.push('> 完整原始记录（含推理过程与工具输出）在同名 `.jsonl` 里。');
out.push('');
out.push('---');
out.push('');

let userCount = 0;
let assistantCount = 0;
let toolCount = 0;
let skipped = 0;
const toolsByTurn = new Map(); // turn -> [lines]

for (const r of records) {
  if (r.type === 'user/message') {
    // 只留真人说的话：agent-instructions / system 之类的注入消息整条跳过
    const kind = (r.data && r.data.source && r.data.source.kind) || 'user';
    if (kind !== 'user') { skipped++; continue; }
    const text = textOf(r.data && r.data.content);
    if (!text) { skipped++; continue; }
    userCount++;
    out.push('');
    out.push(`## 👤 用户 · ${hhmm(r.time)}`);
    out.push('');
    out.push(text);
    out.push('');
  } else if (r.type === 'assistant/message') {
    const msg = r.data && r.data.message;
    const text = textOf(msg && msg.content);
    if (!text) continue;
    assistantCount++;
    out.push(`### 🤖 助手${r.data && r.data.turn != null ? `（第 ${r.data.turn} 轮）` : ''} · ${hhmm(r.time)}`);
    out.push('');
    out.push(text);
    out.push('');
  } else if (r.type === 'tool/call') {
    const name = r.data && r.data.name;
    const brief = briefArgs(name, r.data && r.data.arguments);
    toolCount++;
    const turn = (r.data && r.data.turn) || 0;
    if (!toolsByTurn.has(turn)) toolsByTurn.set(turn, []);
    toolsByTurn.get(turn).push(`\`${name}\`${brief ? ' — ' + brief : ''}`);
  }
}

// 工具流水附录
if (toolsByTurn.size) {
  out.push('');
  out.push('---');
  out.push('');
  out.push('## 🔧 工具调用流水（精简）');
  out.push('');
  out.push('> 只记「哪个工具、干了什么」，完整参数与返回值见 `.jsonl`。');
  out.push('');
  for (const turn of [...toolsByTurn.keys()].sort((a, b) => a - b)) {
    out.push(`**第 ${turn} 轮**`);
    out.push('');
    for (const line of toolsByTurn.get(turn)) out.push(`- ${line}`);
    out.push('');
  }
}

out.push('---');
out.push('');
out.push(`**统计**：用户消息 ${userCount} 条 · 助手回复 ${assistantCount} 条 · 工具调用 ${toolCount} 次 · 原始记录 ${records.length} 条` +
  (skipped ? `（已过滤系统注入消息 ${skipped} 条）` : ''));

fs.writeFileSync(outPrefix + '.md', out.join('\n'));
console.log(`OK  frames=${starts.length - 1} records=${records.length} user=${userCount} assistant=${assistantCount} tool=${toolCount}`);
console.log(`    ${outPrefix}.md`);
console.log(`    ${outPrefix}.jsonl  (${(jsonl.length / 1024 / 1024).toFixed(1)} MB)`);
