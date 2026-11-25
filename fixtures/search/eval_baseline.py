"""T-C2 检索基线评测（PC 侧，真库副本）。

目的：在"要不要把 FTS 接进检索链路"这件事上，先拿数值再决定。

评测方式：
- 查询集是**通用词**，不含任何用户原文（不把个人数据写进仓库）；
- 真值在运行时用子串判定：词在库里出现过 = 「应当找到」，没出现过 = 「应当答没有」；
- 对比两种检索：① LIKE（当前兜底路径）② FTS5 trigram（候选方案，临时在副本上建索引）。

注意：不移植向量层。向量层的作用是在时间窗内**重排**，不改变召回集合，
对"多词/久远条目/召回率"的判断没有决定性影响；接不接 FTS 的决策看这两个就够。

用法：python fixtures/search/eval_baseline.py zhuli_inbox_pre_uninstall.db
"""
import os
import re
import shutil
import sqlite3
import sys
import tempfile
import time

PRESENT_QUERIES = [
    "快递", "取件", "驿站", "工资", "支出", "账单", "银行", "验证码", "会议", "视频",
    "提醒", "短信", "支付", "红包", "发票", "外卖", "打车", "机票", "酒店", "签到",
    "订单", "物流", "派送", "签收", "余额", "收款", "付款", "退款", "优惠", "秒杀",
    "直播", "课程", "作业", "考试", "医院", "挂号", "体检", "电费", "话费", "流量",
    "会员", "续费", "密码", "账号", "登录", "注册", "面试", "简历", "报销", "快递柜",
    "快递 取件", "工资 到账", "会议 提醒", "视频 会员", "银行 转账", "订单 支付",
    "医院 挂号", "课程 作业", "退款 到账",
]
ABSENT_QUERIES = [
    "量子纠缠", "火星殖民", "莎士比亚", "微积分", "鲸落", "琥珀", "冰川", "榫卯",
    "青铜器", "咏春拳", "南极科考", "甲骨文", "弦理论", "莫高窟", "转甲状腺素",
]

PUNCT = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~，。！？；：、“”‘’（）《》·…—"


def like_count(conn, term):
    return conn.execute(
        "SELECT COUNT(*) FROM inbox WHERE title LIKE ? OR text LIKE ? OR note LIKE ?",
        (f"%{term}%", f"%{term}%", f"%{term}%")).fetchone()[0]


def like_search(conn, term, limit=5):
    return conn.execute(
        "SELECT id FROM inbox WHERE title LIKE ? OR text LIKE ? OR note LIKE ? "
        "ORDER BY received_at DESC LIMIT ?",
        (f"%{term}%", f"%{term}%", f"%{term}%", limit)).fetchall()


def fts_search(conn, term, limit=5):
    # trigram 分词器要求查询串 ≥3 字符；两字词退化为 phrase 查询会空，这里按原文 MATCH
    if len(term) < 3:
        return None   # 该方案对两字词不可用，记为 miss（这正是要暴露的差异）
    rows = conn.execute(
        "SELECT i.id FROM inbox i JOIN inbox_fts f ON f.rowid=i.id "
        "WHERE inbox_fts MATCH ? ORDER BY i.received_at DESC LIMIT ?", (term, limit)).fetchall()
    return rows



def top_terms(conn, n=40, min_df=2):
    """从库里抽高频中文 n-gram 当查询集（运行期生成，不把原文写进仓库，也不打印词本身）。"""
    import collections
    df = collections.Counter()
    for title, text in conn.execute("SELECT title, text FROM inbox LIMIT 2000"):
        grams = set()
        for run in re.findall(r"[\u4e00-\u9fa5]+", (title or "") + " " + (text or "")):
            for L in (2, 3):
                for i in range(len(run) - L + 1):
                    grams.add(run[i:i + L])
        for g in grams:
            df[g] += 1
    return [g for g, c in df.most_common(n * 4) if c >= min_df][:n]


