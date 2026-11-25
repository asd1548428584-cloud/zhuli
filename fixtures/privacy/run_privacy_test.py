"""T-B6 假端点全请求体测试（阶段 B 核心验收）。

做法：把 App 的云通道指向本机 mock（http://127.0.0.1:PORT，仅回环允许明文），
逐条发问，然后检查 mock 落盘的**完整请求体**：
  1. 敏感原文零出现（手机号 / 地址 / 证件号）
  2. 红线内容零请求（证件号那一路根本不该发）
  3. 工具返回值回灌时同样不含敏感原文

前提：mock_openai.py 已在跑；手机已装修复版；adb reverse 已建立。

用法：python fixtures/privacy/run_privacy_test.py
"""
import json
import os
import subprocess
import sys
import time

ADB = r"D:/Android/Sdk/platform-tools/adb.exe"
PORT = 8799
HERE = os.path.dirname(os.path.abspath(__file__))
LOG = os.path.join(HERE, "requests.jsonl")
BASE = f"http://127.0.0.1:{PORT}/v1"
PKG = "com.zhuli.app"

# 样本（脱敏后入库；这里的号码/证件都是构造值）
SENSITIVE = {
    "phone": "13800001111",
    "id_card": "110101199003071234",
    "address": "建国路",
    "address_tail": "3 号楼",
}

CASES = [
    ("非敏感闲聊", "讲个冷笑话吧", "cloud", []),
    ("地址脱敏", "讲个冷笑话吧，另外我家在朝阳区建国路 88 号 3 号楼 2 单元", "cloud",
     [SENSITIVE["address"], "88 号", SENSITIVE["address_tail"]]),
    ("手机号脱敏", "讲个冷笑话吧，我手机号 13800001111", "cloud", [SENSITIVE["phone"]]),
    ("红线拦截", "讲个冷笑话吧，我身份证 110101199003071234", "none", [SENSITIVE["id_card"]]),
    ("工具返回回灌", "讲个冷笑话吧 __tool__", "cloud", [SENSITIVE["id_card"], SENSITIVE["phone"]]),
]


def adb(*args, timeout=90):
    return subprocess.run([ADB, *args], capture_output=True, text=True,
                          timeout=timeout, encoding="utf-8", errors="replace")


def log_count():
    if not os.path.exists(LOG):
        return 0
    with open(LOG, encoding="utf-8") as f:
        return sum(1 for _ in f)


def read_from(start):
    if not os.path.exists(LOG):
        return []
    out = []
    with open(LOG, encoding="utf-8") as f:
        for i, line in enumerate(f):
            if i >= start:
                out.append(json.loads(line))
    return out


def configure():
    print("[准备] adb reverse + 写入 mock 端点")
    adb("reverse", f"tcp:{PORT}", f"tcp:{PORT}")
    adb("shell", "am", "force-stop", PKG)
    time.sleep(1.5)
    # 注意：adb shell 会把命令串交给设备端 sh 再解析一次，带空格的参数必须在这里自己加引号，
    # 否则会被空格切开（第一版就是这么翻车的：身份证号被切掉，红线样本实际没送到 App）
    adb("shell", f'am start -n {PKG}/.MainActivity --es cloudbase "{BASE}" '
                 f'--es cloudkey "sk-mock-test" --es cloudmodel "mock-1"')
    time.sleep(5)
    adb("shell", "am", "force-stop", PKG)
    time.sleep(1)


def ask(q, wait=16):
    adb("shell", "am", "force-stop", PKG)
    time.sleep(1.5)
    safe = q.replace('"', '\\"')
    adb("shell", f'am start -n {PKG}/.MainActivity --es q "{safe}"')
    time.sleep(wait)
    adb("shell", "am", "force-stop", PKG)
    time.sleep(1)


def message_texts(body):
    try:
        obj = json.loads(body)
    except Exception:
        return []
    return [m.get("content") or "" for m in (obj.get("messages") or [])]


def main():
    if not os.path.exists(ADB):
        print(f"adb 不存在：{ADB}")
        return 2
    open(LOG, "w", encoding="utf-8").close()   # 每次跑都从空日志开始，避免上一轮残留混进来
    configure()

    results = []
    for name, q, expect, forbidden in CASES:
        before = log_count()
        ask(q)
        new = read_from(before)
        reqs = len(new)
        problems = []

        if expect == "none" and reqs != 0:
            problems.append(f"本应零请求，实际发了 {reqs} 次")
        if expect == "cloud" and reqs == 0:
            problems.append("应出网但一次都没发（可能被闸门拦了，检查 Key/端点）")

        for rec in new:
            for text in message_texts(rec["body"]):
                for token in forbidden:
                    if token in text:
                        problems.append(f"请求体里出现敏感原文：{token}（role/content={text[:40]}…）")

        results.append((name, reqs, problems))

    print("\n" + "=" * 68)
    ok_all = True
    for name, reqs, problems in results:
        status = "PASS" if not problems else "FAIL"
        if problems:
            ok_all = False
        print(f"[{status}] {name:12s} 请求数={reqs}  {('; '.join(problems))[:80]}")
    print("=" * 68)
    print("总判定：", "全部通过" if ok_all else "存在失败项")
    print(f"完整请求体落在：{LOG}（不入库，含真实工具返回）")
    return 0 if ok_all else 1


if __name__ == "__main__":
    sys.exit(main())
