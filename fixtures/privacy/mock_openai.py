"""假 OpenAI 兼容端点（T-B6）。

作用：把每个请求的**完整 body** 原样落盘，供隐私断言使用。
判定层自检不能替代请求体测试 —— D1/D2/D3 全都是"判定通过但请求体漏了"，
所以这里的落盘是唯一可信证据。

用法：
    MOCK_PORT=8799 MOCK_LOG=requests.jsonl python fixtures/privacy/mock_openai.py

响应策略：
- 用户句里带 `__tool__` 且请求带了 tools → 返回一次 tool_calls（list_todo），
  用来验证工具返回值回灌时是否过闸；
- 已经回灌过 role=tool → 返回普通文本，结束循环；
- 其余 → 普通文本。
"""
import json
import os
from datetime import datetime
from http.server import BaseHTTPRequestHandler, HTTPServer

LOG = os.environ.get("MOCK_LOG", "requests.jsonl")
PORT = int(os.environ.get("MOCK_PORT", "8799"))


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_POST(self):
        n = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(n).decode("utf-8", "replace")
        with open(LOG, "a", encoding="utf-8") as f:
            f.write(json.dumps({
                "at": datetime.now().isoformat(timespec="seconds"),
                "path": self.path,
                "has_auth": bool(self.headers.get("Authorization")),
                "body": raw,
            }, ensure_ascii=False) + "\n")

        msgs, obj = [], {}
        try:
            obj = json.loads(raw)
            msgs = obj.get("messages") or []
        except Exception:
            pass

        has_tool_result = any(m.get("role") == "tool" for m in msgs)
        last_user = ""
        for m in reversed(msgs):
            if m.get("role") == "user":
                last_user = m.get("content") or ""
                break

        if obj.get("tools") and not has_tool_result and "__tool__" in last_user:
            message = {
                "role": "assistant",
                "content": None,
                "tool_calls": [{
                    "id": "call_mock_1",
                    "type": "function",
                    "function": {"name": "list_todo", "arguments": "{}"},
                }],
            }
            finish = "tool_calls"
        else:
            message = {"role": "assistant", "content": "（mock）我收到了。"}
            finish = "stop"

        data = json.dumps({
            "id": "mock-1",
            "object": "chat.completion",
            "model": "mock-1",
            "choices": [{"index": 0, "message": message, "finish_reason": finish}],
            "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
        }, ensure_ascii=False).encode("utf-8")

        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


if __name__ == "__main__":
    print(f"mock listening on 127.0.0.1:{PORT}, log -> {LOG}")
    HTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