def auto_section(conn, has_fts, n=40):
    terms = top_terms(conn, n)
    if not terms:
        print("（库太小，抽不出高频词）")
        return
    like_ok = fts_ok = fts_na = 0
    two_char = sum(1 for t in terms if len(t) == 2)
    for t in terms:
        if like_count(conn, t) > 0:
            like_ok += 1
        if has_fts:
            r = fts_search(conn, t)
            if r is None:
                fts_na += 1
            elif r:
                fts_ok += 1
    print(f"\n=== 自动抽样（库里真实高频词 {len(terms)} 个，其中两字词 {two_char} 个）===")
    print(f"  LIKE 可召回：{like_ok}/{len(terms)}")
    if has_fts:
        print(f"  FTS trigram 可召回：{fts_ok}/{len(terms)}，其中因短于 3 字不可用：{fts_na}")
        print(f"  → 两字查询占比 {two_char}/{len(terms)}，trigram 分词器对这类词直接失效")


def main():
    src = sys.argv[1] if len(sys.argv) > 1 else "zhuli_inbox_pre_uninstall.db"
    if not os.path.exists(src):
        print("源库不存在:", src)
        return 2

    fd, path = tempfile.mkstemp(suffix=".db"); os.close(fd)
    shutil.copyfile(src, path)
    conn = sqlite3.connect(path)

    has_fts = True
    try:
        conn.execute("CREATE VIRTUAL TABLE inbox_fts USING fts5(title, text, content='inbox', content_rowid='id', tokenize='trigram')")
        conn.execute("INSERT INTO inbox_fts(inbox_fts) VALUES('rebuild')")
        conn.commit()
    except sqlite3.OperationalError as e:
        has_fts = False
        print("副本上建不出 FTS：", e)

    present = [q for q in PRESENT_QUERIES if like_count(conn, q.split()[0]) > 0]
    absent = [q for q in ABSENT_QUERIES if like_count(conn, q.split()[0]) == 0]

    stats = {"like": {"hit": 0, "fp": 0, "ms": 0.0},
             "fts": {"hit": 0, "fp": 0, "ms": 0.0, "na": 0}}

    print(f"\n查询集：应找到 {len(present)} 条 / 应答没有 {len(absent)} 条（库 {conn.execute('SELECT COUNT(*) FROM inbox').fetchone()[0]} 行）\n")

    for q in present:
        t0 = time.perf_counter(); rows = like_search(conn, q.split()[0]); stats["like"]["ms"] += (time.perf_counter() - t0) * 1000
        ok = len(rows) > 0
        stats["like"]["hit"] += 1 if ok else 0
        if not ok:
            print(f"  [LIKE 漏] {q}")

        if has_fts:
            t0 = time.perf_counter(); fr = fts_search(conn, q); stats["fts"]["ms"] += (time.perf_counter() - t0) * 1000
            if fr is None:
                stats["fts"]["na"] += 1
            elif len(fr) > 0:
                stats["fts"]["hit"] += 1
            else:
                print(f"  [FTS 漏] {q}")

    for q in absent:
        t0 = time.perf_counter(); rows = like_search(conn, q.split()[0]); stats["like"]["ms"] += (time.perf_counter() - t0) * 1000
        if rows:
            stats["like"]["fp"] += 1
            print(f"  [LIKE 误报] {q}")
        if has_fts:
            t0 = time.perf_counter(); fr = fts_search(conn, q); stats["fts"]["ms"] += (time.perf_counter() - t0) * 1000
            if fr:
                stats["fts"]["fp"] += 1
                print(f"  [FTS 误报] {q}")

    print("\n" + "=" * 62)
    print(f"{'方案':10s}{'应找到命中':>12s}{'空查询误报':>12s}{'两字词不可用':>14s}{'平均耗时':>10s}")
    n_p, n_a = max(len(present), 1), max(len(absent), 1)
    print(f"{'LIKE':10s}{stats['like']['hit']:>8d}/{len(present):<3d}{stats['like']['fp']:>10d}/{len(absent):<3d}"
          f"{'-':>14s}{stats['like']['ms']/(n_p+n_a):>8.1f}ms")
    if has_fts:
        print(f"{'FTS(trigram)':10s}{stats['fts']['hit']:>8d}/{len(present):<3d}{stats['fts']['fp']:>10d}/{len(absent):<3d}"
              f"{stats['fts']['na']:>14d}{stats['fts']['ms']/(n_p+n_a):>8.1f}ms")
    print("=" * 62)
    auto_section(conn, has_fts)
    print("\n基线数值请记入 CURRENT_STATE；以后接 FTS / 换 Embedding 后重跑本脚本对比。")

    conn.close(); os.remove(path)
    return 0


if __name__ == "__main__":
    sys.exit(main())
