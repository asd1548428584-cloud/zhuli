"""T-E1 迁移演练：事务能否真正回滚（PC 侧，真库副本）。

演练的三条路径（计划 §6.E T-E1）：
  A 正常升级：v10 → v11 → v12，数据不丢
  B 中途失败：最后一步故意报错 → **整体回滚**，库停在 v10、字段没被改一半
  C 异常后重试：从 B 的状态再跑一遍正确迁移 → 仍能升到 v12

注意：本脚本用 SQL 复刻 `InboxDatabase.upgradeSteps` 的步骤，验证的是"事务+失败即抛"这套做法在
SQLite 上的语义；Kotlin 侧的实现是它的镜像（beginTransaction → upgradeSteps → 失败 rethrow）。
"""
import os
import shutil
import sqlite3
import sys
import tempfile

SRC = sys.argv[1] if len(sys.argv) > 1 else "zhuli_inbox_pre_cleanup.db"

# 复刻 v10→v11、v11→v12 的 DDL
STEPS = [
    ("v10→v11", [
        "CREATE VIRTUAL TABLE IF NOT EXISTS embeddings(id INTEGER PRIMARY KEY, vec BLOB)",
        "ALTER TABLE reminders ADD COLUMN snooze_count INTEGER NOT NULL DEFAULT 0",
    ]),
    ("v11→v12", [
        "ALTER TABLE reminders ADD COLUMN confidence TEXT NOT NULL DEFAULT 'high'",
        "ALTER TABLE reminders ADD COLUMN basis TEXT NOT NULL DEFAULT ''",
    ]),
]

FAIL_STEP = ("v11→v12 注入失败", [
    "ALTER TABLE reminders ADD COLUMN confidence TEXT NOT NULL DEFAULT 'high'",
    "ALTER TABLE reminders ADD COLUMN confidence TEXT NOT NULL DEFAULT 'high'",  # 重复列 → 必失败
])


def make_v10_copy():
    fd, path = tempfile.mkstemp(suffix=".db"); os.close(fd)
    shutil.copyfile(SRC, path)
    c = sqlite3.connect(path)
    for col in ("confidence", "basis", "snooze_count"):
        try:
            c.execute(f"ALTER TABLE reminders DROP COLUMN {col}")
        except sqlite3.OperationalError:
            pass
    c.execute("DROP TABLE IF EXISTS embeddings")
    c.execute("PRAGMA user_version = 10")
    c.commit()
    return path, c


def cols(c, table="reminders"):
    return [r[1] for r in c.execute(f"PRAGMA table_info({table})")]


def run(c, steps, set_version=None):
    c.execute("BEGIN")
    try:
        for sql in steps:
            c.execute(sql)
        if set_version:
            c.execute(f"PRAGMA user_version = {set_version}")
        c.commit()
        return True, None
    except sqlite3.Error as e:
        c.rollback()
        return False, str(e)


def main():
    if not os.path.exists(SRC):
        print("源库不存在:", SRC); return 2
    path, c = make_v10_copy()
    total = c.execute("SELECT COUNT(*) FROM inbox").fetchone()[0]
    base_cols = cols(c)
    print(f"起点：user_version={c.execute('PRAGMA user_version').fetchone()[0]}，"
          f"inbox {total} 行，reminders 列 {base_cols}\n")

    ok = True

    # A 正常升级
    for name, steps in STEPS:
        good, err = run(c, steps)
        print(f"[A] {name}: {'成功' if good else '失败 ' + str(err)}")
        ok &= good
    after_a = cols(c)
    print(f"    → 升级后 reminders 列：{after_a}")
    print(f"    → 数据仍在：{c.execute('SELECT COUNT(*) FROM inbox').fetchone()[0]} 行")
    ok &= {"confidence", "basis", "snooze_count"} <= set(after_a)

    # 回到 v10 重演失败
    c.close(); os.remove(path)
    path, c = make_v10_copy()
    before = cols(c)
    good, err = run(c, FAIL_STEP[1])
    after = cols(c)
    print(f"\n[B] {FAIL_STEP[0]}: {'意外成功' if good else '按预期失败（' + str(err)[:50] + '…）'}")
    print(f"    → 回滚后 reminders 列：{after}")
    rolled_back = (after == before)
    print(f"    → 是否原样回滚：{'是' if rolled_back else '否 —— 出现半套字段！'}")
    ok &= (not good) and rolled_back
    print(f"    → 数据未损：{c.execute('SELECT COUNT(*) FROM inbox').fetchone()[0]} 行")

    # C 异常后重试
    for name, steps in STEPS:
        good, err = run(c, steps)
        ok &= good
    final_cols = cols(c)
    print(f"\n[C] 从失败状态重试升级：{'成功' if {'confidence','basis'} <= set(final_cols) else '失败'}")
    print(f"    → reminders 列：{final_cols}")

    print("\n总判定：", "三条路径均通过" if ok else "存在失败项")
    c.close(); os.remove(path)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
