"""夜间备份演练（T-E2）：给模拟器库灌一份"七张表都有货"的数据。

只在本地副本上改，再由调用方回灌到 emulator-5554，不碰真机、不碰真数据。
"""
import sqlite3
import struct
import sys
import time

DB = sys.argv[1] if len(sys.argv) > 1 else "fixtures/backup_drill/zhuli_inbox.db"
db = sqlite3.connect(DB)
c = db.cursor()
now = int(time.time() * 1000)

# 1) 补几条收件（原有 3 条保留，另加 2 条带期限/金额的，方便验提醒与账本关系）
extra_inbox = [
    ("notification", "com.tencent.mm", "微信", "明天下午两点项目评审",
     "明天下午两点项目评审，会议室 A，记得带笔记本", now - 3_600_000, "工作", now + 86_400_000, 0.0),
    ("notification", "com.taobao.taobao", "淘宝", "订单已发货",
     "您的订单已发货，预计明天送达，金额 129.00 元", now - 7_200_000, "生活", 0, 129.0),
]
for row in extra_inbox:
    c.execute(
        "INSERT INTO inbox (source, package_name, app_label, title, text, received_at,"
        " pending_extract, category, deadline_at, amount) VALUES (?,?,?,?,?,?,0,?,?,?)", row)

# 2) 账本：挂到真实存在的前两条 inbox 上（UNIQUE(inbox_id)）
ids = [r[0] for r in c.execute("SELECT id FROM inbox ORDER BY id LIMIT 2")]
if len(ids) >= 2:
    c.execute("INSERT OR IGNORE INTO ledger (inbox_id, dir, category, amount, occurred_at, note)"
              " VALUES (?,?,?,?,?,?)", (ids[0], "支", "餐饮", 38.5, now - 5_400_000, "午饭"))
    c.execute("INSERT OR IGNORE INTO ledger (inbox_id, dir, category, amount, occurred_at, note)"
              " VALUES (?,?,?,?,?,?)", (ids[1], "支", "购物", 129.0, now - 7_000_000, "淘宝订单"))

# 3) 聊天：5 轮
for role, text in [("user", "帮我记一下明天下午两点的评审"),
                   ("assistant", "记下了：明天 14:00 项目评审"),
                   ("user", "这个月餐饮花了多少"),
                   ("assistant", "本月餐饮 38.5 元，共 1 笔"),
                   ("user", "谢谢")]:
    c.execute("INSERT INTO chat_log (role, content, created_at) VALUES (?,?,?)",
              (role, text, now - 600_000))

# 4) 技能：2 条自动规则
c.execute("INSERT OR IGNORE INTO skills (kind, pattern, arg, enabled, hits, created_at)"
          " VALUES ('remind','取件码','15',1,3,?)", (now - 900_000,))
c.execute("INSERT OR IGNORE INTO skills (kind, pattern, arg, enabled, hits, created_at)"
          " VALUES ('categorize','外卖','餐饮',1,7,?)", (now - 800_000,))

# 5) 向量：给前 3 条收件各造一条 256 维归一化 float32（小端），和 VectorSearch.DIM 一致
for i, iid in enumerate(ids + [ids[0]]):
    v = [0.0] * 256
    v[i % 256] = 1.0
    blob = struct.pack("<256f", *v)
    c.execute("INSERT OR REPLACE INTO embeddings (inbox_id, vec, at) VALUES (?,?,?)",
              (iid, sqlite3.Binary(blob), now))

# 6) 出网留档：2 条脱敏摘要
c.execute("INSERT INTO cloud_log (at, task, host, model, request_redacted, ok, note)"
          " VALUES (?,?,?,?,?,1,?)", (now - 1_000_000, "chat", "tokenrhythm.studio",
                                      "egg-alpha-003", "[姓名] 问我明天的会", "成功"))
c.execute("INSERT INTO cloud_log (at, task, host, model, request_redacted, ok, note)"
          " VALUES (?,?,?,?,?,0,?)", (now - 500_000, "extract", "tokenrhythm.studio",
                                      "egg-alpha-003", "[地址] 的快递", "超时"))

db.commit()
print("seeded:")
for t in ["inbox", "reminders", "ledger", "embeddings", "chat_log", "skills", "cloud_log"]:
    print(f"  {t}: {c.execute(f'SELECT COUNT(*) FROM {t}').fetchone()[0]}")
db.close()
