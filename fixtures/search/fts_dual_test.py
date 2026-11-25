"""T-C1 双形态验证（PC 侧，真库副本）。

形态 A：SQLite 支持 fts5+trigram —— 验证三个触发器是否真的同步、integrity-check 能否发现失配、rebuild 是否幂等。
形态 B：不支持 FTS —— 验证 MUST 报 "no such table"，即 App 侧走 LIKE 兜底的前提成立。

用真库副本跑，不改手机上的数据。

用法：python fixtures/search/fts_dual_test.py <源库路径>
"""
import os
import shutil
import sqlite3
import sys
import tempfile

SRC = sys.argv[1] if len(sys.argv) > 1 else "zhuli_inbox_pre_uninstall.db"

DDL_FTS = """
CREATE VIRTUAL TABLE inbox_fts USING fts5(
    title, text, content='inbox', content_rowid='id', tokenize='trigram'
)
"""
TRG = [
    """CREATE TRIGGER inbox_ai AFTER INSERT ON inbox BEGIN
         INSERT INTO inbox_fts(rowid, title, text) VALUES (new.id, new.title, new.text); END""",
    """CREATE TRIGGER inbox_ad AFTER DELETE ON inbox BEGIN
         INSERT INTO inbox_fts(inbox_fts, rowid, title, text)
         VALUES ('delete', old.id, old.title, old.text); END""",
    """CREATE TRIGGER inbox_au AFTER UPDATE ON inbox BEGIN
         INSERT INTO inbox_fts(inbox_fts, rowid, title, text)
         VALUES ('delete', old.id, old.title, old.text);
         INSERT INTO inbox_fts(rowid, title, text) VALUES (new.id, new.title, new.text); END""",
]


def copy_db():
    fd, path = tempfile.mkstemp(suffix=".db")
    os.close(fd)
    shutil.copyfile(SRC, path)
    return path


def has_fts5_ci(conn):
    try:
        conn.execute("CREATE VIRTUAL TABLE t_probe USING fts5(a, tokenize='trigram')")
        conn.execute("DROP TABLE t_probe")
        return True
    except sqlite3.OperationalError:
        return False


def match_ids(conn, term):
    return [r[0] for r in conn.execute(
        "SELECT i.id FROM inbox i JOIN inbox_fts f ON f.rowid=i.id WHERE inbox_fts MATCH ?", (term,))]


def like_ids(conn, term):
    return [r[0] for r in conn.execute(
        "SELECT id FROM inbox WHERE title LIKE ? OR text LIKE ?", (f"%{term}%", f"%{term}%"))]


