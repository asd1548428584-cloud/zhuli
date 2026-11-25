"""T-C2 检索评测集运行器（PC 侧，真库副本）。

与 eval_baseline.py 的区别：
- baseline 是"抽高频词看两种索引的召回"；本脚本是**类别化评测**，回答的是
  「精确事实 / 多词组合 / 多条汇总 / 不存在信息」这四类问法各自表现如何，
  这正是计划 §7.3 要的指标口径。

四类各自的判定：
- fact   ：词在库里出现过 → 应返回 ≥1 条
- multi  ：两个词**都**出现 → 应返回 ≥1 条（顺带暴露"整串 LIKE"和"分词 AND"的差别）
- bulk   ：库里命中 ≥3 条 → 应返回（且不该被截断成 0）
- absent ：库里没有 → 必须返回 0 条

用法：python fixtures/search/eval_runner.py zhuli_inbox_pre_cleanup.db [eval_set.json]
"""
import json
import os
import shutil
import sqlite3
import sys
import tempfile
import re
import time


def count_all(conn, term):
    return conn.execute(
        "SELECT COUNT(*) FROM inbox WHERE title LIKE ? OR text LIKE ? OR note LIKE ?",
        (f"%{term}%", f"%{term}%", f"%{term}%")).fetchone()[0]


def like_raw(conn, q, limit=5):
    """App 现在的行为：把关键词整串丢进 LIKE（含空格）。"""
    return conn.execute(
        "SELECT id FROM inbox WHERE title LIKE ? OR text LIKE ? OR note LIKE ? "
        "ORDER BY received_at DESC LIMIT ?",
        (f"%{q}%", f"%{q}%", f"%{q}%", limit)).fetchall()


def like_and(conn, words, limit=5):
    """理想行为：多词按 AND 组合。"""
    sql = "SELECT id FROM inbox WHERE " + " AND ".join(
        ["(title LIKE ? OR text LIKE ? OR note LIKE ?)"] * len(words)) + \
        " ORDER BY received_at DESC LIMIT ?"
    params = []
    for w in words:
        params += [f"%{w}%"] * 3
    params.append(limit)
    return conn.execute(sql, params).fetchall()



def kw_score(hay, q):
    """VectorSearch.keywordScore 的移植：整串命中 1.0；否则按空白/标点切分后算比例。"""
    ql = q.lower(); hl = hay.lower()
    if ql in hl:
        return 1.0
    parts = [p for p in re.split(r"[\s\W_]+", ql) if len(p) >= 2]
    if not parts:
        return 0.0
    hit = sum(1 for p in parts if p in hl)
    return 0.0 if hit == 0 else 0.5 + 0.5 * hit / len(parts)


def kw_search(conn, q, limit=5):
    """模拟 recall() 的关键词打分路径（不移植向量，只看 kw 分>0 的候选）。"""
    out = []
    for rid, title, text, note in conn.execute(
            "SELECT id, title, text, note FROM inbox ORDER BY received_at DESC LIMIT 400"):
        if kw_score((title or "") + " " + (text or "") + " " + (note or ""), q) > 0:
            out.append(rid)
            if len(out) >= limit:
                break
    return out

def truth(conn, item):
    q, cat = item["q"], item["cat"]
    words = q.split()
    if cat == "absent":
        return count_all(conn, words[0]) == 0
    if cat == "multi":
        return all(count_all(conn, w) > 0 for w in words)
    if cat == "bulk":
        return count_all(conn, words[0]) >= 3
    return count_all(conn, words[0]) > 0


def main():
    src = sys.argv[1] if len(sys.argv) > 1 else "zhuli_inbox_pre_cleanup.db"
    setfile = sys.argv[2] if len(sys.argv) > 2 else os.path.join(os.path.dirname(__file__), "eval_set.json")
    if not os.path.exists(src):
        print("源库不存在:", src)
        return 2
    spec = json.load(open(setfile, encoding="utf-8"))

    fd, path = tempfile.mkstemp(suffix=".db"); os.close(fd)
    shutil.copyfile(src, path)
    conn = sqlite3.connect(path)

    # 只评测"真值成立"的条目：库里没有的词谈不上召回
    items = [i for i in spec["queries"] if truth(conn, i)]
    by_cat = {}
    for i in items:
        by_cat.setdefault(i["cat"], []).append(i)

    rows_total = conn.execute("SELECT COUNT(*) FROM inbox").fetchone()[0]
    print(f"库 {rows_total} 行；评测条目 {len(items)} / 配置 {len(spec['queries'])}（其余因库里无对应内容跳过）\n")

    headers = [("fact", "精确事实"), ("multi", "多词组合"), ("bulk", "多条汇总"), ("absent", "不存在信息")]
    print(f"{'类别':10s}{'条数':>5s}{'整串LIKE':>10s}{'分词AND':>10s}{'关键词打分':>10s}{'平均耗时':>10s}")
    detail_fail = []
    for cat, name in headers:
        group = by_cat.get(cat, [])
        if not group:
            continue
        hit_raw = hit_and = hit_kw = 0
        ms = 0.0
        for it in group:
            q = it["q"]
            words = q.split()
            if cat == "absent":
                t0 = time.perf_counter(); r_raw = like_raw(conn, q); ms += (time.perf_counter() - t0) * 1000
                ok_raw = len(r_raw) == 0
                t0 = time.perf_counter(); r_and = like_and(conn, words); ms += (time.perf_counter() - t0) * 1000
                ok_and = len(r_and) == 0
            else:
                t0 = time.perf_counter(); r_raw = like_raw(conn, q); ms += (time.perf_counter() - t0) * 1000
                ok_raw = len(r_raw) > 0
                t0 = time.perf_counter(); r_and = like_and(conn, words); ms += (time.perf_counter() - t0) * 1000
                ok_and = len(r_and) > 0
            r_kw = kw_search(conn, q)
            ok_kw = (len(r_kw) == 0) if cat == "absent" else (len(r_kw) > 0)
            hit_raw += 1 if ok_raw else 0
            hit_and += 1 if ok_and else 0
            hit_kw += 1 if ok_kw else 0
            if not ok_raw:
                detail_fail.append((cat, q, "整串LIKE未命中"))
            if not ok_and:
                detail_fail.append((cat, q, "分词AND未命中"))
        n = len(group)
        print(f"{name:10s}{n:>5d}{f'{hit_raw}/{n}':>10s}{f'{hit_and}/{n}':>10s}{f'{hit_kw}/{n}':>10s}{ms/(2*n):>9.1f}ms")

    if detail_fail:
        print("\n未命中明细（前 10）：")
        for cat, q, why in detail_fail[:10]:
            print(f"  [{cat}] {q} — {why}")

    print("\n解读要点：")
    print("  · multi 类若整串 LIKE 明显低于分词 AND，说明现在的检索不支持「两个词分开出现」")
    print("  · absent 类两项都应 100%，否则就是编造风险")
    conn.close(); os.remove(path)
    return 0


if __name__ == "__main__":
    sys.exit(main())