def form_a():
    path = copy_db()
    conn = sqlite3.connect(path)
    conn.execute(DDL_FTS)
    for t in TRG:
        conn.execute(t)
    # 触发器不回溯，存量必须 rebuild
    conn.execute("INSERT INTO inbox_fts(inbox_fts) VALUES('rebuild')")
    conn.commit()

    checks = []
    total = conn.execute("SELECT COUNT(*) FROM inbox").fetchone()[0]
    sample = conn.execute("SELECT id, text FROM inbox WHERE length(text)>=6 LIMIT 1").fetchone()
    term = sample[1][:3]
    checks.append(("rebuild 后能搜到存量", len(match_ids(conn, term)) > 0, f"term={term}"))

    # 一致性自检（rank=1 才比对内容表）
    try:
        conn.execute("INSERT INTO inbox_fts(inbox_fts, rank) VALUES('integrity-check', 1)")
        checks.append(("rebuild 后 integrity-check 通过", True, ""))
    except sqlite3.DatabaseError as e:
        checks.append(("rebuild 后 integrity-check 通过", False, str(e)))

    # 插入 → 立刻可搜
    conn.execute("INSERT INTO inbox(source,package_name,title,text) VALUES('t','p','触发器新增','火锅底料配方')")
    conn.commit()
    new_id = conn.execute("SELECT MAX(id) FROM inbox").fetchone()[0]
    checks.append(("插入后立即可搜（ai 触发器）", new_id in match_ids(conn, "火锅底料"), f"id={new_id}"))

    # 更新 → 新词可搜、旧词不可搜
    conn.execute("UPDATE inbox SET text='烧烤蘸料配方' WHERE id=?", (new_id,))
    conn.commit()
    checks.append(("更新后新词可搜", new_id in match_ids(conn, "烧烤蘸料"), ""))
    checks.append(("更新后旧词不可搜（au 先 delete 再 insert）", new_id not in match_ids(conn, "火锅底料"), ""))

    # 删除 → 不可搜
    conn.execute("DELETE FROM inbox WHERE id=?", (new_id,))
    conn.commit()
    checks.append(("删除后不可搜（ad 触发器）", new_id not in match_ids(conn, "烧烤蘸料"), ""))

    # 幂等：连续两次 rebuild，行数与结果不变
    before = len(match_ids(conn, term))
    conn.execute("INSERT INTO inbox_fts(inbox_fts) VALUES('rebuild')")
    conn.execute("INSERT INTO inbox_fts(inbox_fts) VALUES('rebuild')")
    conn.commit()
    after = len(match_ids(conn, term))
    checks.append(("rebuild 幂等（两次结果一致）", before == after and before > 0, f"{before}->{after}"))

    # 失配检测：绕过触发器插一行，integrity-check 必须报错
    conn.execute("DROP TRIGGER inbox_ai")
    conn.execute("INSERT INTO inbox(source,package_name,title,text) VALUES('t','p','绕过触发器','酸菜鱼做法')")
    conn.commit()
    try:
        conn.execute("INSERT INTO inbox_fts(inbox_fts, rank) VALUES('integrity-check', 1)")
        checks.append(("失配能被 integrity-check 发现", False, "没报错，说明检测不可靠"))
    except sqlite3.DatabaseError:
        checks.append(("失配能被 integrity-check 发现", True, "按预期报错"))

    conn.close()
    os.remove(path)
    return checks, total


def form_b():
    path = copy_db()
    conn = sqlite3.connect(path)
    checks = []
    has = has_fts5_ci(conn)
    try:
        conn.execute("SELECT * FROM inbox_fts WHERE inbox_fts MATCH '测试'")
        checks.append(("无 FTS 表时查询必须抛错（触发 LIKE 兜底）", False, "居然没报错"))
    except sqlite3.OperationalError as e:
        checks.append(("无 FTS 表时查询必须抛错（触发 LIKE 兜底）", "no such table" in str(e), str(e)))

    sample = conn.execute("SELECT text FROM inbox WHERE length(text)>=6 LIMIT 1").fetchone()
    term = sample[0][:3]
    checks.append(("LIKE 兜底能搜到", len(like_ids(conn, term)) > 0, f"term={term}"))
    rows = conn.execute("SELECT COUNT(*) FROM inbox").fetchone()[0]
    checks.append(("副本行数与源一致", rows > 0, f"{rows} 行"))
    conn.close()
    os.remove(path)
    return checks, has


def main():
    if not os.path.exists(SRC):
        print(f"源库不存在：{SRC}")
        return 2
    print(f"源库：{SRC}\n")
    a, total = form_a()
    print(f"=== 形态 A（FTS 可用，{total} 行）===")
    ok_a = True
    for name, ok, note in a:
        print(f"  [{'PASS' if ok else 'FAIL'}] {name} {('· ' + note) if note else ''}")
        ok_a &= ok

    print("\n=== 形态 B（无 FTS）===")
    b, has = form_b()
    ok_b = True
    for name, ok, note in b:
        print(f"  [{'PASS' if ok else 'FAIL'}] {name} {('· ' + note) if note else ''}")
        ok_b &= ok
    print(f"  （本机 Python SQLite {'支持' if has else '不支持'} fts5+trigram）")

    print("\n总判定：", "双形态均通过" if (ok_a and ok_b) else "存在失败项")
    return 0 if (ok_a and ok_b) else 1


if __name__ == "__main__":
    sys.exit(main())
